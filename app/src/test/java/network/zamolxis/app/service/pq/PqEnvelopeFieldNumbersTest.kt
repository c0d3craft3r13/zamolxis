package network.zamolxis.app.service.pq

import network.zamolxis.app.rns.api.util.LxmfFields
import network.zamolxis.crypto.pq.PqEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The post-quantum field numbers are declared twice — in `:crypto-pq`, which is a
 * plain JVM module, and in `:rns-api`, which cannot depend on it — and the two
 * must agree.
 *
 * This is not hypothetical bookkeeping. The receive-side chat filter lives in
 * `:rns-api` and decides whether an inbound LXMessage is user-visible payload; if
 * its idea of "sealed content" drifts from the number the sender actually writes,
 * every sealed message is silently discarded before anything can open it, with a
 * delivery proof still going back to the sender. This module sees both constants,
 * so the drift fails here instead.
 */
class PqEnvelopeFieldNumbersTest {
    @Test
    fun `sealed content field number agrees across modules`() {
        assertEquals(PqEnvelope.FIELD_SEALED_CONTENT, LxmfFields.FIELD_SEALED_CONTENT)
    }

    @Test
    fun `sender key field number agrees across modules`() {
        assertEquals(PqEnvelope.FIELD_SENDER_KEY, LxmfFields.FIELD_SENDER_KEY)
    }

    @Test
    fun `post-quantum fields do not collide with the fields already in use`() {
        val inUse =
            setOf(
                LxmfFields.FIELD_TELEMETRY,
                LxmfFields.FIELD_TELEMETRY_STREAM,
                LxmfFields.FIELD_ICON_APPEARANCE,
                LxmfFields.FIELD_FILE_ATTACHMENTS,
                LxmfFields.FIELD_IMAGE,
                LxmfFields.FIELD_AUDIO,
                LxmfFields.FIELD_COMMANDS,
                LxmfFields.FIELD_RENDERER,
                LxmfFields.FIELD_REACTION,
                LxmfFields.FIELD_REACTION_LEGACY,
                LxmfFields.FIELD_REPLY_HASH,
                LxmfFields.FIELD_REPLY_QUOTE,
                LxmfFields.FIELD_CUSTOM_META,
            )

        assertEquals(emptySet<Int>(), inUse.intersect(setOf(0x50, 0x51)))
    }
}
