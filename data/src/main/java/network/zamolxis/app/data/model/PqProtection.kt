package network.zamolxis.app.data.model

/**
 * What the hybrid post-quantum layer actually did to one stored message.
 *
 * Recorded per message rather than derived from the conversation's current
 * state. The distinction matters: whether a peer *can* be sealed to changes
 * over time — a key arrives, a key change is flagged, the link switches to
 * LoRa — so a badge computed from today's state would relabel yesterday's
 * history. A user auditing what was protected needs the answer for that
 * message, at the moment it was sent or received.
 */
enum class PqProtection {
    /**
     * No hybrid layer. An ordinary Reticulum/LXMF message, encrypted in
     * transit but not against an adversary recording it for a future quantum
     * computer.
     */
    NONE,

    /** Content was sealed with the hybrid layer, and nothing else rode along. */
    SEALED,

    /**
     * Content was sealed, but the message also carried an attachment
     * (image, file or voice) that the hybrid layer does not cover.
     *
     * Kept distinct from [SEALED] so the UI can say which part was protected.
     * Collapsing the two would let a user believe a photo got the same
     * treatment as the text next to it.
     */
    SEALED_PARTIAL,

    /**
     * Arrived sealed and could not be opened.
     *
     * The row is still stored. Dropping it would mean the sender sees a
     * delivery proof for something the recipient never learns exists, which is
     * worse than an honest placeholder — and it is recoverable: resolving a
     * key change or restoring a key pair can make an earlier message readable
     * again, but only if the ciphertext was kept.
     */
    UNOPENED,
    ;

    /** Whether the text of this message got the hybrid layer. */
    val isSealed: Boolean
        get() = this == SEALED || this == SEALED_PARTIAL

    companion object {
        /**
         * Parse a stored value, tolerating anything unrecognised.
         *
         * A row written by a newer build must not crash an older one, and an
         * unknown state is never reported as protected.
         */
        fun fromStored(value: String?): PqProtection =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}
