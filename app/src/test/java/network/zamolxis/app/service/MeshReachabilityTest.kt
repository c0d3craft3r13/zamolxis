package network.zamolxis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line this feeds answers the first question a new user has — "why isn't my message
 * sending" — so it has to be right about silence as well as about traffic.
 */
class MeshReachabilityTest {
    private val start = 1_000L

    private fun heard(vararg pairs: Pair<MeshLinkKind, Long>) = MeshReachability(mapOf(*pairs), startedAtMs = start)

    @Test
    fun `the labels RNS actually reports are classified, not the configured names`() {
        // These are the shapes seen on announces.receivingInterface in the field. An
        // earlier version compared them against the interface names in the database
        // ("g00n.cloud Hub") and so matched nothing at all, leaving the status stuck on
        // "no connection" on a phone that was plainly online.
        assertEquals(MeshLinkKind.INTERNET, MeshLinkKind.of("TCPInterface[g00n.cloud Hub/dfw.us.g00n.cloud:6969]"))
        assertEquals(MeshLinkKind.LOCAL_NETWORK, MeshLinkKind.of("AutoInterface[Local]"))
        assertEquals(MeshLinkKind.BLUETOOTH, MeshLinkKind.of("BLEPeerInterface[BLE-56:31:F5]"))
        assertEquals(MeshLinkKind.RADIO, MeshLinkKind.of("RNodeInterface[My Radio]"))
    }

    @Test
    fun `the shared-instance loopback is not a way of reaching anyone`() {
        // It reaches another app on this same device, which says nothing about whether
        // the user can be contacted.
        assertNull(MeshLinkKind.of("Shared Instance (host)"))
    }

    @Test
    fun `an unrecognised interface is not guessed at`() {
        assertNull(MeshLinkKind.of("SomethingAddedLater"))
    }

    @Test
    fun `a recent announce means that link is carrying`() {
        val state = heard(MeshLinkKind.BLUETOOTH to start)

        val status = state.describe(nowMs = start + 60_000L)

        assertEquals(MeshStatus.Connected(setOf(MeshLinkKind.BLUETOOTH)), status)
    }

    @Test
    fun `a link that has gone quiet past the window stops counting`() {
        val state = heard(MeshLinkKind.INTERNET to start)

        val status = state.describe(nowMs = start + MeshReachability.FRESH_WINDOW_MS + 1)

        assertEquals(MeshStatus.Offline, status)
    }

    @Test
    fun `a fresh start reports searching, not no-connection`() {
        val nothingHeard = MeshReachability(startedAtMs = start)

        val status = nothingHeard.describe(nowMs = start + MeshReachability.SETTLING_MS - 1)

        assertEquals(MeshStatus.Searching, status)
    }

    @Test
    fun `silence after the settling period is called silence`() {
        val nothingHeard = MeshReachability(startedAtMs = start)

        val status = nothingHeard.describe(nowMs = start + MeshReachability.SETTLING_MS + 1)

        assertEquals(MeshStatus.Offline, status)
    }

    @Test
    fun `one live link is enough, even while another has gone quiet`() {
        val state =
            heard(
                MeshLinkKind.INTERNET to start,
                MeshLinkKind.BLUETOOTH to start + MeshReachability.FRESH_WINDOW_MS,
            )

        val status = state.describe(nowMs = start + MeshReachability.FRESH_WINDOW_MS + 1)

        assertEquals(MeshStatus.Connected(setOf(MeshLinkKind.BLUETOOTH)), status)
    }

    @Test
    fun `the order of links is stable so the sentence does not reshuffle`() {
        val now = start + 1
        val state =
            heard(
                MeshLinkKind.INTERNET to start,
                MeshLinkKind.BLUETOOTH to start,
                MeshLinkKind.LOCAL_NETWORK to start,
            )

        val kinds = state.kindsHeardWithin(now).toList()

        assertEquals(
            listOf(MeshLinkKind.BLUETOOTH, MeshLinkKind.LOCAL_NETWORK, MeshLinkKind.INTERNET),
            kinds,
        )
    }

    @Test
    fun `an install that has heard nothing at all is not connected`() {
        assertTrue(MeshReachability().kindsHeardWithin(nowMs = 0L).isEmpty())
    }
}
