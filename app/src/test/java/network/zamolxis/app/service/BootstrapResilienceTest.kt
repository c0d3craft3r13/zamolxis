package network.zamolxis.app.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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

    private fun resilience(
        existing: List<InterfaceConfig> = emptyList(),
        appliedVersion: Int = 0,
        hasDiscoveryPreference: Boolean = false,
    ): BootstrapResilience {
        every { interfaceRepository.allInterfaces } returns flowOf(existing)
        coEvery { interfaceRepository.insertInterface(capture(inserted)) } returns 1L
        coEvery { settingsRepository.getBootstrapResilienceVersion() } returns appliedVersion
        coEvery { settingsRepository.saveBootstrapResilienceVersion(capture(savedVersions)) } answers {}
        coEvery { settingsRepository.hasDiscoverInterfacesPreference() } returns hasDiscoveryPreference
        coEvery { settingsRepository.saveDiscoverInterfacesEnabled(capture(savedDiscovery)) } answers {}
        return BootstrapResilience(interfaceRepository, settingsRepository)
    }

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
}
