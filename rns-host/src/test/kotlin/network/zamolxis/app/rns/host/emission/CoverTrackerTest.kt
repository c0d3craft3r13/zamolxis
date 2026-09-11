package network.zamolxis.app.rns.host.emission

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Making two very different instruments describe the same stretch of time.
 *
 * A scan occupies the window by its nature. Traffic counters do not — they run
 * from boot, and answer for however long it has been since the last question.
 * Left alone, that mismatch reports a whole idle morning's traffic as one
 * window's company.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverTrackerTest {
    private class FakeRadios(
        private val heard: Int?,
    ) : RadioCounter {
        var windowAskedFor: Long? = null

        override suspend fun countNearbyRadios(windowMs: Long): Int? {
            windowAskedFor = windowMs
            return heard
        }
    }

    private class FakeTraffic(
        private val readings: MutableList<Long?>,
    ) : ForeignTrafficCounter {
        val callCount get() = calls
        private var calls = 0

        override fun foreignBytesSinceLastCall(): Long? {
            calls++
            return readings.removeFirstOrNull()
        }
    }

    @Test
    fun `both halves of one window are reported together`() =
        runTest {
            val traffic = FakeTraffic(mutableListOf(999_999, 5_000))
            val tracker = CoverTracker(FakeRadios(heard = 12), traffic)

            val cover = tracker.measure(windowMs = 30_000)

            assertEquals(12, cover.nearbyRadios)
            assertEquals(5_000L, cover.foreignBytes)
        }

    /**
     * The first traffic reading is thrown away deliberately. It covers whatever
     * time has passed since the counter was last asked, which on a freshly
     * started service is the whole uptime — enough to declare a busy line on a
     * phone that has sent nothing for hours.
     */
    @Test
    fun `the stale span before the window is discarded, not counted`() =
        runTest {
            val traffic = FakeTraffic(mutableListOf(50_000_000, 10))
            val tracker = CoverTracker(FakeRadios(heard = 0), traffic)

            val cover = tracker.measure(windowMs = 30_000)

            assertEquals("the window's own traffic, not the backlog", 10L, cover.foreignBytes)
            assertEquals(2, traffic.callCount)
        }

    /**
     * The failure this arrangement exists to avoid. A phone that cannot scan —
     * dark screen, no permission — returns immediately. If the window collapsed
     * with it, the traffic counter would be read twice in a row with no time in
     * between and report a silent line on every such device.
     */
    @Test
    fun `a window that cannot be scanned still takes a window to measure`() =
        runTest {
            val traffic = FakeTraffic(mutableListOf(0, 7_000))
            val tracker = CoverTracker(FakeRadios(heard = null), traffic)

            val startedAt = testScheduler.currentTime
            val cover = tracker.measure(windowMs = 30_000)

            assertTrue("the window must still elapse", testScheduler.currentTime - startedAt >= 30_000)
            assertNull("scanning was impossible and must say so", cover.nearbyRadios)
            assertEquals("the line was measured anyway", 7_000L, cover.foreignBytes)
        }

    @Test
    fun `the window asked for is the window the scan uses`() =
        runTest {
            val radios = FakeRadios(heard = 3)

            CoverTracker(radios, FakeTraffic(mutableListOf(0, 0))).measure(windowMs = 12_345)

            assertEquals(12_345L, radios.windowAskedFor)
        }
}
