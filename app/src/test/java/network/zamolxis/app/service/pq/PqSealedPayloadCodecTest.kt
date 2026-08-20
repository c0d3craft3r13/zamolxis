package network.zamolxis.app.service.pq

import android.app.Application
import network.zamolxis.app.rns.api.util.AppDataParser
import network.zamolxis.app.rns.api.util.LxmfFields
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.msgpack.core.MessagePack

/**
 * The sealed payload is a wire format: two builds of this app have to agree on
 * it, and a message sealed by one is unreadable to the other if they do not. So
 * these tests pin the round trip, the rebuilt LXMF fields, and what happens when
 * the blob is not what this side expected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PqSealedPayloadCodecTest {
    private fun roundTrip(payload: SealedPayload) =
        PqSealedPayloadCodec.decode(PqSealedPayloadCodec.encode(payload))

    @Test
    fun `text survives the round trip`() {
        val decoded = roundTrip(SealedPayload(content = "the mesh goes quiet and comes back"))

        assertEquals("the mesh goes quiet and comes back", decoded.content)
        assertNull(decoded.image)
        assertTrue(decoded.files.isEmpty())
        assertNull(decoded.audio)
        assertNull(decoded.replyQuote)
    }

    @Test
    fun `empty text survives`() {
        // An image with no caption is exactly this.
        assertEquals("", roundTrip(SealedPayload(content = "")).content)
    }

    @Test
    fun `unicode text survives`() {
        val message = "Здравей, свят — 🕊 مرحبا"
        assertEquals(message, roundTrip(SealedPayload(content = message)).content)
    }

    @Test
    fun `an image survives with its format`() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }

        val decoded = roundTrip(SealedPayload("caption", image = SealedPayload.Image("png", bytes)))

        assertEquals("png", decoded.image?.format)
        assertArrayEquals(bytes, decoded.image?.bytes)
    }

    @Test
    fun `several files survive in order`() {
        val payload =
            SealedPayload(
                content = "",
                files =
                    listOf(
                        SealedPayload.FileAttachment("notes.txt", byteArrayOf(1, 2, 3)),
                        SealedPayload.FileAttachment("scan.pdf", ByteArray(1000) { 7 }),
                    ),
            )

        val decoded = roundTrip(payload)

        assertEquals(listOf("notes.txt", "scan.pdf"), decoded.files.map { it.name })
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.files[0].bytes)
        assertArrayEquals(ByteArray(1000) { 7 }, decoded.files[1].bytes)
    }

    @Test
    fun `a voice note survives with its mode`() {
        val decoded =
            roundTrip(
                SealedPayload(
                    content = "",
                    audio = SealedPayload.Audio(LxmfFields.AM_OPUS_OGG, byteArrayOf(9, 8, 7)),
                ),
            )

        assertEquals(LxmfFields.AM_OPUS_OGG, decoded.audio?.mode)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.audio?.bytes)
    }

    @Test
    fun `a reply quote survives`() {
        // The quote is the text of an earlier message, which is why it is sealed
        // rather than left in fields[0x31] in the clear.
        assertEquals("what he said", roundTrip(SealedPayload("agreed", replyQuote = "what he said")).replyQuote)
    }

    @Test
    fun `everything at once survives`() {
        val payload =
            SealedPayload(
                content = "all of it",
                image = SealedPayload.Image("webp", byteArrayOf(1)),
                files = listOf(SealedPayload.FileAttachment("a.bin", byteArrayOf(2))),
                audio = SealedPayload.Audio(LxmfFields.AM_OPUS_OGG, byteArrayOf(3)),
                replyQuote = "earlier",
            )

        val decoded = roundTrip(payload)

        assertEquals("all of it", decoded.content)
        assertEquals("webp", decoded.image?.format)
        assertEquals("a.bin", decoded.files.single().name)
        assertEquals(LxmfFields.AM_OPUS_OGG, decoded.audio?.mode)
        assertEquals("earlier", decoded.replyQuote)
    }

    // ------------------------------------------------- rebuilt LXMF fields

    @Test
    fun `rebuilt fields match the shapes the wire uses`() {
        // This is the load-bearing property: the receiver puts these fields back
        // under the numbers the sender removed them from, and the UI parses them
        // with the same code that handles fields which never travelled sealed. If
        // the shape drifts, attachments silently stop rendering.
        val payload =
            SealedPayload(
                content = "caption",
                image = SealedPayload.Image("png", byteArrayOf(0xAB.toByte(), 0xCD.toByte())),
                files = listOf(SealedPayload.FileAttachment("doc.pdf", byteArrayOf(0x01, 0x02))),
                audio = SealedPayload.Audio(LxmfFields.AM_OPUS_OGG, byteArrayOf(0x0F)),
                replyQuote = "hi",
            )

        val json = JSONObject(checkNotNull(AppDataParser.serializeFieldsToJson(payload.toLxmfFields())))

        val image = json.getJSONArray(LxmfFields.FIELD_IMAGE.toString())
        assertEquals("png", image.getString(0))
        assertEquals("abcd", image.getString(1))

        val files = json.getJSONArray(LxmfFields.FIELD_FILE_ATTACHMENTS.toString())
        val first = files.get(0) as JSONArray
        assertEquals("doc.pdf", first.getString(0))
        assertEquals("0102", first.getString(1))

        val audio = json.getJSONArray(LxmfFields.FIELD_AUDIO.toString())
        assertEquals(LxmfFields.AM_OPUS_OGG, audio.getInt(0))
        assertEquals("0f", audio.getString(1))

        // The quote is UTF-8 bytes on the wire, hex once serialized.
        assertEquals("6869", json.getString(LxmfFields.FIELD_REPLY_QUOTE.toString()))
    }

    @Test
    fun `a text-only payload rebuilds no fields`() {
        assertTrue(SealedPayload("just text").toLxmfFields().isEmpty())
    }

    // ------------------------------------------------------------ accounting

    @Test
    fun `attachment size counts every part`() {
        val payload =
            SealedPayload(
                content = "x",
                image = SealedPayload.Image("png", ByteArray(100)),
                files = listOf(SealedPayload.FileAttachment("a", ByteArray(20))),
                audio = SealedPayload.Audio(1, ByteArray(3)),
            )

        assertEquals(123L, payload.attachmentBytes)
        assertTrue(payload.hasAttachments)
    }

    @Test
    fun `text alone counts as no attachments`() {
        assertEquals(0L, SealedPayload("hello").attachmentBytes)
        assertTrue(!SealedPayload("hello").hasAttachments)
    }

    // ---------------------------------------------------------- malformed input

    @Test
    fun `an unknown key is skipped rather than fatal`() {
        // Forward compatibility: a newer build may seal something this one has
        // never heard of, and the message must still be readable.
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packInt(0)
        packer.packString("still readable")
        packer.packInt(0x7F)
        packer.packString("something new")

        assertEquals("still readable", PqSealedPayloadCodec.decode(packer.toByteArray()).content)
    }

    @Test
    fun `a payload with no content is rejected`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packInt(0x7F)
        packer.packString("only an unknown key")

        assertThrows(PqPayloadException::class.java) {
            PqSealedPayloadCodec.decode(packer.toByteArray())
        }
    }

    @Test
    fun `random bytes are rejected`() {
        assertThrows(PqPayloadException::class.java) {
            PqSealedPayloadCodec.decode(ByteArray(64) { it.toByte() })
        }
    }

    @Test
    fun `an implausible attachment count is rejected before allocating`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packInt(0)
        packer.packString("hi")
        packer.packInt(LxmfFields.FIELD_FILE_ATTACHMENTS)
        packer.packArrayHeader(100_000)

        assertThrows(PqPayloadException::class.java) {
            PqSealedPayloadCodec.decode(packer.toByteArray())
        }
    }

    @Test
    fun `a truncated blob is rejected`() {
        val full = PqSealedPayloadCodec.encode(SealedPayload("hello", replyQuote = "quote"))

        assertThrows(PqPayloadException::class.java) {
            PqSealedPayloadCodec.decode(full.copyOf(full.size / 2))
        }
    }
}
