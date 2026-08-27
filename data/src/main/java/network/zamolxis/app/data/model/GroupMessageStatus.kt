package network.zamolxis.app.data.model

/**
 * Per-recipient delivery state of an outgoing group message.
 *
 * One group message fans out to one LXMF message per member, so the group
 * message itself has no single status — each row in `group_message_status`
 * tracks one recipient. Stored as the enum name in
 * `group_message_status.status`.
 *
 * Ordering matters: [DELIVERED] and [READ] are terminal for the delivery
 * pipeline, and the update guard in the repository refuses to move a row in
 * either state back to [SENT] or [PENDING] (a late "sent" callback must not
 * erase a receipt that already arrived).
 */
enum class GroupMessageStatus {
    /** Not yet handed to the transport. */
    PENDING,

    /** Handed to the transport, no receipt yet. */
    SENT,

    /** The recipient's LXMF delivery receipt arrived. */
    DELIVERED,

    /** The recipient signalled the message was read. */
    READ,

    /** Delivery to this recipient failed. */
    FAILED,
    ;

    companion object {
        /** Parse a stored value, defaulting to [PENDING] for anything unrecognised. */
        fun fromStored(value: String?): GroupMessageStatus = entries.firstOrNull { it.name == value } ?: PENDING
    }
}
