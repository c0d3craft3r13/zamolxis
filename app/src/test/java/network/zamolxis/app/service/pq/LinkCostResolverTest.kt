package network.zamolxis.app.service.pq

import network.zamolxis.crypto.pq.LinkCost
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCostResolverTest {
    @Test
    fun `rnode is expensive`() {
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOf("RNode"))
    }

    @Test
    fun `ip and short-range interfaces are cheap`() {
        for (type in listOf("TCPClient", "TCPServer", "AutoInterface", "AndroidBLE", "I2P")) {
            assertEquals("$type should be cheap", LinkCost.CHEAP, LinkCostResolver.costOf(type))
        }
    }

    @Test
    fun `an unknown or absent interface is treated as cheap`() {
        // Being wrong in this direction spends airtime; being wrong the other way
        // would withhold protection from a link that could carry it.
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOf(null))
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOf("SomethingNew"))
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOf(""))
    }

    @Test
    fun `interface names are matched case-insensitively`() {
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOf("rnode"))
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOf("RNODE"))
    }

    @Test
    fun `a peer heard on several interfaces takes the cheapest`() {
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOfAny(listOf("RNode", "TCPClient")))
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOfAny(listOf("AndroidBLE", "RNode")))
    }

    @Test
    fun `a peer heard only over radio is expensive`() {
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOfAny(listOf("RNode")))
    }

    @Test
    fun `no recorded sightings is cheap`() {
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOfAny(emptyList()))
    }
}
