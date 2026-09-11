package network.zamolxis.app.rns.host.emission

import android.net.TrafficStats
import android.os.Process

/**
 * Cumulative counts of bytes this device has sent since it booted.
 *
 * Split out from the platform so the arithmetic that matters — subtracting our
 * own traffic, surviving a counter that restarts — can be exercised without a
 * phone, and so the one question only a phone can answer (whether the platform
 * answers at all) is asked in one place.
 */
interface TxCounters {
    /** Everything this device has sent, or null if the platform will not say. */
    fun deviceTotal(): Long?

    /** What this app has sent, or null if the platform will not say. */
    fun ours(): Long?
}

/**
 * The counters as Android reports them.
 *
 * Both processes of this app share one UID, so a single subtraction removes the
 * UI process and the `:reticulum` service together.
 *
 * Only transmitted bytes are counted. What hides an upload is other uploads;
 * a download is not missed by ignoring received bytes, because the
 * acknowledgements it generates are themselves transmissions and show up here.
 */
class PlatformTxCounters(
    private val uid: Int = Process.myUid(),
) : TxCounters {
    override fun deviceTotal(): Long? = TrafficStats.getTotalTxBytes().takeIf { it >= 0 }

    override fun ours(): Long? = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 }
}

/**
 * How much traffic this device sent that was not ours.
 *
 * A network observer watches this subscriber, so the only traffic that can hide
 * ours is traffic leaving the same device. That is what this measures, and it
 * measures it by reading counters — nothing is transmitted to find out.
 */
class NetworkCoverMeter(
    private val counters: TxCounters = PlatformTxCounters(),
) : ForeignTrafficCounter {
    private var previousForeign: Long? = null

    /**
     * Foreign bytes sent since the previous call, or null if the platform will
     * not report the counters at all.
     *
     * The first call establishes a baseline and reports nothing. The counters
     * run from boot, so reporting their absolute value would count an entire
     * uptime as one window's worth of company and start padding on a device
     * that has been idle for hours.
     *
     * A counter that has gone backwards — a reboot between calls — is treated
     * the same way: re-baseline, report nothing. Both cases fail towards no
     * cover, which is the direction that keeps a quiet device quiet.
     */
    override fun foreignBytesSinceLastCall(): Long? {
        val foreign = foreignTotal() ?: return null
        val previous = previousForeign
        previousForeign = foreign

        val isFirstReadingOrRestart = previous == null || foreign < previous
        return if (isFirstReadingOrRestart) 0 else foreign - previous
    }

    /**
     * Everything sent since boot that was not ours, or null if either half of
     * the subtraction is missing. Clamped at zero: if the platform ever reports
     * our share as larger than the device's, the difference is meaningless
     * rather than merely small, and no company is the safe reading of it.
     */
    private fun foreignTotal(): Long? {
        val total = counters.deviceTotal() ?: return null
        val own = counters.ours() ?: return null
        return (total - own).coerceAtLeast(0)
    }
}
