package network.zamolxis.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import network.zamolxis.app.data.db.entity.LocalPqKeyEntity
import network.zamolxis.app.data.db.entity.PeerPqKeyEntity
import network.zamolxis.app.data.db.entity.PqKeyDeliveryEntity
import network.zamolxis.app.data.db.entity.RetiredPqKeyEntity

/**
 * Storage for hybrid post-quantum key material.
 *
 * Split across three tables — our key pairs, peers' public keys, and which pairs
 * have exchanged — because each has a different lifetime and a different scope.
 */
@Dao
interface PqKeyDao {
    // ------------------------------------------------------------ our own keys

    @Query("SELECT * FROM local_pq_keys WHERE identityHash = :identityHash")
    suspend fun getLocalKey(identityHash: String): LocalPqKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalKey(key: LocalPqKeyEntity)

    /**
     * Pairs rotated out but still inside the retention window, newest first.
     *
     * Newest first because a message that will open under a retired key is most
     * likely to open under the one most recently retired, and every miss costs a
     * decapsulation.
     */
    @Query("SELECT * FROM retired_pq_keys WHERE identityHash = :identityHash ORDER BY retiredTimestamp DESC")
    suspend fun getRetiredKeys(identityHash: String): List<RetiredPqKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRetiredKey(key: RetiredPqKeyEntity)

    /**
     * Forget pairs past the retention window.
     *
     * This is the step that makes rotation mean anything: until the old private
     * half is gone, it still opens every sealed message ever recorded under it.
     */
    @Query("DELETE FROM retired_pq_keys WHERE retiredTimestamp < :before")
    suspend fun deleteRetiredKeysBefore(before: Long): Int

    /**
     * Rotate: retire what is stored, then install the replacement.
     *
     * One transaction, because the state between the two — no live key and a
     * retired one — is a state where sealing has no key to use at all.
     */
    @Transaction
    suspend fun rotateLocalKey(
        replacement: LocalPqKeyEntity,
        retiredAt: Long,
    ) {
        getLocalKey(replacement.identityHash)?.let { previous ->
            insertRetiredKey(
                RetiredPqKeyEntity(
                    identityHash = previous.identityHash,
                    publicKeyHex = previous.publicKey.joinToString("") { "%02x".format(it) },
                    retiredTimestamp = retiredAt,
                    encryptedKeyPair = previous.encryptedKeyPair,
                ),
            )
        }
        upsertLocalKey(replacement)
    }

    // ---------------------------------------------------------- peers' keys

    @Query("SELECT * FROM peer_pq_keys WHERE peerHash = :peerHash")
    suspend fun getPeerKey(peerHash: String): PeerPqKeyEntity?

    @Query("SELECT * FROM peer_pq_keys WHERE peerHash = :peerHash")
    fun observePeerKey(peerHash: String): Flow<PeerPqKeyEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeerKey(key: PeerPqKeyEntity)

    /**
     * Record the sealed format a peer says it can read.
     *
     * A targeted UPDATE rather than a row replace, for the same reason
     * [recordAnnouncedFingerprint] is: this arrives on every message, and letting
     * it rewrite the whole row would give a malformed one a way to blank a key we
     * already accepted.
     *
     * No row means nothing to record. The declaration is only ever useful
     * alongside a key, and the key's own arrival creates the row.
     */
    @Query("UPDATE peer_pq_keys SET protocolVersion = :version, updatedTimestamp = :now WHERE peerHash = :peerHash")
    suspend fun recordProtocolVersion(
        peerHash: String,
        version: Int,
        now: Long,
    )

    /**
     * Record the fingerprint from an announce without disturbing a key we
     * already hold.
     *
     * Deliberately not a REPLACE of the whole row: an announce is unauthenticated
     * at this layer, and letting one blank out a key we already accepted would
     * hand an attacker a way to force the conversation back down to classical
     * cryptography just by broadcasting.
     */
    @Transaction
    suspend fun recordAnnouncedFingerprint(
        peerHash: String,
        fingerprint: ByteArray,
        now: Long,
    ) {
        val existing = getPeerKey(peerHash)
        upsertPeerKey(
            existing?.copy(announcedFingerprint = fingerprint, updatedTimestamp = now)
                ?: PeerPqKeyEntity(
                    peerHash = peerHash,
                    announcedFingerprint = fingerprint,
                    updatedTimestamp = now,
                ),
        )
    }

    /**
     * Flag a peer whose offered key contradicts the one we hold, and keep the
     * offered key aside pending a human decision.
     *
     * The stored `publicKey` is deliberately left untouched — overwriting it here
     * is exactly the substitution this flag exists to catch.
     */
    @Query(
        "UPDATE peer_pq_keys SET keyChangeUnresolved = 1, pendingPublicKey = :offered, " +
            "updatedTimestamp = :now WHERE peerHash = :peerHash",
    )
    suspend fun flagKeyChange(
        peerHash: String,
        offered: ByteArray,
        now: Long,
    )

    /**
     * Accept the pending key: it becomes the trusted one and the flag clears.
     *
     * Only ever called from an explicit user decision.
     */
    @Query(
        "UPDATE peer_pq_keys SET publicKey = pendingPublicKey, pendingPublicKey = NULL, " +
            "keyChangeUnresolved = 0, updatedTimestamp = :now " +
            "WHERE peerHash = :peerHash AND pendingPublicKey IS NOT NULL",
    )
    suspend fun acceptPendingKey(
        peerHash: String,
        now: Long,
    )

    /**
     * Reject the pending key: it is discarded and the previously trusted key stands.
     *
     * The flag clears too, so sealing resumes with the original key — which is the
     * right outcome if the user believes the replacement was an impostor.
     */
    @Query(
        "UPDATE peer_pq_keys SET pendingPublicKey = NULL, keyChangeUnresolved = 0, " +
            "updatedTimestamp = :now WHERE peerHash = :peerHash",
    )
    suspend fun rejectPendingKey(
        peerHash: String,
        now: Long,
    )

    /** Peers currently showing an unexplained key change, for the UI to surface. */
    @Query("SELECT * FROM peer_pq_keys WHERE keyChangeUnresolved = 1")
    fun observeUnresolvedKeyChanges(): Flow<List<PeerPqKeyEntity>>

    /**
     * Record that a peer offered a key contradicting its announced fingerprint.
     *
     * Creates the row if the mismatch is the first thing we ever stored about
     * this peer — the event is worth keeping even with no key to compare against
     * later.
     */
    @Transaction
    suspend fun recordFingerprintMismatch(
        peerHash: String,
        now: Long,
    ) {
        val existing = getPeerKey(peerHash)
        upsertPeerKey(
            existing?.copy(fingerprintMismatchTimestamp = now, updatedTimestamp = now)
                ?: PeerPqKeyEntity(
                    peerHash = peerHash,
                    fingerprintMismatchTimestamp = now,
                    updatedTimestamp = now,
                ),
        )
    }

    /** Clear the mismatch marker once the user has seen it. */
    @Query(
        "UPDATE peer_pq_keys SET fingerprintMismatchTimestamp = NULL, updatedTimestamp = :now " +
            "WHERE peerHash = :peerHash",
    )
    suspend fun clearFingerprintMismatch(
        peerHash: String,
        now: Long,
    )

    // ------------------------------------------------------------- deliveries

    @Query(
        "SELECT EXISTS(SELECT 1 FROM pq_key_deliveries " +
            "WHERE identityHash = :identityHash AND peerHash = :peerHash)",
    )
    suspend fun hasDeliveredOurKey(
        identityHash: String,
        peerHash: String,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordDelivery(delivery: PqKeyDeliveryEntity)

    /**
     * Forget that our key reached this peer, so it is attached again.
     *
     * Used after rotating our own key: the peer holds the old one and would
     * otherwise never be sent the replacement.
     */
    @Query("DELETE FROM pq_key_deliveries WHERE identityHash = :identityHash")
    suspend fun clearDeliveriesFor(identityHash: String)
}
