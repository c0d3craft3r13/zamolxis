package network.zamolxis.app.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.zamolxis.app.data.model.TcpCommunityServer
import network.zamolxis.app.data.model.TcpCommunityServers
import network.zamolxis.app.repository.InterfaceRepository
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.rns.api.RnsCore
import network.zamolxis.app.rns.api.model.InterfaceConfig
import network.zamolxis.app.service.manager.InterfaceTransportObserver
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Watches whether the seeded bootstrap hubs actually carry traffic, and swaps them out
 * when they do not.
 *
 * [BootstrapResilience] made sure an install starts with several hubs rather than one.
 * That alone does not help if every seeded hub is a host that accepts the connection and
 * then says nothing — which is exactly how `rns.beleth.net` failed. "Connected" was the
 * only health signal the app had, and a hub like that is connected forever.
 *
 * So the signal here is an announce arriving on the interface. After
 * [SILENCE_GRACE_SECONDS] of a live stack, hubs that have delivered nothing are handed to
 * [BootstrapRotationPolicy], which decides whether to retire them and what to try next.
 *
 * Deliberately conservative: it checks once per app start, never rotates while the device
 * has no network at all, and stops after [BootstrapRotationPolicy.MAX_ROTATIONS] attempts
 * across the install's lifetime.
 */
@Singleton
class BootstrapHubHealth
    @Inject
    constructor(
        private val rnsCore: RnsCore,
        private val interfaceRepository: InterfaceRepository,
        private val settingsRepository: SettingsRepository,
        private val transportObserver: InterfaceTransportObserver,
        private val interfaceConfigManager: InterfaceConfigManager,
    ) {
        /** Interface names an announce has arrived on since this process started. */
        private val interfacesHeardFrom = ConcurrentHashMap.newKeySet<String>()

        /**
         * Start listening, and schedule the one check this process performs.
         *
         * @param scope the application scope; both jobs live as long as the process
         */
        fun start(scope: CoroutineScope) {
            scope.launch {
                rnsCore.observeAnnounces().collect { announce ->
                    announce.receivingInterface?.let(interfacesHeardFrom::add)
                }
            }
            scope.launch {
                delay(SILENCE_GRACE_SECONDS.seconds)
                runCatching { evaluateOnce() }
                    .onFailure { Log.e(TAG, "Bootstrap hub health check failed", it) }
            }
        }

        /**
         * Judge the currently enabled bootstrap hubs once and act on the verdict.
         *
         * @return the decision that was taken, for logging and tests
         */
        suspend fun evaluateOnce(): BootstrapRotationPolicy.Decision {
            val entities = interfaceRepository.allInterfaceEntities.first()
            val configs = interfaceRepository.allInterfaces.first()

            val presentEndpoints =
                configs
                    .filterIsInstance<InterfaceConfig.TCPClient>()
                    .map { BootstrapRotationPolicy.endpointOf(it.targetHost, it.targetPort) }
                    .toSet()

            val hubs =
                configs
                    .filterIsInstance<InterfaceConfig.TCPClient>()
                    .filter { it.enabled && it.bootstrapOnly }
                    .mapNotNull { config ->
                        val id = entities.firstOrNull { it.name == config.name }?.id ?: return@mapNotNull null
                        BootstrapRotationPolicy.HubState(
                            id = id,
                            name = config.name,
                            endpoint = BootstrapRotationPolicy.endpointOf(config.targetHost, config.targetPort),
                            heardAnnounce = config.name in interfacesHeardFrom,
                        )
                    }

            val decision =
                BootstrapRotationPolicy.decide(
                    hubs = hubs,
                    knownServers = TcpCommunityServers.servers,
                    presentEndpoints = presentEndpoints,
                    transport = transportObserver.snapshotTransport(),
                    rotationsUsed = settingsRepository.getBootstrapRotationsUsed(),
                )

            Log.i(TAG, "Bootstrap hub health: ${decision.reason}")
            if (decision.isNoOp) return decision

            applyRotation(decision)
            return decision
        }

        private suspend fun applyRotation(decision: BootstrapRotationPolicy.Decision) {
            decision.retire.forEach { interfaceRepository.toggleInterfaceEnabled(it, false) }
            decision.add.forEach { interfaceRepository.insertInterface(toBootstrapConfig(it)) }
            settingsRepository.saveBootstrapRotationsUsed(
                settingsRepository.getBootstrapRotationsUsed() + 1,
            )

            // Restart the stack so the swap takes effect now. Waiting for the next launch
            // is what made the original failure so opaque: the user had already decided
            // the app was broken by then.
            interfaceConfigManager
                .applyInterfaceChanges()
                .onFailure { Log.e(TAG, "Could not apply rotated bootstrap hubs", it) }
        }

        private fun toBootstrapConfig(server: TcpCommunityServer): InterfaceConfig.TCPClient =
            InterfaceConfig.TCPClient(
                name = server.name,
                enabled = true,
                targetHost = server.host,
                targetPort = server.port,
                bootstrapOnly = true,
            )

        companion object {
            private const val TAG = "BootstrapHubHealth"

            /**
             * How long a hub gets to produce its first announce.
             *
             * Measured hubs deliver announces within seconds of connecting, and the known
             * bad one dropped the client every ~82 seconds — so this has to be long enough
             * to be past a normal connect and short enough that a whole session is not
             * spent waiting. Ninety seconds also lets the stack finish coming up first.
             */
            const val SILENCE_GRACE_SECONDS = 90L
        }
    }
