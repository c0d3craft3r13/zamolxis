package network.zamolxis.app.service.pq

import network.zamolxis.app.data.model.InterfaceType
import network.zamolxis.crypto.pq.LinkCost
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCostResolverTest {
    @Test
    fun `rnode is expensive`() {
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOf("RNode"))
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOf(InterfaceType.RNODE))
    }

    @Test
    fun `ip and short-range interfaces are cheap`() {
        for (type in listOf("TCPClient", "TCPServer", "AutoInterface", "AndroidBLE", "I2P")) {
            assertEquals("$type should be cheap", LinkCost.CHEAP, LinkCostResolver.costOf(type))
        }
    }

    @Test
    fun `every interface type except rnode is cheap`() {
        // Iterates the enum rather than listing values, so a variant added to
        // InterfaceType shows up here as well as at the compiler's exhaustiveness
        // check in costOf.
        for (type in InterfaceType.entries.filter { it != InterfaceType.RNODE }) {
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
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOf(InterfaceType.UNKNOWN))
    }

    @Test
    fun `interface names are matched case-insensitively`() {
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOf("rnode"))
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOf("RNODE"))
    }

    @Test
    fun `a peer heard on several interfaces takes the cheapest`() {
        assertEquals(
            LinkCost.CHEAP,
            LinkCostResolver.costOfTypes(listOf(InterfaceType.RNODE, InterfaceType.TCP_CLIENT)),
        )
        assertEquals(
            LinkCost.CHEAP,
            LinkCostResolver.costOfTypes(listOf(InterfaceType.BLE, InterfaceType.RNODE)),
        )
    }

    @Test
    fun `a peer heard only over radio is expensive`() {
        assertEquals(LinkCost.EXPENSIVE, LinkCostResolver.costOfTypes(listOf(InterfaceType.RNODE)))
    }

    @Test
    fun `no recorded sightings is cheap`() {
        assertEquals(LinkCost.CHEAP, LinkCostResolver.costOfTypes(emptyList()))
    }
}
