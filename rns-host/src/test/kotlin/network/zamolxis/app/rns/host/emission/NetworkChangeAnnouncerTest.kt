package network.zamolxis.app.rns.host.emission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.rns.host.persistence.ServiceSettingsAccessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Recording an announce only when one happened.
 *
 * The record is load-bearing in two directions: the app process reads it as
 * "already announced, skip this cycle", and the settings screen shows it as the
 * last time this device was heard. Writing it unconditionally — which is what
 * the service used to do, around a call that emitted nothing — defers the real
 * announce indefinitely on a phone that changes networks often, and shows a time
 * that never happened.
 *
 * So every test here asks the same question from a different angle: did the
 * record follow an actual transmission?
 */
@RunWith(RobolectricTestRunner::class)
class NetworkChangeAnnouncerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = ServiceSettingsAccessor(context)

    private val announcedNames = mutableListOf<String>()
    private val recorded = mutableListOf<Long>()

    private var name: String? = "Scout"
    private var outcome: Result<Unit> = Result.success(Unit)

    private fun announcer() =
        NetworkChangeAnnouncer(
            emissions = EmissionPolicy(context),
            displayName = { name },
            announce = { who ->
                announcedNames += who
                outcome
            },
            recordAnnounced = { at -> recorded += at },
            now = { FIXED_NOW },
        )

    @Before
    fun setUp() {
        settings.setRadioSilence(false)
        announcedNames.clear()
        recorded.clear()
        name = "Scout"
        outcome = Result.success(Unit)
    }

    @Test
    fun `a successful announce is made and then recorded`() =
        runTest {
            val announced = announcer().announceOnNetworkChange()

            assertTrue(announced)
            assertEquals(listOf("Scout"), announcedNames)
            assertEquals(listOf(FIXED_NOW), recorded)
        }

    /**
     * The bug this exists to prevent. An announce that failed used to be written
     * down as though it had succeeded, which told the periodic schedule it had
     * nothing left to do.
     */
    @Test
    fun `a failed announce is not recorded as one`() =
        runTest {
            outcome = Result.failure(IllegalStateException("stack not ready"))

            val announced = announcer().announceOnNetworkChange()

            assertFalse(announced)
            assertEquals("it was attempted", listOf("Scout"), announcedNames)
            assertEquals("but must not be written down", emptyList<Long>(), recorded)
        }

    /**
     * A network change is exactly the moment a device has moved, which is when
     * an unprompted transmission is worth the most to whoever is looking for it.
     * Counted at the announce, not at the return value.
     */
    @Test
    fun `silence stops the announce before it is made`() =
        runTest {
            settings.setRadioSilence(true)

            val announced = announcer().announceOnNetworkChange()

            assertFalse(announced)
            assertEquals("nothing may reach the stack", emptyList<String>(), announcedNames)
            assertEquals(emptyList<Long>(), recorded)
        }

    /** With no identity there is nothing to announce under, and nothing to record. */
    @Test
    fun `no active identity means no announce`() =
        runTest {
            name = null

            val announced = announcer().announceOnNetworkChange()

            assertFalse(announced)
            assertEquals(emptyList<String>(), announcedNames)
            assertEquals(emptyList<Long>(), recorded)
        }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
