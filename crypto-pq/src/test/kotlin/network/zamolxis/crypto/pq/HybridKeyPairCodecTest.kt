package network.zamolxis.crypto.pq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/** Storage round-trip for the private halves — the material that sits at rest. */
class HybridKeyPairCodecTest {
    private val kem = HybridKem(SecureRandom())

    @Test
    fun `a restored key pair still opens messages sealed to it`() {
        val original = kem.generateKeyPair()
        val sealed = kem.seal(original.publicKey, "survives storage".toByteArray())

        val restored = HybridKeyCodec.decodeKeyPair(HybridKeyCodec.encodeKeyPair(original))

        assertArrayEquals("survives storage".toByteArray(), kem.open(restored, sealed))
    }

    @Test
    fun `a restored key pair can still be sealed to`() {
        val original = kem.generateKeyPair()
        val restored = HybridKeyCodec.decodeKeyPair(HybridKeyCodec.encodeKeyPair(original))

        val sealed = kem.seal(restored.publicKey, "sealed to restored".toByteArray())

        assertArrayEquals("sealed to restored".toByteArray(), kem.open(original, sealed))
    }

    @Test
    fun `the public half survives intact`() {
        val original = kem.generateKeyPair()
        val restored = HybridKeyCodec.decodeKeyPair(HybridKeyCodec.encodeKeyPair(original))

        assertEquals(original.publicKey, restored.publicKey)
    }

    @Test
    fun `encoding is stable across repeated calls`() {
        val keys = kem.generateKeyPair()
        assertArrayEquals(HybridKeyCodec.encodeKeyPair(keys), HybridKeyCodec.encodeKeyPair(keys))
    }

    @Test
    fun `a truncated blob is rejected`() {
        val encoded = HybridKeyCodec.encodeKeyPair(kem.generateKeyPair())

        for (length in listOf(0, 1, 20, 36, encoded.size - 1)) {
            assertThrows(HybridKemException::class.java) {
                HybridKeyCodec.decodeKeyPair(encoded.copyOf(length))
            }
        }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val encoded = HybridKeyCodec.encodeKeyPair(kem.generateKeyPair())
        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decodeKeyPair(encoded + 0) }
    }

    @Test
    fun `an unknown version is rejected`() {
        val encoded = HybridKeyCodec.encodeKeyPair(kem.generateKeyPair()).also { it[0] = 7 }
        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decodeKeyPair(encoded) }
    }

    @Test
    fun `an absurd length prefix is rejected instead of allocating`() {
        val encoded = HybridKeyCodec.encodeKeyPair(kem.generateKeyPair())

        // Claim ~2GB for the ML-KEM private half. Decoding must refuse on the
        // prefix rather than trying to honour it.
        val offset = 1 + HybridKem.X25519_KEY_BYTES
        encoded[offset] = 0x7F
        encoded[offset + 1] = 0xFF.toByte()
        encoded[offset + 2] = 0xFF.toByte()
        encoded[offset + 3] = 0xFF.toByte()

        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decodeKeyPair(encoded) }
    }

    @Test
    fun `a zero length prefix is rejected`() {
        val encoded = HybridKeyCodec.encodeKeyPair(kem.generateKeyPair())
        val offset = 1 + HybridKem.X25519_KEY_BYTES
        for (i in 0 until Int.SIZE_BYTES) encoded[offset + i] = 0

        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decodeKeyPair(encoded) }
    }

    @Test
    fun `destroying the original does not affect an already-restored copy`() {
        val original = kem.generateKeyPair()
        val encoded = HybridKeyCodec.encodeKeyPair(original)
        val restored = HybridKeyCodec.decodeKeyPair(encoded)
        val sealed = kem.seal(restored.publicKey, "independent".toByteArray())

        original.destroy()

        assertArrayEquals("independent".toByteArray(), kem.open(restored, sealed))
    }
}
