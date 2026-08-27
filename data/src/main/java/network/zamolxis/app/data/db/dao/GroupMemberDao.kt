package network.zamolxis.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import network.zamolxis.app.data.db.entity.GroupMemberEntity

@Dao
interface GroupMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<GroupMemberEntity>)

    /** Members that have not left (soft leave sets `leftAt`). */
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND leftAt IS NULL")
    suspend fun getActiveMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query(
        "SELECT * FROM group_members WHERE groupId = :groupId AND memberHash = :memberHash",
    )
    suspend fun getMember(
        groupId: String,
        memberHash: String,
    ): GroupMemberEntity?

    @Query(
        """
        UPDATE group_members SET leftAt = :leftAt
        WHERE groupId = :groupId AND memberHash = :memberHash
        """,
    )
    suspend fun markLeft(
        groupId: String,
        memberHash: String,
        leftAt: Long,
    )

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM group_members
            WHERE groupId = :groupId AND memberHash = :memberHash AND leftAt IS NULL
        )
        """,
    )
    suspend fun isActiveMember(
        groupId: String,
        memberHash: String,
    ): Boolean

    /** Explicit roster wipe — see [GroupMessageDao.deleteForGroup]. */
    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}
