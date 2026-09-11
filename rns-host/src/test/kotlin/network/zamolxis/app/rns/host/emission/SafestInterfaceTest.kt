package network.zamolxis.app.rns.host.emission

import network.zamolxis.app.rns.api.model.EmissionMedium
import network.zamolxis.app.rns.api.model.InterfaceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sending the key that identifies this device down the least revealing path.
 *
 * An announce publishes the identity's public key in the clear to everyone in
 * earshot. The content does not change with the interface; the audience does,
 * and picking the wrong one hands that key to anyone with a receiver near the
 * operator instead of to a network peer.
 */
class SafestInterfaceTest {
    private val carriedRadio = InterfaceConfig.RNode(name = "belt radio", connectionMode = "usb")
    private val remoteRadio = InterfaceConfig.RNode(name = "roof radio", connectionMode = "tcp")
    private val bluetooth = InterfaceConfig.AndroidBLE(name = "phone to phone")
    private val network = InterfaceConfig.TCPClient(name = "home link", targetHost = "h.invalid", targetPort = 4242)

    /** The order is the policy, so it is pinned rather than inferred from a sample. */
    @Test
    fun `the ranking runs from observed to triangulated`() {
        assertEquals(
            listOf(
                EmissionMedium.NETWORK,
                EmissionMedium.LORA_REMOTE,
                EmissionMedium.BLUETOOTH_LE,
                EmissionMedium.LORA_CARRIED,
            ),
            SafestInterface.riskOrder(),
        )
    }

    @Test
    fun `a wire is preferred over every radio`() {
        val chosen = SafestInterface.among(listOf(carriedRadio, bluetooth, remoteRadio, network))

        assertEquals("home link", chosen)
    }

    /**
     * The distinction the medium classification exists for: both are LoRa, but a
     * fix on the remote one lands where the box is, not where the operator is.
     */
    @Test
    fun `a radio the operator is not standing next to beats one in their pocket`() {
        val chosen = SafestInterface.among(listOf(carriedRadio, remoteRadio))

        assertEquals("roof radio", chosen)
    }

    @Test
    fun `bluetooth is preferred over a carried radio`() {
        val chosen = SafestInterface.among(listOf(carriedRadio, bluetooth))

        assertEquals("phone to phone", chosen)
    }

    /**
     * A node that refuses to announce on the only interface it has is a node
     * nobody can reach. Choosing the safest available is the intent; declining
     * to speak belongs to radio silence, which the operator turns on knowingly.
     */
    @Test
    fun `the only interface is used even when it is the worst one`() {
        val chosen = SafestInterface.among(listOf(carriedRadio))

        assertEquals("belt radio", chosen)
    }

    @Test
    fun `a disabled interface is not a candidate`() {
        val chosen = SafestInterface.among(listOf(carriedRadio, network.copy(enabled = false)))

        assertEquals("belt radio", chosen)
    }

    /** Naming no interface means "announce the way the stack would anyway". */
    @Test
    fun `nothing to choose between names no interface`() {
        assertNull(SafestInterface.among(emptyList()))
        assertNull(SafestInterface.among(listOf(network.copy(enabled = false))))
    }
}
