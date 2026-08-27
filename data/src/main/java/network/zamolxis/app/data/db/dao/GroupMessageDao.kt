package network.zamolxis.app.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import network.zamolxis.app.data.db.entity.GroupMessageEntity

@Dao
interface GroupMessageDao {
    /**
     * Insert ignoring PK conflicts — the (groupId, msgId) pair is the dedup
     * key against LXMF replay. Returns the new rowId, or -1 when the row
     * already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: GroupMessageEntity): Long

    /**
     * Paged history, newest first (UI displays with reverseLayout), ordered by
     * the local receive clock to stay immune to sender clock skew — same
     * approach as `MessageDao.getMessagesForConversationPaged`.
     */
    @Query(
        """
        SELECT * FROM group_messages
        WHERE groupId = :groupId
        ORDER BY receivedAt DESC
        """,
    )
    fun getMessagesPaged(groupId: String): PagingSource<Int, GroupMessageEntity>

    @Query(
        """
        SELECT * FROM group_messages
        WHERE groupId = :groupId
        ORDER BY receivedAt ASC
        """,
    )
    fun observeMessages(groupId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND msgId = :msgId")
    suspend fun getMessage(
        groupId: String,
        msgId: String,
    ): GroupMessageEntity?

    /**
     * Explicit history wipe. The `groups` foreign key would cascade this on
     * group deletion anyway; doing it by hand keeps the delete deterministic
     * regardless of whether the connection has `PRAGMA foreign_keys` on.
     */
    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}
