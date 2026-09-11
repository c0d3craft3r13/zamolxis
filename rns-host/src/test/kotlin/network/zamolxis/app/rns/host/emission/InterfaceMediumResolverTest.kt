package network.zamolxis.app.rns.host.emission

import network.zamolxis.app.rns.api.model.EmissionMedium
import network.zamolxis.app.rns.api.model.InterfaceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a radio from a wire when all you are handed is a name someone chose.
 *
 * Every failure here has the same shape: something gets padded that should have
 * stayed quiet. So the tests are written around the ways the name can lie.
 */
class InterfaceMediumResolverTest {
    private val lora = InterfaceConfig.RNode(name = "wifi", connectionMode = "usb")
    private val tcp = InterfaceConfig.TCPClient(name = "Roof antenna", targetHost = "h.invalid", targetPort = 4242)
    private val ble = InterfaceConfig.AndroidBLE(name = "Bluetooth LE")

    private val resolver = InterfaceMediumResolver { listOf(lora, tcp, ble) }

    /**
     * The whole point. An operator may call a LoRa radio "wifi", and the stack
     * hands that name back verbatim. Reading the name instead of the
     * configuration would put padding into the air.
     */
    @Test
    fun `a radio named after a wire is still a radio`() {
        assertEquals(EmissionMedium.LORA_CARRIED, resolver.mediumOf("wifi"))
        assertFalse(
            "no measurement may make a radio paddable",
            resolver.mayPad("wifi", AmbientCover(nearbyRadios = 900, foreignBytes = Long.MAX_VALUE)),
        )
    }

    /** And the reverse: a network interface named like an antenna is still a network. */
    @Test
    fun `a wire named after an antenna is still a wire`() {
        assertEquals(EmissionMedium.NETWORK, resolver.mediumOf("Roof antenna"))
    }

    @Test
    fun `a busy line may be padded, a quiet one may not`() {
        val busy = AmbientCover(nearbyRadios = null, foreignBytes = CoverThresholds.FOREIGN_BYTES)
        val quiet = AmbientCover(nearbyRadios = null, foreignBytes = 0)

        assertTrue(resolver.mayPad("Roof antenna", busy))
        assertFalse(resolver.mayPad("Roof antenna", quiet))
    }

    /**
     * A destination with no known path reports no interface. That is ordinary,
     * and it is not an invitation to pick one.
     */
    @Test
    fun `no path means no padding`() {
        assertNull(resolver.mediumOf(null))
        assertNull(resolver.mediumOf(""))
        assertNull(resolver.mediumOf("   "))
        assertFalse(resolver.mayPad(null, AmbientCover(nearbyRadios = 900, foreignBytes = Long.MAX_VALUE)))
    }

    /** A name belonging to nothing configured is not a licence to guess. */
    @Test
    fun `a name matching nothing is left unknown`() {
        assertNull(resolver.mediumOf("something that was never configured"))
        assertFalse(
            resolver.mayPad("something that was never configured", AmbientCover(null, Long.MAX_VALUE)),
        )
    }

    /**
     * Interfaces the stack creates for itself have no configured entry, so its
     * own rendering is read as a fallback. Both ways of reaching an RNode print
     * the same class; both are LoRa and neither is ever padded, so nothing is
     * lost by not telling them apart here.
     */
    @Test
    fun `the stack's own rendering is understood when nothing was configured`() {
        val empty = InterfaceMediumResolver { emptyList() }

        assertEquals(EmissionMedium.LORA_CARRIED, empty.mediumOf("RNodeInterface[/dev/ttyUSB0]"))
        assertEquals(EmissionMedium.NETWORK, empty.mediumOf("TCPInterface[Server/1.2.3.4:4242]"))
        assertEquals(EmissionMedium.NETWORK, empty.mediumOf("AutoInterface[wlan0/fe80::1]"))
        assertEquals(EmissionMedium.NETWORK, empty.mediumOf("I2PInterface[abc]"))
        assertEquals(EmissionMedium.BLUETOOTH_LE, empty.mediumOf("BLEInterface[phone]"))
    }

    /** Configuration wins over the stack's rendering when both could match. */
    @Test
    fun `what the operator configured decides, not what the name looks like`() {
        val namedLikeATcpInterface =
            InterfaceConfig.RNode(name = "TCPInterface[Server/1.2.3.4:4242]", connectionMode = "usb")
        val misleading = InterfaceMediumResolver { listOf(namedLikeATcpInterface) }

        assertEquals(
            EmissionMedium.LORA_CARRIED,
            misleading.mediumOf("TCPInterface[Server/1.2.3.4:4242]"),
        )
    }
}
