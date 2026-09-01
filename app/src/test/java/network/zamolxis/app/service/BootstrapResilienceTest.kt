package network.zamolxis.app.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.data.database.entity.InterfaceEntity
import network.zamolxis.app.data.model.TcpCommunityServers
import network.zamolxis.app.repository.InterfaceRepository
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.rns.api.model.InterfaceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this guards against: an install seeded with a single bootstrap hub that
 * went bad reached the network never, and nothing on screen explained why.
 */
class BootstrapResilienceTest {
    private val interfaceRepository = mockk<InterfaceRepository>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val inserted = mutableListOf<InterfaceConfig>()
    private val savedVersions = mutableListOf<Int>()
    private val savedDiscovery = mutableListOf<Boolean>()
    private val toggledIds = mutableListOf<Long>()
    private val toggledStates = mutableListOf<Boolean>()
    private val savedRotations = mutableListOf<Int>()

    private fun resilience(
        existing: List<InterfaceConfig> = emptyList(),
        appliedVersion: Int = 0,
        hasDiscoveryPreference: Boolean = false,
        rotationsUsed: Int = 0,
    ): BootstrapResilience {
        every { interfaceRepository.allInterfaces } returns flowOf(existing)
        every { interfaceRepository.allInterfaceEntities } returns flowOf(entitiesFor(existing))
        coEvery { interfaceRepository.insertInterface(capture(inserted)) } returns 1L
        coEvery {
            interfaceRepository.toggleInterfaceEnabled(capture(toggledIds), capture(toggledStates))
        } answers {}
        coEvery { settingsRepository.getBootstrapResilienceVersion() } returns appliedVersion
        coEvery { settingsRepository.saveBootstrapResilienceVersion(capture(savedVersions)) } answers {}
        coEvery { settingsRepository.hasDiscoverInterfacesPreference() } returns hasDiscoveryPreference
        coEvery { settingsRepository.saveDiscoverInterfacesEnabled(capture(savedDiscovery)) } answers {}
        coEvery { settingsRepository.getBootstrapRotationsUsed() } returns rotationsUsed
        coEvery { settingsRepository.saveBootstrapRotationsUsed(capture(savedRotations)) } answers {}
        return BootstrapResilience(interfaceRepository, settingsRepository)
    }

    /** Rows carrying the ids the repair looks names up by. */
    private fun entitiesFor(configs: List<InterfaceConfig>): List<InterfaceEntity> =
        configs.mapIndexed { index, config ->
            InterfaceEntity(
                id = (index + 1).toLong(),
                name = config.name,
                type = "TCPClient",
                enabled = config.enabled,
                configJson = "{}",
            )
        }

    /** A hub as the seeding and the rotation both create them. */
    private fun bootstrapHub(
        name: String,
        host: String,
        port: Int,
        enabled: Boolean,
    ) = InterfaceConfig.TCPClient(
        name = name,
        enabled = enabled,
        targetHost = host,
        targetPort = port,
        bootstrapOnly = true,
    )

    private fun tcpClient(
        host: String,
        port: Int,
    ) = InterfaceConfig.TCPClient(name = "existing", targetHost = host, targetPort = port)

    @Test
    fun `a fresh install gets every bootstrap hub, not one`() =
        runTest {
            val outcome = resilience().applyOnce()

            val expected = TcpCommunityServers.bootstrapServers.map { it.name }
            assertEquals(expected, outcome.hubsAdded)
            assertEquals(expected, inserted.map { it.name })
            assertTrue("more than one seed is the whole point", expected.size > 1)
        }

    @Test
    fun `every seeded hub is marked bootstrap_only so RNS can detach it later`() =
        runTest {
            resilience().applyOnce()

            val clients = inserted.filterIsInstance<InterfaceConfig.TCPClient>()
            assertEquals(inserted.size, clients.size)
            assertTrue(clients.all { it.bootstrapOnly })
            assertTrue(clients.all { it.enabled })
        }

    @Test
    fun `a hub the install already has is not added twice`() =
        runTest {
            val already = TcpCommunityServers.bootstrapServers.first()
            val outcome = resilience(existing = listOf(tcpClient(already.host, already.port))).applyOnce()

            assertFalse(outcome.hubsAdded.contains(already.name))
            assertEquals(TcpCommunityServers.bootstrapServers.size - 1, outcome.hubsAdded.size)
        }

    @Test
    fun `an existing hub is matched on endpoint, not on the name the user may have changed`() =
        runTest {
            val already = TcpCommunityServers.bootstrapServers.first()
            val renamedAndUpperCased =
                InterfaceConfig.TCPClient(
                    name = "My Own Name For It",
                    targetHost = already.host.uppercase(),
                    targetPort = already.port,
                )

            val outcome = resilience(existing = listOf(renamedAndUpperCased)).applyOnce()

            assertFalse(outcome.hubsAdded.contains(already.name))
        }

    @Test
    fun `a hub on the same host but a different port is a different route`() =
        runTest {
            val already = TcpCommunityServers.bootstrapServers.first()
            val outcome = resilience(existing = listOf(tcpClient(already.host, already.port + 1))).applyOnce()

            assertTrue(outcome.hubsAdded.contains(already.name))
        }

    @Test
    fun `discovery is turned on when the user has never chosen`() =
        runTest {
            val outcome = resilience(hasDiscoveryPreference = false).applyOnce()

            assertTrue(outcome.discoveryEnabled)
            assertEquals(listOf(true), savedDiscovery)
        }

    @Test
    fun `discovery the user switched off stays off`() =
        runTest {
            val outcome = resilience(hasDiscoveryPreference = true).applyOnce()

            assertFalse(outcome.discoveryEnabled)
            assertTrue("must not overwrite a deliberate choice", savedDiscovery.isEmpty())
        }

    @Test
    fun `the repair records its version so it does not run again`() =
        runTest {
            resilience().applyOnce()

            assertEquals(listOf(BootstrapResilience.CURRENT_VERSION), savedVersions)
        }

    @Test
    fun `a hub the user deleted is not resurrected on the next launch`() =
        runTest {
            val outcome = resilience(appliedVersion = BootstrapResilience.CURRENT_VERSION).applyOnce()

            assertTrue(outcome.alreadyApplied)
            assertTrue(inserted.isEmpty())
            assertTrue(savedDiscovery.isEmpty())
        }

    @Test
    fun `the default autoconnect count leaves room for more than one discovered hub`() {
        assertTrue(
            "a single auto-connected hub reintroduces the single point of failure",
            BootstrapResilience.DEFAULT_AUTOCONNECT_DISCOVERED > 1,
        )
    }

    // --- v2: undoing what the hub rotation did ---------------------------------------

    @Test
    fun `an install that never rotated is left exactly as it was`() =
        runTest {
            val seed = TcpCommunityServers.bootstrapServers.first()
            resilience(
                existing = listOf(bootstrapHub(seed.name, seed.host, seed.port, enabled = false)),
                rotationsUsed = 0,
            ).applyOnce()

            assertTrue("a hub the user switched off is theirs to switch off", toggledIds.isEmpty())
            assertTrue("nothing to give back", savedRotations.isEmpty())
        }

    @Test
    fun `the seeds a rotation switched off are switched back on`() =
        runTest {
            val seeds = TcpCommunityServers.bootstrapServers
            val outcome =
                resilience(
                    existing = seeds.map { bootstrapHub(it.name, it.host, it.port, enabled = false) },
                    rotationsUsed = 3,
                ).applyOnce()

            assertEquals(seeds.map { it.name }, outcome.seedsRestored)
            assertTrue("every toggle here turns something on", toggledStates.all { it })
            assertEquals(seeds.size, toggledIds.size)
        }

    @Test
    fun `a hub no longer on the community list is switched off`() =
        runTest {
            val outcome =
                resilience(
                    existing = listOf(bootstrapHub("FireZen", "firezen.com", 4242, enabled = true)),
                    rotationsUsed = 2,
                ).applyOnce()

            assertEquals(listOf("FireZen"), outcome.deadHubsRetired)
            assertEquals(listOf(false), toggledStates)
        }

    @Test
    fun `a hub still on the list is left enabled`() =
        runTest {
            val alive = TcpCommunityServers.servers.first { !it.isBootstrap && !it.host.endsWith(".onion") }
            val outcome =
                resilience(
                    existing = listOf(bootstrapHub(alive.name, alive.host, alive.port, enabled = true)),
                    rotationsUsed = 1,
                ).applyOnce()

            assertTrue("it answers, so it stays", outcome.deadHubsRetired.isEmpty())
            assertTrue(toggledIds.isEmpty())
        }

    @Test
    fun `an interface the user added themselves is never touched`() =
        runTest {
            val outcome =
                resilience(
                    existing = listOf(tcpClient("my.own.hub", 4242)),
                    rotationsUsed = 3,
                ).applyOnce()

            assertTrue("not bootstrap_only, so not ours to retire", outcome.deadHubsRetired.isEmpty())
            assertTrue(toggledIds.isEmpty())
        }

    @Test
    fun `the rotation budget is returned so a genuinely bad hub can still be replaced`() =
        runTest {
            val outcome = resilience(rotationsUsed = 3).applyOnce()

            assertTrue(outcome.rotationBudgetReset)
            assertEquals(listOf(0), savedRotations)
        }
}
