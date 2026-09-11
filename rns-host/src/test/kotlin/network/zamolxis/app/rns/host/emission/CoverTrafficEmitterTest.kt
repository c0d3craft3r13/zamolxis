package network.zamolxis.app.rns.host.emission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.rns.api.model.InterfaceConfig
import network.zamolxis.app.rns.api.util.CoverTraffic
import network.zamolxis.app.rns.api.util.LxmfFields
import network.zamolxis.app.rns.host.persistence.ServiceSettingsAccessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Who gets a message that says nothing, and who must never.
 *
 * Each test here is a way of putting bytes somewhere they should not go: into
 * the air on a radio, onto a quiet line, out of a device whose operator asked
 * for silence. They assert on what was actually sent rather than on a return
 * value, because a refusal that still transmitted would look identical to the
 * caller and the bytes would already be gone.
 */
@RunWith(RobolectricTestRunner::class)
class CoverTrafficEmitterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = ServiceSettingsAccessor(context)

    private val onLora = byteArrayOf(1)
    private val onNetwork = byteArrayOf(2)
    private val onBluetooth = byteArrayOf(3)
    private val nowhere = byteArrayOf(4)

    private val interfaces =
        listOf(
            InterfaceConfig.RNode(name = "radio", connectionMode = "usb"),
            InterfaceConfig.TCPClient(name = "line", targetHost = "h.invalid", targetPort = 4242),
            InterfaceConfig.AndroidBLE(name = "bluetooth"),
        )

    private val paths =
        mapOf(
            onLora.first() to "radio",
            onNetwork.first() to "line",
            onBluetooth.first() to "bluetooth",
        )

    private val sent = mutableListOf<Pair<Byte, Map<Int, Any>>>()

    private fun emitter(random: Random = Random(1)) =
        CoverTrafficEmitter(
            emissions = EmissionPolicy(context),
            resolver = InterfaceMediumResolver { interfaces },
            correspondents = { listOf(onLora, onNetwork, onBluetooth, nowhere) },
            nextHopInterfaceName = { paths[it.first()] },
            sendCover = { destination, fields ->
                sent += destination.first() to fields
                Result.success(Unit)
            },
            random = random,
        )

    @Before
    fun setUp() {
        settings.setRadioSilence(false)
        sent.clear()
    }

    private val busyEverywhere =
        AmbientCover(
            nearbyRadios = CoverThresholds.NEARBY_RADIOS,
            foreignBytes = CoverThresholds.FOREIGN_BYTES,
        )

    /**
     * The one that must never fail. No amount of company makes a radio safe to
     * pad: locating a transmitter works on the signal whatever else is in the
     * band, and the airtime spent is taken from a real message later.
     */
    @Test
    fun `nothing is ever sent over a radio`() =
        runTest {
            emitter().emitOnce(busyEverywhere)

            assertTrue("cover must never reach a LoRa peer", sent.none { it.first == onLora.first() })
        }

    @Test
    fun `a busy line and a crowded room both get cover`() =
        runTest {
            emitter().emitOnce(busyEverywhere)

            val destinations = sent.map { it.first }.toSet()
            assertEquals(setOf(onNetwork.first(), onBluetooth.first()), destinations)
        }

    @Test
    fun `a quiet line gets nothing`() =
        runTest {
            emitter().emitOnce(AmbientCover(nearbyRadios = 0, foreignBytes = 0))

            assertEquals(emptyList<Pair<Byte, Map<Int, Any>>>(), sent)
        }

    /** A window nobody could measure is not a window with company in it. */
    @Test
    fun `an unmeasurable window gets nothing`() =
        runTest {
            emitter().emitOnce(AmbientCover.UNKNOWN)

            assertEquals(emptyList<Pair<Byte, Map<Int, Any>>>(), sent)
        }

    @Test
    fun `a peer with no known path gets nothing`() =
        runTest {
            emitter().emitOnce(busyEverywhere)

            assertTrue("an unknown path is not an invitation", sent.none { it.first == nowhere.first() })
        }

    /**
     * Cover is a transmission nobody asked for, which is the whole category
     * silence removes. Counted at the send, not at the return value: a refusal
     * that transmitted first would look the same to the caller.
     */
    @Test
    fun `silence stops cover entirely`() =
        runTest {
            settings.setRadioSilence(true)

            emitter().emitOnce(busyEverywhere)

            assertEquals("silence must reach the stack, not just the result", emptyList<Any>(), sent)
        }

    @Test
    fun `what is sent is recognisable cover and nothing else`() =
        runTest {
            emitter().emitOnce(busyEverywhere)

            assertTrue(sent.isNotEmpty())
            sent.forEach { (_, fields) ->
                assertEquals(setOf(LxmfFields.FIELD_CUSTOM_META), fields.keys)
            }
        }

    /** Two cover messages must not carry the same padding, or they pair up. */
    @Test
    fun `every cover message is padded differently`() =
        runTest {
            emitter().emitOnce(busyEverywhere)

            val paddings = sent.map { paddingOf(it.second).toList() }
            assertEquals("padding must not repeat between messages", paddings.size, paddings.toSet().size)
        }

    /**
     * A fixed period is a signature on its own: transmissions arriving every
     * ninety seconds identify the software without anything being decrypted.
     */
    @Test
    fun `the interval is never the same twice running`() {
        val emitter = emitter(Random(7))
        val intervals = (1..50).map { emitter.nextIntervalMs() }

        assertTrue("a timer would produce one value", intervals.toSet().size > 10)
    }

    /**
     * The distribution reaches arbitrarily small and large values. Neither a
     * burst nor an hour of nothing should follow from one unlucky draw.
     */
    @Test
    fun `however the draw falls the interval stays within bounds`() {
        val emitter = emitter(Random(13))

        (1..2000).forEach { _ ->
            val interval = emitter.nextIntervalMs()
            assertTrue(
                "interval $interval outside bounds",
                interval in CoverTrafficEmitter.MIN_INTERVAL_MS..CoverTrafficEmitter.MAX_INTERVAL_MS,
            )
        }
    }

    /** Bounded, but still centred where it was meant to be. */
    @Test
    fun `the average gap is about what was asked for`() {
        val emitter = emitter(Random(29))
        val mean = (1..5000).map { emitter.nextIntervalMs() }.average()

        assertTrue(
            "mean $mean is far from ${CoverTrafficEmitter.MEAN_INTERVAL_MS}",
            mean > CoverTrafficEmitter.MEAN_INTERVAL_MS * 0.7 &&
                mean < CoverTrafficEmitter.MEAN_INTERVAL_MS * 1.3,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun paddingOf(fields: Map<Int, Any>): ByteArray =
        (fields[LxmfFields.FIELD_CUSTOM_META] as Map<String, ByteArray>)
            .getValue(CoverTraffic.CUSTOM_META_KEY_PADDING)
}
