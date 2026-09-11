package network.zamolxis.app.rns.host.emission

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps cover traffic going for as long as the service lives.
 *
 * ## The order of the two steps
 *
 * Measure, then decide. The measurement occupies a window — counting radios
 * means listening for one — so what it reports is the company that was there
 * during the window just ended, not company from some earlier reading that may
 * have gone. Deciding from a stale measurement is how a device carries on
 * padding after everyone else has left the room.
 *
 * ## Why silence is checked before measuring
 *
 * Measuring transmits nothing, so it would be safe to do under silence — but it
 * spends thirty seconds of Bluetooth scanning to produce an answer that cannot
 * be acted on. An operator who has asked the device to go quiet is not usually
 * in a position to spend battery on questions with no consequence.
 */
class CoverTrafficLoop(
    private val emissions: EmissionPolicy,
    private val tracker: CoverTracker,
    private val emitter: CoverTrafficEmitter,
) {
    /**
     * Run until [scope] is cancelled.
     *
     * Returns the [Job] rather than discarding it, so a caller can stop this on
     * its own terms and a test can await a cycle instead of sleeping.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                runCycle()
                delay(emitter.nextIntervalMs())
            }
        }

    /** One measure-then-decide pass. Separated so a test can run exactly one. */
    suspend fun runCycle() {
        if (!emissions.mayEmit()) return
        emitter.emitOnce(tracker.measure())
    }
}
