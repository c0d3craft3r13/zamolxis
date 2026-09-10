package network.zamolxis.app.rns.host.emission

import android.content.Context
import network.zamolxis.app.rns.host.persistence.ServiceSettingsAccessor

/**
 * Whether this device may put bytes on the air on its own initiative.
 *
 * ## What silence is for
 *
 * Left alone, the app transmits without anyone asking it to: it syncs with a
 * propagation relay every hour, announces its identity's public key every three,
 * sweeps for paths at startup and again on a timer, and announces afresh on
 * every network change. None of that is a bug — it is what makes a mesh
 * messenger work. It is also a beacon, and for someone who must not be found,
 * a beacon on a schedule is the thing that finds them.
 *
 * Silence means: **transmit when the operator acts, and at no other time.**
 * Sending a message still sends it. Nothing else starts a transmission.
 *
 * ## What it cannot do
 *
 * It does not make the radio invisible. A transmission the operator asks for is
 * still a transmission, and on LoRa direction-finding works on the physical
 * signal whatever is inside it. Silence removes the transmissions nobody chose;
 * choosing when to make the rest is the operator's job and no software can do
 * it for them.
 *
 * It also does not, on its own, stop this device answering the network — a path
 * request from a stranger still draws a path response, which is a transmission
 * an adversary can provoke. That is a separate decision with a visible cost
 * (an unanswered path request is an incoming call that never rings) and is not
 * folded into this flag.
 *
 * ## Why it is read here rather than passed in
 *
 * Both processes start transmissions: the UI process schedules announces,
 * syncs and path sweeps, and the `:reticulum` service announces on network
 * changes of its own accord. A flag either of them could hold privately would
 * be a flag the other ignores, so it lives in the cross-process store that
 * already carries settings both sides need.
 */
class EmissionPolicy(
    context: Context,
) {
    private val settings = ServiceSettingsAccessor(context.applicationContext)

    /**
     * Whether the operator has asked for silence.
     *
     * Read on every call rather than cached. The flag is written by the other
     * process, and a cached copy would keep transmitting for as long as the
     * cache was stale — which is exactly the window in which silence was asked
     * for and mattered.
     */
    val isSilent: Boolean
        get() = settings.getRadioSilence()

    /** Whether an emission this device would start on its own may go ahead. */
    fun mayEmit(): Boolean = !isSilent
}

/**
 * Raised when something this device would have transmitted was held back.
 *
 * Distinct from a failure on purpose. A caller that cannot tell "the radio is
 * off by choice" from "the announce failed" will report the second, and the
 * user will be told something is broken when in fact it is doing what they
 * asked.
 */
class RadioSilentException(
    operation: String,
) : Exception("$operation was not sent: the operator asked for radio silence")
