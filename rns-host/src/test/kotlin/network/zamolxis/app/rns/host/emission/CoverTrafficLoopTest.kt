package network.zamolxis.app.rns.host.emission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.rns.api.model.InterfaceConfig
import network.zamolxis.app.rns.host.persistence.ServiceSettingsAccessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Measuring and deciding, in that order and no other.
 *
 * A decision taken from an earlier reading is how a device keeps padding in a
 * room everyone has left, so the ordering is asserted rather than assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CoverTrafficLoopTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = ServiceSettingsAccessor(context)

    private val peer = byteArrayOf(9)
    private val events = mutableListOf<String>()

    private var radiosHeard: Int? = 0
    private var foreignBytes: Long? = 0

    private val tracker =
        CoverTracker(
            radios =
                object : RadioCounter {
                    override suspend fun countNearbyRadios(windowMs: Long): Int? {
                        events += "measured"
                        return radiosHeard
                    }
                },
            traffic = { foreignBytes },
        )

    private fun loop() =
        CoverTrafficLoop(
            emissions = EmissionPolicy(context),
            tracker = tracker,
            emitter =
                CoverTrafficEmitter(
                    emissions = EmissionPolicy(context),
                    resolver =
                        InterfaceMediumResolver {
                            listOf(InterfaceConfig.TCPClient(name = "line", targetHost = "h.invalid", targetPort = 1))
                        },
                    correspondents = { listOf(peer) },
                    nextHopInterfaceName = { "line" },
                    sendCover = { _, _ ->
                        events += "sent"
                        Result.success(Unit)
                    },
                    random = Random(3),
                ),
        )

    @Before
    fun setUp() {
        settings.setRadioSilence(false)
        events.clear()
    }

    /**
     * The window has to be listened to before anything is decided from it. A
     * loop that emitted first would be acting on the previous window, which on
     * a device that has just been carried somewhere else is the wrong room.
     */
    @Test
    fun `it listens before it decides`() =
        runTest {
            foreignBytes = CoverThresholds.FOREIGN_BYTES

            loop().runCycle()

            assertEquals(listOf("measured", "sent"), events)
        }

    @Test
    fun `a quiet window sends nothing`() =
        runTest {
            foreignBytes = 0

            loop().runCycle()

            assertEquals("measuring happened, sending did not", listOf("measured"), events)
        }

    /**
     * Measuring transmits nothing, so it would be safe under silence — but it
     * spends thirty seconds of scanning to answer a question that cannot be
     * acted on, and an operator who asked for quiet is rarely in a position to
     * spend battery on that.
     */
    @Test
    fun `silence stops it before it even listens`() =
        runTest {
            settings.setRadioSilence(true)
            foreignBytes = CoverThresholds.FOREIGN_BYTES

            loop().runCycle()

            assertEquals("nothing at all should happen under silence", emptyList<String>(), events)
        }

    /** Lifting silence starts it again without anything being rebuilt. */
    @Test
    fun `it resumes when silence is lifted`() =
        runTest {
            settings.setRadioSilence(true)
            val running = loop()
            running.runCycle()
            assertTrue(events.isEmpty())

            settings.setRadioSilence(false)
            foreignBytes = CoverThresholds.FOREIGN_BYTES
            running.runCycle()

            assertEquals(listOf("measured", "sent"), events)
        }
}
