package network.zamolxis.crypto.pq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The AAD is what stops a sealed payload from being valid anywhere other than
 * the conversation direction it was made for. These tests are less about the
 * bytes than about that property holding end to end through [HybridKem].
 */
class PqAadTest {
    private val kem = HybridKem(SecureRandom())

    private val alice = "aa11bb22cc33dd44"
    private val bob = "9988776655443322"

    @Test
    fun `both sides derive the same bytes for one direction`() {
        assertArrayEquals(
            PqAad.forDirection(alice, bob),
            PqAad.forDirection(alice, bob),
        )
    }

    @Test
    fun `direction is not symmetric`() {
        // Reflecting a payload back at its own sender must not open. If the AAD
        // were order-insensitive, it would.
        assertNotEquals(
            PqAad.forDirection(alice, bob).toList(),
            PqAad.forDirection(bob, alice).toList(),
        )
    }

    @Test
    fun `hash case and surrounding whitespace do not change the result`() {
        // Hashes reach this function from several code paths — some hex-formatted
        // locally, some read back out of the database. A case difference would
        // produce an AAD that cannot open its own ciphertext, and the failure would
        // look like tampering.
        assertArrayEquals(
            PqAad.forDirection(alice, bob),
            PqAad.forDirection(" ${alice.uppercase()} ", bob.uppercase()),
        )
    }

    @Test
    fun `a payload sealed for one recipient does not open for another`() {
        val recipient = kem.generateKeyPair()
        val message = "meet at the usual place".toByteArray()

        val sealed = kem.seal(recipient.publicKey, message, PqAad.forDirection(alice, bob))

        // Same key pair, same ciphertext — only the claimed direction differs.
        assertThrows(HybridKemException::class.java) {
            kem.open(recipient, sealed, PqAad.forDirection(alice, "ffffffffffffffff"))
        }
    }

    @Test
    fun `a payload reflected back at its sender does not open`() {
        val recipient = kem.generateKeyPair()
        val sealed = kem.seal(recipient.publicKey, "ack".toByteArray(), PqAad.forDirection(alice, bob))

        assertThrows(HybridKemException::class.java) {
            kem.open(recipient, sealed, PqAad.forDirection(bob, alice))
        }
    }

    @Test
    fun `the matching direction still opens`() {
        val recipient = kem.generateKeyPair()
        val message = "the mesh goes quiet and comes back".toByteArray()
        val aad = PqAad.forDirection(alice, bob)

        assertArrayEquals(message, kem.open(recipient, kem.seal(recipient.publicKey, message, aad), aad))
    }

    @Test
    fun `an unbound payload does not open as a bound one`() {
        // Guards the migration direction: a build that forgot to pass the AAD must
        // not silently interoperate with one that passes it, because the whole
        // point of the binding is that it cannot be dropped.
        val recipient = kem.generateKeyPair()
        val sealed = kem.seal(recipient.publicKey, "hello".toByteArray())

        assertThrows(HybridKemException::class.java) {
            kem.open(recipient, sealed, PqAad.forDirection(alice, bob))
        }
    }

    @Test
    fun `the domain separator is present and the hashes are readable in order`() {
        val aad = String(PqAad.forDirection(alice, bob), Charsets.US_ASCII)
        assertTrue(aad.startsWith("zamolxis/pq-aad/v1"))
        assertTrue(aad.endsWith("$alice|$bob"))
        assertFalse(aad.contains(alice.uppercase()))
    }
}
