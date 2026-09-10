package network.zamolxis.app.rns.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which interfaces put a signal in the air, and which only put bytes on a wire.
 *
 * Everything downstream — whether to go quiet, whether padding is cover or just
 * more noise from the only device making any — reads this answer. Getting it
 * wrong in the safe direction costs a feature; getting it wrong in the other
 * direction transmits from a position someone asked us not to give away.
 */
class EmissionMediumTest {
    @Test
    fun `an RNode on the end of a cable or a pairing is a radio in the operator's bag`() {
        assertEquals(
            EmissionMedium.LORA_CARRIED,
            InterfaceConfig.RNode(connectionMode = "usb").emissionMedium,
        )
        assertEquals(
            EmissionMedium.LORA_CARRIED,
            InterfaceConfig.RNode(connectionMode = "classic").emissionMedium,
        )
        assertEquals(
            EmissionMedium.LORA_CARRIED,
            InterfaceConfig.RNode(connectionMode = "ble").emissionMedium,
        )
    }

    /**
     * The distinction the whole classification exists for. An RNode reached over
     * the network is the same radio spending the same airtime, but a fix on it
     * lands where the box is — which is why someone put it there.
     */
    @Test
    fun `an RNode reached over the network is a radio somewhere else`() {
        assertEquals(
            EmissionMedium.LORA_REMOTE,
            InterfaceConfig.RNode(connectionMode = "tcp").emissionMedium,
        )
    }

    @Test
    fun `bluetooth is its own case, close range but still radiating`() {
        val medium = InterfaceConfig.AndroidBLE().emissionMedium

        assertEquals(EmissionMedium.BLUETOOTH_LE, medium)
        assertTrue("BLE is heard, not merely observed", medium.radiates)
        assertTrue("whoever hears it is already within metres", medium.revealsOperatorPosition)
    }

    @Test
    fun `everything carried over IP is one case`() {
        assertEquals(EmissionMedium.NETWORK, InterfaceConfig.AutoInterface().emissionMedium)
        assertEquals(
            EmissionMedium.NETWORK,
            InterfaceConfig.TCPClient(targetHost = "example.invalid", targetPort = 4242).emissionMedium,
        )
        assertEquals(EmissionMedium.NETWORK, InterfaceConfig.TCPServer().emissionMedium)
        assertEquals(EmissionMedium.NETWORK, InterfaceConfig.UDP().emissionMedium)
    }

    /**
     * Stated as a rule rather than per-value, so that a medium added later is
     * caught here instead of quietly inheriting whichever answer was convenient.
     */
    @Test
    fun `exactly one medium does not radiate`() {
        val quiet = EmissionMedium.entries.filter { !it.radiates }

        assertEquals(listOf(EmissionMedium.NETWORK), quiet)
    }

    @Test
    fun `nothing carried over the network gives away where anyone is standing`() {
        assertFalse(EmissionMedium.NETWORK.revealsOperatorPosition)
        assertFalse(EmissionMedium.NETWORK.hasAirtimeBudget)
    }

    /**
     * A remote RNode is the one medium that radiates without exposing the
     * operator. If that stops being true the split has collapsed and the two
     * LoRa cases may as well be one.
     */
    @Test
    fun `a remote radio spends airtime without exposing its operator`() {
        assertTrue(EmissionMedium.LORA_REMOTE.radiates)
        assertTrue(EmissionMedium.LORA_REMOTE.hasAirtimeBudget)
        assertFalse(EmissionMedium.LORA_REMOTE.revealsOperatorPosition)
    }

    /** Only LoRa answers to firmware that holds transmissions once the budget is spent. */
    @Test
    fun `airtime is budgeted on LoRa and nowhere else`() {
        val budgeted = EmissionMedium.entries.filter { it.hasAirtimeBudget }.toSet()

        assertEquals(setOf(EmissionMedium.LORA_CARRIED, EmissionMedium.LORA_REMOTE), budgeted)
    }
}
