package network.zamolxis.app.rns.host.emission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the meter says when it cannot listen.
 *
 * This is the half that has to be right. A crowd it fails to hear costs
 * nothing — padding simply does not start. A silence it invents is the
 * opposite: the rule concludes there is company, and the device begins a
 * steady rhythm in a room where it is the only thing transmitting.
 *
 * So every way of not knowing has to arrive as "no answer", never as a number.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothCoverMeterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Robolectric grants no runtime permissions unless a test asks for them,
     * which is the ungranted case exactly. Revoking a real permission cannot be
     * tested on a device: Android kills the process that loses one.
     */
    @Test
    fun `without permission to scan it reports no answer`() =
        runTest {
            assertNull(BluetoothCoverMeter(context).countNearbyRadios(windowMs = 10))
        }

    /** A phone with Bluetooth switched off is not a phone in an empty room. */
    @Test
    fun `with no bluetooth at all it reports no answer`() =
        runTest {
            assertNull(BluetoothCoverMeter(context, adapter = null).countNearbyRadios(windowMs = 10))
        }

    /**
     * Measured, not supposed. Two phones on one desk at one moment: the awake one
     * heard twenty-three radios, the dozing one heard none, and waking it produced
     * sixteen. The system's own dump showed four milliseconds of a six-second scan
     * actually running and the rest suspended.
     *
     * So a dark screen must not produce a number. The zero it would produce is
     * exactly the zero an empty room produces, and the two mean opposite things.
     */
    @Test
    fun `a dozing phone reports no answer rather than an empty room`() =
        runTest {
            val meter = BluetoothCoverMeter(context, adapter = null, isScreenAwake = { false })

            assertNull(meter.countNearbyRadios(windowMs = 10))
        }
}
