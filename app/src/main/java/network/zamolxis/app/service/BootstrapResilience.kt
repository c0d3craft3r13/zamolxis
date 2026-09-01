package network.zamolxis.app.service

import android.util.Log
import kotlinx.coroutines.flow.first
import network.zamolxis.app.data.model.TcpCommunityServer
import network.zamolxis.app.data.model.TcpCommunityServers
import network.zamolxis.app.repository.InterfaceRepository
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.rns.api.model.InterfaceConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot repair for installs that can lose their only route into the network.
 *
 * A fresh install used to be seeded with a single bootstrap hub. When that hub went
 * bad the app reached the network never: `rns.beleth.net` accepted the TCP connection,
 * delivered no announces, and dropped the client every ~82 seconds, so the Network tab
 * stayed empty forever with nothing on screen to explain it. Swapping in a different
 * single hub only moves the failure.
 *
 * Two things fix it, and RNS already implements both — they were simply switched off:
 *
 *  - **Redundant seeds.** Every hub in [TcpCommunityServers.bootstrapServers] is added,
 *    not one. They are `bootstrap_only`, so RNS detaches them once enough discovered
 *    interfaces are connected; the cost is temporary.
 *  - **Interface discovery.** With discovery on, the app learns hubs announced by the
 *    network itself and connects to them. That is what makes the seed list
 *    self-healing rather than a list that rots: the seeds only have to work once, long
 *    enough to hear about somebody else.
 *
 * Fresh installs get the seeds from `InterfaceDatabase`; this class exists for everyone
 * who installed before, and to raise the discovery default without overriding a choice
 * the user actually made.
 *
 * Idempotent and versioned: it runs once per [CURRENT_VERSION] and records that it did.
 * Hubs the user deleted afterwards stay deleted — it will not resurrect them on the
 * next launch.
 */
@Singleton
class BootstrapResilience
    @Inject
    constructor(
        private val interfaceRepository: InterfaceRepository,
        private val settingsRepository: SettingsRepository,
    ) {
        /**
         * What the repair did, for logging and tests.
         *
         * @param alreadyApplied true when this version had already run and nothing was touched
         * @param hubsAdded names of bootstrap hubs inserted by this run
         * @param discoveryEnabled true when this run turned interface discovery on
         */
        data class Outcome(
            val alreadyApplied: Boolean = false,
            val hubsAdded: List<String> = emptyList(),
            val discoveryEnabled: Boolean = false,
            val seedsRestored: List<String> = emptyList(),
            val deadHubsRetired: List<String> = emptyList(),
            val rotationBudgetReset: Boolean = false,
        )

        suspend fun applyOnce(): Outcome {
            if (settingsRepository.getBootstrapResilienceVersion() >= CURRENT_VERSION) {
                return Outcome(alreadyApplied = true)
            }

            val hubsAdded = addMissingBootstrapHubs()
            val discoveryEnabled = enableDiscoveryIfUnset()
            val rotationRepair = undoRotationDamage()

            settingsRepository.saveBootstrapResilienceVersion(CURRENT_VERSION)
            Log.i(
                TAG,
                "Applied v$CURRENT_VERSION: hubs=$hubsAdded discoveryEnabled=$discoveryEnabled " +
                    "seedsRestored=${rotationRepair.seedsRestored} " +
                    "deadHubsRetired=${rotationRepair.deadHubsRetired} " +
                    "rotationBudgetReset=${rotationRepair.rotationBudgetReset}",
            )
            return rotationRepair.copy(hubsAdded = hubsAdded, discoveryEnabled = discoveryEnabled)
        }

        /**
         * Add any known bootstrap hub the install does not already have.
         *
         * Matched on host and port rather than on name: the name is the user's to
         * change, the endpoint is what decides whether the route already exists.
         */
        private suspend fun addMissingBootstrapHubs(): List<String> {
            val existingEndpoints =
                interfaceRepository.allInterfaces
                    .first()
                    .filterIsInstance<InterfaceConfig.TCPClient>()
                    .map { endpointOf(it.targetHost, it.targetPort) }
                    .toSet()

            val added = mutableListOf<String>()
            TcpCommunityServers.bootstrapServers
                .filterNot { endpointOf(it.host, it.port) in existingEndpoints }
                .forEach { server ->
                    interfaceRepository.insertInterface(toBootstrapConfig(server))
                    added += server.name
                }
            return added
        }

        /**
         * Turn interface discovery on, but only where the user has never said otherwise.
         *
         * Discovery shipped defaulting to off, which is why the seed list could not heal
         * itself. Raising the default outright would also flip anyone who had turned it
         * off deliberately, so the unset case is the only one changed here.
         */
        private suspend fun enableDiscoveryIfUnset(): Boolean {
            if (settingsRepository.hasDiscoverInterfacesPreference()) return false
            settingsRepository.saveDiscoverInterfacesEnabled(true)
            return true
        }

        /**
         * Put back what the hub rotation took away, on installs where it ran.
         *
         * [network.zamolxis.app.service.BootstrapHubHealth] judged a hub silent by
         * testing its configured name against a set of RNS interface labels — an
         * equality test that was never true, so every hub read as silent whether or not
         * it was carrying traffic. Installs rotated the full
         * [BootstrapRotationPolicy.MAX_ROTATIONS] times and stopped, and what they
         * stopped on was whatever came next in the list. Measured on a phone: the three
         * seed hubs, all three answering, disabled in favour of three that answered
         * nothing, with no budget left to try again.
         *
         * Fixing the comparison does not help those installs — their seeds are already
         * off and their budget already spent. So:
         *
         *  - bootstrap hubs no longer in [TcpCommunityServers.servers] are switched off.
         *    That list was just cleared of everything that stopped answering, which
         *    makes "not on the list" the precise description of a hub rotation should
         *    never have installed.
         *  - the seeds are switched back on.
         *  - the rotation budget is returned, so a genuinely bad hub can still be
         *    rotated away from later — this time on a signal that works.
         *
         * Gated on the rotation having actually run. A user who switched a hub off
         * themselves gets left alone; `rotationsUsed > 0` is the evidence that the
         * change was ours to undo. Interfaces are disabled rather than deleted, exactly
         * as the rotation itself retires them, so nothing the user might want back is
         * destroyed.
         */
        private suspend fun undoRotationDamage(): Outcome {
            if (settingsRepository.getBootstrapRotationsUsed() <= 0) return Outcome()

            val entities = interfaceRepository.allInterfaceEntities.first()
            val configs = interfaceRepository.allInterfaces.first()
            val knownEndpoints =
                TcpCommunityServers.servers.map { endpointOf(it.host, it.port) }.toSet()
            val seedEndpoints =
                TcpCommunityServers.bootstrapServers.map { endpointOf(it.host, it.port) }.toSet()

            val retired = mutableListOf<String>()
            val restored = mutableListOf<String>()
            configs
                .filterIsInstance<InterfaceConfig.TCPClient>()
                .filter { it.bootstrapOnly }
                .forEach { config ->
                    val id = entities.firstOrNull { it.name == config.name }?.id ?: return@forEach
                    val endpoint = endpointOf(config.targetHost, config.targetPort)
                    when {
                        config.enabled && endpoint !in knownEndpoints -> {
                            interfaceRepository.toggleInterfaceEnabled(id, false)
                            retired += config.name
                        }
                        !config.enabled && endpoint in seedEndpoints -> {
                            interfaceRepository.toggleInterfaceEnabled(id, true)
                            restored += config.name
                        }
                    }
                }

            settingsRepository.saveBootstrapRotationsUsed(0)
            return Outcome(
                seedsRestored = restored,
                deadHubsRetired = retired,
                rotationBudgetReset = true,
            )
        }

        private fun toBootstrapConfig(server: TcpCommunityServer): InterfaceConfig.TCPClient =
            InterfaceConfig.TCPClient(
                name = server.name,
                enabled = true,
                targetHost = server.host,
                targetPort = server.port,
                bootstrapOnly = true,
            )

        private fun endpointOf(
            host: String,
            port: Int,
        ): String = "${host.lowercase()}:$port"

        companion object {
            private const val TAG = "BootstrapResilience"

            /**
             * Bump when the repair itself changes — for instance if a later
             * [TcpCommunityServers.bootstrapServers] gains a hub that existing installs
             * should also receive. Installs re-run the repair once per version.
             *
             * v2 adds [undoRotationDamage], for the installs that rotated themselves
             * onto unreachable hubs while `heardAnnounce` could never be true.
             */
            const val CURRENT_VERSION = 2

            /**
             * Discovered interfaces to auto-connect to when the user has not chosen a
             * number. Three matches the seed count: enough that losing one hub is not
             * losing the network, few enough to stay unremarkable next to the BLE
             * scanning the service already does.
             *
             * Read by `InterfaceConfigManager` for the never-configured case only; an
             * explicit 0 from the user is still honoured.
             */
            const val DEFAULT_AUTOCONNECT_DISCOVERED = 3
        }
    }
