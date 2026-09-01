package network.zamolxis.app.data.model

/**
 * Represents a community TCP server for Reticulum networking.
 *
 * @param name User-friendly name for the server
 * @param host Hostname or IP address
 * @param port TCP port number
 * @param isBootstrap When true, this server is recommended as a bootstrap interface.
 *                    Bootstrap interfaces auto-detach once sufficient discovered
 *                    interfaces are connected (RNS 1.1.0+ feature).
 */
data class TcpCommunityServer(
    val name: String,
    val host: String,
    val port: Int,
    val isBootstrap: Boolean = false,
)

/**
 * List of known community TCP servers for Reticulum.
 *
 * Selected servers are marked as bootstrap candidates based on:
 * - Reputation in the community
 * - Long-term reliability
 * - Geographic distribution
 */
object TcpCommunityServers {
    val servers: List<TcpCommunityServer> =
        listOf(
            // Bootstrap servers: the ones a fresh install depends on, so they are
            // chosen from measurement rather than reputation. Each was left
            // connected for five minutes and judged on two things: whether the
            // socket survived, and how many announces it actually delivered.
            //
            // The previous three bootstrap entries (rns.beleth.net,
            // rns.quad4.io, firezen.com) all failed the first test: they accept
            // the TCP connection, deliver nothing, and drop the client every
            // ~82 seconds — measured identically under both the Python and the
            // Kotlin backend, so it is the hosts, not our stack. A fresh install
            // pointed only at those reached the network never, and the Network
            // tab stayed empty forever with no error to explain it.
            TcpCommunityServer("g00n.cloud Hub", "dfw.us.g00n.cloud", 6969, isBootstrap = true),
            TcpCommunityServer("Jon's Node", "rns.jlamothe.net", 4242, isBootstrap = true),
            TcpCommunityServer("noDNS2", "193.26.158.230", 4965, isBootstrap = true),
            // Regular community servers. Rotation draws replacements from this list,
            // so an entry that does not answer is not harmless decoration — it is a
            // hub the app may hand someone as a rescue. Every entry below answered a
            // TCP connect when last measured (1 Sep 2026) — three attempts each, all
            // three succeeding except noDNS1, which managed two; eleven hosts that
            // answered none of three were removed rather than annotated,
            // because the annotation that used to sit here was invisible to the code
            // that reads this list and three dead hosts were rotated onto a phone.
            //
            // Re-measure before adding one back. The Tor entry cannot be probed this
            // way and is kept for deliberate manual selection only — see
            // BootstrapRotationPolicy.requiresTor.
            TcpCommunityServer(
                "interloper node (Tor)",
                "intrcxv4fa72e5ovler5dpfwsiyuo34tkcwfy5snzstxkhec75okowqd.onion",
                4242,
            ),
            TcpCommunityServer("noDNS1", "202.61.243.41", 4965),
            TcpCommunityServer("Quortal TCP Node", "reticulum.qortal.link", 4242),
            TcpCommunityServer("R-Net TCP", "istanbul.reserve.network", 9034),
            TcpCommunityServer("RNS bnZ-NODE01", "node01.rns.bnz.se", 4242),
            TcpCommunityServer("RNS_Transport_US-East", "45.77.109.86", 4965),
            TcpCommunityServer("SparkN0de", "aspark.uber.space", 44860),
        )

    /**
     * Get only servers marked as bootstrap candidates.
     */
    val bootstrapServers: List<TcpCommunityServer>
        get() = servers.filter { it.isBootstrap }
}
