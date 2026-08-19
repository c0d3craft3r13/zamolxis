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

    // ---------------------------------------------------------- peers' keys

    @Query("SELECT * FROM peer_pq_keys WHERE peerHash = :peerHash")
    suspend fun getPeerKey(peerHash: String): PeerPqKeyEntity?

    @Query("SELECT * FROM peer_pq_keys WHERE peerHash = :peerHash")
    fun observePeerKey(peerHash: String): Flow<PeerPqKeyEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeerKey(key: PeerPqKeyEntity)

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
