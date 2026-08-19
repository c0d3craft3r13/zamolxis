package network.zamolxis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dialog asks the user to read a fingerprint out loud to their contact and
 * compare it. That only works if the rendering is actually readable, so the
 * formatting is worth pinning down.
 */
class PqKeyChangeDialogTest {
    @Test
    fun `a fingerprint renders as groups of four hex characters`() {
        val fingerprint = ByteArray(16) { it.toByte() }

        assertEquals(
            "0001 0203 0405 0607 0809 0A0B 0C0D 0E0F",
            formatFingerprint(fingerprint),
        )
    }

    @Test
    fun `hex is upper case`() {
        // Lower-case hex is harder to read aloud accurately, and 'b' versus '6'
        // over a poor phone line is exactly the confusion to avoid.
        assertEquals("ABCD EF01", formatFingerprint(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01)))
    }

    @Test
    fun `high bytes are not sign-extended`() {
        // A naive toString(16) on a negative Byte yields something like "ffffffff".
        assertEquals("FFFE 8000", formatFingerprint(byteArrayOf(-1, -2, -128, 0)))
    }

    @Test
    fun `every byte is rendered`() {
        val fingerprint = ByteArray(16) { (it * 7).toByte() }

        val hexOnly = formatFingerprint(fingerprint).replace(" ", "")

        assertEquals(fingerprint.size * 2, hexOnly.length)
    }

    @Test
    fun `two different keys render differently`() {
        val first = formatFingerprint(ByteArray(16) { it.toByte() })
        val second = formatFingerprint(ByteArray(16) { (it + 1).toByte() })

        assertEquals(false, first == second)
    }

    @Test
    fun `an empty fingerprint renders as empty rather than throwing`() {
        assertEquals("", formatFingerprint(ByteArray(0)))
    }
}
