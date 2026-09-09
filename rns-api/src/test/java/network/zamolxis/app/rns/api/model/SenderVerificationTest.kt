package network.zamolxis.app.rns.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule both backends read an inbound message's authenticity through.
 *
 * It is a handful of branches, and it decides whether someone can put a
 * contact's name on a message that contact did not write — so every branch is
 * spelled out here rather than left to whichever LXMF stack is compiled in.
 */
class SenderVerificationTest {
    @Test
    fun `a validated signature is a verified sender`() {
        assertEquals(
            SenderVerification.VERIFIED,
            SenderVerification.of(signatureValidated = true, unverifiedReason = null),
        )
    }

    @Test
    fun `an unknown identity is unverified but not an attack`() {
        assertEquals(
            SenderVerification.SOURCE_UNKNOWN,
            SenderVerification.of(
                signatureValidated = false,
                unverifiedReason = SenderVerification.REASON_SOURCE_UNKNOWN,
            ),
        )
    }

    @Test
    fun `a signature that does not match the claimed identity is invalid`() {
        assertEquals(
            SenderVerification.SIGNATURE_INVALID,
            SenderVerification.of(
                signatureValidated = false,
                unverifiedReason = SenderVerification.REASON_SIGNATURE_INVALID,
            ),
        )
    }

    /**
     * Upstream reaches this state when the validation itself threw. It proves
     * nothing about the sender, so it cannot be read as the forgiving branch —
     * or that exception becomes the way in.
     */
    @Test
    fun `an unvalidated signature with no reason given fails closed`() {
        assertEquals(
            SenderVerification.SIGNATURE_INVALID,
            SenderVerification.of(signatureValidated = false, unverifiedReason = null),
        )
    }

    /** A reason this build does not recognise is still a failure to verify. */
    @Test
    fun `an unknown reason code fails closed`() {
        assertEquals(
            SenderVerification.SIGNATURE_INVALID,
            SenderVerification.of(signatureValidated = false, unverifiedReason = 0x7F),
        )
    }

    /**
     * The reason a validated flag wins outright: upstream sets the reason on the
     * message and never clears it, so a stale one must not undo a good signature.
     */
    @Test
    fun `a validated signature outranks a leftover reason code`() {
        assertEquals(
            SenderVerification.VERIFIED,
            SenderVerification.of(
                signatureValidated = true,
                unverifiedReason = SenderVerification.REASON_SIGNATURE_INVALID,
            ),
        )
    }

    // ── what reaches the user ────────────────────────────────────────────────

    @Test
    fun `only a forged signature is undeliverable`() {
        assertTrue(SenderVerification.VERIFIED.isDeliverable)
        assertTrue("a first contact must still be able to say hello", SenderVerification.SOURCE_UNKNOWN.isDeliverable)
        assertFalse(SenderVerification.SIGNATURE_INVALID.isDeliverable)
    }

    /**
     * A message built without anyone filling this in must not claim its sender
     * was proven. Under-claiming is a badge; over-claiming is impersonation.
     */
    @Test
    fun `the default on a received message is not verified`() {
        val message =
            ReceivedMessage(
                messageHash = "hash",
                content = "hello",
                sourceHash = ByteArray(16),
                destinationHash = ByteArray(16),
                timestamp = 0L,
            )

        assertEquals(SenderVerification.SOURCE_UNKNOWN, message.senderVerification)
    }

    /** The wire values are LXMF's, not ours, and must not drift from upstream. */
    @Test
    fun `the reason codes match the LXMF wire vocabulary`() {
        assertEquals(0x01, SenderVerification.REASON_SOURCE_UNKNOWN)
        assertEquals(0x02, SenderVerification.REASON_SIGNATURE_INVALID)
    }
}
