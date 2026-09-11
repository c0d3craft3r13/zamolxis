package network.zamolxis.app.rns.host.emission

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Is there any way at all to hear a room while the phone is asleep?
 *
 * Low-power scanning from a foreground service has already been shown not to
 * work: the system suspends it and hands back a zero indistinguishable from an
 * empty room. Before concluding that Bluetooth company simply cannot be
 * measured on a sleeping phone — which would mean padding there can never be
 * justified — the other ways of asking are worth trying, because the answer
 * decides whether a whole medium keeps the rule or loses it.
 *
 * Three are tried in the one state that matters, screen off with the process
 * held in the foreground:
 *  - low latency, in case the suspension is a power-saving choice rather than a rule;
 *  - opportunistic, which starts no scan of its own and only hears what another
 *    app's scan turns up;
 *  - filtered, since the documented restriction is on *unfiltered* background
 *    scanning, and a filter that matches anything at all would at least prove
 *    the radio is still listening.
 *
 * Nothing is asserted. This reports what the platform does.
 */
@RunWith(AndroidJUnit4::class)
class SleepingScanModeProbe {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun holdTheProcessInTheForeground() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        context.startForegroundService(Intent(context, ScanProbeService::class.java))
        Thread.sleep(SETTLE_MS)
    }

    @After
    fun wakeAndStop() {
        shell("input keyevent KEYCODE_WAKEUP")
        context.stopService(Intent(context, ScanProbeService::class.java))
    }

    @Test
    fun somethingOrNothingHearsASleepingRoom() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        assumeTrue("no Bluetooth on this device", manager?.adapter?.isEnabled == true)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(SETTLE_MS)

        val results =
            buildString {
                append("asleep=${!power.isInteractive}")
                append(" lowLatency=${scan(ScanSettings.SCAN_MODE_LOW_LATENCY, filtered = false)}")
                append(" opportunistic=${scan(ScanSettings.SCAN_MODE_OPPORTUNISTIC, filtered = false)}")
                append(" filtered=${scan(ScanSettings.SCAN_MODE_LOW_POWER, filtered = true)}")
            }

        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(SETTLE_MS)
        report("$results | awake lowPower=${scan(ScanSettings.SCAN_MODE_LOW_POWER, filtered = false)}")
    }

    private fun scan(
        mode: Int,
        filtered: Boolean,
    ): Int {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = manager.adapter.bluetoothLeScanner
        val heard = Collections.synchronizedSet(mutableSetOf<String>())
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult?,
                ) {
                    result?.device?.address?.let { heard.add(it) }
                }
            }
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(mode)
                .setReportDelay(0)
                .build()
        // A filter that excludes nothing: the documented restriction is on unfiltered
        // background scanning, so this asks whether merely having one changes anything.
        val filters = if (filtered) listOf(ScanFilter.Builder().build()) else null

        scanner.startScan(filters, settings, callback)
        Thread.sleep(WINDOW_MS)
        scanner.stopScan(callback)
        val count = heard.size
        heard.clear()
        return count
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
        const val WINDOW_MS = 10_000L
        const val SETTLE_MS = 2_000L
    }
}
