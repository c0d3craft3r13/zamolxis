package network.zamolxis.app.rns.host.emission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import network.zamolxis.app.rns.host.persistence.ServiceSettingsAccessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The flag that decides whether this device speaks unprompted.
 *
 * It is stored where both processes can read it and re-read on every call,
 * because both of them start transmissions on their own: the UI process
 * schedules announces, relay syncs and path sweeps, and the service announces
 * whenever the network changes. A flag either held privately, or held in a
 * cache, would be a flag the other kept transmitting through — and the window
 * where that mattered is exactly the window where silence was asked for.
 */
@RunWith(RobolectricTestRunner::class)
class RadioSilenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = ServiceSettingsAccessor(context)
    private val policy = EmissionPolicy(context)

    @Before
    fun setUp() {
        settings.setRadioSilence(false)
    }

    /**
     * A device already in someone's hands behaves tomorrow as it did today.
     * Silence is a decision its owner makes, not one an update makes for them.
     */
    @Test
    fun `silence is off until it is asked for`() {
        assertTrue("an install that never asked must keep working as it did", policy.mayEmit())
        assertFalse(policy.isSilent)
    }

    @Test
    fun `asking for silence stops emitting`() {
        settings.setRadioSilence(true)

        assertFalse(policy.mayEmit())
        assertTrue(policy.isSilent)
    }

    @Test
    fun `lifting it starts again`() {
        settings.setRadioSilence(true)
        settings.setRadioSilence(false)

        assertTrue(policy.mayEmit())
    }

    /**
     * The one that matters. A policy built at app start and consulted for hours
     * afterwards has to see a flag set later by the other process — otherwise
     * the operator asks for silence and the radio keeps talking until something
     * happens to rebuild the object.
     */
    @Test
    fun `a policy built earlier still sees the flag change`() {
        val longLived = EmissionPolicy(context)
        assertTrue(longLived.mayEmit())

        settings.setRadioSilence(true)

        assertFalse("silence must take effect without waiting for a restart", longLived.mayEmit())
    }

    /** The refusal names what was held back, so a log line says what happened. */
    @Test
    fun `being held back says so plainly`() {
        val message = RadioSilentException("relay sync").message.orEmpty()

        assertTrue(message, message.contains("relay sync"))
        assertTrue(message, message.contains("radio silence"))
    }
}
