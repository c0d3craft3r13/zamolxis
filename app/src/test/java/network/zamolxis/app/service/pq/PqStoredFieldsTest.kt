package network.zamolxis.app.service.pq

import android.app.Application
import network.zamolxis.app.data.model.PqProtection
import network.zamolxis.app.rns.api.util.LxmfFields
import network.zamolxis.crypto.pq.PqEnvelope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What gets written to the message row after the layer has opened a message.
 *
 * The stakes here are ordinary-looking but real: put the fields back wrong and
 * attachments vanish from the UI; keep the ciphertext and every attachment is
 * stored twice, as bytes and again as hex; drop the ciphertext too early and a
 * message that a key-change resolution could have opened is lost for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PqStoredFieldsTest {
    private val sealedField = PqEnvelope.FIELD_SEALED_CONTENT.toString()
    private val senderKeyField = PqEnvelope.FIELD_SENDER_KEY.toString()

    private fun opened(
        unsealedFields: Map<Int, Any> = emptyMap(),
        protection: PqProtection = PqProtection.SEALED,
    ) = PqMessageSealer.Incoming(
        content = "opened",
        protection = protection,
        unsealedFields = unsealedFields,
    )

    @Test
    fun `an opened message keeps neither the ciphertext nor the sender key`() {
        val received = """{"$senderKeyField": "aabb", "$sealedField": "deadbeef"}"""

        val stored = PqFieldsJson.storedFieldsFor(received, opened())

        // Both are spent: the key has been taken in, and keeping 1.1 KB of
        // ciphertext for a message already in plaintext is pure waste.
        assertNull(stored)
    }

    @Test
    fun `unsealed attachments are put back under their own field numbers`() {
        val received = """{"$sealedField": "deadbeef"}"""
        val fields =
            mapOf<Int, Any>(
                LxmfFields.FIELD_IMAGE to listOf("png", byteArrayOf(0xAB.toByte(), 0xCD.toByte())),
            )

        val stored = JSONObject(checkNotNull(PqFieldsJson.storedFieldsFor(received, opened(fields))))

        val image = stored.getJSONArray(LxmfFields.FIELD_IMAGE.toString())
        assertEquals("png", image.getString(0))
        assertEquals("abcd", image.getString(1))
        assertFalse(stored.has(sealedField))
    }

    @Test
    fun `fields that arrived unsealed are kept alongside`() {
        // Reply target, reactions and telemetry ride outside the seal because the
        // protocol layer reads them before the app does. They must survive.
        val received = """{"$sealedField": "deadbeef", "48": "abcd1234", "2": "0102"}"""

        val stored =
            JSONObject(
                checkNotNull(
                    PqFieldsJson.storedFieldsFor(
                        received,
                        opened(mapOf(LxmfFields.FIELD_AUDIO to listOf(16, byteArrayOf(1)))),
                    ),
                ),
            )

        assertEquals("abcd1234", stored.getString("48"))
        assertEquals("0102", stored.getString("2"))
        assertTrue(stored.has(LxmfFields.FIELD_AUDIO.toString()))
    }

    @Test
    fun `a message that could not be opened keeps its ciphertext untouched`() {
        val received = """{"$sealedField": "deadbeef"}"""

        val stored =
            PqFieldsJson.storedFieldsFor(
                received,
                PqMessageSealer.Incoming("", PqProtection.UNOPENED),
            )

        // This is the only copy of the message. Resolving a key change or restoring
        // a key pair can still open it — but only if it is still here.
        assertEquals(received, stored)
    }

    @Test
    fun `an ordinary message is stored exactly as it arrived`() {
        val received = """{"6": ["png", "aabb"]}"""

        val stored =
            PqFieldsJson.storedFieldsFor(received, PqMessageSealer.Incoming("hi", PqProtection.NONE))

        assertEquals(
            "aabb",
            JSONObject(checkNotNull(stored)).getJSONArray("6").getString(1),
        )
    }

    @Test
    fun `no fields at all stays null rather than an empty object`() {
        assertNull(PqFieldsJson.storedFieldsFor(null, PqMessageSealer.Incoming("hi", PqProtection.NONE)))
        assertNull(PqFieldsJson.storedFieldsFor("", PqMessageSealer.Incoming("hi", PqProtection.NONE)))
    }

    @Test
    fun `an unparseable blob is kept rather than discarded`() {
        // Losing the fields would lose the attachments with them; the parsers
        // downstream already tolerate a blob they cannot read.
        val received = "not json {{"

        assertEquals(received, PqFieldsJson.storedFieldsFor(received, opened()))
    }

    @Test
    fun `a partially sealed message keeps the attachment that rode outside`() {
        // The sender's oversize fallback leaves the image in its own field and seals
        // only the text, so that field is still the real attachment.
        val received = """{"$sealedField": "deadbeef", "6": ["png", "aabb"]}"""

        val stored =
            JSONObject(
                checkNotNull(
                    PqFieldsJson.storedFieldsFor(
                        received,
                        opened(protection = PqProtection.SEALED_PARTIAL),
                    ),
                ),
            )

        assertEquals("aabb", stored.getJSONArray("6").getString(1))
        assertFalse(stored.has(sealedField))
    }
}
