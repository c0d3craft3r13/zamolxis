package network.zamolxis.app.rns.host.emission

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Counts radios close enough to be heard. Implemented by [BluetoothCoverMeter]. */
interface RadioCounter {
    suspend fun countNearbyRadios(windowMs: Long = CoverThresholds.WINDOW_MS): Int?
}

/** Counts bytes this device sent that were not ours. Implemented by [NetworkCoverMeter]. */
interface ForeignTrafficCounter {
    fun foreignBytesSinceLastCall(): Long?
}

/**
 * One reading of how much company there is, across both media at once.
 *
 * The two measurements have to describe the same stretch of time or they cannot
 * be compared against thresholds meant for one window. Counting radios occupies
 * the window by its nature — a scan runs for as long as you listen. Traffic
 * counters do not: they run from boot, and asking them yields however long it
 * has been since the last question. So the traffic counter is read once at the
 * start to throw away that stale span, and again at the end.
 */
class CoverTracker(
    private val radios: RadioCounter,
    private val traffic: ForeignTrafficCounter,
) {
    /**
     * Listen for [windowMs] and report what was around.
     *
     * The window elapses whether or not the radios can be counted. If a dark
     * screen or a missing permission makes scanning impossible, that half comes
     * back unmeasured — but the traffic half still describes a full window
     * rather than an instant, which would otherwise read as a silent line on
     * every device that cannot scan.
     */
    suspend fun measure(windowMs: Long = CoverThresholds.WINDOW_MS): AmbientCover =
        coroutineScope {
            // Discarded on purpose: this reading covers whatever time has passed
            // since the last one, which is not the window being measured.
            traffic.foreignBytesSinceLastCall()

            val heard = async { radios.countNearbyRadios(windowMs) }
            delay(windowMs)

            AmbientCover(
                nearbyRadios = heard.await(),
                foreignBytes = traffic.foreignBytesSinceLastCall(),
            )
        }
}
