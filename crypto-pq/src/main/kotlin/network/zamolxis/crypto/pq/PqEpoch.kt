package network.zamolxis.crypto.pq

import java.security.SecureRandom

/**
 * Sealing a run of messages under one hybrid handshake.
 *
 * ## The problem this exists for
 *
 * [HybridKem] encapsulates afresh for every message, which costs
 * [HybridKem.OVERHEAD_BYTES] — 1149 bytes, dominated by the ML-KEM ciphertext.
 * A Reticulum packet carries 295 bytes of LXMF content, so every sealed message
 * is nearly four times over the single-packet budget before its first
 * character. It therefore travels as a link plus a multi-packet transfer, while
 * an ordinary short message travels as one packet.
 *
 * That is not merely slow. Anyone forwarding traffic can count packets, and a
 * conversation where every message is a link and a transfer does not look like
 * anyone else's. The protection was announcing itself.
 *
 * One handshake per *epoch* fixes it. The opening message pays the full price
 * once; every message after it carries [CONTINUE_OVERHEAD_BYTES] — 29 bytes —
 * and an ordinary text fits back inside one packet, shaped like an unsealed one.
 *
 * ## Why an epoch and not a ratchet
 *
 * A ratchet advances state per message and both ends must stay in step. LXMF is
 * not an ordered or reliable channel: packets are dropped, messages arrive out
 * of order, and a propagation node holds mail for up to thirty days before
 * delivering it. A ratchet that loses step there does not degrade — the
 * conversation stops being readable.
 *
 * Here every message key comes from the epoch root and its own counter:
 *
 * ```
 * key(i) = HKDF(root, "message" || i)
 * ```
 *
 * Any counter opens at any time, in any order, a month late, with gaps. There
 * is no state to desynchronise, which is the whole reason for the design.
 *
 * The price is worth stating plainly: within an epoch there is no forward
 * secrecy — whoever holds the root reads every message of that epoch. Forward
 * secrecy is not this layer's job and this layer must not be sold as providing
 * it. It belongs to rotating the long-lived key an epoch is opened against,
 * because that key decapsulates any recorded opening and therefore any root.
 *
 * ## Layout
 *
 * ```
 * opening      [1] 0x02 | [32] ephemeral X25519 | [1088] ML-KEM ciphertext
 *              | [N] AES-256-GCM ciphertext including its tag
 *
 * continuation [1] 0x03 | [8] epoch id | [4] counter, big-endian
 *              | [N] AES-256-GCM ciphertext including its tag
 * ```
 *
 * The opening is counter zero. The epoch id is derived from the root, so it
 * names the epoch without being negotiated and without revealing anything about
 * it; the receiver uses it to find which root opens a continuation.
 */
public object PqEpoch {
    /** First byte of a blob that opens an epoch. */
    public const val WIRE_OPEN: Byte = 2

    /** First byte of a blob sealed under an epoch already open. */
    public const val WIRE_CONTINUE: Byte = 3

    /** Length of an epoch root, and of every message key derived from one. */
    public const val ROOT_BYTES: Int = 32

    /** Length of the identifier that names an epoch on the wire. */
    public const val EPOCH_ID_BYTES: Int = 8

    /** The counter of the message that opens an epoch. */
    public const val OPENING_COUNTER: Int = 0

    /**
     * Bytes a continuation adds to its plaintext: version, epoch id, counter and
     * the GCM tag. The nonce is derived from the counter and never sent.
     */
    public const val CONTINUE_OVERHEAD_BYTES: Int =
        1 + EPOCH_ID_BYTES + EPOCH_COUNTER_BYTES + (HybridKem.GCM_TAG_BITS / 8)

    /** A freshly opened epoch: what to send, and what the sender must remember. */
    public class Started(
        /** Keep this to seal the rest of the epoch. Secret. */
        public val root: ByteArray,
        /** Names the epoch to the receiver. */
        public val epochId: ByteArray,
        /** The blob to put on the wire. */
        public val wire: ByteArray,
    )

    /** An accepted opening: the epoch to remember, and the message it carried. */
    public class Accepted(
        public val root: ByteArray,
        public val epochId: ByteArray,
        public val plaintext: ByteArray,
    )

    /**
     * Open a new epoch to [recipient] and seal [plaintext] as its first message.
     *
     * @param aad context both ends can reconstruct — see [PqAad]
     */
    public fun start(
        recipient: HybridPublicKey,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
        random: SecureRandom = SecureRandom(),
    ): Started {
        val encapsulated = HybridKem(random).encapsulate(recipient, HybridKem.EPOCH_SALT)
        val root = encapsulated.secret
        val header = byteArrayOf(WIRE_OPEN) + encapsulated.ephemeralPublic + encapsulated.kemCiphertext

        return Started(
            root = root,
            epochId = epochIdFor(root),
            wire = header + epochEncrypt(root, OPENING_COUNTER, aad + header, plaintext),
        )
    }

    /**
     * Seal a message into an epoch already open.
     *
     * [counter] must never repeat within one epoch. Reusing one reuses a message
     * key and its nonce together, which takes AES-GCM apart completely — so a
     * caller that cannot be certain where it got to must start a fresh epoch
     * rather than guess.
     */
    public fun seal(
        root: ByteArray,
        counter: Int,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(root.size == ROOT_BYTES) { "epoch root must be $ROOT_BYTES bytes" }
        require(counter > OPENING_COUNTER) { "counter $counter belongs to the opening" }

        val header = byteArrayOf(WIRE_CONTINUE) + epochIdFor(root) + counter.toBigEndian()
        return header + epochEncrypt(root, counter, aad + header, plaintext)
    }

    /**
     * Accept an opening blob: recover the epoch and the message it carried.
     *
     * @throws HybridKemException if this is not an opening, or will not open
     */
    public fun accept(
        keyPair: HybridKeyPair,
        wire: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): Accepted {
        val header = openingHeaderOf(wire)

        // One catch-all, for the same reason HybridKem.open has one: which step
        // failed must not be visible to whoever is probing with altered blobs.
        @Suppress("TooGenericExceptionCaught")
        return try {
            val root =
                HybridKem().decapsulate(
                    keyPair,
                    header.ephemeralPublic,
                    header.kemCiphertext,
                    HybridKem.EPOCH_SALT,
                )
            Accepted(
                root = root,
                epochId = epochIdFor(root),
                plaintext = epochDecrypt(root, OPENING_COUNTER, aad + header.bytes, header.body),
            )
        } catch (e: Exception) {
            throw HybridKemException("Cannot open epoch", e)
        }
    }

    /**
     * Open a continuation under a root already held.
     *
     * @throws HybridKemException if this is not a continuation, names a
     *   different epoch, or will not open
     */
    public fun open(
        root: ByteArray,
        wire: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(root.size == ROOT_BYTES) { "epoch root must be $ROOT_BYTES bytes" }
        val header = continuationHeaderOf(wire, epochIdFor(root))

        @Suppress("TooGenericExceptionCaught")
        return try {
            epochDecrypt(root, header.counter, aad + header.bytes, header.body)
        } catch (e: Exception) {
            throw HybridKemException("Cannot open epoch message", e)
        }
    }

    /** Which epoch a continuation names, or null when [wire] is not one. */
    public fun epochIdOf(wire: ByteArray): ByteArray? = peekContinuation(wire)?.epochId

    /** Whether [wire] opens an epoch rather than continuing one. */
    public fun isOpening(wire: ByteArray): Boolean = wire.isNotEmpty() && wire[0] == WIRE_OPEN

    /** Whether [wire] belongs to this layer at all. */
    public fun isEpochWire(wire: ByteArray): Boolean = wire.isNotEmpty() && (wire[0] == WIRE_OPEN || wire[0] == WIRE_CONTINUE)

    /**
     * The public name of an epoch.
     *
     * Derived from the root rather than chosen, so both ends agree without
     * negotiating, and it gives nothing away: recovering the root from it would
     * mean inverting SHA-256.
     */
    public fun epochIdFor(root: ByteArray): ByteArray = epochHkdf(root, EPOCH_ID_SALT, ByteArray(0), EPOCH_ID_BYTES)
}
