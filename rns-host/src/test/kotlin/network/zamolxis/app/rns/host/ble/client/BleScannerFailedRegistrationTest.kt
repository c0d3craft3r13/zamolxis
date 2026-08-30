package network.zamolxis.app.rns.host.ble.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Context
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * A failed `startScan` leaves its callback registered with the Bluetooth stack;
 * only `stopScan` gives that client record back. Without releasing it, every
 * retry adds another — measured on a Motorola Edge 50 Fusion as 270 stale
 * `app_if: 0` entries for this package, after which `startScan` always failed
 * with SCAN_FAILED_APPLICATION_REGISTRATION_FAILED and `openGattServer`
 * returned null. BLE then stayed dead across app restarts until Bluetooth
 * itself was toggled.
 *
 * The callback is read reflectively: it is private, and driving the real scan
 * loop to obtain it would make the test wait on adaptive scan intervals for
 * something these assertions do not depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScannerFailedRegistrationTest {
    private lateinit var mockContext: Context
    private lateinit var mockAdapter: BluetoothAdapter
    private lateinit var mockLeScanner: BluetoothLeScanner
    private lateinit var scanner: BleScanner

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Explicit stubs rather than relaxed mocks: these three are the only
        // platform objects the scanner touches on this path, and stating them
        // outright keeps the test honest about what it exercises.
        mockContext = mockk()
        mockAdapter = mockk()
        mockLeScanner = mockk()

        every { mockAdapter.isEnabled } returns true
        every { mockAdapter.bluetoothLeScanner } returns mockLeScanner
        every { mockLeScanner.stopScan(any<ScanCallback>()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a failed scan hands its registration back to the stack`() =
        runTest(UnconfinedTestDispatcher()) {
            scanner = BleScanner(mockContext, mockAdapter, this)
            val callback = scanCallbackOf(scanner)

            callback.onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)

            // stopScan is what frees the client record. Skipping it is the leak.
            verify(exactly = 1) { mockLeScanner.stopScan(callback) }
            assertFalse("a failed scan must not leave the scanner marked active", scanner.isScanning.value)
        }

    @Test
    fun `every repeated failure releases its own registration`() =
        runTest(UnconfinedTestDispatcher()) {
            scanner = BleScanner(mockContext, mockAdapter, this)
            val callback = scanCallbackOf(scanner)

            // The retry loop is what turned one failure into 270 stale records,
            // so the release has to happen per failure, not once.
            var released = 0
            every { mockLeScanner.stopScan(callback) } answers { released++ }

            repeat(5) {
                callback.onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
            }

            // One release per failure — anything less and the stack keeps a
            // record for every retry, which is how the pool reached 270.
            assertEquals(5, released)
        }

    private fun scanCallbackOf(scanner: BleScanner): ScanCallback {
        val field = BleScanner::class.java.getDeclaredField("scanCallback")
        field.isAccessible = true
        return field.get(scanner) as ScanCallback
    }
}
