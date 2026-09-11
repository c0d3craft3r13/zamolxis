package network.zamolxis.app.rns.host.emission

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a foreground service lets a scan survive a sleeping phone.
 *
 * This is the question left open when the cover meter learned to refuse a dark
 * screen. An ordinary app's scan is suspended when the device dozes — measured,
 * with the system reporting four active milliseconds out of six seconds — and
 * the zero it returns is the same zero an empty room returns. But the app runs
 * its stack in a foreground service, and if that is exempt, the refusal is
 * costing a measurement that could have been taken. A phone spends most of its
 * life with the screen off; a rule that only works while someone is looking at
 * it barely works.
 *
 * The scan here runs in the same process as the test. A foreground service is
 * started first, so the only thing that differs from the earlier measurement is
 * how important this process is to the system.
 *
 * The outcome is reported rather than asserted: what it shows is a fact about
 * this Android build, not something the code can be right or wrong about. Both
 * answers are useful and neither should fail a build.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundScanThrottlingTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        context.startForegroundService(Intent(context, ScanProbeService::class.java))
        Thread.sleep(SETTLE_MS)
    }

    @After
    fun stopEverything() {
        context.stopService(Intent(context, ScanProbeService::class.java))
        shell("input keyevent KEYCODE_WAKEUP")
    }

    @Test
    fun aForegroundServiceScansOrDoesNotWhileTheScreenIsOff() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        assumeTrue("no Bluetooth on this device", manager?.adapter?.isEnabled == true)

        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // The meter refuses a dark screen on purpose; this measurement is about what
        // the platform does, so it asks the scanner directly rather than through it.
        val meter = BluetoothCoverMeter(context, isScreenAwake = { true })

        // Without this the measurement means nothing: a service that failed to reach
        // the foreground would leave the process ordinary, and the zero below would be
        // the throttling already known about rather than an answer to the question.
        val importanceAsleepBefore = processImportance()

        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(SETTLE_MS)
        val wasAsleep = !power.isInteractive
        val importanceAsleep = processImportance()
        val heardAsleep = runBlocking { meter.countNearbyRadios(windowMs = WINDOW_MS) }

        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(SETTLE_MS)
        val heardAwake = runBlocking { meter.countNearbyRadios(windowMs = WINDOW_MS) }

        report(
            "importance before=${describe(importanceAsleepBefore)} " +
                "asleep=${describe(importanceAsleep)}; " +
                "screen off (asleep=$wasAsleep): $heardAsleep radios; " +
                "screen on: $heardAwake radios",
        )
        assertEquals(
            "the probe service never reached the foreground, so nothing was measured",
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            importanceAsleepBefore,
        )
    }

    /** What the system currently thinks this process is worth keeping alive. */
    private fun processImportance(): Int {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance
    }

    private fun describe(importance: Int): String =
        when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            else -> "other($importance)"
        }

    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(command).close()
    }

    private fun report(line: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", System.lineSeparator() + line + System.lineSeparator()) },
        )
    }

    private companion object {
        const val WINDOW_MS = 15_000L
        const val SETTLE_MS = 2_000L
    }
}
