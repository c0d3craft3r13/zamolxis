package network.zamolxis.app.rns.host.emission

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * How many other radios are close enough to be heard.
 *
 * ## Why this does not reuse the scanner that already exists
 *
 * `BleScanner` filters every scan to the Reticulum service UUID, because its
 * job is finding peers. That makes it exactly the wrong instrument here: it
 * would count how many devices are running this same software, when the
 * question is how many ordinary radios — headphones, watches, a television —
 * are around to be indistinguishable from. Ten peers is not a crowd to hide in;
 * ten strangers is.
 *
 * ## What it does with what it hears
 *
 * Counts distinct radios and keeps nothing. An unfiltered scan sees every
 * device in range, which is precisely the kind of record that must not exist on
 * a phone that may be taken: addresses live in a set for the length of one
 * window, are never written down, never logged, and are dropped before the
 * count is returned.
 *
 * ## What it costs
 *
 * Nothing on the air. Scanning is listening; the radio receives and transmits
 * nothing to do it.
 *
 * ## When it cannot answer
 *
 * Missing permission, Bluetooth switched off, a platform that refuses the scan,
 * or a screen that has gone dark all return null rather than a number. A
 * silence that could not be measured is not the same as an empty room, and
 * must never be read as one.
 *
 * The dark screen is not a guess. Measured on two phones on the same desk at
 * the same moment: the awake one heard twenty-three radios, the dozing one
 * heard none, and waking it made sixteen appear. The system dump said why —
 * of six seconds of scanning, four milliseconds were active and the rest
 * suspended. Android stops a low-power scan when the device dozes, and the
 * zero it returns is indistinguishable from a genuinely empty room.
 *
 * This is read conservatively on purpose. A foreground service may well keep a
 * scan alive where an ordinary app's is suspended; that has not been measured
 * here, and until it is, an unmeasurable window reports nothing rather than a
 * number that may have been silently throttled to zero.
 */
class BluetoothCoverMeter(
    private val context: Context,
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter,
    private val isScreenAwake: () -> Boolean = {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
    },
) : RadioCounter {
    /**
     * Distinct radios heard during [windowMs], or null if listening is not possible.
     *
     * The window is short deliberately — see [CoverThresholds.WINDOW_MS] for why
     * a longer one counts a single phone several times over.
     */
    override suspend fun countNearbyRadios(windowMs: Long): Int? {
        val scanner = usableScanner() ?: return null

        val heard = Collections.synchronizedSet(mutableSetOf<String>())
        val callback = countingCallback(heard)
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build()

        return try {
            // No ScanFilter on purpose: the crowd worth hiding in is everyone else's
            // hardware, not the devices running this software.
            withContext(Dispatchers.Main) { scanner.startScan(null, settings, callback) }
            delay(windowMs)
            heard.size
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the scan.
            null
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { scanner.stopScan(callback) }
            }
            heard.clear()
        }
    }

    /**
     * The scanner to listen with, or null if listening would not produce a number
     * worth believing: no permission, Bluetooth off, or a screen dark enough that
     * the system will suspend the scan and hand back a zero that looks exactly
     * like an empty room.
     */
    private fun usableScanner(): BluetoothLeScanner? {
        if (!hasScanPermission()) return null
        if (!isScreenAwake()) return null
        return adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    }

    private fun countingCallback(heard: MutableSet<String>) =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                remember(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { remember(it) }
            }

            /** Address in, count out. Nothing about who was heard survives the window. */
            private fun remember(result: ScanResult?) {
                val address = runCatching { result?.device?.address }.getOrNull()
                if (address != null) heard.add(address)
            }
        }

    private fun hasScanPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
