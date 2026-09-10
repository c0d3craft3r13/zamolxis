package network.zamolxis.app.data.repository

import android.util.Log
import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import network.zamolxis.app.data.db.dao.PqEpochDao
import network.zamolxis.app.data.db.entity.PqEpochEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The post-quantum epochs a conversation is sealed under.
 *
 * Keeps the roots wrapped on the way in and unwrapped on the way out, and
 * decides when an epoch has run long enough to be replaced. The crypto is in
 * `PqEpoch`; what lives here is the bookkeeping that makes it usable across
 * restarts — which epoch is current, which counter is next, and which old roots
 * are still needed to read mail that has not arrived yet.
 */
@Singleton
class PqEpochRepository
    @Inject
    constructor(
        private val dao: PqEpochDao,
        private val encryptor: SecretBlobEncryptor,
    ) {
        companion object {
            private const val TAG = "PqEpochRepository"

            /**
             * Messages one epoch carries before a fresh handshake.
             *
             * The handshake costs about 1.1 KB and everything after it costs 29
             * bytes, so this is how thinly that cost is spread. Five hundred is
             * ordinary weeks of conversation, and small enough that a root, if it
             * ever leaks, does not open a year.
             */
            const val MESSAGES_PER_EPOCH = 500

            /** And an epoch is replaced on age even if it stays quiet. */
            const val EPOCH_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000

            /**
             * How long a root is kept after it was last used.
             *
             * A propagation node holds a message for thirty days
             * (`LXMRouter.MESSAGE_EXPIRY`), so anything shorter turns ordinary late
             * delivery into mail that arrives and cannot be read. Thirty-five gives
             * that limit room rather than sitting exactly on it.
             */
            const val ROOT_RETENTION_MS = 35L * 24 * 60 * 60 * 1000
        }

        /** An epoch with its root in the clear, ready to seal or open with. */
        class Usable(
            val epochId: String,
            val root: ByteArray,
            /** The counter reserved for this message. Meaningless on an inbound epoch. */
            val counter: Int,
        )

        /**
         * Reserve the next counter of the epoch we are sealing to [peerHash] with.
         *
         * @return null when there is no current epoch, when it has run its course,
         *   or when its root cannot be unwrapped — all of which mean the same thing
         *   to the caller: open a new one.
         */
        suspend fun reserveOutbound(
            identityHash: String,
            peerHash: String,
            now: Long = System.currentTimeMillis(),
        ): Usable? {
            val current = dao.currentOutbound(identityHash, peerHash)?.takeIf { !isSpent(it, now) } ?: return null
            val reserved = dao.reserveNext(identityHash, peerHash, now) ?: current
            return unwrap(reserved)?.let {
                Usable(epochId = reserved.epochId, root = it, counter = reserved.nextCounter)
            }
        }

        /**
         * Remember the epoch we have just opened to [peerHash], replacing any before it.
         *
         * Old roots are dropped here rather than on a timer. This is the moment the
         * table grows, it happens about once a week per conversation, and hanging
         * the cleanup off it means the retention window is enforced by the same
         * code path that creates the thing being retained — rather than by a
         * scheduled job that can quietly stop running and leave every root ever
         * derived sitting on the device.
         */
        suspend fun startOutbound(
            identityHash: String,
            peerHash: String,
            epochId: String,
            root: ByteArray,
            now: Long = System.currentTimeMillis(),
        ) {
            dao.replaceOutbound(
                PqEpochEntity(
                    identityHash = identityHash,
                    peerHash = peerHash,
                    outbound = true,
                    epochId = epochId,
                    encryptedRoot = encryptor.encryptBlobWithDeviceKey(root),
                    // The opening is counter zero, so the next message is one.
                    nextCounter = 1,
                    messageCount = 1,
                    createdTimestamp = now,
                    lastUsedTimestamp = now,
                ),
            )
            prune(now)
        }

        /** Remember an epoch a peer opened to us. */
        suspend fun acceptInbound(
            identityHash: String,
            peerHash: String,
            epochId: String,
            root: ByteArray,
            now: Long = System.currentTimeMillis(),
        ) {
            dao.upsert(
                PqEpochEntity(
                    identityHash = identityHash,
                    peerHash = peerHash,
                    outbound = false,
                    epochId = epochId,
                    encryptedRoot = encryptor.encryptBlobWithDeviceKey(root),
                    nextCounter = 0,
                    messageCount = 1,
                    createdTimestamp = now,
                    lastUsedTimestamp = now,
                ),
            )
        }

        /**
         * The root a peer's message names, or null if we do not hold it.
         *
         * Null is an ordinary outcome, not a fault: it means the opening never
         * arrived, or arrived on a device this one was not restored from. The
         * message stays stored and unreadable rather than being discarded, and the
         * next epoch the peer opens starts the conversation working again.
         */
        suspend fun inboundRoot(
            identityHash: String,
            peerHash: String,
            epochId: String,
            now: Long = System.currentTimeMillis(),
        ): ByteArray? {
            val stored = dao.inbound(identityHash, peerHash, epochId) ?: return null
            val root = unwrap(stored) ?: return null
            dao.touchInbound(identityHash, peerHash, epochId, now)
            return root
        }

        /** Drop roots that nothing still in flight could need. */
        suspend fun prune(now: Long = System.currentTimeMillis()): Int = dao.deleteOlderThan(now - ROOT_RETENTION_MS)

        /**
         * Whether an epoch has carried enough, or gone on long enough, to retire.
         *
         * Both limits matter for the same reason and neither is about secrecy
         * within the epoch — that does not exist here. They bound how much one
         * root is worth, and they bound how far a counter can run.
         */
        private fun isSpent(
            epoch: PqEpochEntity,
            now: Long,
        ): Boolean =
            epoch.messageCount >= MESSAGES_PER_EPOCH ||
                now - epoch.createdTimestamp >= EPOCH_LIFETIME_MS ||
                now < epoch.createdTimestamp

        /**
         * A root that will not unwrap is a root that is gone.
         *
         * That happens when the Keystore key did not survive a device restore. The
         * epoch is unusable either way; saying so here means the send path opens a
         * fresh one instead of failing the message.
         */
        private fun unwrap(epoch: PqEpochEntity): ByteArray? =
            try {
                encryptor.decryptBlobWithDeviceKey(epoch.encryptedRoot)
            } catch (e: Exception) {
                Log.w(TAG, "Epoch ${epoch.epochId} root cannot be unwrapped", e)
                null
            }
    }
