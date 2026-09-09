package network.zamolxis.app.rns.host.persistence

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.rns.api.RnsBackend
import network.zamolxis.app.rns.api.RnsCore
import network.zamolxis.app.rns.api.model.NetworkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Loading contact keys into the identity cache is what makes an inbound
 * signature checkable at all, so these tests are about a security precondition
 * rather than about startup performance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerIdentityPrimerTest {
    private val alice = "aa".repeat(16) to ByteArray(32) { 1 }
    private val bob = "bb".repeat(16) to ByteArray(32) { 2 }

    private class Fixture {
        val core = mockk<RnsCore>()
        val backend = mockk<RnsBackend>()
        val contacts = mockk<ContactRepository>()
        val status = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)

        /** Every batch the backend was asked to remember, in order. */
        val loaded = mutableListOf<List<Pair<String, ByteArray>>>()

        init {
            every { backend.core } returns core
            every { core.networkStatus } returns status
            coEvery { core.restorePeerIdentities(any()) } answers {
                val batch = firstArg<List<Pair<String, ByteArray>>>()
                loaded += batch
                Result.success(batch.size)
            }
        }

        fun primer() = PeerIdentityPrimer(contacts)
    }

    @Test
    fun `contact keys are loaded when the stack becomes ready`() =
        runTest {
            val f = Fixture()
            coEvery { f.contacts.getRestorableContactIdentitiesForActiveIdentity() } returns listOf(alice, bob)

            // The watcher collects for as long as the service lives, so the test
            // owns its job and cancels it rather than waiting for it to finish.
            val job = f.primer().start(f.backend, this)
            runCurrent()
            f.status.value = NetworkStatus.READY
            runCurrent()

            assertEquals(listOf(listOf(alice, bob)), f.loaded)
            job.cancel()
            advanceUntilIdle()
        }

    /** Until the stack is up there is nothing to load them into. */
    @Test
    fun `nothing is loaded before the stack is ready`() =
        runTest {
            val f = Fixture()
            coEvery { f.contacts.getRestorableContactIdentitiesForActiveIdentity() } returns listOf(alice)

            val job = f.primer().start(f.backend, this)
            runCurrent()
            f.status.value = NetworkStatus.CONNECTING
            runCurrent()

            assertEquals(emptyList<List<Pair<String, ByteArray>>>(), f.loaded)
            job.cancel()
            advanceUntilIdle()
        }

    /**
     * Interfaces changing restarts the stack, which empties the cache. Priming
     * again is the difference between "verified" and "sender unknown" for every
     * contact afterwards.
     */
    @Test
    fun `a restart loads the keys again`() =
        runTest {
            val f = Fixture()
            coEvery { f.contacts.getRestorableContactIdentitiesForActiveIdentity() } returns listOf(alice)

            val job = f.primer().start(f.backend, this)
            runCurrent()
            f.status.value = NetworkStatus.READY
            runCurrent()
            // Stepped one transition at a time because a StateFlow conflates: the
            // real stack spends real time initializing, and collapsing the two here
            // would test the harness rather than the restart.
            f.status.value = NetworkStatus.INITIALIZING
            runCurrent()
            f.status.value = NetworkStatus.READY
            runCurrent()

            assertEquals(listOf(listOf(alice), listOf(alice)), f.loaded)
            job.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `an empty address book asks the backend for nothing`() =
        runTest {
            val f = Fixture()
            coEvery { f.contacts.getRestorableContactIdentitiesForActiveIdentity() } returns emptyList()

            assertEquals(0, f.primer().prime(f.backend))
            assertEquals("an empty address book asks the backend for nothing", 0, f.loaded.size)
        }

    /**
     * A database that will not answer must not take the service down with it —
     * but it also must not look like a successful prime.
     */
    @Test
    fun `a failing contact store is survivable`() =
        runTest {
            val f = Fixture()
            coEvery { f.contacts.getRestorableContactIdentitiesForActiveIdentity() } throws IllegalStateException("db gone")

            assertEquals(0, f.primer().prime(f.backend))
        }

    /** One rejected batch must not cost every contact after it. */
    @Test
    fun `a rejected batch does not stop the rest`() =
        runTest {
            val f = Fixture()
            coEvery { f.contacts.getRestorableContactIdentitiesForActiveIdentity() } returns listOf(alice)
            coEvery { f.core.restorePeerIdentities(any()) } returns Result.failure(IllegalStateException("stack busy"))

            assertEquals(0, f.primer().prime(f.backend))
        }
}
