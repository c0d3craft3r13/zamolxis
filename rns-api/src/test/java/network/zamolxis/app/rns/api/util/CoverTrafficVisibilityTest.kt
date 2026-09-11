package network.zamolxis.app.rns.api.util

import network.zamolxis.app.rns.api.model.ReceivedMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cover traffic has to vanish on arrival.
 *
 * A message sent to say nothing must never reach a conversation, and the reason
 * it does not is that the filter both backends already share drops a message
 * with no content whose only field is custom metadata. That is an arrangement
 * between two files rather than a single explicit rule, so it is pinned here:
 * widen the visibility filter in a way that would render one of these and this
 * fails, rather than empty bubbles appearing in someone's chat.
 */
class CoverTrafficVisibilityTest {
    private fun received(
        content: String = "",
        fields: Map<Int, Any>? = null,
    ): ReceivedMessage =
        ReceivedMessage(
            messageHash = "abc",
            content = content,
            sourceHash = ByteArray(16),
            destinationHash = ByteArray(16),
            timestamp = 0,
            fieldsJson = fields?.let { asJson(it) },
        )

    /** Mirrors how the backends render outgoing fields into the JSON the filter reads. */
    private fun asJson(fields: Map<Int, Any>): String {
        val root = JSONObject()
        fields.forEach { (id, value) ->
            val rendered =
                when (value) {
                    is Map<*, *> ->
                        JSONObject().apply {
                            value.forEach { (k, v) -> put(k.toString(), v.toString()) }
                        }

                    else -> value
                }
            root.put(id.toString(), rendered)
        }
        return root.toString()
    }

    /** The one that matters. */
    @Test
    fun `cover traffic never reaches a conversation`() {
        val message = received(content = "", fields = CoverTraffic.fields())

        assertFalse(
            "a message sent to say nothing must not appear as a chat bubble",
            message.isUserVisibleChatMessage(),
        )
        assertTrue("and it must be recognisable as cover", CoverTraffic.isCoverTraffic(message))
    }

    /**
     * The dangerous direction. Something that says nothing is discarded; if the
     * recogniser were loose, a real message could be discarded too, and silently.
     */
    @Test
    fun `a real message carrying padding is still a real message`() {
        val message = received(content = "meet at the bridge", fields = CoverTraffic.fields())

        assertTrue(message.isUserVisibleChatMessage())
        assertFalse("content means it is real, whatever else it carries", CoverTraffic.isCoverTraffic(message))
    }

    @Test
    fun `an ordinary message is not mistaken for cover`() {
        assertFalse(CoverTraffic.isCoverTraffic(received(content = "hello")))
        assertFalse(CoverTraffic.isCoverTraffic(received(content = "", fields = null)))
    }

    @Test
    fun `malformed fields are not cover`() {
        val broken =
            ReceivedMessage(
                messageHash = "abc",
                content = "",
                sourceHash = ByteArray(16),
                destinationHash = ByteArray(16),
                timestamp = 0,
                fieldsJson = "{not json",
            )

        assertFalse(CoverTraffic.isCoverTraffic(broken))
    }

    /** It rides under upstream's app-metadata field, so other clients ignore it entirely. */
    @Test
    fun `padding travels under the documented extension point`() {
        val fields = CoverTraffic.fields()

        assertEquals(setOf(LxmfFields.FIELD_CUSTOM_META), fields.keys)
    }

    /**
     * LXMF compresses payloads. Zeroes would collapse and the message would
     * arrive at whatever size the compressor chose, rather than the size that
     * was asked for — which is the single property this exists to control.
     */
    @Test
    fun `the padding is random, so it does not compress away`() {
        val first = paddingOf(CoverTraffic.fields())
        val second = paddingOf(CoverTraffic.fields())

        assertEquals(CoverTraffic.DEFAULT_PADDING_BYTES, first.size)
        assertFalse("two cover messages must not carry identical padding", first.contentEquals(second))
        assertTrue("padding of one repeated byte would compress", first.toSet().size > 2)
    }

    @Test
    fun `padding with no bytes is refused`() {
        val refused = runCatching { CoverTraffic.fields(paddingBytes = 0) }

        assertTrue("empty padding is an empty message, not cover", refused.isFailure)
    }

    @Suppress("UNCHECKED_CAST")
    private fun paddingOf(fields: Map<Int, Any>): ByteArray =
        (fields[LxmfFields.FIELD_CUSTOM_META] as Map<String, ByteArray>)
            .getValue(CoverTraffic.CUSTOM_META_KEY_PADDING)
}
