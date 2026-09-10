package network.zamolxis.crypto.pq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The epoch layer.
 *
 * Two things are being proved here, and the second matters more than the first.
 *
 * One: the size. An epoch exists so that a sealed message stops being four times
 * the size of a single Reticulum packet, because that shape is what identifies a
 * protected conversation to anyone counting packets.
 *
 * Two: that nothing can strand a message. This layer replaces a per-message
 * handshake with shared state, and shared state on an unordered, lossy channel
 * that delivers a month late is exactly how a messenger loses someone's
 * conversation for good. So the out-of-order, gap and late-arrival cases are not
 * edge cases here — they are the normal case, and they are tested as such.
 */
class PqEpochTest {
    private val kem = HybridKem()
    private val recipient = kem.generateKeyPair()
    private val aad = PqAad.forDirection("aa11", "bb22")
    private val message = "meet at the usual place".toByteArray()

    // ── the round trip ───────────────────────────────────────────────────────

    @Test
    fun `an opening carries its first message`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        val accepted = PqEpoch.accept(recipient, started.wire, aad)

        assertArrayEquals(message, accepted.plaintext)
    }

    @Test
    fun `both ends derive the same epoch`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        val accepted = PqEpoch.accept(recipient, started.wire, aad)

        assertArrayEquals(started.root, accepted.root)
        assertArrayEquals(started.epochId, accepted.epochId)
    }

    @Test
    fun `a continuation round trips`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val second = "and bring the other thing".toByteArray()

        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = second, aad = aad)

        assertArrayEquals(second, PqEpoch.open(started.root, wire, aad))
    }

    @Test
    fun `an empty message round trips`() {
        val started = PqEpoch.start(recipient.publicKey, ByteArray(0), aad)

        assertArrayEquals(ByteArray(0), PqEpoch.accept(recipient, started.wire, aad).plaintext)
    }

    @Test
    fun `a large message round trips`() {
        val big = ByteArray(400_000) { (it % 251).toByte() }
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        val wire = PqEpoch.seal(started.root, counter = 7, plaintext = big, aad = aad)

        assertArrayEquals(big, PqEpoch.open(started.root, wire, aad))
    }

    // ── the size, which is the point ─────────────────────────────────────────

    @Test
    fun `a continuation costs twenty-nine bytes`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        assertEquals(29, PqEpoch.CONTINUE_OVERHEAD_BYTES)
        assertEquals(message.size + 29, wire.size)
    }

    /**
     * The number this whole layer exists for. A Reticulum packet carries 295
     * bytes of LXMF content; a per-message handshake costs 1149 before the text.
     * An ordinary message has to fit, or it travels as a link and a transfer and
     * looks like nothing else on the mesh.
     */
    @Test
    fun `an ordinary text still fits in one Reticulum packet`() {
        val singlePacketContentLimit = 295
        val text = ByteArray(200) { 'a'.code.toByte() }
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        val wire = PqEpoch.seal(started.root, counter = 3, plaintext = text, aad = aad)

        assertTrue(
            "a 200-byte message sealed to ${wire.size} bytes must fit in $singlePacketContentLimit",
            wire.size < singlePacketContentLimit,
        )
    }

    @Test
    fun `an opening costs what a single handshake has always cost`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        // version + ephemeral X25519 + ML-KEM ciphertext + GCM tag.
        val expected = 1 + 32 + HybridKem.ML_KEM_CIPHERTEXT_BYTES + 16 + message.size
        assertEquals(expected, started.wire.size)
    }

    // ── the channel this has to survive ──────────────────────────────────────

    /**
     * Reticulum delivers out of order as a matter of course. Every counter is
     * independent, so arrival order is not a thing this layer has an opinion on.
     */
    @Test
    fun `messages open in any order`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val sealed = (1..5).associateWith { PqEpoch.seal(started.root, it, "m$it".toByteArray(), aad) }

        for (counter in listOf(4, 1, 5, 3, 2)) {
            assertArrayEquals("m$counter".toByteArray(), PqEpoch.open(started.root, sealed.getValue(counter), aad))
        }
    }

    /**
     * A dropped packet on LoRa is ordinary. A ratchet would stall here; nothing
     * stalls, because a missing counter is not a missing step.
     */
    @Test
    fun `a gap does not stop what follows`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        val far = PqEpoch.seal(started.root, counter = 1000, plaintext = message, aad = aad)

        assertArrayEquals(message, PqEpoch.open(started.root, far, aad))
    }

    /**
     * A propagation node holds mail for thirty days. There is no clock in any of
     * this — a message sealed now opens whenever it turns up, which is the
     * property that makes late delivery survivable.
     */
    @Test
    fun `a month-old message opens exactly as a fresh one does`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val early = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        // Everything the epoch needs is the root and the counter in the blob.
        val laterEpoch = PqEpoch.start(recipient.publicKey, message, aad)
        PqEpoch.seal(laterEpoch.root, counter = 1, plaintext = message, aad = aad)

        assertArrayEquals(message, PqEpoch.open(started.root, early, aad))
    }

    // ── what must not open ───────────────────────────────────────────────────

    @Test
    fun `another recipient cannot accept the opening`() {
        val stranger = kem.generateKeyPair()
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        assertThrows(HybridKemException::class.java) {
            PqEpoch.accept(stranger, started.wire, aad)
        }
    }

    @Test
    fun `another root cannot open a continuation`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val other = PqEpoch.start(recipient.publicKey, message, aad)
        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        assertThrows(HybridKemException::class.java) { PqEpoch.open(other.root, wire, aad) }
    }

    /**
     * The reason the epoch id is checked before the tag: a blob from another
     * epoch is a different failure from a corrupted one, and saying so early
     * costs nothing because the id is public anyway.
     */
    @Test
    fun `a continuation names the epoch it belongs to`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        assertArrayEquals(started.epochId, PqEpoch.epochIdOf(wire))
    }

    /**
     * The AAD binds one direction of one conversation. A blob lifted out of it —
     * reflected at its sender, or replayed into another chat — must not open.
     */
    @Test
    fun `a blob cannot be moved to another conversation`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)
        val elsewhere = PqAad.forDirection("bb22", "aa11")

        assertThrows(HybridKemException::class.java) { PqEpoch.open(started.root, wire, elsewhere) }
        assertThrows(HybridKemException::class.java) {
            PqEpoch.accept(recipient, started.wire, elsewhere)
        }
    }

    /** The counter is in the clear, so it is authenticated rather than trusted. */
    @Test
    fun `editing the counter breaks the message`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        wire[1 + PqEpoch.EPOCH_ID_BYTES + 3] = 9

        assertThrows(HybridKemException::class.java) { PqEpoch.open(started.root, wire, aad) }
    }

    /** So is the epoch id. */
    @Test
    fun `editing the epoch id breaks the message`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        wire[1] = (wire[1].toInt() xor 0x01).toByte()

        assertThrows(HybridKemException::class.java) { PqEpoch.open(started.root, wire, aad) }
    }

    /** The whole opening header is authenticated, KEM ciphertext included. */
    @Test
    fun `editing the opening header breaks it`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        started.wire[40] = (started.wire[40].toInt() xor 0x01).toByte()

        assertThrows(HybridKemException::class.java) { PqEpoch.accept(recipient, started.wire, aad) }
    }

    @Test
    fun `a flipped bit in the body breaks the message`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)
        wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0x01).toByte()

        assertThrows(HybridKemException::class.java) { PqEpoch.open(started.root, wire, aad) }
    }

    @Test
    fun `a truncated blob is refused rather than throwing something else`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val wire = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        for (length in listOf(0, 1, 5, PqEpoch.EPOCH_ID_BYTES, wire.size - 1)) {
            assertThrows(
                "truncating to $length bytes must be refused",
                HybridKemException::class.java,
            ) { PqEpoch.open(started.root, wire.copyOf(length), aad) }
        }
        for (length in listOf(0, 1, 33, 1120)) {
            assertThrows(
                "truncating an opening to $length bytes must be refused",
                HybridKemException::class.java,
            ) { PqEpoch.accept(recipient, started.wire.copyOf(length), aad) }
        }
    }

    // ── keeping the two wire formats apart ───────────────────────────────────

    @Test
    fun `a per-message blob is not mistaken for an epoch`() {
        val legacy = kem.seal(recipient.publicKey, message, aad)

        assertFalse(PqEpoch.isEpochWire(legacy))
        assertNull(PqEpoch.epochIdOf(legacy))
        assertThrows(HybridKemException::class.java) { PqEpoch.accept(recipient, legacy, aad) }
    }

    @Test
    fun `an epoch blob is not mistaken for a per-message one`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        assertThrows(HybridKemException::class.java) { kem.open(recipient, started.wire, aad) }
    }

    @Test
    fun `openings and continuations are told apart`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)
        val continued = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)

        assertTrue(PqEpoch.isOpening(started.wire))
        assertFalse(PqEpoch.isOpening(continued))
        assertTrue(PqEpoch.isEpochWire(continued))
        assertNull(PqEpoch.epochIdOf(started.wire))
    }

    // ── freshness ────────────────────────────────────────────────────────────

    @Test
    fun `two epochs to the same peer share nothing`() {
        val first = PqEpoch.start(recipient.publicKey, message, aad)
        val second = PqEpoch.start(recipient.publicKey, message, aad)

        assertNotEquals(first.root.toList(), second.root.toList())
        assertNotEquals(first.epochId.toList(), second.epochId.toList())
        assertNotEquals(first.wire.toList(), second.wire.toList())
    }

    @Test
    fun `the same message at two counters looks different`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        val one = PqEpoch.seal(started.root, counter = 1, plaintext = message, aad = aad)
        val two = PqEpoch.seal(started.root, counter = 2, plaintext = message, aad = aad)

        assertNotEquals(
            one.copyOfRange(PqEpoch.EPOCH_ID_BYTES + 5, one.size).toList(),
            two.copyOfRange(PqEpoch.EPOCH_ID_BYTES + 5, two.size).toList(),
        )
    }

    /** The opening owns counter zero; handing it out again would reuse a key. */
    @Test
    fun `the opening counter cannot be sealed twice`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        assertThrows(IllegalArgumentException::class.java) {
            PqEpoch.seal(started.root, counter = PqEpoch.OPENING_COUNTER, plaintext = message, aad = aad)
        }
    }

    @Test
    fun `a root of the wrong size is refused at the door`() {
        assertThrows(IllegalArgumentException::class.java) {
            PqEpoch.seal(ByteArray(16), counter = 1, plaintext = message, aad = aad)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PqEpoch.open(ByteArray(16), ByteArray(64), aad)
        }
    }

    @Test
    fun `the epoch id is a function of the root alone`() {
        val started = PqEpoch.start(recipient.publicKey, message, aad)

        assertArrayEquals(started.epochId, PqEpoch.epochIdFor(started.root))
        assertEquals(PqEpoch.EPOCH_ID_BYTES, started.epochId.size)
    }
}
