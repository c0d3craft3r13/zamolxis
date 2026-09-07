package network.zamolxis.app.service

import network.zamolxis.app.data.model.TcpCommunityServer
import network.zamolxis.app.rns.host.manager.CurrentTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub this guards against is the one that is *connected and useless*: it accepts the
 * socket, delivers no announces, and drops the client every ~82 seconds. Every
 * connection-based liveness check calls that healthy.
 */
class BootstrapRotationPolicyTest {
    private val candidates =
        listOf(
            TcpCommunityServer("Alpha", "alpha.example", 4242),
            TcpCommunityServer("Bravo", "bravo.example", 4242),
            TcpCommunityServer("Charlie", "charlie.example", 4242),
        )

    private fun hub(
        id: Long,
        name: String,
        host: String,
        heard: Boolean,
    ) = BootstrapRotationPolicy.HubState(
        id = id,
        name = name,
        endpoint = BootstrapRotationPolicy.endpointOf(host, PORT),
        heardAnnounce = heard,
    )

    private fun decide(
        hubs: List<BootstrapRotationPolicy.HubState>,
        present: Set<String> = emptySet(),
        transport: CurrentTransport = CurrentTransport.WIFI_LIKE,
        rotationsUsed: Int = 0,
    ) = BootstrapRotationPolicy.decide(hubs, candidates, present, transport, rotationsUsed)

    @Test
    fun `a connected hub that never delivered an announce is retired`() {
        val decision = decide(listOf(hub(1L, "Silent", "silent.example", heard = false)))

        assertEquals(listOf(1L), decision.retire)
        assertEquals(1, decision.add.size)
    }

    @Test
    fun `one hub delivering announces is enough to leave everything alone`() {
        val decision =
            decide(
                listOf(
                    hub(1L, "Silent", "silent.example", heard = false),
                    hub(2L, "Working", "working.example", heard = true),
                ),
            )

        assertTrue(decision.reason, decision.isNoOp)
    }

    @Test
    fun `silence with no network at all is not blamed on the hub`() {
        val decision =
            decide(
                listOf(hub(1L, "Silent", "silent.example", heard = false)),
                transport = CurrentTransport.NONE,
            )

        assertTrue(decision.reason, decision.isNoOp)
    }

    @Test
    fun `a VPN-only default network still counts as having a transport`() {
        val decision =
            decide(
                listOf(hub(1L, "Silent", "silent.example", heard = false)),
                transport = CurrentTransport.UNKNOWN,
            )

        assertEquals(listOf(1L), decision.retire)
    }

    @Test
    fun `rotation stops at the cap so a broken uplink cannot churn the stack`() {
        val decision =
            decide(
                listOf(hub(1L, "Silent", "silent.example", heard = false)),
                rotationsUsed = BootstrapRotationPolicy.MAX_ROTATIONS,
            )

        assertTrue(decision.reason, decision.isNoOp)
    }

    @Test
    fun `a replacement already present is not offered again`() {
        val alreadyHave = BootstrapRotationPolicy.endpointOf(candidates[0].host, candidates[0].port)

        val decision = decide(listOf(hub(1L, "Silent", "silent.example", heard = false)), present = setOf(alreadyHave))

        assertEquals(listOf(candidates[1].name), decision.add.map { it.name })
    }

    @Test
    fun `hubs are not retired when there is nothing left to try`() {
        val allPresent = candidates.map { BootstrapRotationPolicy.endpointOf(it.host, it.port) }.toSet()

        val decision = decide(listOf(hub(1L, "Silent", "silent.example", heard = false)), present = allPresent)

        assertTrue("a bad route beats no route", decision.isNoOp)
        assertTrue(decision.reason.contains("already been tried"))
    }

    @Test
    fun `as many replacements are brought in as hubs go out`() {
        val decision =
            decide(
                listOf(
                    hub(1L, "S1", "s1.example", heard = false),
                    hub(2L, "S2", "s2.example", heard = false),
                ),
            )

        assertEquals(2, decision.retire.size)
        assertEquals(decision.retire.size, decision.add.size)
    }

    @Test
    fun `an install with no bootstrap hubs is left alone`() {
        val decision = decide(emptyList())

        assertTrue(decision.reason, decision.isNoOp)
    }

    @Test
    fun `endpoints compare case-insensitively on the host`() {
        assertEquals(
            BootstrapRotationPolicy.endpointOf("Example.NET", PORT),
            BootstrapRotationPolicy.endpointOf("example.net", PORT),
        )
    }

    @Test
    fun `the reason names the hubs on both sides of the swap`() {
        val decision = decide(listOf(hub(1L, "DeadHost", "dead.example", heard = false)))

        assertTrue(decision.reason, decision.reason.contains("DeadHost"))
        assertTrue(decision.reason, decision.reason.contains(candidates[0].name))
    }

    private companion object {
        const val PORT = 4242
    }

    // --- what "heard" actually has to match ------------------------------------------

    @Test
    fun `an RNS label is matched by the endpoint it carries`() {
        val label = "TCPInterface[g00n.cloud Hub/dfw.us.g00n.cloud:6969]"

        assertTrue(BootstrapRotationPolicy.labelServes(label, "dfw.us.g00n.cloud:6969"))
    }

    @Test
    fun `the label is never equal to the configured name, which is why membership failed`() {
        val label = "TCPInterface[g00n.cloud Hub/dfw.us.g00n.cloud:6969]"

        assertNotEquals(
            "the broken version asked whether this label set contained the configured name",
            "g00n.cloud Hub",
            label,
        )
    }

    @Test
    fun `a port that is a prefix of another port does not match`() {
        val label = "TCPInterface[Alpha/alpha.example:4242]"

        assertTrue(BootstrapRotationPolicy.labelServes(label, "alpha.example:4242"))
        assertFalse(
            "a bare substring test would call this a match",
            BootstrapRotationPolicy.labelServes(label, "alpha.example:42"),
        )
    }

    @Test
    fun `matching an RNS label ignores case in the host`() {
        assertTrue(
            BootstrapRotationPolicy.labelServes(
                "TCPInterface[Jon's Node/RNS.JLAMOTHE.NET:4242]",
                "rns.jlamothe.net:4242",
            ),
        )
    }

    @Test
    fun `a different port on the same host is a different hub`() {
        val label = "TCPInterface[noDNS2/193.26.158.230:4965]"

        assertTrue(BootstrapRotationPolicy.labelServes(label, "193.26.158.230:4965"))
        assertFalse(BootstrapRotationPolicy.labelServes(label, "193.26.158.230:4242"))
    }

    @Test
    fun `labels from the other interface kinds never match a hub`() {
        listOf("AutoInterface[Local]", "BLEPeerInterface[BLE-56:31:F5]").forEach { label ->
            assertFalse(label, BootstrapRotationPolicy.labelServes(label, "dfw.us.g00n.cloud:6969"))
        }
    }

    // --- Tor is a choice, not a rescue ------------------------------------------------

    @Test
    fun `a Tor-only replacement is never offered automatically`() {
        val onion =
            TcpCommunityServer(
                "interloper node (Tor)",
                "intrcxv4fa72e5ovler5dpfwsiyuo34tkcwfy5snzstxkhec75okowqd.onion",
                4242,
            )
        val reachable = TcpCommunityServer("Quortal TCP Node", "reticulum.qortal.link", 4242)

        val decision =
            BootstrapRotationPolicy.decide(
                hubs = listOf(hub(1L, "Silent", "silent.example", heard = false)),
                knownServers = listOf(onion, reachable),
                presentEndpoints = setOf(BootstrapRotationPolicy.endpointOf("silent.example", PORT)),
                transport = CurrentTransport.WIFI_LIKE,
                rotationsUsed = 0,
            )

        assertEquals(listOf(reachable.name), decision.add.map { it.name })
    }

    @Test
    fun `an onion host is recognised whatever its case`() {
        assertTrue(BootstrapRotationPolicy.requiresTor("ABC.ONION"))
        assertFalse(BootstrapRotationPolicy.requiresTor("reticulum.qortal.link"))
        assertFalse(
            "a host that merely contains the word is not Tor",
            BootstrapRotationPolicy.requiresTor("onion.example.com"),
        )
    }
}
