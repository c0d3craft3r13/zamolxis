package network.zamolxis.app.service.group

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.zamolxis.app.data.model.GroupMessageStatus
import network.zamolxis.app.data.model.GroupRole
import network.zamolxis.app.data.repository.AnnounceRepository
import network.zamolxis.app.data.repository.GroupRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.notifications.NotificationHelper
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.rns.api.RnsLxmf
import network.zamolxis.app.rns.api.model.DeliveryMethod
import network.zamolxis.app.rns.api.model.Identity
import network.zamolxis.app.rns.api.util.LxmfFields
import network.zamolxis.app.rns.api.util.hexToBytes
import network.zamolxis.app.rns.api.util.toHex
import network.zamolxis.app.service.pq.LinkCostResolver
import network.zamolxis.app.service.pq.PqMessageSealer
import network.zamolxis.app.service.pq.SealedPayload
import network.zamolxis.app.util.validation.InputValidator
import network.zamolxis.app.util.validation.ValidationResult
import network.zamolxis.crypto.pq.LinkCost
import network.zamolxis.crypto.pq.PqMode
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fan-out group chat: one group message becomes one LXMF message per member.
 *
 * There is no group address on the wire — Reticulum only delivers to a single
 * destination — so a group is a shared secret (the [GroupWireCodec.GroupEnvelope.gid])
 * plus a roster every member holds, and "sending to the group" means sealing and
 * sending a copy to each active member. Receivers deduplicate on `(gid, mid)`
 * ([GroupRepository.saveIncomingMessage] ignores repeats), so LXMF re-delivery
 * via a propagation node is harmless.
 *
 * Membership changes are gossiped as control frames ([GroupWireCodec.GroupCtl]):
 * the creating admin broadcasts the full roster on every change, so a member
 * that missed an earlier change is repaired by the next one. The flip side is
 * the known v1 limitation: if a [createGroup] MEMBERS_SYNC never reaches a
 * member at all, that member learns about the group only when a later control
 * frame lands — there is no on-demand re-sync yet.
 *
 * Two more v1 limitations worth stating plainly:
 *  - **There is no invitation step.** A MEMBERS_SYNC for a group we have never
 *    heard of creates it locally, provided the sender lists itself as the
 *    creating ADMIN and lists us in the roster. That check stops a third party
 *    from forging someone else's group, but not the sender from adding us to
 *    their own: any peer that can reach us can put a group in our chat list.
 *    The user's recourse is to leave and delete it
 *    ([GroupRepository.deleteGroup]); blocking the peer stops the traffic
 *    upstream of this class.
 *  - **An admin is trusted for as long as it is an admin.** Roles come from
 *    the roster, so an admin can rename the group, add anyone, and remove
 *    anyone. There is no signature over the roster: a member that trusts the
 *    admin's LXMF identity is trusting everything the admin says about the
 *    group.
 *
 * Delivery status is tracked per recipient: every outgoing message gets one
 * PENDING row per member ([GroupRepository.saveOutgoingMessage]), promoted to
 * SENT with the per-recipient LXMF hash when the send returns, and to
 * DELIVERED/FAILED by the receipt stream collected in [start].
 */
@Suppress("LongParameterList", "TooManyFunctions") // Seam between group store, transport and PQ layer; one cohesive surface.
@Singleton
class GroupChatManager
    @Inject
    constructor(
        private val groupRepository: GroupRepository,
        private val rnsLxmf: RnsLxmf,
        private val identityRepository: IdentityRepository,
        private val settingsRepository: SettingsRepository,
        private val announceRepository: AnnounceRepository,
        private val pqMessageSealer: PqMessageSealer,
        private val notificationHelper: NotificationHelper,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val started = AtomicBoolean(false)

        /**
         * Start collecting LXMF delivery receipts and matching them to fan-out
         * legs. Idempotent — safe to call from every Reticulum (re)connect.
         */
        fun start() {
            if (!started.compareAndSet(false, true)) {
                Log.w(TAG, "GroupChatManager already started")
                return
            }
            scope.launch {
                try {
                    rnsLxmf.observeDeliveryStatus().collect { update ->
                        val mapped =
                            when (update.status) {
                                "delivered", "propagated" -> GroupMessageStatus.DELIVERED
                                "failed" -> GroupMessageStatus.FAILED
                                "retrying_propagated" -> GroupMessageStatus.SENT
                                else -> null
                            } ?: return@collect
                        runCatching {
                            // Receipt hashes are hex; the stored lxmfHash is lowercase
                            // (ByteArray.toHex), so normalise before matching.
                            groupRepository.updateStatusByLxmfHash(update.messageHash.lowercase(), mapped)
                        }.onFailure { Log.w(TAG, "Could not apply group delivery status", it) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Group delivery-status collection stopped", e)
                }
            }
        }

        /**
         * Create a group with the caller as ADMIN and broadcast the roster.
         *
         * The group is persisted before the MEMBERS_SYNC goes out, and the
         * groupId is returned even when some (or all) roster sends fail: the
         * roster is re-broadcast on every later membership change, so a missed
         * send is repaired then — a member reachable by no later change stays
         * unaware of the group (see class kdoc).
         */
        suspend fun createGroup(
            name: String,
            memberHashes: List<String>,
        ): Result<String> =
            runCatching {
                val trimmedName = name.trim()
                require(trimmedName.isNotEmpty()) { "Group name cannot be empty" }
                val myHash = myDestinationHash() ?: error("No active identity")
                val members = memberHashes.map { it.lowercase() }.filter { it != myHash }.distinct()
                require(members.all { HASH_PATTERN.matches(it) }) { "Invalid member hash" }
                require(members.size + 1 <= MAX_GROUP_MEMBERS) { "Group is limited to $MAX_GROUP_MEMBERS members" }

                val groupId = ByteArray(16).also(SecureRandom()::nextBytes).toHex()
                val now = System.currentTimeMillis()
                val roster = listOf(myHash to GroupRole.ADMIN) + members.map { it to GroupRole.MEMBER }
                groupRepository.createLocalGroup(
                    groupId = groupId,
                    name = trimmedName,
                    createdBy = myHash,
                    createdAt = now,
                    members = roster,
                )
                fanOutControl(
                    groupId = groupId,
                    body =
                        GroupWireCodec.GroupBody.MembersSync(
                            name = trimmedName,
                            createdBy = myHash,
                            createdAt = now,
                            members = roster.map { GroupWireCodec.MemberEntry(it.first, it.second.name) },
                        ),
                    recipients = members,
                )
                groupId
            }

        /**
         * Send a text message to every active member of the group.
         *
         * Returns the msgId once the message is persisted and every fan-out leg
         * has been attempted; per-recipient failures are recorded on that
         * recipient's status row ([GroupMessageStatus.FAILED]) rather than
         * failing the whole send.
         */
        suspend fun sendGroupMessage(
            groupId: String,
            content: String,
        ): Result<String> =
            runCatching {
                val sanitized =
                    when (val validation = InputValidator.validateMessageContent(content)) {
                        is ValidationResult.Success -> validation.value
                        is ValidationResult.Error -> error(validation.message)
                    }
                val myHash = myDestinationHash() ?: error("No active identity")
                require(groupRepository.isActiveMember(groupId, myHash)) { "Not an active member of this group" }

                val recipients =
                    groupRepository
                        .getActiveMembers(groupId)
                        .map { it.memberHash }
                        .filter { it != myHash }
                val msgId = UUID.randomUUID().toString()
                groupRepository.saveOutgoingMessage(
                    groupId = groupId,
                    msgId = msgId,
                    content = sanitized,
                    timestamp = System.currentTimeMillis(),
                    memberHashes = recipients,
                )

                val envelope = GroupWireCodec.GroupEnvelope(gid = groupId, mid = msgId)
                // A context we cannot build fails every leg identically — record
                // it on each status row rather than throwing away a message the
                // UI has already shown as sent.
                val context =
                    fanOutContext().getOrElse { error ->
                        val reason = error.message ?: "Send failed"
                        recipients.forEach { member ->
                            groupRepository.setMemberStatus(
                                msgId = msgId,
                                memberHash = member,
                                status = GroupMessageStatus.FAILED,
                                errorMessage = reason,
                            )
                        }
                        error(reason)
                    }
                for (member in recipients) {
                    when (val result = deliverToMember(context, member, envelope, sanitized)) {
                        is FanOutResult.Sent ->
                            groupRepository.setMemberStatus(
                                msgId = msgId,
                                memberHash = member,
                                status = GroupMessageStatus.SENT,
                                lxmfHash = result.lxmfHash,
                            )
                        is FanOutResult.Failed ->
                            groupRepository.setMemberStatus(
                                msgId = msgId,
                                memberHash = member,
                                status = GroupMessageStatus.FAILED,
                                errorMessage = result.error,
                            )
                    }
                }
                msgId
            }

        /**
         * Rename the group (admin only) and notify members with GROUP_UPDATED.
         */
        suspend fun renameGroup(
            groupId: String,
            newName: String,
        ): Result<Unit> =
            runCatching {
                val trimmedName = newName.trim()
                require(trimmedName.isNotEmpty()) { "Group name cannot be empty" }
                requireAdmin(groupId)
                groupRepository.updateGroupName(groupId, trimmedName)
                fanOutControl(
                    groupId = groupId,
                    body = GroupWireCodec.GroupBody.GroupUpdated(trimmedName),
                    ctl = GroupWireCodec.GroupCtl.GROUP_UPDATED,
                    recipients = otherActiveMembers(groupId),
                )
            }

        /**
         * Add members (admin only) and broadcast the new full roster.
         */
        suspend fun addMembers(
            groupId: String,
            memberHashes: List<String>,
        ): Result<Unit> =
            runCatching {
                val myHash = requireAdmin(groupId)
                val newMembers = memberHashes.map { it.lowercase() }.filter { it != myHash }.distinct()
                require(newMembers.all { HASH_PATTERN.matches(it) }) { "Invalid member hash" }

                val group =
                    groupRepository.getGroup(groupId) ?: error("Unknown group")
                val roster = currentRoster(groupId).toMutableMap()
                newMembers.forEach { roster.putIfAbsent(it, GroupRole.MEMBER) }
                require(roster.size <= MAX_GROUP_MEMBERS) { "Group is limited to $MAX_GROUP_MEMBERS members" }
                groupRepository.applyMembersSync(groupId, roster.toList(), System.currentTimeMillis())
                fanOutControl(
                    groupId = groupId,
                    body =
                        GroupWireCodec.GroupBody.MembersSync(
                            name = group.name,
                            createdBy = group.createdBy,
                            createdAt = group.createdAt,
                            members = roster.map { GroupWireCodec.MemberEntry(it.key, it.value.name) },
                        ),
                    recipients = roster.keys.filter { it != myHash },
                )
            }

        /**
         * Remove a member (admin only) and broadcast the new full roster to the
         * remaining members. The removed member is not notified in v1 — they
         * simply stop receiving traffic.
         */
        suspend fun removeMember(
            groupId: String,
            memberHash: String,
        ): Result<Unit> =
            runCatching {
                val myHash = requireAdmin(groupId)
                val target = memberHash.lowercase()
                require(target != myHash) { "Use leaveGroup to remove yourself" }

                val group = groupRepository.getGroup(groupId) ?: error("Unknown group")
                val roster = currentRoster(groupId).toMutableMap().apply { remove(target) }
                groupRepository.applyMembersSync(groupId, roster.toList(), System.currentTimeMillis())
                fanOutControl(
                    groupId = groupId,
                    body =
                        GroupWireCodec.GroupBody.MembersSync(
                            name = group.name,
                            createdBy = group.createdBy,
                            createdAt = group.createdAt,
                            members = roster.map { GroupWireCodec.MemberEntry(it.key, it.value.name) },
                        ),
                    recipients = roster.keys.filter { it != myHash },
                )
            }

        /**
         * Leave a group ourselves: tell the other members, then soft-leave the
         * local member row.
         */
        suspend fun leaveGroup(groupId: String): Result<Unit> =
            runCatching {
                val myHash = myDestinationHash() ?: error("No active identity")
                fanOutControl(
                    groupId = groupId,
                    body = null,
                    ctl = GroupWireCodec.GroupCtl.MEMBER_LEFT,
                    recipients = otherActiveMembers(groupId),
                )
                groupRepository.leaveGroup(groupId, myHash, System.currentTimeMillis())
            }

        /**
         * Entry point for [network.zamolxis.app.service.MessageCollector]: one
         * inbound message that carried a group envelope. Never throws — a bad
         * or hostile envelope is dropped with a log line, not an exception
         * that would kill the collection loop.
         */
        suspend fun handleIncoming(
            envelope: GroupWireCodec.GroupEnvelope,
            sourceHash: String,
            content: String,
            timestamp: Long,
        ) {
            runCatching { dispatchIncoming(envelope, sourceHash, content, timestamp) }
                .onFailure { Log.e(TAG, "Failed to handle group envelope for ${envelope.gid}", it) }
        }

        private suspend fun dispatchIncoming(
            envelope: GroupWireCodec.GroupEnvelope,
            sourceHash: String,
            content: String,
            timestamp: Long,
        ) {
            when (envelope.ctl) {
                null -> handleIncomingMessage(envelope, sourceHash, content, timestamp)
                GroupWireCodec.GroupCtl.MEMBERS_SYNC ->
                    handleMembersSync(envelope, envelope.body as GroupWireCodec.GroupBody.MembersSync, sourceHash)
                GroupWireCodec.GroupCtl.GROUP_UPDATED ->
                    handleGroupUpdated(envelope, envelope.body as GroupWireCodec.GroupBody.GroupUpdated, sourceHash)
                GroupWireCodec.GroupCtl.MEMBER_LEFT -> {
                    // Only the sender can mark themselves left — the frame carries
                    // no target, so sourceHash is the member.
                    groupRepository.markMemberLeft(envelope.gid, sourceHash, System.currentTimeMillis())
                }
            }
        }

        private suspend fun handleIncomingMessage(
            envelope: GroupWireCodec.GroupEnvelope,
            sourceHash: String,
            content: String,
            timestamp: Long,
        ) {
            if (!groupRepository.isActiveMember(envelope.gid, sourceHash)) {
                Log.w(TAG, "Dropping group message from non-member $sourceHash in ${envelope.gid}")
                return
            }
            // Our own membership matters too: after leaveGroup the other members
            // keep sending for as long as their roster is stale, and storing
            // those copies would keep a group we left growing (and notifying).
            // Control frames are exempt — a MEMBERS_SYNC that re-adds us is how
            // a re-join arrives.
            val myHash = myDestinationHash()
            if (myHash == null || !groupRepository.isActiveMember(envelope.gid, myHash)) {
                Log.w(TAG, "Dropping group message for ${envelope.gid} — we are not an active member")
                return
            }
            val inserted =
                groupRepository.saveIncomingMessage(
                    groupId = envelope.gid,
                    msgId = envelope.mid,
                    senderHash = sourceHash,
                    content = content,
                    timestamp = timestamp,
                )
            if (!inserted) {
                Log.d(TAG, "Duplicate group message ${envelope.mid} in ${envelope.gid} — ignoring")
                return
            }
            runCatching {
                val groupName = groupRepository.getGroup(envelope.gid)?.name ?: envelope.gid.take(8)
                notificationHelper.notifyMessageReceived(
                    // Synthetic key, not a real destination hash: keeps the
                    // notification tag per-group and lets MainActivity route the
                    // tap to the group chat instead of a 1:1 conversation.
                    destinationHash = GROUP_NOTIFICATION_KEY_PREFIX + envelope.gid,
                    peerName = groupName,
                    messagePreview = content.take(100),
                    isFavorite = false,
                )
            }.onFailure { Log.w(TAG, "Could not post group message notification", it) }
        }

        private suspend fun handleMembersSync(
            envelope: GroupWireCodec.GroupEnvelope,
            body: GroupWireCodec.GroupBody.MembersSync,
            sourceHash: String,
        ) {
            val roster = body.members.map { it.hash to GroupRole.fromStored(it.role) }
            if (groupRepository.getGroup(envelope.gid) == null) {
                // A stranger must not be able to enroll this device into a group:
                // only accept a new group from its self-declared creator, who must
                // list themselves as ADMIN and must list us as a member.
                val myHash = myDestinationHash()
                val creatorClaimsAdmin =
                    body.createdBy == sourceHash &&
                        body.members.any { it.hash == sourceHash && it.role == GroupRole.ADMIN.name }
                if (!creatorClaimsAdmin || myHash == null || body.members.none { it.hash == myHash }) {
                    Log.w(TAG, "Dropping MEMBERS_SYNC for unknown group ${envelope.gid} from $sourceHash")
                    return
                }
                groupRepository.createLocalGroup(
                    groupId = envelope.gid,
                    name = body.name,
                    createdBy = body.createdBy,
                    createdAt = body.createdAt,
                    members = roster,
                )
                return
            }
            if (!isActiveAdmin(envelope.gid, sourceHash)) {
                Log.w(TAG, "Dropping MEMBERS_SYNC from non-admin $sourceHash in ${envelope.gid}")
                return
            }
            groupRepository.applyMembersSync(envelope.gid, roster, System.currentTimeMillis())
        }

        private suspend fun handleGroupUpdated(
            envelope: GroupWireCodec.GroupEnvelope,
            body: GroupWireCodec.GroupBody.GroupUpdated,
            sourceHash: String,
        ) {
            if (!isActiveAdmin(envelope.gid, sourceHash)) {
                Log.w(TAG, "Dropping GROUP_UPDATED from non-admin $sourceHash in ${envelope.gid}")
                return
            }
            groupRepository.updateGroupName(envelope.gid, body.name)
        }

        // ==================== Fan-out ====================

        /** Outcome of one fan-out leg, reduced to what the status row needs. */
        private sealed interface FanOutResult {
            data class Sent(
                val lxmfHash: String,
            ) : FanOutResult

            data class Failed(
                val error: String,
            ) : FanOutResult
        }

        /**
         * Send a control frame to each recipient. Per-recipient failures are
         * logged, not propagated — control frames have no status rows, and one
         * unreachable member must not stop the others from being told.
         */
        private suspend fun fanOutControl(
            groupId: String,
            body: GroupWireCodec.GroupBody?,
            ctl: GroupWireCodec.GroupCtl = GroupWireCodec.GroupCtl.MEMBERS_SYNC,
            recipients: List<String>,
        ) {
            val envelope =
                GroupWireCodec.GroupEnvelope(
                    gid = groupId,
                    mid = UUID.randomUUID().toString(),
                    ctl = ctl,
                    body = body,
                )
            val context =
                fanOutContext().getOrElse {
                    Log.w(TAG, "Control frame $ctl for group $groupId could not be sent: ${it.message}")
                    return
                }
            for (member in recipients) {
                if (deliverToMember(context, member, envelope, content = "") is FanOutResult.Failed) {
                    Log.w(TAG, "Control frame $ctl for group $groupId did not reach $member")
                }
            }
        }

        /**
         * The parts of a send that are identical for every recipient: local
         * identity, LXMF source identity and the two settings the PQ layer and
         * the transport need.
         *
         * Read once per fan-out rather than once per member — a 20-member group
         * would otherwise make 80 store round-trips to answer the same four
         * questions, and a settings change mid-fan-out could send half the
         * copies under one post-quantum mode and half under another.
         */
        private class FanOutContext(
            val identityHash: String,
            val ourDestinationHash: String,
            val sourceIdentity: Identity,
            val mode: PqMode,
            val tryPropagation: Boolean,
        )

        /** [FanOutContext] or the reason the whole fan-out cannot proceed. */
        @Suppress("ReturnCount") // One early return per missing precondition.
        private suspend fun fanOutContext(): Result<FanOutContext> {
            val identity =
                runCatching { identityRepository.getActiveIdentitySync() }.getOrNull()
                    ?: return Result.failure(IllegalStateException("No active identity"))
            val sourceIdentity =
                rnsLxmf.getLxmfIdentity().getOrElse {
                    return Result.failure(IllegalStateException("LXMF identity unavailable: ${it.message}"))
                }
            val mode =
                runCatching { settingsRepository.getPostQuantumMode() }.getOrElse {
                    return Result.failure(IllegalStateException("Post-quantum mode unreadable: ${it.message}"))
                }
            val tryPropagation =
                runCatching { settingsRepository.getTryPropagationOnFail() }.getOrDefault(true)
            return Result.success(
                FanOutContext(
                    identityHash = identity.identityHash,
                    ourDestinationHash = identity.destinationHash,
                    sourceIdentity = sourceIdentity,
                    mode = mode,
                    tryPropagation = tryPropagation,
                ),
            )
        }

        /**
         * Seal (or not, per the PQ layer's decision) and send one copy of a
         * group message to one member.
         *
         * The envelope rides inside the seal when the payload is sealed; when
         * the layer sends plain, it is merged into the LXMF fields here so the
         * receiver's [GroupWireCodec.extractFromFieldsJson] still finds it.
         */
        @Suppress("ReturnCount") // Each early return is a distinct per-recipient outcome; nesting would hide which.
        private suspend fun deliverToMember(
            context: FanOutContext,
            memberHash: String,
            envelope: GroupWireCodec.GroupEnvelope,
            content: String,
        ): FanOutResult {
            val destination =
                runCatching { memberHash.hexToBytes() }.getOrElse {
                    return FanOutResult.Failed("Invalid member hash: ${it.message}")
                }

            val envelopeMap = GroupWireCodec.toMap(envelope)
            val payload = SealedPayload(content = content, group = envelopeMap)
            val outgoing =
                runCatching {
                    pqMessageSealer.prepareOutgoing(
                        identityHash = context.identityHash,
                        ourDestinationHash = context.ourDestinationHash,
                        peerHash = memberHash,
                        payload = payload,
                        mode = context.mode,
                        linkCost = linkCostFor(memberHash),
                    )
                }.getOrElse {
                    return FanOutResult.Failed("Post-quantum layer failed: ${it.message}")
                }

            val wireContent: String
            val wireExtras: Map<Int, Any>
            when (outgoing) {
                is PqMessageSealer.Outgoing.Refused ->
                    return FanOutResult.Failed("Sealing refused: ${outgoing.reason.name}")
                is PqMessageSealer.Outgoing.Sealed -> {
                    wireContent = outgoing.wire.content
                    wireExtras = outgoing.wire.extraFields
                }
                is PqMessageSealer.Outgoing.Plain -> {
                    wireContent = outgoing.wire.content
                    wireExtras =
                        outgoing.wire.extraFields +
                        mapOf(
                            LxmfFields.FIELD_CUSTOM_META to
                                mapOf(LxmfFields.CUSTOM_META_KEY_GROUP to envelopeMap),
                        )
                }
            }

            return rnsLxmf
                .sendLxmfMessageWithMethod(
                    destinationHash = destination,
                    content = wireContent,
                    sourceIdentity = context.sourceIdentity,
                    deliveryMethod = DeliveryMethod.DIRECT,
                    tryPropagationOnFail = context.tryPropagation,
                    extraFields = wireExtras.ifEmpty { null },
                ).fold(
                    onSuccess = { receipt ->
                        runCatching {
                            pqMessageSealer.onSendSucceeded(context.identityHash, memberHash, outgoing)
                        }.onFailure { Log.w(TAG, "Could not record hybrid key delivery", it) }
                        FanOutResult.Sent(receipt.messageHash.toHex())
                    },
                    onFailure = { FanOutResult.Failed(it.message ?: "Send failed") },
                )
        }

        /**
         * Cost of the link this member was last heard on — same rule as the
         * 1:1 send path, so group sends do not quietly pay airtime the chat
         * indicator said they would not.
         */
        private suspend fun linkCostFor(destinationHash: String): LinkCost =
            runCatching {
                val sightings = announceRepository.getRecentInterfaceSightings(destinationHash).first()
                LinkCostResolver.costOfTypes(sightings.map { it.interfaceType })
            }.getOrElse {
                Log.w(TAG, "Could not determine link cost for $destinationHash", it)
                LinkCost.CHEAP
            }

        // ==================== Membership helpers ====================

        private suspend fun myDestinationHash(): String? = runCatching { identityRepository.getActiveIdentitySync()?.destinationHash }.getOrNull()

        /** The active roster as hash → role, self included. */
        private suspend fun currentRoster(groupId: String): Map<String, GroupRole> =
            groupRepository
                .getActiveMembers(groupId)
                .associate { it.memberHash to GroupRole.fromStored(it.role) }

        private suspend fun otherActiveMembers(groupId: String): List<String> {
            val myHash = myDestinationHash()
            return groupRepository
                .getActiveMembers(groupId)
                .map { it.memberHash }
                .filter { it != myHash }
        }

        private suspend fun isActiveAdmin(
            groupId: String,
            memberHash: String,
        ): Boolean =
            groupRepository
                .getActiveMembers(groupId)
                .firstOrNull { it.memberHash == memberHash }
                ?.let { GroupRole.fromStored(it.role) == GroupRole.ADMIN } == true

        /** Fail unless the local identity is an active ADMIN; returns my hash. */
        private suspend fun requireAdmin(groupId: String): String {
            val myHash = myDestinationHash() ?: error("No active identity")
            check(isActiveAdmin(groupId, myHash)) { "Admin role required" }
            return myHash
        }

        companion object {
            private const val TAG = "GroupChatManager"
            private val HASH_PATTERN = Regex("[0-9a-f]{32}")

            /**
             * Ceiling on roster size, counting ourselves.
             *
             * Fan-out makes one LXMF message per member, so cost grows linearly
             * with the roster while the slowest link sets the pace — an
             * unbounded group would sit in the send loop for minutes on LoRa
             * and flood the mesh doing it. Enforced on the paths that grow a
             * roster ([createGroup], [addMembers]); an oversized roster
             * arriving in a MEMBERS_SYNC is still applied, because refusing it
             * would leave us with a membership view the rest of the group does
             * not share.
             */
            const val MAX_GROUP_MEMBERS = 32

            /**
             * Prefix of the synthetic conversation key used for group message
             * notifications (`"group:<groupId>"`). MainActivity recognizes it and
             * routes the tap to the group chat screen.
             */
            const val GROUP_NOTIFICATION_KEY_PREFIX = "group:"
        }
    }
