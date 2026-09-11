package network.zamolxis.app.rns.host.emission

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether this phone can actually hear the crowd it is supposed to hide in.
 *
 * Two things can only be settled on hardware. First, an unfiltered scan has to
 * return devices at all: this app asks for Bluetooth scanning with
 * `neverForLocation`, and the system strips location-revealing results from
 * apps that make that promise — an undercount is safe, but its size is worth
 * knowing rather than assuming. Second, the count has to be of everything
 * around, not of peers running this same software, which is what the existing
 * scanner would have reported.
 *
 * The measured number is logged, not asserted against a threshold. How many
 * radios are within range of a desk is not a property of the code.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothCoverMeterInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        }
        wakeAndWait()
    }

    /**
     * Android suspends a low-power scan on a dozing device, so a dark screen turns
     * a scan test into a throttling test. Waking is asynchronous — the shell call
     * returns long before the power state changes — so this waits for the state
     * rather than firing the key and hoping, which is what made this test fail on
     * a phone that was about to be perfectly capable of scanning.
     */
    private fun wakeAndWait() {
        shell("input keyevent KEYCODE_WAKEUP")
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val giveUpAt = SystemClock.elapsedRealtime() + WAKE_TIMEOUT_MS
        while (!power.isInteractive && SystemClock.elapsedRealtime() < giveUpAt) {
            Thread.sleep(WAKE_POLL_MS)
        }
    }

    @Test
    fun anUnfilteredScanHearsTheRoom() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        assumeTrue("no Bluetooth on this device", manager?.adapter?.isEnabled == true)

        val heard = runBlocking { BluetoothCoverMeter(context).countNearbyRadios(windowMs = SCAN_WINDOW_MS) }

        assertNotNull("scanning was possible but reported nothing at all", heard)
        report("radios heard in ${SCAN_WINDOW_MS}ms: $heard (threshold ${CoverThresholds.NEARBY_RADIOS})")
        assertTrue("a count cannot be negative", heard!! >= 0)
    }

    /**
     * The finding this whole guard exists for, reproduced on the hardware that
     * produced it. Two phones on one desk at one moment disagreed completely —
     * the awake one heard twenty-three radios, the dozing one none — and the
     * system's dump showed why: four milliseconds of a six-second scan ran, and
     * the rest was suspended.
     *
     * A zero from a suspended scan is the same zero an empty room gives. If this
     * ever starts returning a number instead of no answer, the rule has begun
     * reading throttling as solitude.
     */
    @Test
    fun aDozingPhoneAdmitsItCannotHear() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        assumeTrue("no Bluetooth on this device", manager?.adapter?.isEnabled == true)

        val heard =
            try {
                shell("input keyevent KEYCODE_SLEEP")
                Thread.sleep(SETTLE_MS)
                runBlocking { BluetoothCoverMeter(context).countNearbyRadios(windowMs = SHORT_WINDOW_MS) }
            } finally {
                wakeAndWait()
            }

        report("while dozing the meter reported: $heard")
        assertNull("a suspended scan must report no answer, not an empty room", heard)
    }

    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(command).close()
    }

    /**
     * Reported through the instrumentation itself rather than printed or logged.
     * Some phones restrict what an app can read back out of logcat, and neither
     * stdout nor a log line survives into the test report — while this number is
     * the whole reason for running on hardware.
     */
    private fun report(line: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", System.lineSeparator() + line + System.lineSeparator()) },
        )
    }

    private companion object {
        /**
         * The real window, not a shortened one. Low-power scanning listens in
         * bursts of roughly half a second every five, so a few seconds of
         * observation can catch no burst at all and report an empty room that is
         * full — which is what a shorter window here did on one of two phones
         * sitting on the same desk.
         */
        const val SCAN_WINDOW_MS = CoverThresholds.WINDOW_MS

        /** Long enough to prove the refusal, short enough not to sit on a dark screen. */
        const val SHORT_WINDOW_MS = 2_000L

        /** Give the power state a moment to actually change before measuring it. */
        const val SETTLE_MS = 1_500L

        /** How long to wait for a wake to take effect before giving up on it. */
        const val WAKE_TIMEOUT_MS = 5_000L
        const val WAKE_POLL_MS = 100L
    }
}
