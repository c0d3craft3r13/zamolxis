package network.zamolxis.app.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import network.zamolxis.app.data.db.DatabaseTransactionRunner
import network.zamolxis.app.data.db.dao.GroupDao
import network.zamolxis.app.data.db.dao.GroupMemberDao
import network.zamolxis.app.data.db.dao.GroupMessageAggregate
import network.zamolxis.app.data.db.dao.GroupMessageDao
import network.zamolxis.app.data.db.dao.GroupMessageStatusDao
import network.zamolxis.app.data.db.dao.LocalIdentityDao
import network.zamolxis.app.data.db.entity.GroupEntity
import network.zamolxis.app.data.db.entity.GroupMemberEntity
import network.zamolxis.app.data.db.entity.GroupMessageEntity
import network.zamolxis.app.data.db.entity.GroupMessageStatusEntity
import network.zamolxis.app.data.model.GroupMessageStatus
import network.zamolxis.app.data.model.GroupRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data layer for group chats.
 *
 * Groups are scoped to the active local identity via [GroupEntity.identityHash],
 * resolved the same way [ConversationRepository] does it. Everything else —
 * members, messages, per-recipient delivery status — hangs off the group's
 * primary key alone.
 *
 * Writes that must land together go through [DatabaseTransactionRunner] rather
 * than Room's `@Transaction`: that annotation is only honoured on `@Dao` types,
 * so on a repository it reads like a guarantee while doing nothing.
 */
@Singleton
class GroupRepository
    @Inject
    constructor(
        private val groupDao: GroupDao,
        private val groupMemberDao: GroupMemberDao,
        private val groupMessageDao: GroupMessageDao,
        private val groupMessageStatusDao: GroupMessageStatusDao,
        private val localIdentityDao: LocalIdentityDao,
        private val transactionRunner: DatabaseTransactionRunner,
    ) {
        /**
         * Create a group locally, with its initial member list, owned by the
         * active identity.
         */
        suspend fun createLocalGroup(
            groupId: String,
            name: String,
            createdBy: String,
            createdAt: Long,
            members: List<Pair<String, GroupRole>>,
        ) = transactionRunner.inTransaction {
            val activeIdentity =
                localIdentityDao.getActiveIdentitySync()
                    ?: error("No active identity found")
            groupDao.insertGroup(
                GroupEntity(
                    groupId = groupId,
                    identityHash = activeIdentity.identityHash,
                    name = name,
                    createdBy = createdBy,
                    createdAt = createdAt,
                ),
            )
            groupMemberDao.insertAll(
                members.map { (memberHash, role) ->
                    GroupMemberEntity(
                        groupId = groupId,
                        memberHash = memberHash,
                        role = role.name,
                        addedAt = createdAt,
                    )
                },
            )
        }

        suspend fun getGroup(groupId: String): GroupEntity? = groupDao.getGroup(groupId)

        fun observeGroup(groupId: String): Flow<GroupEntity?> = groupDao.observeGroup(groupId)

        /**
         * Group overviews for the chat list, scoped to the active identity.
         * Automatically switches when identity changes.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observeGroupOverviews(): Flow<List<GroupEntity>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    groupDao.observeGroupsForIdentity(identity.identityHash)
                }
            }

        /**
         * Persist an outgoing group message: the message row itself, one
         * PENDING fan-out leg per recipient, and the group's chat-list preview.
         */
        suspend fun saveOutgoingMessage(
            groupId: String,
            msgId: String,
            content: String,
            timestamp: Long,
            memberHashes: List<String>,
        ) = transactionRunner.inTransaction {
            val myHash =
                localIdentityDao.getActiveIdentitySync()?.destinationHash
                    ?: error("No active identity found")
            groupMessageDao.insertIgnore(
                GroupMessageEntity(
                    groupId = groupId,
                    msgId = msgId,
                    senderHash = myHash,
                    content = content,
                    timestamp = timestamp,
                    receivedAt = System.currentTimeMillis(),
                    isFromMe = true,
                ),
            )
            groupMessageStatusDao.upsert(
                memberHashes.map { memberHash ->
                    GroupMessageStatusEntity(
                        msgId = msgId,
                        memberHash = memberHash,
                        status = GroupMessageStatus.PENDING.name,
                    )
                },
            )
            groupDao.updateLastMessage(groupId, content, timestamp)
        }

        /**
         * Persist an incoming group message, deduplicated on (groupId, msgId).
         *
         * Returns true when the row was actually inserted — only then are the
         * unread count and chat-list preview touched, so LXMF replay of an
         * already-stored message is a no-op.
         */
        suspend fun saveIncomingMessage(
            groupId: String,
            msgId: String,
            senderHash: String,
            content: String,
            timestamp: Long,
        ): Boolean =
            transactionRunner.inTransaction {
                val rowId =
                    groupMessageDao.insertIgnore(
                        GroupMessageEntity(
                            groupId = groupId,
                            msgId = msgId,
                            senderHash = senderHash,
                            content = content,
                            timestamp = timestamp,
                            receivedAt = System.currentTimeMillis(),
                            isFromMe = false,
                        ),
                    )
                if (rowId == -1L) {
                    return@inTransaction false
                }
                groupDao.incrementUnreadCount(groupId)
                groupDao.updateLastMessage(groupId, content, timestamp)
                true
            }

        /**
         * Match a delivery receipt back to its fan-out leg. Terminal states are
         * sticky — the SQL guard refuses to move a DELIVERED/READ leg back to
         * SENT/PENDING. Returns true when any row was updated.
         */
        suspend fun updateStatusByLxmfHash(
            lxmfHash: String,
            status: GroupMessageStatus,
        ): Boolean = groupMessageStatusDao.updateStatusByLxmfHash(lxmfHash, status.name) > 0

        /**
         * Set one recipient's leg explicitly, e.g. when the per-recipient LXMF
         * hash becomes known after the send, or when a send fails.
         */
        suspend fun setMemberStatus(
            msgId: String,
            memberHash: String,
            status: GroupMessageStatus,
            lxmfHash: String? = null,
            errorMessage: String? = null,
        ) {
            groupMessageStatusDao.upsert(
                listOf(
                    GroupMessageStatusEntity(
                        msgId = msgId,
                        memberHash = memberHash,
                        status = status.name,
                        lxmfHash = lxmfHash,
                        errorMessage = errorMessage,
                    ),
                ),
            )
        }

        suspend fun markGroupRead(groupId: String) = groupDao.markRead(groupId)

        fun observeAggregateStatuses(groupId: String): Flow<List<GroupMessageAggregate>> = groupMessageStatusDao.observeAggregateStatuses(groupId)

        suspend fun getActiveMembers(groupId: String): List<GroupMemberEntity> = groupMemberDao.getActiveMembers(groupId)

        fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> = groupMemberDao.observeMembers(groupId)

        suspend fun isActiveMember(
            groupId: String,
            memberHash: String,
        ): Boolean = groupMemberDao.isActiveMember(groupId, memberHash)

        /**
         * Apply an authoritative member list received from the group: add
         * members we don't have, soft-leave active members missing from the
         * list. Existing rows keep their role (so the creator stays ADMIN
         * even if the sender lists them as an ordinary member), and a re-join
         * keeps the original [GroupMemberEntity.addedAt].
         */
        suspend fun applyMembersSync(
            groupId: String,
            members: List<Pair<String, GroupRole>>,
            now: Long,
        ) = transactionRunner.inTransaction {
            val incoming = members.map { it.first }.toSet()
            groupMemberDao
                .getActiveMembers(groupId)
                .filter { it.memberHash !in incoming }
                .forEach { groupMemberDao.markLeft(groupId, it.memberHash, now) }

            val rejoins = mutableListOf<GroupMemberEntity>()
            for ((memberHash, role) in members) {
                val existing = groupMemberDao.getMember(groupId, memberHash)
                if (existing == null) {
                    rejoins +=
                        GroupMemberEntity(
                            groupId = groupId,
                            memberHash = memberHash,
                            role = role.name,
                            addedAt = now,
                        )
                } else if (existing.leftAt != null) {
                    rejoins += existing.copy(leftAt = null)
                }
            }
            if (rejoins.isNotEmpty()) {
                groupMemberDao.insertAll(rejoins)
            }
        }

        suspend fun updateGroupName(
            groupId: String,
            name: String,
        ) = groupDao.updateName(groupId, name)

        suspend fun markMemberLeft(
            groupId: String,
            memberHash: String,
            now: Long,
        ) = groupMemberDao.markLeft(groupId, memberHash, now)

        /** Leave a group ourselves — a soft leave of our own member row. */
        suspend fun leaveGroup(
            groupId: String,
            myHash: String,
            now: Long,
        ) = groupMemberDao.markLeft(groupId, myHash, now)

        /**
         * Remove a group from this device entirely: fan-out status rows first
         * (they are resolved through `group_messages`, so they have to go
         * before it), then history, roster and the group itself.
         *
         * Local only — the other members keep their copy. Leaving and deleting
         * are separate steps by design: [leaveGroup] tells the group we are
         * gone, this drops what we still hold.
         */
        suspend fun deleteGroup(groupId: String) =
            transactionRunner.inTransaction {
                groupMessageStatusDao.deleteForGroup(groupId)
                groupMessageDao.deleteForGroup(groupId)
                groupMemberDao.deleteForGroup(groupId)
                groupDao.deleteGroup(groupId)
            }
    }
