package network.zamolxis.crypto.pq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class HybridKemTest {
    private val kem = HybridKem(SecureRandom())

    // ---------------------------------------------------------------- basics

    @Test
    fun `round trips a message`() {
        val bob = kem.generateKeyPair()
        val message = "the mesh goes quiet and comes back".toByteArray()

        val sealed = kem.seal(bob.publicKey, message)

        assertArrayEquals(message, kem.open(bob, sealed))
    }

    @Test
    fun `round trips an empty message`() {
        val bob = kem.generateKeyPair()
        assertArrayEquals(ByteArray(0), kem.open(bob, kem.seal(bob.publicKey, ByteArray(0))))
    }

    @Test
    fun `round trips a payload larger than one LXMF message`() {
        val bob = kem.generateKeyPair()
        val big = ByteArray(200_000).also { SecureRandom().nextBytes(it) }
        assertArrayEquals(big, kem.open(bob, kem.seal(bob.publicKey, big)))
    }

    @Test
    fun `generated key pairs have the FIPS 203 sizes`() {
        val keys = kem.generateKeyPair().publicKey
        assertEquals(HybridKem.X25519_KEY_BYTES, keys.x25519.size)
        assertEquals(HybridKem.ML_KEM_PUBLIC_KEY_BYTES, keys.mlKem.size)
    }

    @Test
    fun `overhead matches the documented constant`() {
        val bob = kem.generateKeyPair()
        val plaintext = ByteArray(64)
        assertEquals(
            plaintext.size + HybridKem.OVERHEAD_BYTES,
            kem.seal(bob.publicKey, plaintext).size,
        )
    }

    // ------------------------------------------------------- confidentiality

    @Test
    fun `sealing the same message twice produces different ciphertext`() {
        val bob = kem.generateKeyPair()
        val message = "repeat".toByteArray()

        val first = kem.seal(bob.publicKey, message)
        val second = kem.seal(bob.publicKey, message)

        assertFalse("fresh randomness must make each sealing unique", first.contentEquals(second))
    }

    @Test
    fun `plaintext does not appear in the sealed blob`() {
        val bob = kem.generateKeyPair()
        val marker = "ATTACKTHEMESHATDAWN".toByteArray()

        val sealed = kem.seal(bob.publicKey, marker)

        assertFalse(sealed.asList().windowed(marker.size).any { it.toByteArray().contentEquals(marker) })
    }

    // -------------------------------------------------------------- rejection

    @Test
    fun `another recipient cannot open the message`() {
        val bob = kem.generateKeyPair()
        val mallory = kem.generateKeyPair()

        val sealed = kem.seal(bob.publicKey, "for bob only".toByteArray())

        assertThrows(HybridKemException::class.java) { kem.open(mallory, sealed) }
    }

    @Test
    fun `a flipped bit anywhere in the blob is rejected`() {
        val bob = kem.generateKeyPair()
        val sealed = kem.seal(bob.publicKey, "integrity".toByteArray())

        // Sample every region: version, ephemeral key, KEM ciphertext, nonce, body.
        val probes = listOf(0, 1, 40, 600, 1121, 1125, sealed.size - 1)
        for (index in probes) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertThrows(
                "tampering at byte $index must be rejected",
                HybridKemException::class.java,
            ) { kem.open(bob, tampered) }
        }
    }

    @Test
    fun `truncated input is rejected rather than crashing`() {
        val bob = kem.generateKeyPair()
        val sealed = kem.seal(bob.publicKey, "truncate me".toByteArray())

        for (length in listOf(0, 1, 33, HybridKem.OVERHEAD_BYTES - 1)) {
            assertThrows(HybridKemException::class.java) { kem.open(bob, sealed.copyOf(length)) }
        }
    }

    @Test
    fun `an unknown wire version is rejected`() {
        val bob = kem.generateKeyPair()
        val sealed = kem.seal(bob.publicKey, "future".toByteArray()).also { it[0] = 99 }

        assertThrows(HybridKemException::class.java) { kem.open(bob, sealed) }
    }

    // --------------------------------------------------------------- the AAD

    @Test
    fun `aad round trips when it matches`() {
        val bob = kem.generateKeyPair()
        val aad = "message-id:42".toByteArray()

        val sealed = kem.seal(bob.publicKey, "bound".toByteArray(), aad)

        assertArrayEquals("bound".toByteArray(), kem.open(bob, sealed, aad))
    }

    @Test
    fun `a message cannot be replayed under different aad`() {
        val bob = kem.generateKeyPair()
        val sealed = kem.seal(bob.publicKey, "bound".toByteArray(), "message-id:42".toByteArray())

        assertThrows(HybridKemException::class.java) {
            kem.open(bob, sealed, "message-id:43".toByteArray())
        }
    }

    // ------------------------------------------------- the hybrid is a hybrid

    @Test
    fun `swapping in another recipient's x25519 half breaks decryption`() {
        val bob = kem.generateKeyPair()
        val mallory = kem.generateKeyPair()

        // Mallory keeps her own ML-KEM key but claims Bob's X25519 key. If the
        // classical half alone decided the key, this would still open.
        val mixed = HybridPublicKey(x25519 = bob.publicKey.x25519, mlKem = mallory.publicKey.mlKem)
        val sealed = kem.seal(mixed, "hybrid".toByteArray())

        assertThrows(HybridKemException::class.java) { kem.open(bob, sealed) }
        assertThrows(HybridKemException::class.java) { kem.open(mallory, sealed) }
    }

    @Test
    fun `swapping in another recipient's ml-kem half breaks decryption`() {
        val bob = kem.generateKeyPair()
        val mallory = kem.generateKeyPair()

        val mixed = HybridPublicKey(x25519 = mallory.publicKey.x25519, mlKem = bob.publicKey.mlKem)
        val sealed = kem.seal(mixed, "hybrid".toByteArray())

        assertThrows(HybridKemException::class.java) { kem.open(bob, sealed) }
        assertThrows(HybridKemException::class.java) { kem.open(mallory, sealed) }
    }

    // ------------------------------------------------------------- key hygiene

    @Test
    fun `each generated key pair is distinct`() {
        val first = kem.generateKeyPair().publicKey
        val second = kem.generateKeyPair().publicKey
        assertNotEquals(first, second)
    }

    @Test
    fun `destroy clears private key material`() {
        val keys = kem.generateKeyPair()
        val sealed = kem.seal(keys.publicKey, "gone".toByteArray())

        keys.destroy()

        assertThrows(HybridKemException::class.java) { kem.open(keys, sealed) }
    }

    @Test
    fun `public keys compare by content`() {
        val original = kem.generateKeyPair().publicKey
        val copy = HybridPublicKey(original.x25519.copyOf(), original.mlKem.copyOf())

        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
    }

    @Test
    fun `malformed public key material is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            HybridPublicKey(ByteArray(31), ByteArray(HybridKem.ML_KEM_PUBLIC_KEY_BYTES))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HybridPublicKey(ByteArray(HybridKem.X25519_KEY_BYTES), ByteArray(1183))
        }
    }

    @Test
    fun `keys from one instance work with another`() {
        val bob = HybridKem(SecureRandom()).generateKeyPair()
        val sender = HybridKem(SecureRandom())

        val sealed = sender.seal(bob.publicKey, "portable".toByteArray())

        assertTrue(HybridKem(SecureRandom()).open(bob, sealed).isNotEmpty())
    }
}
