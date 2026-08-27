package network.zamolxis.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import network.zamolxis.app.data.db.entity.GroupEntity

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId")
    suspend fun getGroup(groupId: String): GroupEntity?

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId")
    fun observeGroup(groupId: String): Flow<GroupEntity?>

    /**
     * All groups of one identity for the chat list, most recent activity first.
     * Groups without any message yet sort by their creation time.
     */
    @Query(
        """
        SELECT * FROM `groups`
        WHERE identityHash = :identityHash
        ORDER BY COALESCE(lastMessageTimestamp, createdAt) DESC
        """,
    )
    fun observeGroupsForIdentity(identityHash: String): Flow<List<GroupEntity>>

    @Query(
        """
        UPDATE `groups` SET lastMessage = :text, lastMessageTimestamp = :timestamp
        WHERE groupId = :groupId
        """,
    )
    suspend fun updateLastMessage(
        groupId: String,
        text: String,
        timestamp: Long,
    )

    /**
     * Unconditional on purpose — the caller decides whether a message should
     * count as unread (own messages and duplicates never reach this).
     */
    @Query("UPDATE `groups` SET unreadCount = unreadCount + 1 WHERE groupId = :groupId")
    suspend fun incrementUnreadCount(groupId: String)

    @Query("UPDATE `groups` SET unreadCount = 0 WHERE groupId = :groupId")
    suspend fun markRead(groupId: String)

    @Query("UPDATE `groups` SET name = :name WHERE groupId = :groupId")
    suspend fun updateName(
        groupId: String,
        name: String,
    )

    @Query("DELETE FROM `groups` WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)
}
