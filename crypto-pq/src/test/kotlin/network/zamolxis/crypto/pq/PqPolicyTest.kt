package network.zamolxis.crypto.pq

import org.junit.Assert.assertEquals
import org.junit.Test

class PqPolicyTest {
    private fun decide(
        mode: PqMode,
        peer: PeerPqSupport,
        link: LinkCost = LinkCost.CHEAP,
    ) = PqPolicy.decide(mode, peer, link)

    // ------------------------------------------------------------------- off

    @Test
    fun `off never seals, whatever the peer supports`() {
        for (peer in PeerPqSupport.entries) {
            for (link in LinkCost.entries) {
                assertEquals(
                    "mode=OFF peer=$peer link=$link",
                    PqDecision.SendPlain(PlainReason.DISABLED_BY_USER),
                    decide(PqMode.OFF, peer, link),
                )
            }
        }
    }

    // --------------------------------------------------------- opportunistic

    @Test
    fun `opportunistic seals when the peer's key is known and the link is cheap`() {
        assertEquals(PqDecision.Seal, decide(PqMode.OPPORTUNISTIC, PeerPqSupport.KEY_KNOWN))
    }

    @Test
    fun `opportunistic falls back to plain for a plain reticulum peer`() {
        assertEquals(
            PqDecision.SendPlain(PlainReason.PEER_UNSUPPORTED),
            decide(PqMode.OPPORTUNISTIC, PeerPqSupport.UNSUPPORTED),
        )
    }

    @Test
    fun `opportunistic falls back to plain when the key has not arrived yet`() {
        assertEquals(
            PqDecision.SendPlain(PlainReason.PEER_KEY_NOT_YET_KNOWN),
            decide(PqMode.OPPORTUNISTIC, PeerPqSupport.ADVERTISED_KEY_MISSING),
        )
    }

    @Test
    fun `opportunistic spares an expensive link the overhead`() {
        assertEquals(
            PqDecision.SendPlain(PlainReason.LINK_TOO_EXPENSIVE),
            decide(PqMode.OPPORTUNISTIC, PeerPqSupport.KEY_KNOWN, LinkCost.EXPENSIVE),
        )
    }

    // -------------------------------------------------------------- required

    @Test
    fun `required seals even over an expensive link`() {
        assertEquals(
            PqDecision.Seal,
            decide(PqMode.REQUIRED, PeerPqSupport.KEY_KNOWN, LinkCost.EXPENSIVE),
        )
    }

    @Test
    fun `required refuses rather than downgrading to a plain peer`() {
        assertEquals(
            PqDecision.Refuse(PlainReason.PEER_UNSUPPORTED),
            decide(PqMode.REQUIRED, PeerPqSupport.UNSUPPORTED),
        )
    }

    @Test
    fun `required refuses while the peer's key is still missing`() {
        assertEquals(
            PqDecision.Refuse(PlainReason.PEER_KEY_NOT_YET_KNOWN),
            decide(PqMode.REQUIRED, PeerPqSupport.ADVERTISED_KEY_MISSING),
        )
    }

    // ------------------------------------------------------------ invariants

    @Test
    fun `required never silently sends plaintext`() {
        for (peer in PeerPqSupport.entries) {
            for (link in LinkCost.entries) {
                val decision = decide(PqMode.REQUIRED, peer, link)
                assertEquals(
                    "REQUIRED must seal or refuse, never downgrade (peer=$peer link=$link)",
                    true,
                    decision is PqDecision.Seal || decision is PqDecision.Refuse,
                )
            }
        }
    }

    @Test
    fun `only required can refuse to send`() {
        for (mode in listOf(PqMode.OFF, PqMode.OPPORTUNISTIC)) {
            for (peer in PeerPqSupport.entries) {
                for (link in LinkCost.entries) {
                    assertEquals(
                        "mode=$mode must never strand a message (peer=$peer link=$link)",
                        false,
                        decide(mode, peer, link) is PqDecision.Refuse,
                    )
                }
            }
        }
    }

    @Test
    fun `sealing only ever happens when the peer's key is known`() {
        for (mode in PqMode.entries) {
            for (peer in PeerPqSupport.entries) {
                for (link in LinkCost.entries) {
                    if (decide(mode, peer, link) is PqDecision.Seal) {
                        assertEquals(
                            "sealed to a peer whose key we lack (mode=$mode peer=$peer)",
                            PeerPqSupport.KEY_KNOWN,
                            peer,
                        )
                    }
                }
            }
        }
    }
}
