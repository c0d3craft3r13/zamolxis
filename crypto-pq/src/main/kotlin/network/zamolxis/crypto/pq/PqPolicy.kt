package network.zamolxis.crypto.pq

/**
 * What the sender knows about the recipient's post-quantum capability.
 */
public enum class PeerPqSupport {
    /** The recipient advertised a hybrid key and we hold it. Sealing is possible. */
    KEY_KNOWN,

    /** The recipient advertised a hybrid key but we have not fetched it yet. */
    ADVERTISED_KEY_MISSING,

    /** The recipient has never advertised one — an ordinary Reticulum peer. */
    UNSUPPORTED,
}

/**
 * How costly the chosen link is, in airtime terms.
 *
 * Sealing adds [HybridKem.OVERHEAD_BYTES] to every message. That is nothing on
 * TCP and material on LoRa, where it can mean several extra seconds of airtime
 * on a shared channel.
 */
public enum class LinkCost {
    /** TCP, Wi-Fi, BLE — overhead is not worth reasoning about. */
    CHEAP,

    /** LoRa and similar sub-kbps radio links. */
    EXPENSIVE,
}

/** What the sender should do with a given message. */
public sealed interface PqDecision {
    /** Seal the content with the hybrid layer before handing it to LXMF. */
    public data object Seal : PqDecision

    /**
     * Send as an ordinary LXMF message. [reason] is for the UI and the logs, so
     * a user can see *why* a conversation is not post-quantum protected rather
     * than assuming it is.
     */
    public data class SendPlain(
        val reason: PlainReason,
    ) : PqDecision

    /**
     * Do not send at all. Only produced when the user demanded post-quantum
     * protection and it cannot be delivered — failing loudly beats silently
     * downgrading someone who is relying on it.
     */
    public data class Refuse(
        val reason: PlainReason,
    ) : PqDecision
}

/** Why a message is not being sealed. */
public enum class PlainReason {
    /** Recipient is a plain Reticulum client. */
    PEER_UNSUPPORTED,

    /** Recipient advertised a key we have not obtained yet. */
    PEER_KEY_NOT_YET_KNOWN,

    /** Sealing is off in settings. */
    DISABLED_BY_USER,

    /** Link is too slow to justify the overhead, and the user allowed the tradeoff. */
    LINK_TOO_EXPENSIVE,
}

/**
 * The user's stance on post-quantum sealing.
 */
public enum class PqMode {
    /** Never seal. */
    OFF,

    /** Seal whenever the recipient can read it and the link can afford it. */
    OPPORTUNISTIC,

    /**
     * Seal always, refusing to send if that is impossible.
     *
     * For someone who would rather a message not go out at all than go out
     * readable to a future quantum adversary.
     */
    REQUIRED,
}

/**
 * Decides whether a single outgoing message gets the hybrid layer.
 *
 * Deliberately a pure function with no I/O: this is the point where a mistake
 * does not leak data but silently strands messages, so it needs to be trivially
 * testable in isolation.
 */
public object PqPolicy {
    public fun decide(
        mode: PqMode,
        peer: PeerPqSupport,
        link: LinkCost,
    ): PqDecision {
        if (mode == PqMode.OFF) {
            return PqDecision.SendPlain(PlainReason.DISABLED_BY_USER)
        }

        val blocker =
            when (peer) {
                PeerPqSupport.UNSUPPORTED -> PlainReason.PEER_UNSUPPORTED
                PeerPqSupport.ADVERTISED_KEY_MISSING -> PlainReason.PEER_KEY_NOT_YET_KNOWN
                PeerPqSupport.KEY_KNOWN -> null
            }
        if (blocker != null) {
            return when (mode) {
                PqMode.REQUIRED -> PqDecision.Refuse(blocker)
                else -> PqDecision.SendPlain(blocker)
            }
        }

        // The peer can read a sealed message. The only remaining question is cost,
        // and REQUIRED means the user already accepted the airtime.
        return if (link == LinkCost.EXPENSIVE && mode == PqMode.OPPORTUNISTIC) {
            PqDecision.SendPlain(PlainReason.LINK_TOO_EXPENSIVE)
        } else {
            PqDecision.Seal
        }
    }
}
