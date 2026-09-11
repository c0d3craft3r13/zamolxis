package network.zamolxis.app.rns.host.emission

import network.zamolxis.app.rns.api.model.EmissionMedium
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When padding hides something and when it is the only thing there is to see.
 *
 * The rule is the same everywhere — pad only where there is company — but what
 * counts as company differs by who is watching, and the mistake that matters is
 * always the same one: reporting cover that is not there and starting to
 * transmit in an empty room.
 */
class AmbientCoverTest {
    @Test
    fun `nothing measured yet is treated as no company`() {
        EmissionMedium.entries.forEach { medium ->
            assertFalse(
                "$medium must not pad before anything is measured",
                medium.hasCover(AmbientCover.UNKNOWN),
            )
        }
    }

    /**
     * The distinction the meters go to some trouble to preserve. A scan the
     * system suspended and a genuinely empty room both yield no radios; only the
     * first is unmeasurable. Neither may start padding, but conflating them
     * would have thrown away the only signal that says which one happened.
     */
    @Test
    fun `an unmeasurable window is no company, not an empty one`() {
        val couldNotListen = AmbientCover(nearbyRadios = null, foreignBytes = null)
        val listenedAndHeardNobody = AmbientCover(nearbyRadios = 0, foreignBytes = 0)

        assertFalse(EmissionMedium.BLUETOOTH_LE.hasCover(couldNotListen))
        assertFalse(EmissionMedium.BLUETOOTH_LE.hasCover(listenedAndHeardNobody))
        assertNotEquals(couldNotListen, listenedAndHeardNobody)
    }

    /** One medium failing to measure must not silence the other. */
    @Test
    fun `a phone that cannot scan can still see its own line is busy`() {
        val blindButBusy = AmbientCover(nearbyRadios = null, foreignBytes = CoverThresholds.FOREIGN_BYTES)

        assertFalse(EmissionMedium.BLUETOOTH_LE.hasCover(blindButBusy))
        assertTrue(EmissionMedium.NETWORK.hasCover(blindButBusy))
    }

    @Test
    fun `bluetooth pads once enough other radios are audible`() {
        val crowd = AmbientCover(nearbyRadios = CoverThresholds.NEARBY_RADIOS, foreignBytes = 0)

        assertTrue(EmissionMedium.BLUETOOTH_LE.hasCover(crowd))
    }

    @Test
    fun `one radio short of the threshold is still an empty room`() {
        val nearly = AmbientCover(nearbyRadios = CoverThresholds.NEARBY_RADIOS - 1, foreignBytes = 0)

        assertFalse(EmissionMedium.BLUETOOTH_LE.hasCover(nearly))
    }

    /**
     * A network observer watches this subscriber, not the internet, so the only
     * traffic that can hide ours is traffic from this same device. Radios in the
     * room are no help at all.
     */
    @Test
    fun `radios in the room are not cover on a network`() {
        val crowdedButSilentLine = AmbientCover(nearbyRadios = 500, foreignBytes = 0)

        assertFalse(EmissionMedium.NETWORK.hasCover(crowdedButSilentLine))
    }

    @Test
    fun `the network pads once the device itself has been talking`() {
        val busyLine = AmbientCover(nearbyRadios = 0, foreignBytes = CoverThresholds.FOREIGN_BYTES)

        assertTrue(EmissionMedium.NETWORK.hasCover(busyLine))
    }

    /** An idle phone keeping its connections open must not read as company. */
    @Test
    fun `a trickle from a sleeping phone is not cover`() {
        val idle = AmbientCover(nearbyRadios = 0, foreignBytes = 200)

        assertFalse(EmissionMedium.NETWORK.hasCover(idle))
    }

    /**
     * Company cannot make a radio safe: locating a transmitter works on the
     * signal itself however busy the band is, and padding spends airtime a real
     * message will want. No measurement changes this answer.
     */
    @Test
    fun `LoRa never pads however busy it is around`() {
        val everythingAtOnce = AmbientCover(nearbyRadios = Int.MAX_VALUE, foreignBytes = Long.MAX_VALUE)

        assertFalse(EmissionMedium.LORA_CARRIED.hasCover(everythingAtOnce))
        assertFalse(EmissionMedium.LORA_REMOTE.hasCover(everythingAtOnce))
    }

    /**
     * The window is short so that a phone rotating its advertising address is
     * not counted several times over. Lengthening it past a rotation period
     * reintroduces exactly the miscount the rule exists to avoid.
     */
    @Test
    fun `the observation window stays well inside an address rotation`() {
        val bluetoothAddressRotationMs = 15 * 60 * 1000L

        assertTrue(
            "a window this long would count one phone as a crowd",
            CoverThresholds.WINDOW_MS < bluetoothAddressRotationMs / 2,
        )
    }
}
