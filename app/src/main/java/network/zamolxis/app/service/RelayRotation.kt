package network.zamolxis.app.service

import network.zamolxis.app.data.repository.Announce
import kotlin.random.Random

/**
 * Which propagation node to hand this device's mail to, when nobody chose one.
 *
 * ## Why not the nearest
 *
 * Picking the node with the fewest hops is the obvious answer and the wrong one
 * here, for two reasons that compound.
 *
 * The nearest node is near *this device*. Whoever runs it — or watches it —
 * learns something about where its users are, and a relay chosen by proximity
 * hands that out for free. Worse, hop count is stable: the same device in the
 * same place picks the same relay every time, so the choice becomes a name. An
 * operator who moves and keeps syncing through the same node has told that node
 * they moved; one who never moves has told it that too.
 *
 * Choosing at random from whatever is reachable breaks both. There is nothing to
 * read in the choice, and no run of choices to follow.
 *
 * ## Why mail is not lost by moving around
 *
 * LXMF propagation nodes peer with each other and sync what they hold, and mail
 * sits on a node for thirty days. A message deposited on one node is reachable
 * from another, so rotating between them costs latency in the worst case, not
 * delivery.
 *
 * ## What "reachable" means
 *
 * A node that announced recently. An announce is the only evidence a node is
 * still running, and the table keeps entries long after the node behind them
 * went away. Preferring fresh ones avoids rotating onto a dead relay and
 * waiting out a sync timeout for nothing — but if nothing is fresh, a stale
 * candidate is still better than refusing to sync at all.
 */
object RelayRotation {
    /**
     * How recently a node must have announced to be preferred.
     *
     * A chosen default. Long enough to survive a node that announces a few times
     * a day and a phone that was off overnight; short enough that a node which
     * has been gone since yesterday stops being the first choice.
     */
    const val FRESHNESS_MS: Long = 24 * 60 * 60 * 1000

    /**
     * Pick a relay, or null when there is nothing to pick from.
     *
     * @param candidates propagation nodes known from announces.
     * @param exclude nodes not to pick — the current one when rotating away from
     *   it, or ones already tried and found unreachable.
     * @param random injected so the choice can be made deterministic in a test;
     *   nothing about the real one is derived from the device.
     */
    fun pick(
        candidates: List<Announce>,
        exclude: Collection<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): Announce? {
        val eligible = candidates.filterNot { it.destinationHash in exclude }
        if (eligible.isEmpty()) return null

        val fresh = eligible.filter { now - it.lastSeenTimestamp <= FRESHNESS_MS }
        val pool = fresh.ifEmpty { eligible }
        return pool[random.nextInt(pool.size)]
    }
}
