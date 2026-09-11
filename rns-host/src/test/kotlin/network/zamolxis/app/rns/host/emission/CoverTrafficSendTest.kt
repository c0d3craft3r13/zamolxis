package network.zamolxis.app.rns.host.emission

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.rns.api.RnsLxmf
import network.zamolxis.app.rns.api.model.DeliveryMethod
import network.zamolxis.app.rns.api.model.Identity
import network.zamolxis.app.rns.api.model.MessageReceipt
import network.zamolxis.app.rns.api.util.CoverTraffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a cover message leaves — and the one way it must never leave.
 *
 * Assertions are on what reached the messaging layer, not on the returned
 * result: a call that went out the wrong way and reported success would pass
 * any test that only looked at the result.
 */
class CoverTrafficSendTest {
    private val sender = Identity(hash = ByteArray(16) { 7 }, publicKey = ByteArray(0), privateKey = null)
    private val destination = ByteArray(16) { 3 }

    private val method = slot<DeliveryMethod>()
    private val fallback = slot<Boolean>()
    private val content = slot<String>()
    private val fieldsSent = slot<Map<Int, Any>>()
    private var calls = 0

    private val lxmf =
        mockk<RnsLxmf> {
            coEvery {
                sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = capture(content),
                    sourceIdentity = any(),
                    deliveryMethod = capture(method),
                    tryPropagationOnFail = capture(fallback),
                    imageData = any(),
                    imageFormat = any(),
                    fileAttachments = any(),
                    replyToMessageId = any(),
                    replyQuotedContent = any(),
                    iconAppearance = any(),
                    extraFields = capture(fieldsSent),
                )
            } answers {
                calls++
                Result.success(mockk<MessageReceipt>())
            }
        }

    /**
     * The decision that matters. A real message that cannot reach its recipient
     * is worth parking on a relay for thirty days; padding is not. Parking it
     * would fill someone else's relay with junk and hand its operator a record
     * of this device's sending rhythm — the thing cover exists to blur.
     */
    @Test
    fun `cover is never parked on a relay`() =
        runTest {
            sendCover(lxmf, destination, CoverTraffic.fields(), sender)

            assertFalse("an unreachable peer gets nothing, not a relayed copy", fallback.captured)
            assertEquals(DeliveryMethod.DIRECT, method.captured)
        }

    @Test
    fun `cover says nothing and carries only its padding`() =
        runTest {
            val fields = CoverTraffic.fields()

            sendCover(lxmf, destination, fields, sender)

            assertEquals("", content.captured)
            assertEquals(fields, fieldsSent.captured)
            assertTrue(
                "what arrives must be recognisable as cover on the other end",
                fieldsSent.captured.keys == fields.keys,
            )
        }

    /** With no identity to send as, nothing goes out at all. */
    @Test
    fun `no active identity means no cover`() =
        runTest {
            val result = sendCover(lxmf, destination, CoverTraffic.fields(), sender = null)

            assertTrue(result.isFailure)
            assertEquals("nothing may reach the messaging layer", 0, calls)
        }
}
