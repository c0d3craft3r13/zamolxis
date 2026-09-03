package network.zamolxis.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule under test: a name this app invented is not an answer.
 *
 * The bug it pins was seen on two phones. A call placed to
 * `631b64c15025938e3f7ea3ad61693102` put "Peer ECC3C632" at the top of the caller's
 * screen — the peer's `lxst.telephony` destination, a string the caller had never
 * seen — and the callee saw "Peer E6FE406D" for a caller it could have named.
 */
class CallPeerLabelTest {
    private val dialled = "631b64c15025938e3f7ea3ad61693102"

    @Test
    fun `prefers the first real name`() {
        assertEquals("Мот60", CallPeerLabel.of(listOf(null, "", "Мот60", "Greg"), dialled))
    }

    @Test
    fun `skips a name the app generated for the telephony destination`() {
        assertEquals("Мот60", CallPeerLabel.of(listOf("Peer ECC3C632", "Мот60"), dialled))
    }

    @Test
    fun `falls back to the dialled address rather than a generated name`() {
        assertEquals("631b64...693102", CallPeerLabel.of(listOf("Peer ECC3C632"), dialled))
    }

    @Test
    fun `falls back to the dialled address when nothing is known`() {
        assertEquals("631b64...693102", CallPeerLabel.of(emptyList(), dialled))
    }

    @Test
    fun `Unknown Peer is a placeholder too`() {
        assertEquals("631b64...693102", CallPeerLabel.of(listOf("Unknown Peer"), dialled))
    }

    @Test
    fun `a chosen name that merely looks like a hash label is kept if it is not one`() {
        // Lowercase hex and a wrong length are not what the generator emits.
        assertEquals("Peer ecc3c632", CallPeerLabel.of(listOf("Peer ecc3c632"), dialled))
        assertEquals("Peer ECC3C6", CallPeerLabel.of(listOf("Peer ECC3C6"), dialled))
    }

    @Test
    fun `a short hash is shown whole`() {
        assertEquals("631b64c1", CallPeerLabel.shortenHash("631b64c1"))
    }
}
