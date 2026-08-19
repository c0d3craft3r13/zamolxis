package network.zamolxis.app.service.pq

import android.app.Application
import java.security.SecureRandom
import network.zamolxis.app.rns.api.util.AppDataParser
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.PqEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PqFieldsJsonTest {
    private val kem = HybridKem(SecureRandom())

    /**
     * The real round trip: fields as the send path builds them, through the same
     * serializer the receive path feeds on. A hand-written JSON fixture would only
     * prove the test agrees with itself.
     */
    @Test
    fun `fields survive the real serialization used on the wire`() {
        val bob = kem.generateKeyPair()
        val alice = kem.generateKeyPair()
        val sealed = kem.seal(bob.publicKey, "through the wire".toByteArray())
        val outgoing = PqEnvelope.fieldsFor(sealed, ourKey = alice.publicKey)

        val fieldsJson = AppDataParser.serializeFieldsToJson(outgoing.mapValues { it.value as Any })
        val recovered = PqFieldsJson.extract(fieldsJson)

        assertArrayEquals(outgoing[PqEnvelope.FIELD_SEALED_CONTENT], recovered[PqEnvelope.FIELD_SEALED_CONTENT])
        assertArrayEquals(outgoing[PqEnvelope.FIELD_SENDER_KEY], recovered[PqEnvelope.FIELD_SENDER_KEY])
        // And the recovered payload genuinely still opens.
        assertArrayEquals(
            "through the wire".toByteArray(),
            kem.open(bob, PqEnvelope.parse(recovered)!!.sealedContent),
        )
    }

    @Test
    fun `a key-only first-contact message survives`() {
        val alice = kem.generateKeyPair()
        val json =
            AppDataParser.serializeFieldsToJson(
                PqEnvelope.keyOnlyFields(alice.publicKey).mapValues { it.value as Any },
            )

        val recovered = PqFieldsJson.extract(json)

        assertEquals(alice.publicKey, PqEnvelope.senderKeyFrom(recovered))
        assertNull(PqEnvelope.parse(recovered))
    }

    @Test
    fun `ordinary messages yield nothing`() {
        assertTrue(PqFieldsJson.extract(null).isEmpty())
        assertTrue(PqFieldsJson.extract("").isEmpty())
        assertTrue(PqFieldsJson.extract("   ").isEmpty())
        assertTrue(PqFieldsJson.extract("""{"6":"deadbeef","7":["1","ab"]}""").isEmpty())
    }

    @Test
    fun `unrelated fields are left alone`() {
        val alice = kem.generateKeyPair()
        val json =
            AppDataParser.serializeFieldsToJson(
                PqEnvelope.keyOnlyFields(alice.publicKey).mapValues { it.value as Any } +
                    mapOf(6 to "cafe" as Any, 7 to listOf("1", "beef") as Any),
            )

        val recovered = PqFieldsJson.extract(json)

        assertEquals(setOf(PqEnvelope.FIELD_SENDER_KEY), recovered.keys)
    }

    @Test
    fun `malformed json does not throw`() {
        assertTrue(PqFieldsJson.extract("not json at all").isEmpty())
        assertTrue(PqFieldsJson.extract("{unbalanced").isEmpty())
    }

    @Test
    fun `a non-hex field is dropped rather than half-decoded`() {
        // Handing the crypto layer a partially decoded payload would surface as a
        // tampering error instead of a malformed-field one.
        val json = """{"${PqEnvelope.FIELD_SEALED_CONTENT}":"zzzz"}"""

        assertTrue(PqFieldsJson.extract(json).isEmpty())
    }

    @Test
    fun `an odd-length hex field is dropped`() {
        val json = """{"${PqEnvelope.FIELD_SEALED_CONTENT}":"abc"}"""

        assertTrue(PqFieldsJson.extract(json).isEmpty())
    }

    @Test
    fun `field numbers match the decimal keys the serializer writes`() {
        // 0x50 / 0x51 become "80" / "81"; a mismatch here would silently mean the
        // receive path never sees a sealed message at all.
        assertEquals(80, PqEnvelope.FIELD_SENDER_KEY)
        assertEquals(81, PqEnvelope.FIELD_SEALED_CONTENT)
    }
}
