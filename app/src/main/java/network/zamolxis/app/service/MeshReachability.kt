package network.zamolxis.app.service

import network.zamolxis.app.data.model.InterfaceType

/**
 * The kind of link an announce arrived over, in the terms a person would use.
 *
 * Deliberately coarser than the interface list: someone asking "why is my message not
 * sending" needs to know whether anything is reaching them at all, not whether it came
 * over `TCPClient` or `BackboneInterface`.
 */
enum class MeshLinkKind {
    BLUETOOTH,
    LOCAL_NETWORK,
    INTERNET,
    RADIO,
    ;

    companion object {
        /**
         * Classify the interface an announce arrived on.
         *
         * The label is what RNS reports, not what the interface is called in the
         * database: `"TCPInterface[g00n.cloud Hub/dfw.us.g00n.cloud:6969]"`,
         * `"AutoInterface[Local]"`, `"BLEPeerInterface[BLE-56:31:F5]"`. Matching those
         * against configured names never works, which is what
         * [network.zamolxis.app.data.model.InterfaceType.fromName] already exists to
         * handle — it is the same classifier the announce table is stored with.
         *
         * @return null for anything that is not a way of reaching another person —
         *   including the shared-instance loopback, which only reaches another app on
         *   this device.
         */
        fun of(interfaceLabel: String): MeshLinkKind? =
            when (InterfaceType.fromName(interfaceLabel)) {
                InterfaceType.BLE -> BLUETOOTH
                InterfaceType.AUTO -> LOCAL_NETWORK
                InterfaceType.TCP_CLIENT, InterfaceType.TCP_SERVER -> INTERNET
                InterfaceType.RNODE -> RADIO
                InterfaceType.SHARED_INSTANCE, InterfaceType.UNKNOWN -> null
            }
    }
}

/**
 * What the mesh has actually delivered, and when.
 *
 * Built from announces arriving on real interfaces — the same evidence
 * [BootstrapRotationPolicy] judges hubs by, and for the same reason: a connected socket
 * proves nothing. Nothing here polls; the numbers move only when something is heard.
 *
 * @param lastHeardAtByKind monotonic timestamps (`SystemClock.elapsedRealtime`)
 * @param startedAtMs when the app began listening, so a fresh start is not mistaken for
 *   silence
 */
data class MeshReachability(
    val lastHeardAtByKind: Map<MeshLinkKind, Long> = emptyMap(),
    val startedAtMs: Long = 0L,
) {
    /** Kinds that delivered something recently enough to still be believed. */
    fun kindsHeardWithin(
        nowMs: Long,
        windowMs: Long = FRESH_WINDOW_MS,
    ): Set<MeshLinkKind> =
        lastHeardAtByKind
            .filterValues { nowMs - it <= windowMs }
            .keys
            .toSortedSet(compareBy { it.ordinal })

    /**
     * What to tell the user right now.
     *
     * Three states, because that is how many a person can act on: something is getting
     * through (and over what), we have only just started looking, or nothing is
     * reaching us.
     */
    fun describe(
        nowMs: Long,
        windowMs: Long = FRESH_WINDOW_MS,
        settlingMs: Long = SETTLING_MS,
    ): MeshStatus {
        val kinds = kindsHeardWithin(nowMs, windowMs)
        return when {
            kinds.isNotEmpty() -> MeshStatus.Connected(kinds)
            nowMs - startedAtMs < settlingMs -> MeshStatus.Searching
            else -> MeshStatus.Offline
        }
    }

    companion object {
        /**
         * How long an announce keeps counting as evidence.
         *
         * Announces are not on a fixed schedule, and a quiet five minutes on an
         * otherwise healthy link is normal; anything shorter would flicker.
         */
        const val FRESH_WINDOW_MS = 5 * 60 * 1000L

        /**
         * Grace after start before silence is called silence. The stack needs seconds to
         * come up, and "no connection" on a launching app is a lie.
         */
        const val SETTLING_MS = 30 * 1000L
    }
}

/** The three things worth saying about reachability. */
sealed interface MeshStatus {
    /** Something is getting through, over [kinds]. */
    data class Connected(
        val kinds: Set<MeshLinkKind>,
    ) : MeshStatus

    /** Just started; too early to claim anything. */
    data object Searching : MeshStatus

    /** Nothing has reached us for a while. */
    data object Offline : MeshStatus
}
