package network.zamolxis.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Per-recipient delivery state of one outgoing group message.
 *
 * A group message fans out to one LXMF message per member; each row here
 * tracks one of those fan-out legs. [lxmfHash] is the underlying per-recipient
 * LXMF message hash hex and is how delivery receipts are matched back — hence
 * its own index. [status] holds a `GroupMessageStatus` name.
 */
@Entity(
    tableName = "group_message_status",
    primaryKeys = ["msgId", "memberHash"],
    indices = [
        Index("lxmfHash"),
        Index("msgId"),
    ],
)
data class GroupMessageStatusEntity(
    val msgId: String,
    val memberHash: String, // LXMF destination hash hex of the recipient
    val status: String, // GroupMessageStatus name
    val lxmfHash: String? = null, // Per-recipient LXMF message hash hex, for receipt matching
    val errorMessage: String? = null,
)
