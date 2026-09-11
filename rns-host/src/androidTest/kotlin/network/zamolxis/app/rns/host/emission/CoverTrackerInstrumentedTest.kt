package network.zamolxis.app.rns.host.emission

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.zamolxis.app.rns.api.model.EmissionMedium
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Both meters, on a real phone, describing the same real window.
 *
 * Everything above this reads an [AmbientCover] and decides from it. The
 * measurements it holds come from two very different instruments — one that
 * occupies the window by listening, one that reads counters running since boot
 * — and the arithmetic that makes them describe the same stretch of time is
 * exercised off-device against fakes. What only a phone can show is that the
 * two produce sane numbers together at all: that scanning and the traffic
 * counters coexist, that neither returns nonsense when driven by the real
 * platform, and that a window measured here looks like a window.
 */
@RunWith(AndroidJUnit4::class)
class CoverTrackerInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareDevice() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
        }
        // A dark screen makes the meter refuse to count, which is correct and is
        // covered elsewhere; here the question is what a working window reports.
        automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val giveUpAt = SystemClock.elapsedRealtime() + WAKE_TIMEOUT_MS
        while (!power.isInteractive && SystemClock.elapsedRealtime() < giveUpAt) {
            Thread.sleep(WAKE_POLL_MS)
        }
    }

    @Test
    fun aRealWindowProducesRealNumbers() {
        val tracker = CoverTracker(BluetoothCoverMeter(context), NetworkCoverMeter())

        val started = SystemClock.elapsedRealtime()
        val cover = runBlocking { tracker.measure(windowMs = WINDOW_MS) }
        val elapsed = SystemClock.elapsedRealtime() - started

        report(
            "window ${elapsed}ms: radios=${cover.nearbyRadios} foreignBytes=${cover.foreignBytes} " +
                "(thresholds ${CoverThresholds.NEARBY_RADIOS} / ${CoverThresholds.FOREIGN_BYTES})",
        )

        assertTrue("the window must actually last a window", elapsed >= WINDOW_MS)
        assertNotNull("the phone's own traffic must be measurable", cover.foreignBytes)
        assertTrue("a byte count cannot be negative", cover.foreignBytes!! >= 0)
        cover.nearbyRadios?.let { assertTrue("a radio count cannot be negative", it >= 0) }
    }

    /**
     * The rule reads this, so it is worth seeing it come out of a real device
     * rather than only out of fakes: what a phone on a desk actually decides.
     */
    @Test
    fun aRealWindowDecidesSomething() {
        val tracker = CoverTracker(BluetoothCoverMeter(context), NetworkCoverMeter())

        val cover = runBlocking { tracker.measure(windowMs = WINDOW_MS) }

        val overBluetooth = EmissionMedium.BLUETOOTH_LE.hasCover(cover)
        val overNetwork = EmissionMedium.NETWORK.hasCover(cover)
        val overRadio = EmissionMedium.LORA_CARRIED.hasCover(cover)

        report("this phone would pad: bluetooth=$overBluetooth network=$overNetwork lora=$overRadio")

        assertFalse("no measurement may ever make a radio paddable", overRadio)
    }

    private fun report(line: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", System.lineSeparator() + line + System.lineSeparator()) },
        )
    }

    private companion object {
        const val WINDOW_MS = CoverThresholds.WINDOW_MS
        const val WAKE_TIMEOUT_MS = 5_000L
        const val WAKE_POLL_MS = 100L
    }
}
