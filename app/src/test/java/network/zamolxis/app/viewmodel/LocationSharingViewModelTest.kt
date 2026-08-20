package network.zamolxis.app.viewmodel

import android.app.Application
import network.zamolxis.app.data.model.EnrichedContact
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.data.repository.ReceivedLocationRepository
import network.zamolxis.app.service.LocationSharingManager
import network.zamolxis.app.service.SharingEvent
import network.zamolxis.app.service.SharingSession
import network.zamolxis.app.ui.model.LocationSharingState
import network.zamolxis.app.ui.model.SharingDuration
import io.mockk.Runs
import io.mockk.clearAllMocks
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for LocationSharingViewModel.
 *
 * Tests the location sharing state computation and action delegation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationSharingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockLocationSharingManager: LocationSharingManager
    private lateinit var mockContactRepository: ContactRepository
    private lateinit var mockReceivedLocationRepository: ReceivedLocationRepository
    private lateinit var viewModel: LocationSharingViewModel

    // Flows for mocking
    private lateinit var activeSessionsFlow: MutableStateFlow<List<SharingSession>>
    private lateinit var isSharingFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockLocationSharingManager = mockk()
        mockContactRepository = mockk()
        mockReceivedLocationRepository = mockk()

        // Initialize flows
        activeSessionsFlow = MutableStateFlow(emptyList())
        isSharingFlow = MutableStateFlow(false)

        // Mock manager flows
        every { mockLocationSharingManager.activeSessions } returns activeSessionsFlow
        every { mockLocationSharingManager.isSharing } returns isSharingFlow

        // Mock manager actions
        every { mockLocationSharingManager.startSharing(any(), any(), any()) } just Runs
        every { mockLocationSharingManager.stopSharing(any()) } just Runs

        // Mock repository - default empty contacts
        every { mockContactRepository.getEnrichedContacts() } returns flowOf(emptyList())

        every { mockLocationSharingManager.sharingEvents } returns MutableSharedFlow()
        every { mockReceivedLocationRepository.observeHasLocation(any()) } returns flowOf(false)

        viewModel =
            LocationSharingViewModel(
                context = ApplicationProvider.getApplicationContext(),
                locationSharingManager = mockLocationSharingManager,
                contactRepository = mockContactRepository,
                receivedLocationRepository = mockReceivedLocationRepository,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== setCurrentPeer Tests ==========

    @Test
    fun `setCurrentPeer updates currentPeerHash`() {
        val testHash = "abc123def456"

        viewModel.setCurrentPeer(testHash)

        assertEquals(testHash, viewModel.currentPeerHash.value)
    }

    @Test
    fun `setCurrentPeer with null clears currentPeerHash`() {
        viewModel.setCurrentPeer("abc123")
        viewModel.setCurrentPeer(null)

        assertNull(viewModel.currentPeerHash.value)
    }

    // ========== locationSharingState Tests ==========

    @Test
    fun `locationSharingState is NONE when no peer set`() =
        runTest {
            // No peer set - initial value should be NONE
            assertEquals(LocationSharingState.NONE, viewModel.locationSharingState.value)
        }

    @Test
    fun `locationSharingState is NONE when not sharing in either direction`() =
        runTest {
            val peerHash = "abc123"
            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.NONE, viewModel.locationSharingState.value)
            job.cancel()
        }

    @Test
    fun `locationSharingState is SHARING_WITH_THEM when we share but they dont`() =
        runTest {
            val peerHash = "abc123"

            // We have an active session with this peer
            activeSessionsFlow.value = listOf(createSharingSession(peerHash))

            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.SHARING_WITH_THEM, viewModel.locationSharingState.value)
            job.cancel()
        }

    @Test
    fun `locationSharingState is THEY_SHARE_WITH_ME when they share but we dont`() =
        runTest {
            val peerHash = "abc123"

            // They are sharing with us (contact has isReceivingLocationFrom = true)
            val contact = createEnrichedContact(peerHash, isReceivingLocationFrom = true)
            every { mockContactRepository.getEnrichedContacts() } returns flowOf(listOf(contact))

            // Recreate viewModel with updated mock
            viewModel = LocationSharingViewModel(
                    context = ApplicationProvider.getApplicationContext(),
                    locationSharingManager = mockLocationSharingManager,
                    contactRepository = mockContactRepository,
                    receivedLocationRepository = mockReceivedLocationRepository,
                )

            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.THEY_SHARE_WITH_ME, viewModel.locationSharingState.value)
            job.cancel()
        }

    @Test
    fun `locationSharingState is MUTUAL when both sharing`() =
        runTest {
            val peerHash = "abc123"

            // We have an active session with this peer
            activeSessionsFlow.value = listOf(createSharingSession(peerHash))

            // They are also sharing with us
            val contact = createEnrichedContact(peerHash, isReceivingLocationFrom = true)
            every { mockContactRepository.getEnrichedContacts() } returns flowOf(listOf(contact))

            // Recreate viewModel with updated mock
            viewModel = LocationSharingViewModel(
                    context = ApplicationProvider.getApplicationContext(),
                    locationSharingManager = mockLocationSharingManager,
                    contactRepository = mockContactRepository,
                    receivedLocationRepository = mockReceivedLocationRepository,
                )

            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.MUTUAL, viewModel.locationSharingState.value)
            job.cancel()
        }

    // ========== startSharing Tests ==========

    @Test
    fun `startSharing delegates to manager with correct parameters`() {
        val peerHash = "abc123"
        val peerName = "Alice"
        val duration = SharingDuration.ONE_HOUR

        viewModel.setCurrentPeer(peerHash)
        assertEquals(peerHash, viewModel.currentPeerHash.value)

        viewModel.startSharing(peerName, duration)

        verify {
            mockLocationSharingManager.startSharing(
                contactHashes = listOf(peerHash),
                displayNames = mapOf(peerHash to peerName),
                duration = duration,
            )
        }
    }

    @Test
    fun `startSharing does nothing when no peer set`() {
        // Verify precondition
        assertNull(viewModel.currentPeerHash.value)

        viewModel.startSharing("Alice", SharingDuration.ONE_HOUR)

        verify(exactly = 0) { mockLocationSharingManager.startSharing(any(), any(), any()) }
    }

    // ========== stopSharing Tests ==========

    @Test
    fun `stopSharing delegates to manager with current peer`() {
        val peerHash = "abc123"

        viewModel.setCurrentPeer(peerHash)
        assertEquals(peerHash, viewModel.currentPeerHash.value)

        viewModel.stopSharing()

        verify { mockLocationSharingManager.stopSharing(peerHash) }
    }

    @Test
    fun `stopSharing does nothing when no peer set`() {
        // Verify precondition
        assertNull(viewModel.currentPeerHash.value)

        viewModel.stopSharing()

        verify(exactly = 0) { mockLocationSharingManager.stopSharing(any()) }
    }

    // ========== startSharingWith Tests ==========

    @Test
    fun `startSharingWith delegates to manager without setting current peer`() {
        val peerHash = "abc123"
        val peerName = "Bob"
        val duration = SharingDuration.FOUR_HOURS

        // Verify currentPeerHash is null before
        assertNull(viewModel.currentPeerHash.value)

        viewModel.startSharingWith(peerHash, peerName, duration)

        // Should delegate to manager
        verify {
            mockLocationSharingManager.startSharing(
                contactHashes = listOf(peerHash),
                displayNames = mapOf(peerHash to peerName),
                duration = duration,
            )
        }

        // Should NOT set current peer
        assertNull(viewModel.currentPeerHash.value)
    }

    // ========== stopSharingWith Tests ==========

    @Test
    fun `stopSharingWith delegates to manager`() {
        val peerHash = "abc123"

        // Verify currentPeerHash is unchanged (null)
        assertNull(viewModel.currentPeerHash.value)

        viewModel.stopSharingWith(peerHash)

        verify { mockLocationSharingManager.stopSharing(peerHash) }

        // Should still NOT have a current peer
        assertNull(viewModel.currentPeerHash.value)
    }

    // ========== isSharing Tests ==========

    // ========== Moved from MessagingViewModel ==========

    @Test
    fun `a blocked share is reported to the user`() =
        runTest {
            // The master toggle lives in Settings, so a share refused there is
            // invisible on the conversation screen: without this the tap looks like
            // it worked and nothing happens.
            val events = MutableSharedFlow<SharingEvent>(extraBufferCapacity = 4)
            every { mockLocationSharingManager.sharingEvents } returns events
            val vm =
                LocationSharingViewModel(
                    context = ApplicationProvider.getApplicationContext(),
                    locationSharingManager = mockLocationSharingManager,
                    contactRepository = mockContactRepository,
                    receivedLocationRepository = mockReceivedLocationRepository,
                )

            // Collected on Main, which this test sets to an unconfined dispatcher, so
            // the subscription is live before the event is emitted. sharingMessage
            // has no replay: a late collector would simply miss it.
            val seen = mutableListOf<String>()
            val job = backgroundScope.launch(Dispatchers.Main) { vm.sharingMessage.collect { seen += it } }

            events.emit(SharingEvent.Blocked)
            testDispatcher.scheduler.advanceUntilIdle()
            job.cancel()

            assertEquals(1, seen.size)
            assertTrue("message should point at Settings", seen.first().contains("Settings"))
        }

    @Test
    fun `hasContactLocation follows the current peer`() =
        runTest {
            every { mockReceivedLocationRepository.observeHasLocation("peer-with") } returns flowOf(true)
            every { mockReceivedLocationRepository.observeHasLocation("peer-without") } returns flowOf(false)

            // WhileSubscribed: the flow only tracks the peer while something collects it.
            val job = backgroundScope.launch { viewModel.hasContactLocation.collect { } }

            viewModel.setCurrentPeer("peer-with")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(true, viewModel.hasContactLocation.value)

            viewModel.setCurrentPeer("peer-without")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(false, viewModel.hasContactLocation.value)
            job.cancel()
        }

    @Test
    fun `hasContactLocation is false with no peer`() =
        runTest {
            val job = backgroundScope.launch { viewModel.hasContactLocation.collect { } }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(false, viewModel.hasContactLocation.value)
            job.cancel()
        }

    @Test
    fun `isSharing exposes manager isSharing state`() {
        assertEquals(isSharingFlow, viewModel.isSharing)
    }

    @Test
    fun `isSharing reflects manager state changes`() =
        runTest {
            assertEquals(false, viewModel.isSharing.value)

            isSharingFlow.value = true

            assertEquals(true, viewModel.isSharing.value)
        }

    // ========== Helper Functions ==========

    private fun createSharingSession(destinationHash: String): SharingSession =
        SharingSession(
            destinationHash = destinationHash,
            displayName = "Test User",
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 3600_000,
        )

    private fun createEnrichedContact(
        destinationHash: String,
        isReceivingLocationFrom: Boolean = false,
    ): EnrichedContact =
        EnrichedContact(
            destinationHash = destinationHash,
            publicKey = null,
            displayName = "Test User",
            customNickname = null,
            announceName = null,
            lastSeenTimestamp = null,
            hops = null,
            isOnline = false,
            hasConversation = false,
            unreadCount = 0,
            lastMessageTimestamp = null,
            notes = null,
            tags = null,
            addedTimestamp = System.currentTimeMillis(),
            addedVia = "MANUAL",
            isPinned = false,
            isReceivingLocationFrom = isReceivingLocationFrom,
        )
}
