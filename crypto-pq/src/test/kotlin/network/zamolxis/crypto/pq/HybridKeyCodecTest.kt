package network.zamolxis.crypto.pq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class HybridKeyCodecTest {
    private val kem = HybridKem(SecureRandom())

    @Test
    fun `round trips a public key`() {
        val key = kem.generateKeyPair().publicKey

        val decoded = HybridKeyCodec.decode(HybridKeyCodec.encode(key))

        assertEquals(key, decoded)
        assertArrayEquals(key.x25519, decoded.x25519)
        assertArrayEquals(key.mlKem, decoded.mlKem)
    }

    @Test
    fun `encoding has the documented length`() {
        val encoded = HybridKeyCodec.encode(kem.generateKeyPair().publicKey)
        assertEquals(HybridKeyCodec.ENCODED_BYTES, encoded.size)
    }

    @Test
    fun `a decoded key still works for sealing`() {
        val bob = kem.generateKeyPair()
        val overTheWire = HybridKeyCodec.decode(HybridKeyCodec.encode(bob.publicKey))

        val sealed = kem.seal(overTheWire, "survived the wire".toByteArray())

        assertArrayEquals("survived the wire".toByteArray(), kem.open(bob, sealed))
    }

    @Test
    fun `wrong length is rejected`() {
        val encoded = HybridKeyCodec.encode(kem.generateKeyPair().publicKey)

        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decode(encoded.copyOf(10)) }
        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decode(encoded + 0) }
        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decode(ByteArray(0)) }
    }

    @Test
    fun `unknown version is rejected`() {
        val encoded = HybridKeyCodec.encode(kem.generateKeyPair().publicKey).also { it[0] = 42 }
        assertThrows(HybridKemException::class.java) { HybridKeyCodec.decode(encoded) }
    }

    // ------------------------------------------------------------ fingerprints

    @Test
    fun `fingerprint has the documented length and is stable`() {
        val key = kem.generateKeyPair().publicKey

        val first = HybridKeyCodec.fingerprint(key)
        val second = HybridKeyCodec.fingerprint(key)

        assertEquals(HybridKeyCodec.FINGERPRINT_BYTES, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun `different keys get different fingerprints`() {
        val first = HybridKeyCodec.fingerprint(kem.generateKeyPair().publicKey)
        val second = HybridKeyCodec.fingerprint(kem.generateKeyPair().publicKey)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `fingerprint verifies the key it was taken from`() {
        val key = kem.generateKeyPair().publicKey
        assertTrue(HybridKeyCodec.matchesFingerprint(key, HybridKeyCodec.fingerprint(key)))
    }

    @Test
    fun `fingerprint rejects a different key`() {
        val advertised = HybridKeyCodec.fingerprint(kem.generateKeyPair().publicKey)
        val substituted = kem.generateKeyPair().publicKey

        assertFalse(HybridKeyCodec.matchesFingerprint(substituted, advertised))
    }

    @Test
    fun `fingerprint covers both halves of the key`() {
        val honest = kem.generateKeyPair().publicKey
        val attacker = kem.generateKeyPair().publicKey
        val advertised = HybridKeyCodec.fingerprint(honest)

        // Swap in the attacker's ML-KEM half, keep the honest X25519 half — the
        // shape an attacker would use to force the classical-only path.
        val tamperedKem = HybridPublicKey(x25519 = honest.x25519, mlKem = attacker.mlKem)
        val tamperedX = HybridPublicKey(x25519 = attacker.x25519, mlKem = honest.mlKem)

        assertFalse(HybridKeyCodec.matchesFingerprint(tamperedKem, advertised))
        assertFalse(HybridKeyCodec.matchesFingerprint(tamperedX, advertised))
    }

    @Test
    fun `fingerprint of wrong length never matches`() {
        val key = kem.generateKeyPair().publicKey
        val truncated = HybridKeyCodec.fingerprint(key).copyOf(8)

        assertFalse(HybridKeyCodec.matchesFingerprint(key, truncated))
    }
}
