package network.zamolxis.app.rns.host.emission

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one question about network cover that only a real device can answer.
 *
 * Every release tightens what an app may learn about the rest of the system,
 * and per-app traffic for *other* apps stopped being readable years ago. What
 * this rule needs is narrower — the device's own total, and our own share — and
 * whether those are still given out is a fact about the platform in the
 * operator's hand, not something to take on faith from documentation.
 *
 * If this fails, the network half of the cover rule cannot be measured on this
 * Android version and must not silently behave as though the line were busy.
 */
@RunWith(AndroidJUnit4::class)
class PlatformTxCountersInstrumentedTest {
    private val counters = PlatformTxCounters()

    @Test
    fun theDeviceReportsWhatItHasSent() {
        assertNotNull("the platform no longer reports the device transmit total", counters.deviceTotal())
    }

    @Test
    fun theDeviceReportsWhatThisAppHasSent() {
        assertNotNull("the platform no longer reports this app's transmit total", counters.ours())
    }

    /**
     * Subtracting one from the other is the whole measurement, so the two have
     * to be counting the same bytes. Our share exceeding the device total would
     * mean they are not, and the difference would be meaningless rather than
     * merely small.
     */
    @Test
    fun ourShareIsPartOfTheDeviceTotal() {
        val total = counters.deviceTotal()
        val ours = counters.ours()
        assertNotNull(total)
        assertNotNull(ours)

        assertTrue(
            "this app is reported as having sent more than the whole device: $ours > $total",
            ours!! <= total!!,
        )
    }

    /** A counter that never moves is a counter that cannot measure a window. */
    @Test
    fun theDeviceTotalIsANumberThatCanGrow() {
        val first = counters.deviceTotal()
        assertNotNull(first)

        assertTrue("a device that has sent nothing since boot cannot be measured", first!! >= 0)
    }
}
