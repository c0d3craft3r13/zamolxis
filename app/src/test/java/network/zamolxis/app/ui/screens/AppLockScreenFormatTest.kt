package network.zamolxis.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The countdown shown while the PIN pad is refusing entries.
 */
class AppLockScreenFormatTest {
    @Test
    fun `seconds are padded to two digits`() {
        assertEquals("1:05", formatRemaining(65_000L))
    }

    @Test
    fun `a whole minute reads as zero seconds`() {
        assertEquals("1:00", formatRemaining(60_000L))
    }

    @Test
    fun `the half-hour cap is shown in full`() {
        assertEquals("30:00", formatRemaining(1_800_000L))
    }

    /**
     * Rounding up, so a fraction of a second left never reads as none left. A
     * countdown sitting at 0:00 while the pad still refuses looks broken, and
     * this screen is the wrong place to make someone doubt the app.
     */
    @Test
    fun `a part-second remaining still shows one second`() {
        assertEquals("0:01", formatRemaining(1L))
        assertEquals("0:01", formatRemaining(999L))
    }

    @Test
    fun `nothing left reads as zero`() {
        assertEquals("0:00", formatRemaining(0L))
    }

    /** Defensive: a clock adjustment can hand this a negative figure. */
    @Test
    fun `a negative remainder does not produce a negative clock`() {
        assertEquals("0:00", formatRemaining(-5_000L))
    }
}
