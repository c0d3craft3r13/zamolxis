package network.zamolxis.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import network.zamolxis.app.data.db.entity.GroupMessageStatusEntity

@Dao
interface GroupMessageStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(statuses: List<GroupMessageStatusEntity>)

    /**
     * Update every fan-out leg matching an LXMF receipt. Terminal states are
     * sticky: a leg already DELIVERED or READ never moves back to SENT or
     * PENDING (a late send callback must not erase a receipt that already
     * arrived), while upgrades into DELIVERED/READ always apply.
     *
     * Returns the number of rows updated.
     */
    @Query(
        """
        UPDATE group_message_status SET status = :status
        WHERE lxmfHash = :lxmfHash
        AND (status NOT IN ('DELIVERED', 'READ') OR :status IN ('DELIVERED', 'READ'))
        """,
    )
    suspend fun updateStatusByLxmfHash(
        lxmfHash: String,
        status: String,
    ): Int

    @Query("SELECT * FROM group_message_status WHERE msgId = :msgId")
    suspend fun getStatusesForMessage(msgId: String): List<GroupMessageStatusEntity>

    @Query("SELECT * FROM group_message_status WHERE msgId = :msgId")
    fun observeStatusesForMessage(msgId: String): Flow<List<GroupMessageStatusEntity>>

    /**
     * Fold each message's per-recipient legs into one status for the chat UI:
     * FAILED if every leg failed, DELIVERED if every leg is DELIVERED or READ,
     * SENT if every leg reached at least SENT, PENDING if any leg is still
     * pending, SENT otherwise.
     */
    @Query(
        """
        SELECT msgId,
            CASE
                WHEN SUM(CASE WHEN status != 'FAILED' THEN 1 ELSE 0 END) = 0 THEN 'FAILED'
                WHEN SUM(CASE WHEN status NOT IN ('DELIVERED', 'READ') THEN 1 ELSE 0 END) = 0 THEN 'DELIVERED'
                WHEN SUM(CASE WHEN status NOT IN ('SENT', 'DELIVERED', 'READ') THEN 1 ELSE 0 END) = 0 THEN 'SENT'
                WHEN SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) > 0 THEN 'PENDING'
                ELSE 'SENT'
            END AS status
        FROM group_message_status
        WHERE msgId IN (SELECT msgId FROM group_messages WHERE groupId = :groupId)
        GROUP BY msgId
        """,
    )
    fun observeAggregateStatuses(groupId: String): Flow<List<GroupMessageAggregate>>

    /**
     * Drop every fan-out leg belonging to a group's messages.
     *
     * This table deliberately has no foreign key: its own key is (msgId,
     * memberHash), while `group_messages` is keyed by (groupId, msgId), so an
     * FK would need a redundant groupId column on every status row. The price
     * is that deletion is not cascaded for us — hence this query, which must
     * run *before* the group's messages go, since it resolves them through
     * `group_messages`.
     */
    @Query(
        """
        DELETE FROM group_message_status
        WHERE msgId IN (SELECT msgId FROM group_messages WHERE groupId = :groupId)
        """,
    )
    suspend fun deleteForGroup(groupId: String)
}

/**
 * Per-message rollup of the per-recipient fan-out states, for rendering one
 * status tick next to an outgoing group message.
 */
data class GroupMessageAggregate(
    val msgId: String,
    val status: String, // GroupMessageStatus name
)
