package network.zamolxis.crypto.pq

/**
 * How the hybrid layer occupies LXMF message fields.
 *
 * LXMF carries a numeric field map alongside message content. Sealed payloads
 * and key material travel there rather than in the content string, so a client
 * that does not understand these fields still receives a well-formed LXMF
 * message and simply ignores them.
 *
 * ## Field numbers
 *
 * `0x50` and `0x51` are chosen to sit clear of everything upstream LXMF defines
 * (0x01–0x0A) and of the fields this app already uses for attachments,
 * reactions and replies (0x02–0x07, 0x09, 0x10, 0x30, 0x31, 0x40).
 */
public object PqEnvelope {
    /** Sender's own hybrid public key, encoded by [HybridKeyCodec.encode]. */
    public const val FIELD_SENDER_KEY: Int = 0x50

    /** Sealed content, produced by [HybridKem.seal]. */
    public const val FIELD_SEALED_CONTENT: Int = 0x51

    /**
     * What a received message carries for this layer.
     *
     * @property sealedContent the blob to hand to [HybridKem.open]
     * @property senderKey the sender's key, present while they still believe we
     *   might not have it
     */
    public class Incoming(
        public val sealedContent: ByteArray,
        public val senderKey: HybridPublicKey?,
    )

    /**
     * Build the fields for an outgoing sealed message.
     *
     * @param sealedContent output of [HybridKem.seal]
     * @param ourKey attached when the recipient may not hold it yet — see
     *   [PqKeyExchange.shouldAttachOurKey]
     */
    public fun fieldsFor(
        sealedContent: ByteArray,
        ourKey: HybridPublicKey?,
    ): Map<Int, ByteArray> =
        buildMap {
            put(FIELD_SEALED_CONTENT, sealedContent)
            if (ourKey != null) put(FIELD_SENDER_KEY, HybridKeyCodec.encode(ourKey))
        }

    /**
     * Fields carrying only our key, for a message that is *not* sealed.
     *
     * This is what breaks the deadlock at first contact: the opening message
     * cannot be sealed, because the recipient's key is unknown, but it can still
     * hand over ours so their reply can be.
     */
    public fun keyOnlyFields(ourKey: HybridPublicKey): Map<Int, ByteArray> = mapOf(FIELD_SENDER_KEY to HybridKeyCodec.encode(ourKey))

    /**
     * Whether a received field map carries a sealed payload.
     *
     * A message may carry [FIELD_SENDER_KEY] alone — that is the first-contact
     * case — and is not sealed.
     */
    public fun isSealed(fields: Map<Int, ByteArray>): Boolean = fields.containsKey(FIELD_SEALED_CONTENT)

    /**
     * Read a sender key out of any incoming message, sealed or not.
     *
     * @return null when absent; throws when present but malformed, because a
     *   corrupt key must not be mistaken for "this peer has no key" — that
     *   silent downgrade is what an attacker stripping key material wants.
     */
    public fun senderKeyFrom(fields: Map<Int, ByteArray>): HybridPublicKey? = fields[FIELD_SENDER_KEY]?.let(HybridKeyCodec::decode)

    /**
     * Parse the hybrid-layer parts of a received message.
     *
     * @return null if the message carries no sealed content, i.e. an ordinary
     *   LXMF message that this layer should leave alone
     * @throws HybridKemException if a sender key is present but malformed
     */
    public fun parse(fields: Map<Int, ByteArray>): Incoming? {
        val sealed = fields[FIELD_SEALED_CONTENT] ?: return null
        return Incoming(sealedContent = sealed, senderKey = senderKeyFrom(fields))
    }
}
