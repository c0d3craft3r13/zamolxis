package network.zamolxis.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import network.zamolxis.app.data.db.entity.PqEpochEntity

/**
 * Storage for post-quantum epochs.
 *
 * Two access patterns, and they are not symmetric. Sending needs the one epoch
 * currently in use with a peer, and needs its counter to move forward exactly
 * once per message. Receiving needs whichever epoch a message names, however old
 * — a propagation node holds mail for thirty days, so a root is looked up long
 * after its epoch stopped being current.
 */
@Dao
interface PqEpochDao {
    /**
     * The epoch we are currently sealing to [peerHash] with.
     *
     * Newest wins. There should only ever be one outbound row per peer —
     * [replaceOutbound] sees to that — but ordering means a leftover row can
     * never quietly become the one in use.
     */
    @Query(
        """
        SELECT * FROM pq_epochs
        WHERE identityHash = :identityHash AND peerHash = :peerHash AND outbound = 1
        ORDER BY createdTimestamp DESC LIMIT 1
        """,
    )
    suspend fun currentOutbound(
        identityHash: String,
        peerHash: String,
    ): PqEpochEntity?

    /** The epoch a received message names, or null if we never held it. */
    @Query(
        """
        SELECT * FROM pq_epochs
        WHERE identityHash = :identityHash AND peerHash = :peerHash
          AND outbound = 0 AND epochId = :epochId
        """,
    )
    suspend fun inbound(
        identityHash: String,
        peerHash: String,
        epochId: String,
    ): PqEpochEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(epoch: PqEpochEntity)

    @Query(
        """
        DELETE FROM pq_epochs
        WHERE identityHash = :identityHash AND peerHash = :peerHash AND outbound = 1
        """,
    )
    suspend fun deleteOutbound(
        identityHash: String,
        peerHash: String,
    )

    /**
     * Install a new outbound epoch, removing whatever preceded it.
     *
     * One statement rather than two so a crash between them cannot leave two
     * outbound epochs for one peer — the state where "which counter is next" has
     * two answers, and the one that reuses a key is as likely as the other.
     */
    @Transaction
    suspend fun replaceOutbound(epoch: PqEpochEntity) {
        deleteOutbound(epoch.identityHash, epoch.peerHash)
        upsert(epoch)
    }

    /**
     * Move an outbound epoch on by one message.
     *
     * Written as an increment in SQL rather than a read-modify-write in Kotlin:
     * two sends racing through the latter would both read the same counter and
     * both use it, and a repeated counter in AES-GCM is not a degraded message,
     * it is a broken cipher.
     */
    @Query(
        """
        UPDATE pq_epochs
        SET nextCounter = nextCounter + 1,
            messageCount = messageCount + 1,
            lastUsedTimestamp = :now
        WHERE identityHash = :identityHash AND peerHash = :peerHash
          AND outbound = 1 AND epochId = :epochId
        """,
    )
    suspend fun advance(
        identityHash: String,
        peerHash: String,
        epochId: String,
        now: Long,
    )

    @Query(
        """
        UPDATE pq_epochs SET lastUsedTimestamp = :now
        WHERE identityHash = :identityHash AND peerHash = :peerHash
          AND outbound = 0 AND epochId = :epochId
        """,
    )
    suspend fun touchInbound(
        identityHash: String,
        peerHash: String,
        epochId: String,
        now: Long,
    )

    /**
     * Drop epochs nothing can still need.
     *
     * The cutoff has to clear the thirty days a propagation node may hold a
     * message, or pruning becomes a way to lose mail that was always going to
     * arrive late.
     */
    @Query("DELETE FROM pq_epochs WHERE lastUsedTimestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** For tests and diagnostics. */
    @Query("SELECT COUNT(*) FROM pq_epochs")
    suspend fun count(): Int
}
