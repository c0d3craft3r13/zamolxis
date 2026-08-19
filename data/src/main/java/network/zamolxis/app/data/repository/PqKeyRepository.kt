package network.zamolxis.app.data.repository

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import network.zamolxis.app.data.db.dao.PqKeyDao
import network.zamolxis.app.data.db.entity.LocalPqKeyEntity
import network.zamolxis.app.data.db.entity.PeerPqKeyEntity
import network.zamolxis.app.data.db.entity.PqKeyDeliveryEntity
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.HybridKemException
import network.zamolxis.crypto.pq.HybridKeyCodec
import network.zamolxis.crypto.pq.HybridKeyPair
import network.zamolxis.crypto.pq.HybridPublicKey
import network.zamolxis.crypto.pq.PeerPqSupport
import network.zamolxis.crypto.pq.PqKeyExchange

/**
 * Owns hybrid post-quantum key material: generating our own, storing peers', and
 * answering the one question the send path needs — can this peer read a sealed
 * message.
 *
 * The private halves are wrapped by the Android Keystore before they touch the
 * database, matching how the Reticulum identity key is protected. That matters
 * more than usual here: the message database itself is not encrypted, so an
 * unwrapped key sitting in a table would be readable by anyone who extracted the
 * app's data directory.
 */
@Singleton
class PqKeyRepository
    @Inject
    constructor(
        private val dao: PqKeyDao,
        private val encryptor: SecretBlobEncryptor,
        private val kem: HybridKem,
    ) {
        /**
         * Our key pair for [identityHash], generating and storing one on first use.
         *
         * @return the pair, or null if the stored blob cannot be unwrapped — which
         *   happens when the Keystore key did not survive a device restore. Null is
         *   returned rather than regenerating: a fresh key would silently orphan
         *   every peer that already holds the old one, and the user needs to be told
         *   instead.
         */
        suspend fun ourKeyPair(identityHash: String): HybridKeyPair? {
            dao.getLocalKey(identityHash)?.let { stored ->
                return try {
                    HybridKeyCodec.decodeKeyPair(encryptor.decryptBlobWithDeviceKey(stored.encryptedKeyPair))
                } catch (e: HybridKemException) {
                    Log.e(TAG, "Stored hybrid key pair for $identityHash is unreadable", e)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot unwrap hybrid key pair for $identityHash", e)
                    null
                }
            }

            val generated = kem.generateKeyPair()
            dao.upsertLocalKey(
                LocalPqKeyEntity(
                    identityHash = identityHash,
                    publicKey = HybridKeyCodec.encode(generated.publicKey),
                    encryptedKeyPair =
                        encryptor.encryptBlobWithDeviceKey(HybridKeyCodec.encodeKeyPair(generated)),
                    createdTimestamp = System.currentTimeMillis(),
                ),
            )
            Log.i(TAG, "Generated hybrid post-quantum key pair for identity $identityHash")
            return generated
        }

        /** Our public key, for announcing a fingerprint or attaching to a message. */
        suspend fun ourPublicKey(identityHash: String): HybridPublicKey? =
            ourKeyPair(identityHash)?.publicKey

        /** The 16-byte fingerprint to advertise in announces. */
        suspend fun ourFingerprint(identityHash: String): ByteArray? =
            ourPublicKey(identityHash)?.let(HybridKeyCodec::fingerprint)

        // ------------------------------------------------------------ peer state

        /** Everything the exchange logic needs about one peer. */
        suspend fun peerState(
            identityHash: String,
            peerHash: String,
        ): PqKeyExchange.PeerState {
            val stored = dao.getPeerKey(peerHash)
            return PqKeyExchange.PeerState(
                knownKey = stored?.usableKey(),
                announcedFingerprint = stored?.announcedFingerprint,
                ourKeyDelivered = dao.hasDeliveredOurKey(identityHash, peerHash),
            )
        }

        /** Whether this peer can read a sealed message right now. */
        suspend fun supportFor(
            identityHash: String,
            peerHash: String,
        ): PeerPqSupport = PqKeyExchange.support(peerState(identityHash, peerHash))

        /**
         * A peer's stored key, or null while an unresolved key change is pending.
         *
         * Sealing to a key that may have been substituted is worse than not
         * sealing: it produces a conversation the user believes is protected while
         * an attacker reads it.
         */
        private fun PeerPqKeyEntity.usableKey(): HybridPublicKey? {
            if (keyChangeUnresolved) return null
            val encoded = publicKey ?: return null
            return try {
                HybridKeyCodec.decode(encoded)
            } catch (e: HybridKemException) {
                Log.e(TAG, "Stored hybrid key for $peerHash is corrupt", e)
                null
            }
        }

        // ------------------------------------------------------ incoming updates

        /** Record the fingerprint from a peer's announce. Never clears a stored key. */
        suspend fun recordAnnouncedFingerprint(
            peerHash: String,
            fingerprint: ByteArray,
        ) {
            dao.recordAnnouncedFingerprint(peerHash, fingerprint, System.currentTimeMillis())
        }

        /**
         * Handle a hybrid key that arrived in a message.
         *
         * @return what was decided, so the caller can surface a mismatch or a
         *   change to the user rather than letting it pass unseen
         */
        suspend fun acceptIncomingKey(
            identityHash: String,
            peerHash: String,
            offered: HybridPublicKey,
        ): PqKeyExchange.KeyAcceptance {
            val state = peerState(identityHash, peerHash)
            val outcome = PqKeyExchange.acceptIncomingKey(offered, state)
            val now = System.currentTimeMillis()

            when (outcome) {
                PqKeyExchange.KeyAcceptance.Accepted -> {
                    val existing = dao.getPeerKey(peerHash)
                    dao.upsertPeerKey(
                        existing?.copy(
                            publicKey = HybridKeyCodec.encode(offered),
                            updatedTimestamp = now,
                        ) ?: PeerPqKeyEntity(
                            peerHash = peerHash,
                            publicKey = HybridKeyCodec.encode(offered),
                            updatedTimestamp = now,
                        ),
                    )
                    Log.i(TAG, "Accepted hybrid key from $peerHash")
                }

                PqKeyExchange.KeyAcceptance.ChangedKey -> {
                    // Stored key deliberately left in place. Replacing it here is
                    // exactly the substitution we are trying to detect. The offered
                    // key is kept aside so the user can still accept a genuine
                    // rotation — the peer will not send it again once it believes
                    // we have a key.
                    dao.flagKeyChange(peerHash, HybridKeyCodec.encode(offered), now)
                    Log.w(TAG, "Peer $peerHash offered a different hybrid key; flagged for review")
                }

                PqKeyExchange.KeyAcceptance.FingerprintMismatch -> {
                    // Persisted, not just logged. This is the loudest signal the
                    // layer can produce — the announce or the message was altered
                    // in transit — and the only party who can resolve it is the
                    // user, by checking the key against the person out of band.
                    dao.recordFingerprintMismatch(peerHash, now)
                    Log.w(TAG, "Rejected hybrid key from $peerHash: does not match announced fingerprint")
                }

                PqKeyExchange.KeyAcceptance.AlreadyKnown -> Unit
            }
            return outcome
        }

        /** Note that our key reached this peer, so it stops being attached. */
        suspend fun markOurKeyDelivered(
            identityHash: String,
            peerHash: String,
        ) {
            dao.recordDelivery(
                PqKeyDeliveryEntity(
                    identityHash = identityHash,
                    peerHash = peerHash,
                    deliveredTimestamp = System.currentTimeMillis(),
                ),
            )
        }

        /** Peers whose key changed without explanation, for the UI to raise. */
        fun observeUnresolvedKeyChanges(): Flow<List<String>> =
            dao.observeUnresolvedKeyChanges().map { rows -> rows.map { it.peerHash } }

        /** Whether this peer currently has a key change awaiting a decision. */
        suspend fun hasUnresolvedKeyChange(peerHash: String): Boolean =
            dao.getPeerKey(peerHash)?.keyChangeUnresolved == true

        /**
         * The fingerprints either side of a pending key change, for the user to
         * compare out of band — over a phone call, or in person.
         *
         * Showing both is the point. "This contact's key changed" is unactionable
         * on its own; a user who can read the new fingerprint back to the person
         * they think they are talking to can actually tell a reinstall from an
         * impostor.
         *
         * @return trusted fingerprint to pending fingerprint, or null if there is
         *   no pending change
         */
        // ReturnCount: four of these are "there is no pending change to show" from
        // four different directions — no row, not flagged, no trusted key, no
        // pending key. Nesting them would put the one interesting branch four
        // levels deep.
        @Suppress("ReturnCount")
        suspend fun keyChangeFingerprints(peerHash: String): Pair<ByteArray, ByteArray>? {
            val row = dao.getPeerKey(peerHash) ?: return null
            if (!row.keyChangeUnresolved) return null
            val trusted = row.publicKey ?: return null
            val pending = row.pendingPublicKey ?: return null
            return try {
                HybridKeyCodec.fingerprint(HybridKeyCodec.decode(trusted)) to
                    HybridKeyCodec.fingerprint(HybridKeyCodec.decode(pending))
            } catch (e: HybridKemException) {
                Log.e(TAG, "Cannot render key-change fingerprints for $peerHash", e)
                null
            }
        }

        /** Whether this peer has an unacknowledged fingerprint mismatch. */
        suspend fun hasFingerprintMismatch(peerHash: String): Boolean =
            dao.getPeerKey(peerHash)?.fingerprintMismatchTimestamp != null

        /** Mark the mismatch as seen, once the user has been shown it. */
        suspend fun acknowledgeFingerprintMismatch(peerHash: String) {
            dao.clearFingerprintMismatch(peerHash, System.currentTimeMillis())
        }

        /**
         * Replace this identity's hybrid key pair with a fresh one.
         *
         * The old key stops being used the moment the row is overwritten. Delivery
         * records are cleared in the same breath, because every peer still believes
         * it holds our key: without that, the replacement would never be attached
         * to a message and those conversations would go quiet in one direction —
         * they would seal to a key we no longer have.
         *
         * Peers see the new key as a change and are asked to confirm it, which is
         * the correct outcome: from their side a rotation and an impostor look
         * identical, and only the user can tell them apart.
         *
         * @return the new public key, or null if generation or storage failed
         */
        suspend fun rotateOurKeyPair(identityHash: String): HybridPublicKey? =
            try {
                val generated = kem.generateKeyPair()
                dao.upsertLocalKey(
                    LocalPqKeyEntity(
                        identityHash = identityHash,
                        publicKey = HybridKeyCodec.encode(generated.publicKey),
                        encryptedKeyPair =
                            encryptor.encryptBlobWithDeviceKey(HybridKeyCodec.encodeKeyPair(generated)),
                        createdTimestamp = System.currentTimeMillis(),
                    ),
                )
                dao.clearDeliveriesFor(identityHash)
                Log.i(TAG, "Rotated hybrid post-quantum key pair for identity $identityHash")
                generated.publicKey
            } catch (e: Exception) {
                Log.e(TAG, "Could not rotate the hybrid key pair for $identityHash", e)
                null
            }

        /**
         * Resolve a pending key change on the user's explicit instruction.
         *
         * @param accept true to trust the new key, false to keep the existing one
         *   and discard the offer
         */
        suspend fun resolveKeyChange(
            peerHash: String,
            accept: Boolean,
        ) {
            val now = System.currentTimeMillis()
            if (accept) {
                dao.acceptPendingKey(peerHash, now)
                Log.i(TAG, "User accepted the replacement hybrid key for $peerHash")
            } else {
                dao.rejectPendingKey(peerHash, now)
                Log.i(TAG, "User rejected the replacement hybrid key for $peerHash")
            }
        }

        private companion object {
            private const val TAG = "PqKeyRepository"
        }
    }
