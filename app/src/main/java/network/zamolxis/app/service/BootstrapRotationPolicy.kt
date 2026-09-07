package network.zamolxis.app.service

import network.zamolxis.app.data.model.TcpCommunityServer
import network.zamolxis.app.rns.host.manager.CurrentTransport

/**
 * Decides whether the seeded bootstrap hubs have to be replaced.
 *
 * The failure this exists for is a hub that is *connected and useless*:
 * `rns.beleth.net` accepted the TCP connection, delivered no announces, and dropped the
 * client every ~82 seconds. Every liveness signal the app had said "connected", so
 * nothing ever rotated and the Network tab stayed empty forever.
 *
 * Hearing an announce is the only honest evidence a hub is carrying traffic, so that is
 * what this policy judges on. Pure and synchronous on purpose — the interesting part is
 * the rules, and they should be testable without a network, a clock or a coroutine.
 */
object BootstrapRotationPolicy {
    /**
     * How many times an install may rotate its bootstrap hubs before giving up.
     *
     * A cap is not optional. "No announces" also describes a device whose data is
     * nominally up but going nowhere — a captive portal, a dead SIM, a firewall — and
     * against that, rotating hubs is useless motion that costs a stack restart every
     * time. Three attempts is enough to walk past a couple of dead hosts and not enough
     * to churn.
     */
    const val MAX_ROTATIONS = 3

    /**
     * A seeded hub and whether anything was ever heard through it.
     *
     * @param id database id of the interface row
     * @param name the interface's display name
     * @param endpoint `host:port`, lowercased — see [endpointOf]
     * @param heardAnnounce true when at least one announce arrived on this interface
     */
    data class HubState(
        val id: Long,
        val name: String,
        val endpoint: String,
        val heardAnnounce: Boolean,
    )

    /**
     * @param retire ids of hubs to disable; empty means leave everything alone
     * @param add community servers to enable in their place
     * @param reason why, for the log — this decision is otherwise invisible
     */
    data class Decision(
        val retire: List<Long> = emptyList(),
        val add: List<TcpCommunityServer> = emptyList(),
        val reason: String,
    ) {
        val isNoOp: Boolean get() = retire.isEmpty() && add.isEmpty()
    }

    fun endpointOf(
        host: String,
        port: Int,
    ): String = "${host.lowercase()}:$port"

    /**
     * Whether an RNS interface label refers to the interface serving [endpoint].
     *
     * The endpoint is the only part of a hub that appears in both worlds. RNS names its
     * interfaces `"TCPInterface[g00n.cloud Hub/dfw.us.g00n.cloud:6969]"`, while the app
     * knows the hub by the name the user can rename and the host/port it dials. Testing
     * the configured name for membership in the set of RNS labels — which is what this
     * replaces — is an equality test that is never true, so every hub read as silent
     * whether or not it was carrying traffic, and installs rotated until they hit
     * [MAX_ROTATIONS]. Measured on a phone: three seed hubs, all three answering,
     * retired in favour of three that answered nothing, with no budget left to return.
     */
    fun labelServes(
        label: String,
        endpoint: String,
    ): Boolean {
        var from = 0
        while (from <= label.length - endpoint.length) {
            val at = label.indexOf(endpoint, from, ignoreCase = true)
            if (at < 0) return false
            // A bare substring test would let `host:42` match `host:4242`, so the port
            // has to end where the endpoint does.
            val after = at + endpoint.length
            if (after >= label.length || !label[after].isDigit()) return true
            from = at + 1
        }
        return false
    }

    /**
     * Whether [host] can only be reached through Tor.
     *
     * A `.onion` address needs a SOCKS proxy that the user has to install and run
     * themselves (Orbot). Choosing it is a deliberate act; handing it to someone as an
     * emergency replacement is not, and the interface this policy builds carries
     * `socksProxyEnabled = false`, so the address does not even resolve. One was rotated
     * onto a phone that way and could never have connected.
     */
    fun requiresTor(host: String): Boolean = host.endsWith(".onion", ignoreCase = true)

    @Suppress("ReturnCount")
    fun decide(
        hubs: List<HubState>,
        knownServers: List<TcpCommunityServer>,
        presentEndpoints: Set<String>,
        transport: CurrentTransport,
        rotationsUsed: Int,
    ): Decision {
        if (hubs.isEmpty()) {
            return Decision(reason = "no bootstrap hubs enabled")
        }
        if (transport == CurrentTransport.NONE) {
            // Silence proves nothing about the hub when the device has no network at
            // all. Rotating here would burn the candidate list on the walk to the shop.
            return Decision(reason = "no network transport, silence is not the hub's fault")
        }
        if (rotationsUsed >= MAX_ROTATIONS) {
            return Decision(reason = "rotation limit reached ($rotationsUsed/$MAX_ROTATIONS)")
        }
        if (hubs.any { it.heardAnnounce }) {
            // One working hub is the whole requirement. The others may be silent because
            // RNS routed everything through the one that answered first, which is not a
            // fault.
            return Decision(reason = "at least one hub is delivering announces")
        }

        val silent = hubs.filterNot { it.heardAnnounce }
        val replacements =
            knownServers
                .filterNot { endpointOf(it.host, it.port) in presentEndpoints }
                .filterNot { requiresTor(it.host) }
                .take(silent.size)

        if (replacements.isEmpty()) {
            // Retiring the only hubs we have, with nothing to put in their place, would
            // turn a bad route into no route.
            return Decision(reason = "every known server has already been tried")
        }

        return Decision(
            retire = silent.map { it.id },
            add = replacements,
            reason = "silent: ${silent.joinToString { it.name }} -> trying ${replacements.joinToString { it.name }}",
        )
    }
}
