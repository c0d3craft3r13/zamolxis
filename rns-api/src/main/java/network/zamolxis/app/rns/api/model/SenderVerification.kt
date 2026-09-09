package network.zamolxis.app.rns.api.model

/**
 * Whether an inbound LXMF message actually proved who sent it.
 *
 * ## Why this has to exist
 *
 * Reticulum encrypts a message *to* its recipient, which means anyone who knows
 * a destination hash — and announces publish them — can deliver a message
 * there. Nothing in the transport says who wrote it. The only proof of
 * authorship is the LXMF signature over the message, made with the sender's
 * identity key, and a message carries the sender's hash as a plain claim
 * alongside it.
 *
 * So a message whose signature does not verify is not a message from that
 * contact. It is a message from whoever chose to type their hash into it. Shown
 * in the conversation next to real ones it is indistinguishable, which makes
 * impersonation a matter of sending a packet.
 *
 * ## Why the rule lives here
 *
 * Both backends parse this from their own LXMF stack and they did not agree:
 * `lxmf-kt`'s router rejects an invalid signature outright, while upstream
 * Python LXMF hands the message to its delivery callback with the flag attached
 * and leaves the decision to the client — and the client never looked. Same
 * protocol, same app, opposite answers to "is this from Alice".
 *
 * One implementation, in the module both backends already depend on, is the
 * same shape [network.zamolxis.app.rns.api.util.isUserVisibleChatMessage] takes
 * and for the same reason: a rule about what reaches the user must not be
 * something a backend can quietly hold a different opinion about.
 */
enum class SenderVerification {
    /** The signature verified against the identity the message claims to come from. */
    VERIFIED,

    /**
     * The sender's identity is not known here, so there was nothing to check
     * against — usually a first contact whose announce has not arrived yet.
     *
     * Deliverable, because refusing it would mean never hearing from anyone new,
     * but not the same thing as verified and not worth presenting as such.
     */
    SOURCE_UNKNOWN,

    /**
     * The identity is known and the signature did not match it. Someone put this
     * contact's hash on a message they did not write.
     */
    SIGNATURE_INVALID,
    ;

    /**
     * Whether a message carrying this verdict may reach the user at all.
     *
     * False only for [SIGNATURE_INVALID]: an unsigned message from a stranger is
     * a message from a stranger, but a forged signature from a known contact is
     * an attack, and there is no honest way to render it.
     */
    val isDeliverable: Boolean
        get() = this != SIGNATURE_INVALID

    companion object {
        /**
         * Upstream `LXMF.LXMessage.SOURCE_UNKNOWN`. Inlined rather than imported
         * because `:rns-api` sits below both LXMF stacks and must not depend on
         * either; the value is part of the LXMF wire vocabulary, not of one
         * implementation.
         */
        const val REASON_SOURCE_UNKNOWN: Int = 0x01

        /** Upstream `LXMF.LXMessage.SIGNATURE_INVALID`. */
        const val REASON_SIGNATURE_INVALID: Int = 0x02

        /**
         * Read a backend's two LXMF flags as one verdict.
         *
         * @param signatureValidated the LXMF stack's `signature_validated`
         * @param unverifiedReason its `unverified_reason`, or null when it set none
         *
         * An unvalidated signature with no reason given is [SIGNATURE_INVALID],
         * not [SOURCE_UNKNOWN]. Upstream reaches that state when validation threw
         * — a fault inside the check, which says nothing about the sender and so
         * cannot be treated as proof of one. Guessing generously there would make
         * the safest-looking branch the one an attacker aims for.
         */
        fun of(
            signatureValidated: Boolean,
            unverifiedReason: Int?,
        ): SenderVerification =
            when {
                signatureValidated -> VERIFIED
                unverifiedReason == REASON_SOURCE_UNKNOWN -> SOURCE_UNKNOWN
                else -> SIGNATURE_INVALID
            }
    }
}
