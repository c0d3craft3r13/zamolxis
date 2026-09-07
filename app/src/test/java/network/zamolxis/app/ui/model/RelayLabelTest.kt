package network.zamolxis.app.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay card is the one contact that usually has no name, so the DAO's fallback
 * prints its destination hash where the name belongs — and again on the line below.
 */
class RelayLabelTest {
    private val hash = "52f09ed2b7cdfe1a4c9b8e07d3a16f52"

    @Test
    fun `a display name that is only the hash is nameless`() {
        assertTrue(RelayLabel.isNameless(displayName = hash, destinationHash = hash))
    }

    @Test
    fun `case does not decide it`() {
        assertTrue(
            "the hash reaches the UI from announces, lxma links and QR scans, which disagree on case",
            RelayLabel.isNameless(displayName = hash.uppercase(), destinationHash = hash),
        )
    }

    @Test
    fun `an announced name is a name`() {
        assertFalse(RelayLabel.isNameless(displayName = "g00n.cloud Hub", destinationHash = hash))
    }

    @Test
    fun `a nickname that merely contains the hash is still a name`() {
        assertFalse(
            "someone who typed the hash into the nickname field chose it; leave it alone",
            RelayLabel.isNameless(displayName = "relay $hash", destinationHash = hash),
        )
    }

    @Test
    fun `Mayak stands a name in for a bare hash`() {
        assertTrue(RelayLabel.substitutesName(hash, hash, simpleUi = true))
    }

    @Test
    fun `the expert build keeps the hash it navigates by`() {
        assertFalse(RelayLabel.substitutesName(hash, hash, simpleUi = false))
    }

    @Test
    fun `a named relay is left alone in both products`() {
        assertFalse(RelayLabel.substitutesName("g00n.cloud Hub", hash, simpleUi = true))
        assertFalse(RelayLabel.substitutesName("g00n.cloud Hub", hash, simpleUi = false))
    }
}
