package network.zamolxis.app.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import network.zamolxis.app.data.db.dao.PeerIconDao
import network.zamolxis.app.data.db.entity.ContactStatus
import network.zamolxis.app.data.db.entity.PeerIconEntity
import network.zamolxis.app.data.model.InterfaceType
import network.zamolxis.app.data.model.PqProtection
import network.zamolxis.app.data.repository.AnnounceRepository
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.data.repository.ConversationRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.data.repository.PqKeyRepository
import network.zamolxis.app.notifications.NotificationHelper
import network.zamolxis.app.rns.api.RnsCore
import network.zamolxis.app.rns.api.RnsLxmf
import network.zamolxis.app.rns.api.model.ReceivedMessage
import network.zamolxis.app.rns.host.util.PeerNameResolver
import network.zamolxis.app.service.pq.PqFieldsJson
import network.zamolxis.app.service.pq.PqMessageSealer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import network.zamolxis.app.data.repository.Message as DataMessage

/**
 * Application-level service that continuously collects messages and announces from the Reticulum protocol
 * and saves them to the database. This runs independently of any UI components, ensuring
 * messages and announces are received and stored even when no screens are open.
 *
 * This solves the issue where data was only collected when specific screens were active.
 *
 * Note: As of the service-side persistence implementation, announces and messages are now
 * primarily persisted by ServicePersistenceManager in the :reticulum service process.
 * This collector serves as a fallback/safety net and handles:
 * - Notifications for new messages/announces
 * - UI updates via repository flows
 * - De-duplication to avoid double-persistence
 */
// LongParameterList: this collector is the single place that turns inbound
// protocol events into stored rows and notifications, so it legitimately needs
// every repository involved. Grouping them into a holder would hide the
// dependencies from the graph without reducing them.
@Suppress("LongParameterList")
@Singleton
class MessageCollector
    @Inject
    constructor(
        private val rnsCore: RnsCore,
        private val rnsLxmf: RnsLxmf,
        private val conversationRepository: ConversationRepository,
        private val announceRepository: AnnounceRepository,
        private val contactRepository: ContactRepository,
        private val identityRepository: IdentityRepository,
        private val notificationHelper: NotificationHelper,
        private val peerIconDao: PeerIconDao,
        private val pqMessageSealer: PqMessageSealer,
        private val pqKeyRepository: PqKeyRepository,
    ) {
        companion object {
            private const val TAG = "MessageCollector"
            private const val PRESEED_WINDOW_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        }

        // Application-scoped coroutine for background message collection
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Track processed message IDs to avoid duplicates
        private val processedMessageIds = ConcurrentHashMap.newKeySet<String>()

        // Map of peer hashes to names (populated from announces)
        private val peerNames = ConcurrentHashMap<String, String>()

        // Statistics for monitoring
        private val _messagesCollected = MutableStateFlow(0)
        val messagesCollected: StateFlow<Int> = _messagesCollected

        private var isStarted = false

        /**
         * Start collecting messages. Should be called once during app initialization.
         */
        fun startCollecting() {
            if (isStarted) {
                Log.w(TAG, "Message collection already started")
                return
            }

            isStarted = true
            Log.i(TAG, "Starting message collection service")

            // Collect messages from the Reticulum protocol
            scope.launch {
                // Pre-seed processedMessageIds with recent received messages from the DB.
                // This prevents duplicate notifications when messages are replayed via SharedFlow
                // or re-broadcast by drainPendingMessages() after a service restart.
                // Bounded to last 30 days to avoid unbounded memory growth.
                // Done inside the collection coroutine to ensure it completes before we subscribe.
                try {
                    val thirtyDaysAgo = System.currentTimeMillis() - PRESEED_WINDOW_MS
                    val existingIds = conversationRepository.getReceivedMessageIds(since = thirtyDaysAgo)
                    processedMessageIds.addAll(existingIds)
                    Log.i(TAG, "Pre-seeded ${existingIds.size} existing message IDs for notification dedup")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pre-seed message IDs - duplicate notifications may occur", e)
                }

                try {
                    rnsLxmf.observeMessages().collect { receivedMessage ->
                        // De-duplicate: Skip if we've already processed this message in-memory
                        if (receivedMessage.messageHash in processedMessageIds) {
                            Log.d(TAG, "Skipping duplicate message ${receivedMessage.messageHash.take(16)} (in-memory cache)")
                            return@collect
                        }

                        // De-duplicate: Check if message already exists in database
                        // (may have been persisted by ServicePersistenceManager in service process)
                        val existingMessage = conversationRepository.getMessageById(receivedMessage.messageHash)
                        // An unopened row is unfinished work, not a duplicate. The
                        // service process persists what arrives, and it holds no
                        // hybrid key material, so a sealed message lands there as
                        // ciphertext with empty content. This process can open it, so
                        // it falls through to the normal path and the row is replaced
                        // with the real content — otherwise the user is left looking
                        // at a permanently blank message.
                        val awaitingUnseal =
                            PqProtection.fromStored(existingMessage?.pqStatus) == PqProtection.UNOPENED
                        if (existingMessage != null && !awaitingUnseal) {
                            processedMessageIds.add(receivedMessage.messageHash) // Add to cache to avoid repeat DB checks
                            Log.d(TAG, "Message ${receivedMessage.messageHash.take(16)} already in database - checking if notification needed")

                            // Even though message is persisted, we may still need to show notification
                            // and save icon appearance (service process can't do these)
                            val sourceHash = receivedMessage.sourceHash.joinToString("") { "%02x".format(it) }
                            val peerName = getPeerNameWithFallback(sourceHash)

                            // Save icon appearance even for already-persisted messages
                            // (ServicePersistenceManager doesn't have access to icon data)
                            receivedMessage.iconAppearance?.let { appearance ->
                                if (appearance.iconName.isNotEmpty() &&
                                    appearance.foregroundColor.isNotEmpty() &&
                                    appearance.backgroundColor.isNotEmpty()
                                ) {
                                    try {
                                        peerIconDao.upsertIcon(
                                            PeerIconEntity(
                                                destinationHash = sourceHash,
                                                iconName = appearance.iconName,
                                                foregroundColor = appearance.foregroundColor,
                                                backgroundColor = appearance.backgroundColor,
                                                updatedTimestamp = System.currentTimeMillis(),
                                            ),
                                        )
                                        Log.d(TAG, "Saved icon appearance for $sourceHash: ${appearance.iconName} (from duplicate message)")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to save icon appearance for $sourceHash", e)
                                    }
                                }
                            }

                            val isFavorite =
                                try {
                                    announceRepository.getAnnounce(sourceHash)?.isFavorite ?: false
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not check if peer is favorite", e)
                                    false
                                }

                            // Only notify if the message hasn't been read yet
                            // This prevents duplicate notifications after service restart
                            // for messages the user has already seen
                            if (!existingMessage.isRead) {
                                try {
                                    notificationHelper.notifyMessageReceived(
                                        destinationHash = sourceHash,
                                        peerName = peerName,
                                        // From the stored row, not the wire message.
                                        // This branch fires for a message already
                                        // persisted, and what was persisted is the
                                        // unsealed text — the wire copy of a sealed
                                        // message has an empty content slot.
                                        messagePreview = existingMessage.content.take(100),
                                        isFavorite = isFavorite,
                                    )
                                    Log.d(TAG, "Posted notification for already-persisted unread message")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to post notification for already-persisted message", e)
                                }
                            } else {
                                Log.d(TAG, "Skipping notification for already-read message ${receivedMessage.messageHash.take(16)}")
                            }

                            return@collect
                        }

                        // CRITICAL: Verify the message was sent to the current active identity
                        // This prevents messages from being saved to the wrong identity after switching
                        val activeIdentity = identityRepository.getActiveIdentitySync()
                        if (activeIdentity == null) {
                            Log.w(TAG, "No active identity - skipping message")
                            return@collect
                        }

                        val messageDestHash = receivedMessage.destinationHash.joinToString("") { "%02x".format(it) }
                        if (messageDestHash != activeIdentity.destinationHash) {
                            Log.w(
                                TAG,
                                "Message destination $messageDestHash doesn't match active identity " +
                                    "${activeIdentity.destinationHash} - skipping (sent to different identity)",
                            )
                            return@collect
                        }

                        processedMessageIds.add(receivedMessage.messageHash)
                        _messagesCollected.value++

                        val sourceHash = receivedMessage.sourceHash.joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Received new message #${_messagesCollected.value} from $sourceHash")

                        // Post-quantum layer: unseal the content and take in any key
                        // the sender attached. Returns the content unchanged for
                        // ordinary messages, so this is a no-op for the vast
                        // majority of traffic.
                        val pqIncoming = unsealIfNeeded(receivedMessage, sourceHash)
                        if (pqIncoming.protection == PqProtection.UNOPENED) {
                            // Stored, not dropped. The sender already holds a
                            // delivery proof, so discarding it here would leave the
                            // two sides permanently disagreeing about whether the
                            // message exists — and the ciphertext is still in
                            // fieldsJson, so resolving a key change or restoring a
                            // key pair can make it readable later.
                            Log.e(TAG, "Sealed message from $sourceHash could not be opened; storing as unreadable")
                        }

                        // Create data message for storage
                        val now = System.currentTimeMillis()
                        val dataMessage =
                            DataMessage(
                                id = receivedMessage.messageHash,
                                // From sender's perspective
                                destinationHash = sourceHash,
                                content = pqIncoming.content,
                                // Use sender's timestamp for display; receivedAt for sort ordering
                                timestamp = receivedMessage.timestamp,
                                isFromMe = false,
                                status = "delivered",
                                // LXMF attachments
                                fieldsJson = receivedMessage.fieldsJson,
                                // Routing info (hop count and receiving interface)
                                receivedHopCount = receivedMessage.receivedHopCount,
                                receivedInterface = receivedMessage.receivedInterface,
                                // Signal quality metrics (RNode/BLE; null on TCP/Auto/propagated)
                                receivedRssi = receivedMessage.receivedRssi,
                                receivedSnr = receivedMessage.receivedSnr,
                                // LXMF delivery method ("opportunistic" / "direct" / "propagated"
                                // / "paper"). Surfaced in MessageDetailScreen for received
                                // messages — most of the path-info cards (hop count, interface,
                                // RSSI/SNR) are null for propagation-fetched messages, so
                                // without this the detail screen renders essentially empty.
                                deliveryMethod = receivedMessage.deliveryMethod,
                                // Local reception time for sort ordering
                                receivedAt = now,
                                // What the post-quantum layer did to this specific
                                // message, recorded now rather than derived later:
                                // the conversation's ability to seal changes over
                                // time, and re-deriving would relabel history.
                                pqProtection = pqIncoming.protection,
                            )

                        // Get peer name from cache, existing conversation, or use formatted hash
                        val peerName = getPeerNameWithFallback(sourceHash)

                        // Save to database - this creates/updates the conversation and adds the message
                        try {
                            // Prefer public key from message (directly from RNS identity cache)
                            // Fall back to peer_identities lookup if not in message
                            val messagePublicKey = receivedMessage.publicKey
                            val publicKey =
                                messagePublicKey
                                    ?: conversationRepository.getPeerPublicKey(sourceHash)

                            // Store public key to peer_identities if we got it from the message
                            // This ensures future lookups will find it
                            if (messagePublicKey != null) {
                                conversationRepository.updatePeerPublicKey(sourceHash, messagePublicKey)
                                Log.d(TAG, "Stored sender's public key for $sourceHash")
                            }

                            // Store sender's icon appearance if present (Sideband/MeshChat interop)
                            // Icons are stored in peer_icons table (LXMF concept), separate from announces (Reticulum concept)
                            receivedMessage.iconAppearance?.let { appearance ->
                                if (appearance.iconName.isNotEmpty() &&
                                    appearance.foregroundColor.isNotEmpty() &&
                                    appearance.backgroundColor.isNotEmpty()
                                ) {
                                    try {
                                        peerIconDao.upsertIcon(
                                            PeerIconEntity(
                                                destinationHash = sourceHash,
                                                iconName = appearance.iconName,
                                                foregroundColor = appearance.foregroundColor,
                                                backgroundColor = appearance.backgroundColor,
                                                updatedTimestamp = System.currentTimeMillis(),
                                            ),
                                        )
                                        Log.d(TAG, "Saved icon appearance for $sourceHash: ${appearance.iconName}")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to save icon appearance for $sourceHash", e)
                                    }
                                }
                            }

                            conversationRepository.saveMessage(sourceHash, peerName, dataMessage, publicKey)
                            Log.d(TAG, "Message saved to database for peer ${sourceHash.take(16)} (hasPublicKey=${publicKey != null})")

                            // Check if sender is a saved peer (favorite)
                            val isFavorite =
                                try {
                                    announceRepository.getAnnounce(sourceHash)?.isFavorite ?: false
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not check if peer is favorite", e)
                                    false
                                }

                            // Show notification for received message.
                            // Suppressed for a message we already knew about and still
                            // cannot open: the row is being retried on every replay, and
                            // re-announcing the same unreadable message each time the app
                            // starts would be noise, not information.
                            val alreadyKnownAndStillSealed =
                                awaitingUnseal && pqIncoming.protection == PqProtection.UNOPENED
                            try {
                                if (alreadyKnownAndStillSealed) {
                                    Log.d(TAG, "Not re-notifying for a still-unreadable message from $sourceHash")
                                } else {
                                    notificationHelper.notifyMessageReceived(
                                        destinationHash = sourceHash,
                                        peerName = peerName,
                                        // The unsealed content, not the raw message: a
                                        // sealed message carries an empty content slot,
                                        // so the raw value would notify the user about a
                                        // message that looks blank.
                                        messagePreview = pqIncoming.content.take(100),
                                        isFavorite = isFavorite,
                                        // A sealed message we could not open has no
                                        // preview; the helper substitutes a localised
                                        // placeholder rather than notifying about what
                                        // looks like a blank message.
                                        isUnreadable = pqIncoming.protection == PqProtection.UNOPENED,
                                    )
                                    Log.d(TAG, "Posted notification for message (favorite: $isFavorite)")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to post message notification", e)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save message to database", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in message collection loop", e)
                    // Restart collection after error
                    if (isStarted) {
                        Log.w(TAG, "Restarting message collection after error")
                        isStarted = false
                        startCollecting()
                    }
                }
            }

            // Also observe announces to build peer name mapping, store public keys, and persist to database
            scope.launch {
                try {
                    rnsCore.observeAnnounces().collect { announce ->
                        // Conversations are keyed by destination hash (LXMF destination)
                        // But we also need the identity hash for some operations
                        val destinationHash = announce.destinationHash.joinToString("") { "%02x".format(it) }
                        val identityHash = announce.identity.hash.joinToString("") { "%02x".format(it) }

                        Log.d(TAG, "Processing announce: destHash=$destinationHash, identityHash=$identityHash")

                        // Store the public key mapped to the IDENTITY hash (for Reticulum identity restoration)
                        // Note: Conversations are keyed by destination hash, but peer identities use identity hash
                        val peerHash = destinationHash // For conversation/announce operations
                        val publicKey = announce.identity.publicKey
                        if (publicKey.isNotEmpty()) {
                            try {
                                // CRITICAL: Use identity hash for peer identity storage, not destination hash
                                // This allows Reticulum to properly restore identities (hash must match public key)
                                conversationRepository.updatePeerPublicKey(identityHash, publicKey)
                                Log.d(TAG, "Stored public key for peer identity: $identityHash (${publicKey.size} bytes)")
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not store public key for peer", e)
                            }
                        }

                        // Extract name from app_data using smart parser
                        // Prefers displayName from Python's LXMF.display_name_from_app_data()
                        val appData = announce.appData

                        // A post-quantum fingerprint in the announce means this peer
                        // can read sealed messages. Recorded against the destination
                        // hash, which is what the send path keys on.
                        network.zamolxis.app.rns.api.util.AppDataParser
                            .parsePqFingerprint(appData)
                            ?.let { fingerprint ->
                                try {
                                    pqKeyRepository.recordAnnouncedFingerprint(peerHash, fingerprint)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not record post-quantum fingerprint for $peerHash", e)
                                }
                            }
                        val peerName =
                            network.zamolxis.app.reticulum.util.AppDataParser.extractPeerName(
                                appData,
                                peerHash,
                                announce.displayName,
                            )

                        // Cache and update peer name if successfully extracted
                        if (PeerNameResolver.isValidPeerName(peerName)) {
                            peerNames[peerHash] = peerName
                            Log.d(TAG, "Learned peer name for ${peerHash.take(16)}")

                            // Update existing conversation with the new name
                            conversationRepository.updatePeerName(peerHash, peerName)
                        }

                        // Persist announce to database - this ensures announces are saved even when
                        // Discovered Nodes page is not open
                        // Note: ServicePersistenceManager may have already persisted this announce;
                        // saveAnnounce uses UPSERT, so re-saving is safe (just updates timestamp)
                        try {
                            // For propagation nodes, extract transfer size limit from app_data
                            val propagationTransferLimitKb =
                                if (announce.nodeType.name == "PROPAGATION_NODE") {
                                    val metadata =
                                        network.zamolxis.app.reticulum.util.AppDataParser
                                            .extractPropagationNodeMetadata(appData)
                                    metadata.transferLimitKb
                                } else {
                                    null
                                }

                            // Check if announce already exists with recent timestamp (saved by service)
                            // If it exists and was updated within last 5 seconds, skip to avoid unnecessary write
                            // Exception: always update propagation nodes missing their transfer limit
                            val existingAnnounce = announceRepository.getAnnounce(peerHash)
                            val fiveSecondsAgo = System.currentTimeMillis() - 5000
                            val needsTransferLimitUpdate =
                                existingAnnounce != null &&
                                    existingAnnounce.nodeType == "PROPAGATION_NODE" &&
                                    existingAnnounce.propagationTransferLimitKb == null &&
                                    propagationTransferLimitKb != null

                            if (existingAnnounce != null &&
                                existingAnnounce.lastSeenTimestamp > fiveSecondsAgo &&
                                !needsTransferLimitUpdate
                            ) {
                                Log.d(TAG, "Announce already persisted by service: ${peerHash.take(16)}")
                            } else {
                                announceRepository.saveAnnounce(
                                    destinationHash = peerHash,
                                    peerName = peerName,
                                    publicKey = publicKey,
                                    appData = appData,
                                    hops = announce.hops,
                                    timestamp = announce.timestamp,
                                    nodeType = announce.nodeType.name,
                                    receivingInterface = announce.receivingInterface,
                                    receivingInterfaceType = InterfaceType.fromName(announce.receivingInterface).storageName,
                                    aspect = announce.aspect,
                                    stampCost = announce.stampCost,
                                    stampCostFlexibility = announce.stampCostFlexibility,
                                    peeringCost = announce.peeringCost,
                                    propagationTransferLimitKb = propagationTransferLimitKb,
                                )
                                Log.d(
                                    TAG,
                                    "Persisted announce to database (fallback): ${peerHash.take(16)}" +
                                        if (propagationTransferLimitKb != null) " (transfer limit: ${propagationTransferLimitKb}KB)" else "",
                                )
                            }

                            // Check if this announce resolves a pending contact
                            if (publicKey.isNotEmpty()) {
                                try {
                                    val pendingContact = contactRepository.getContact(peerHash)
                                    if (pendingContact?.status == ContactStatus.PENDING_IDENTITY) {
                                        contactRepository.updateContactWithIdentity(peerHash, publicKey)
                                        Log.i(TAG, "Resolved pending contact from announce: $peerHash")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error checking/updating pending contact", e)
                                }
                            }

                            // Show notification for heard announce
                            try {
                                notificationHelper.notifyAnnounceHeard(
                                    destinationHash = peerHash,
                                    peerName = peerName,
                                    hops = announce.hops,
                                    interfaceType = InterfaceType.fromName(announce.receivingInterface),
                                    receivingInterface = announce.receivingInterface,
                                )
                                Log.d(TAG, "Posted notification for announce")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to post announce notification", e)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist announce to database", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing announces for names", e)
                }
            }
        }

        /**
         * Get a display name for a peer, using cached name or formatted hash
         */
        private fun getPeerName(peerHash: String): String {
            // Check if we have a cached name from announces
            peerNames[peerHash]?.let { return it }

            // Format the hash as a short, readable identifier
            return PeerNameResolver.formatHashAsFallback(peerHash)
        }

        /**
         * Get peer name with fallback - uses PeerNameResolver for consistent lookup across the app
         */
        /**
         * Run an inbound message through the post-quantum layer.
         *
         * Never returns null and never drops a message. A sealed payload that
         * cannot be opened comes back as [PqProtection.UNOPENED] with empty
         * content, which is stored as a placeholder the user can see — silently
         * discarding it would leave the sender holding a delivery proof for a
         * message the recipient never learns about, and would throw away
         * ciphertext that resolving a key change could still open.
         *
         * Any failure inside the layer degrades to "store what arrived": a fault in
         * the cryptography must not swallow ordinary messages.
         */
        private suspend fun unsealIfNeeded(
            receivedMessage: ReceivedMessage,
            sourceHash: String,
        ): PqMessageSealer.Incoming {
            val fields = PqFieldsJson.extract(receivedMessage.fieldsJson)
            val asReceived =
                PqMessageSealer.Incoming(
                    content = receivedMessage.content,
                    protection = PqProtection.NONE,
                )
            if (fields.isEmpty()) return asReceived

            val activeIdentity =
                runCatching { identityRepository.getActiveIdentitySync() }.getOrNull()
            if (activeIdentity == null) {
                Log.w(TAG, "No active identity; storing message from $sourceHash as received")
                return asReceived
            }

            return runCatching {
                pqMessageSealer.processIncoming(
                    identityHash = activeIdentity.identityHash,
                    // The AAD the sender bound the payload to is (their destination
                    // hash, ours) — not the identity hash, which the sender never
                    // sees.
                    ourDestinationHash = activeIdentity.destinationHash,
                    peerHash = sourceHash,
                    fallbackContent = receivedMessage.content,
                    fields = fields,
                    hasAttachments = PqFieldsJson.hasUnsealedAttachments(receivedMessage.fieldsJson),
                )
            }.getOrElse {
                Log.e(TAG, "Post-quantum processing failed for message from $sourceHash", it)
                asReceived
            }
        }

        private suspend fun getPeerNameWithFallback(peerHash: String): String {
            val resolvedName =
                PeerNameResolver.resolve(
                    peerHash = peerHash,
                    cachedName = peerNames[peerHash],
                    contactNicknameLookup = {
                        contactRepository.getContact(peerHash)?.customNickname
                    },
                    announcePeerNameLookup = {
                        announceRepository.getAnnounce(peerHash)?.peerName
                    },
                    conversationPeerNameLookup = {
                        conversationRepository.getConversation(peerHash)?.peerName
                    },
                )

            // Cache the resolved name if it's valid
            if (PeerNameResolver.isValidPeerName(resolvedName)) {
                peerNames[peerHash] = resolvedName
            }

            return resolvedName
        }

        /**
         * Update peer name (can be called when announces are received)
         */
        fun updatePeerName(
            peerHash: String,
            name: String,
        ) {
            if (name.isNotBlank()) {
                peerNames[peerHash] = name
                Log.d(TAG, "Updated peer name for ${peerHash.take(16)}")

                // Update in database too
                scope.launch {
                    try {
                        conversationRepository.updatePeerName(peerHash, name)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update peer name in database", e)
                    }
                }
            }
        }

        /**
         * Stop collecting messages (for cleanup)
         */
        fun stopCollecting() {
            isStarted = false
            // CRITICAL: Clear peer names cache to prevent stale data after identity switch
            // This ensures fresh data is fetched from database or announces when restarted
            peerNames.clear()
            processedMessageIds.clear()
            Log.i(TAG, "Stopped message collection service (caches cleared)")
        }

        /**
         * Get statistics about collected messages
         */
        fun getStats(): String = "Messages collected: ${_messagesCollected.value}, Known peers: ${peerNames.size}"
    }
