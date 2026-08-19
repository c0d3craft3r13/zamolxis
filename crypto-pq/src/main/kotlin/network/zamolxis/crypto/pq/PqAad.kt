package network.zamolxis.crypto.pq

/**
 * The additional authenticated data every sealed message is bound to.
 *
 * [HybridKem.seal] takes an `aad` argument and covers it with the GCM tag
 * without encrypting it. Passing nothing there would leave the ciphertext a
 * free-floating blob: valid in any message, to anyone holding it. Binding the
 * pair of endpoints means a sealed payload lifted out of one message is
 * rejected anywhere else — reflected back at its own sender, forwarded to a
 * third party, or re-sent inside a message with a different reply target.
 *
 * Both sides have to derive the identical bytes from what they already know,
 * which is why the pair is (sender destination hash, recipient destination
 * hash) and nothing else:
 *
 *  * the sender knows its own destination hash and the peer's
 *  * the receiver knows the LXMF source hash and its own destination hash
 *
 * Anything finer — a message id, a timestamp — is not available symmetrically
 * before the message exists, and LXMF already signs the whole message with the
 * sender's identity, so per-message uniqueness is covered there.
 *
 * Hashes arrive as hex strings from several code paths, so they are normalised
 * before being mixed in: a difference in case would silently produce a key that
 * cannot open its own ciphertext.
 */
public object PqAad {
    /** Domain separator, so these bytes cannot be mistaken for another protocol's AAD. */
    private const val PREFIX: String = "zamolxis/pq-aad/v1"

    private const val SEPARATOR: Char = '|'

    /**
     * AAD binding a sealed payload to one direction of one conversation.
     *
     * @param senderDestinationHash hex destination hash of the sending side
     * @param recipientDestinationHash hex destination hash of the receiving side
     */
    public fun forDirection(
        senderDestinationHash: String,
        recipientDestinationHash: String,
    ): ByteArray =
        buildString {
            append(PREFIX)
            append(SEPARATOR)
            append(normalise(senderDestinationHash))
            append(SEPARATOR)
            append(normalise(recipientDestinationHash))
        }.toByteArray(Charsets.US_ASCII)

    private fun normalise(hash: String): String = hash.trim().lowercase()
}
