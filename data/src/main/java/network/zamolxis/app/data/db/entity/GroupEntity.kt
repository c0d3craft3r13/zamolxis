package network.zamolxis.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A group chat the active local identity participates in.
 *
 * Unlike conversations the primary key is just [groupId]: group IDs are 16
 * random bytes (32 lowercase hex chars), so a collision across identities is
 * not a concern and the composite key buys nothing. [identityHash] scopes the
 * row to the owning local identity for queries, mirroring the other tables.
 *
 * [lastMessage], [lastMessageTimestamp] and [unreadCount] are denormalized
 * for the chat list so it does not have to join `group_messages` per row.
 */
@Entity(
    tableName = "groups",
    indices = [
        Index("identityHash"),
    ],
)
data class GroupEntity(
    @PrimaryKey
    val groupId: String, // 16 random bytes as 32 lowercase hex chars
    val identityHash: String, // Which local identity owns this group
    val name: String,
    val avatarBytes: ByteArray? = null,
    val createdBy: String, // LXMF destination hash hex of the creator
    val createdAt: Long,
    val lastMessage: String? = null, // Preview of last message, for the chat list
    val lastMessageTimestamp: Long? = null, // For chat-list sorting
    @ColumnInfo(defaultValue = "0")
    val unreadCount: Int = 0,
) {
    // A data class holding a ByteArray gets identity-based equals/hashCode, which
    // would make two equal rows compare unequal and defeat Compose's skipping on
    // the chat list. Written out by hand as AnnounceEntity/ContactEntity do.
    @Suppress("CyclomaticComplexMethod") // Equals must compare all fields for correctness
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupEntity) return false

        return groupId == other.groupId &&
            identityHash == other.identityHash &&
            name == other.name &&
            avatarBytes.contentEqualsNullable(other.avatarBytes) &&
            createdBy == other.createdBy &&
            createdAt == other.createdAt &&
            lastMessage == other.lastMessage &&
            lastMessageTimestamp == other.lastMessageTimestamp &&
            unreadCount == other.unreadCount
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this != null && other != null -> this.contentEquals(other)
            else -> false
        }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + identityHash.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        result = 31 * result + createdBy.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastMessage?.hashCode() ?: 0)
        result = 31 * result + (lastMessageTimestamp?.hashCode() ?: 0)
        result = 31 * result + unreadCount
        return result
    }
}
