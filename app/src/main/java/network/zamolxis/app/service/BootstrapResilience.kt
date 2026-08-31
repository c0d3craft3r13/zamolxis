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
        )

        suspend fun applyOnce(): Outcome {
            if (settingsRepository.getBootstrapResilienceVersion() >= CURRENT_VERSION) {
                return Outcome(alreadyApplied = true)
            }

            val hubsAdded = addMissingBootstrapHubs()
            val discoveryEnabled = enableDiscoveryIfUnset()

            settingsRepository.saveBootstrapResilienceVersion(CURRENT_VERSION)
            Log.i(TAG, "Applied v$CURRENT_VERSION: hubs=$hubsAdded discoveryEnabled=$discoveryEnabled")
            return Outcome(hubsAdded = hubsAdded, discoveryEnabled = discoveryEnabled)
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
             */
            const val CURRENT_VERSION = 1

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
