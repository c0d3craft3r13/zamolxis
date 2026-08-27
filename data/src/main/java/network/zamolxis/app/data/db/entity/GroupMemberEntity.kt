package network.zamolxis.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One member of a group chat.
 *
 * Membership is soft-deleted: leaving sets [leftAt] rather than removing the
 * row, so historical messages can still attribute their sender and a re-join
 * keeps the original [addedAt]. [role] holds a `GroupRole` name
 * ("ADMIN"/"MEMBER").
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "memberHash"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE, // Delete members when the group is deleted
        ),
    ],
    indices = [
        Index("groupId"),
    ],
)
data class GroupMemberEntity(
    val groupId: String,
    val memberHash: String, // LXMF destination hash hex of the member
    val role: String, // GroupRole name: "ADMIN" or "MEMBER"
    val addedAt: Long,
    val leftAt: Long? = null, // Non-null once the member left (soft leave)
)
