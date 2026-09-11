package network.zamolxis.app.rns.host.emission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning two counters that run from boot into "how much company was there just now".
 *
 * Every mistake available here fails the same way — reporting company that was
 * not there, and starting to pad on a device nobody else is using — so each
 * case checks that the arithmetic errs towards no cover.
 */
class NetworkCoverMeterTest {
    private class FakeCounters(
        var total: Long?,
        var own: Long?,
    ) : TxCounters {
        override fun deviceTotal(): Long? = total

        override fun ours(): Long? = own
    }

    /**
     * The counters run from boot. Reporting their absolute value would hand a
     * whole uptime to the first window and start padding on a phone that has
     * been sitting idle all morning.
     */
    @Test
    fun `the first reading is a baseline and reports no company`() {
        val counters = FakeCounters(total = 900_000, own = 100_000)

        assertEquals(0L, NetworkCoverMeter(counters).foreignBytesSinceLastCall())
    }

    @Test
    fun `only what other apps sent between two readings counts`() {
        val counters = FakeCounters(total = 900_000, own = 100_000)
        val meter = NetworkCoverMeter(counters)
        meter.foreignBytesSinceLastCall()

        counters.total = 950_000
        counters.own = 120_000

        assertEquals("20k of the 50k was ours", 30_000L, meter.foreignBytesSinceLastCall())
    }

    /** Our own traffic is not company for itself, however much of it there is. */
    @Test
    fun `a window where only we transmitted has no company in it`() {
        val counters = FakeCounters(total = 500_000, own = 100_000)
        val meter = NetworkCoverMeter(counters)
        meter.foreignBytesSinceLastCall()

        counters.total = 700_000
        counters.own = 300_000

        assertEquals(0L, meter.foreignBytesSinceLastCall())
    }

    /**
     * Both processes of this app share a UID, so the subtraction covers the UI
     * and the service together — but only if the platform reports both numbers.
     * If either is missing there is no answer, and no answer must not be read
     * as a quiet line or as a busy one.
     */
    @Test
    fun `a platform that will not say reports nothing rather than guessing`() {
        assertNull(NetworkCoverMeter(FakeCounters(total = null, own = 100)).foreignBytesSinceLastCall())
        assertNull(NetworkCoverMeter(FakeCounters(total = 100, own = null)).foreignBytesSinceLastCall())
    }

    /** A reboot restarts the counters; the drop is not a negative amount of company. */
    @Test
    fun `counters that restart re-baseline instead of going backwards`() {
        val counters = FakeCounters(total = 900_000, own = 100_000)
        val meter = NetworkCoverMeter(counters)
        meter.foreignBytesSinceLastCall()

        counters.total = 5_000
        counters.own = 1_000

        assertEquals(0L, meter.foreignBytesSinceLastCall())

        counters.total = 15_000
        counters.own = 2_000

        assertEquals("counting resumes from the new baseline", 9_000L, meter.foreignBytesSinceLastCall())
    }

    /** Nonsense in one direction — ours above the total — must not become company. */
    @Test
    fun `our share exceeding the device total is not negative company`() {
        val counters = FakeCounters(total = 1_000, own = 9_000)

        assertEquals(0L, NetworkCoverMeter(counters).foreignBytesSinceLastCall())
    }
}
