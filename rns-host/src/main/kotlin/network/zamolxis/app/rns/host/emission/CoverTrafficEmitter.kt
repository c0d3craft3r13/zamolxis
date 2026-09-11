package network.zamolxis.app.rns.host.emission

import network.zamolxis.app.rns.api.util.CoverTraffic
import kotlin.math.ln
import kotlin.random.Random

/**
 * Decides who receives a message that says nothing, and when.
 *
 * ## Who
 *
 * Only peers already corresponded with. Sending cover to a stranger would
 * manufacture a correspondence between them and this device that an observer
 * would record as real — a cost paid by someone who did not agree to it, to buy
 * this device a little noise. Among existing peers nothing new is implied: they
 * already exchange messages with us, and one more tells an observer nothing it
 * did not already have.
 *
 * ## Where
 *
 * Only where there is company to be lost in, which on a radio is nowhere. The
 * medium behind each peer is resolved from the configured interfaces rather
 * than from any name, and a peer whose path is unknown gets nothing — see
 * [InterfaceMediumResolver].
 *
 * ## When
 *
 * At intervals drawn from an exponential distribution rather than on a timer. A
 * fixed period is itself a signature: an observer who sees transmissions
 * arriving every ninety seconds has learned what software is running without
 * decrypting anything. An exponential gap is memoryless — how long it has been
 * since the last transmission says nothing about how long until the next — so
 * the pattern carries no information about what this device has been doing.
 *
 * ## What it does not claim
 *
 * A real message is sent when the operator acts, and cover is not withheld to
 * make room for it. So the total an observer sees is cover plus real traffic,
 * not a constant rate concealing both. This raises the number of transmissions
 * that must be explained; it does not remove the real one from among them.
 * Saying otherwise would be the more comfortable claim and the false one.
 */
class CoverTrafficEmitter(
    private val emissions: EmissionPolicy,
    private val resolver: InterfaceMediumResolver,
    private val correspondents: suspend () -> List<ByteArray>,
    private val nextHopInterfaceName: suspend (ByteArray) -> String?,
    private val sendCover: suspend (ByteArray, Map<Int, Any>) -> Result<Unit>,
    private val random: Random = Random.Default,
) {
    /**
     * Send cover to every correspondent whose path currently has company on it.
     *
     * @return the destinations written to, for the caller to count or log.
     */
    suspend fun emitOnce(cover: AmbientCover): List<ByteArray> {
        // Cover traffic is by definition a transmission nobody asked for, which
        // is the entire category silence exists to remove.
        if (!emissions.mayEmit()) return emptyList()

        return correspondents()
            .filter { resolver.mayPad(nextHopInterfaceName(it), cover) }
            .filter { sendCover(it, CoverTraffic.fields()).isSuccess }
    }

    /**
     * How long to wait before considering it again.
     *
     * Drawn from an exponential distribution with mean [MEAN_INTERVAL_MS], by
     * inverse transform. Bounded at both ends: the distribution's tail reaches
     * arbitrarily small and arbitrarily large values, and neither a burst nor an
     * hour-long gap is wanted from a draw that was merely unlucky.
     */
    fun nextIntervalMs(): Long {
        val uniform = random.nextDouble()
        val exponential = -MEAN_INTERVAL_MS * ln(1.0 - uniform)
        return exponential.toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    companion object {
        /**
         * Average gap between considering cover, per device.
         *
         * A chosen default rather than a derived one. It is a balance nobody can
         * settle from first principles: cover works better the more of it there
         * is, and every message spends the recipient's battery, their storage,
         * and — when they are offline — room on a propagation node that holds
         * mail for thirty days.
         */
        const val MEAN_INTERVAL_MS: Double = 10 * 60 * 1000.0

        /** No closer together than this, however the draw falls. */
        const val MIN_INTERVAL_MS: Long = 30_000

        /** And no further apart, so a single unlucky draw does not stop cover for an hour. */
        const val MAX_INTERVAL_MS: Long = 45 * 60 * 1000
    }
}
