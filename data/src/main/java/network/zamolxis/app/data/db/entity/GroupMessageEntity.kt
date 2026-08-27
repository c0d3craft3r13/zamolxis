package network.zamolxis.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One message in a group chat.
 *
 * [msgId] is a UUID string chosen by the sender, so the composite primary key
 * doubles as the dedup key: LXMF replay re-delivers the same (groupId, msgId)
 * pair, and inserts use `IGNORE`. [timestamp] is the sender's clock;
 * [receivedAt] is ours and is what ordering is based on (sender clock skew
 * would otherwise scramble history), matching how `messages.receivedAt` is
 * used.
 */
@Entity(
    tableName = "group_messages",
    primaryKeys = ["groupId", "msgId"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE, // Delete messages when the group is deleted
        ),
    ],
    indices = [
        Index("groupId", "timestamp"),
    ],
)
data class GroupMessageEntity(
    val groupId: String,
    val msgId: String, // UUID string from the sender
    val senderHash: String, // LXMF destination hash hex of the sender
    val content: String,
    val timestamp: Long, // Sender's clock
    val receivedAt: Long, // Local clock, used for ordering
    val isFromMe: Boolean,
)
