package network.zamolxis.crypto.pq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PqEnvelopeTest {
    private val kem = HybridKem(SecureRandom())

    @Test
    fun `field numbers stay clear of the ones already in use`() {
        // 0x02-0x07, 0x09, 0x10, 0x30, 0x31, 0x40 are taken by LXMF and by this
        // app's attachment, reaction and reply fields.
        val taken = setOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09, 0x10, 0x30, 0x31, 0x40)

        assertFalse(PqEnvelope.FIELD_SENDER_KEY in taken)
        assertFalse(PqEnvelope.FIELD_SEALED_CONTENT in taken)
        assertTrue(PqEnvelope.FIELD_SENDER_KEY != PqEnvelope.FIELD_SEALED_CONTENT)
    }

    @Test
    fun `a sealed message round trips through the envelope`() {
        val bob = kem.generateKeyPair()
        val alice = kem.generateKeyPair()
        val sealed = kem.seal(bob.publicKey, "field framed".toByteArray())

        val fields = PqEnvelope.fieldsFor(sealed, ourKey = alice.publicKey)
        val incoming = PqEnvelope.parse(fields)

        assertNotNull(incoming)
        assertArrayEquals("field framed".toByteArray(), kem.open(bob, incoming!!.sealedContent))
        assertEquals(alice.publicKey, incoming.senderKey)
    }

    @Test
    fun `a sealed message without an attached key parses fine`() {
        val bob = kem.generateKeyPair()
        val sealed = kem.seal(bob.publicKey, "no key attached".toByteArray())

        val incoming = PqEnvelope.parse(PqEnvelope.fieldsFor(sealed, ourKey = null))

        assertNotNull(incoming)
        assertNull(incoming!!.senderKey)
        assertArrayEquals("no key attached".toByteArray(), kem.open(bob, incoming.sealedContent))
    }

    @Test
    fun `an ordinary message parses as not ours`() {
        val plainLxmfFields = mapOf(0x06 to byteArrayOf(1, 2, 3))

        assertNull(PqEnvelope.parse(plainLxmfFields))
        assertFalse(PqEnvelope.isSealed(plainLxmfFields))
        assertNull(PqEnvelope.senderKeyFrom(plainLxmfFields))
    }

    @Test
    fun `a first-contact message carries a key but no sealed content`() {
        val alice = kem.generateKeyPair()

        val fields = PqEnvelope.keyOnlyFields(alice.publicKey)

        assertFalse("first contact cannot be sealed yet", PqEnvelope.isSealed(fields))
        assertNull(PqEnvelope.parse(fields))
        assertEquals(alice.publicKey, PqEnvelope.senderKeyFrom(fields))
    }

    @Test
    fun `the sender key survives alongside unrelated fields`() {
        val alice = kem.generateKeyPair()
        val fields = PqEnvelope.keyOnlyFields(alice.publicKey) + mapOf(0x06 to byteArrayOf(9))

        assertEquals(alice.publicKey, PqEnvelope.senderKeyFrom(fields))
    }

    @Test
    fun `a malformed sender key raises rather than reading as absent`() {
        // Reading a corrupt key as "peer has no key" would quietly drop the
        // conversation back to classical crypto, which is the attacker's goal.
        val fields = mapOf(PqEnvelope.FIELD_SENDER_KEY to ByteArray(64))

        assertThrows(HybridKemException::class.java) { PqEnvelope.senderKeyFrom(fields) }
    }

    @Test
    fun `a sealed message with a malformed key raises`() {
        val bob = kem.generateKeyPair()
        val fields =
            mapOf(
                PqEnvelope.FIELD_SEALED_CONTENT to kem.seal(bob.publicKey, byteArrayOf(1)),
                PqEnvelope.FIELD_SENDER_KEY to ByteArray(3),
            )

        assertThrows(HybridKemException::class.java) { PqEnvelope.parse(fields) }
    }

    @Test
    fun `isSealed reflects the sealed-content field only`() {
        val bob = kem.generateKeyPair()

        assertTrue(PqEnvelope.isSealed(PqEnvelope.fieldsFor(kem.seal(bob.publicKey, byteArrayOf()), null)))
        assertFalse(PqEnvelope.isSealed(PqEnvelope.keyOnlyFields(bob.publicKey)))
        assertFalse(PqEnvelope.isSealed(emptyMap()))
    }

    // ------------------------------------------- the whole first-contact dance

    @Test
    fun `two peers reach a sealed conversation in three messages`() {
        val alice = kem.generateKeyPair()
        val bob = kem.generateKeyPair()

        var aliceKnowsBob: HybridPublicKey? = null
        var bobKnowsAlice: HybridPublicKey? = null

        // 1. Alice opens. She cannot seal — she has no key for Bob — but she
        //    hands over hers.
        val first = PqEnvelope.keyOnlyFields(alice.publicKey)
        assertFalse(PqEnvelope.isSealed(first))
        bobKnowsAlice = PqEnvelope.senderKeyFrom(first)
        assertEquals(alice.publicKey, bobKnowsAlice)

        // 2. Bob replies. He can seal now, and attaches his own key.
        val second =
            PqEnvelope.fieldsFor(
                kem.seal(bobKnowsAlice!!, "sealed reply".toByteArray()),
                ourKey = bob.publicKey,
            )
        val atAlice = PqEnvelope.parse(second)!!
        assertArrayEquals("sealed reply".toByteArray(), kem.open(alice, atAlice.sealedContent))
        aliceKnowsBob = atAlice.senderKey
        assertEquals(bob.publicKey, aliceKnowsBob)

        // 3. Alice now seals too, and no longer needs to attach her key.
        val third =
            PqEnvelope.fieldsFor(
                kem.seal(aliceKnowsBob!!, "sealed onward".toByteArray()),
                ourKey = null,
            )
        val atBob = PqEnvelope.parse(third)!!
        assertNull(atBob.senderKey)
        assertArrayEquals("sealed onward".toByteArray(), kem.open(bob, atBob.sealedContent))
    }
}
