package network.zamolxis.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameUtilsTest {
    @Test
    fun `generates a name from the first eight hex characters, uppercased`() {
        assertEquals("Peer 970A60FC", generatedDisplayNameFor("970a60fcbb5d3a2e"))
    }

    @Test
    fun `a hash too short to name yields the unknown placeholder`() {
        assertEquals(UNKNOWN_PEER_NAME, generatedDisplayNameFor("970a60"))
    }

    @Test
    fun `recognises what it generates`() {
        assertTrue(isGeneratedDisplayName(generatedDisplayNameFor("970a60fcbb5d3a2e")))
        assertTrue(isGeneratedDisplayName(generatedDisplayNameFor("970a60")))
    }

    @Test
    fun `a name a peer announced is not a generated one`() {
        assertFalse(isGeneratedDisplayName("Мот60"))
        assertFalse(isGeneratedDisplayName("grog"))
        assertFalse(isGeneratedDisplayName(null))
        assertFalse(isGeneratedDisplayName(""))
        assertFalse(isGeneratedDisplayName("Peer Review"))
    }
}
