package network.zamolxis.app.service

import network.zamolxis.app.test.TestFactories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Choosing a relay in a way that says nothing about the device choosing it.
 *
 * The property under test is negative and easy to lose by accident: the choice
 * must not be readable. A selection that quietly favours the nearest node — or
 * that lands on the same node every time — hands an observer the two things
 * this exists to withhold, and it would still look like it was working.
 */
class RelayRotationTest {
    private val now = 1_700_000_000_000L

    private fun node(
        hash: String,
        hops: Int = 1,
        seenAgoMs: Long = 0,
    ) = TestFactories.createAnnounce(
        destinationHash = hash,
        peerName = "node-$hash",
        hops = hops,
        lastSeenTimestamp = now - seenAgoMs,
    )

    /**
     * The whole point. Hop count is stable, so a device that always takes the
     * nearest node picks the same one from the same place — and the choice
     * itself says roughly where that place is.
     */
    @Test
    fun `the nearest node has no special claim`() {
        val near = node("aa", hops = 1)
        val far = node("bb", hops = 9)
        val picks = (1..200).map { RelayRotation.pick(listOf(near, far), now = now, random = Random(it))?.destinationHash }

        assertTrue("the far node must be chosen sometimes", picks.contains("bb"))
        assertTrue("the near node must be chosen sometimes", picks.contains("aa"))
    }

    /** And no node may dominate: over many draws the split stays near even. */
    @Test
    fun `the choice does not lean towards any one node`() {
        val nodes = listOf(node("aa"), node("bb"), node("cc"), node("dd"))
        val counts = mutableMapOf<String, Int>()

        repeat(4_000) { seed ->
            val picked = RelayRotation.pick(nodes, now = now, random = Random(seed))?.destinationHash
            counts[picked!!] = (counts[picked] ?: 0) + 1
        }

        assertEquals("every node must come up", 4, counts.size)
        counts.forEach { (hash, count) ->
            assertTrue("$hash came up $count times out of 4000, which is not an even share", count in 800..1_200)
        }
    }

    /**
     * Rotating away from the current relay means not landing back on it, and a
     * node already tried and found unreachable is not worth a second attempt in
     * the same round.
     */
    @Test
    fun `excluded nodes are never picked`() {
        val nodes = listOf(node("aa"), node("bb"), node("cc"))

        repeat(300) { seed ->
            val picked = RelayRotation.pick(nodes, exclude = setOf("aa", "bb"), now = now, random = Random(seed))
            assertEquals("cc", picked?.destinationHash)
        }
    }

    /**
     * The announce table keeps entries long after the node behind them stopped
     * answering. Rotating onto one of those costs a sync timeout for nothing.
     */
    @Test
    fun `a node that announced recently is preferred over one that went quiet`() {
        val alive = node("aa", seenAgoMs = RelayRotation.FRESHNESS_MS / 2)
        val gone = node("bb", seenAgoMs = RelayRotation.FRESHNESS_MS * 3)

        repeat(300) { seed ->
            val picked = RelayRotation.pick(listOf(gone, alive), now = now, random = Random(seed))
            assertEquals("aa", picked?.destinationHash)
        }
    }

    /** But a stale candidate still beats refusing to sync at all. */
    @Test
    fun `when nothing is fresh a stale node is still used`() {
        val stale = listOf(node("aa", seenAgoMs = RelayRotation.FRESHNESS_MS * 5))

        val picked = RelayRotation.pick(stale, now = now, random = Random(1))

        assertNotNull(picked)
        assertEquals("aa", picked?.destinationHash)
    }

    @Test
    fun `nothing to choose from is not a choice`() {
        assertNull(RelayRotation.pick(emptyList(), now = now, random = Random(1)))
        assertNull(RelayRotation.pick(listOf(node("aa")), exclude = setOf("aa"), now = now, random = Random(1)))
    }

    @Test
    fun `a single candidate is taken as it is`() {
        val only = RelayRotation.pick(listOf(node("aa", hops = 7)), now = now, random = Random(1))

        assertEquals("aa", only?.destinationHash)
    }
}
