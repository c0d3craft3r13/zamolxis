package network.zamolxis.app.data.model

/**
 * Role of a member inside a group chat.
 *
 * Stored as the enum name in `group_members.role` (matching how
 * [PqProtection] is persisted). Kept minimal on purpose: group management
 * only distinguishes who can administer the group from everyone else.
 */
enum class GroupRole {
    /** Can rename the group and add/remove members. */
    ADMIN,

    /** Ordinary participant. */
    MEMBER,
    ;

    companion object {
        /** Parse a stored value, defaulting to [MEMBER] for anything unrecognised. */
        fun fromStored(value: String?): GroupRole = entries.firstOrNull { it.name == value } ?: MEMBER
    }
}
