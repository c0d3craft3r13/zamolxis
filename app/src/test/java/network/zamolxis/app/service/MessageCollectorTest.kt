package network.zamolxis.app.service

import network.zamolxis.app.data.db.dao.PeerIconDao
import network.zamolxis.app.data.model.PqProtection
import network.zamolxis.app.data.repository.AnnounceRepository
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.data.repository.ConversationRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.data.repository.Message
import network.zamolxis.app.notifications.NotificationHelper
import network.zamolxis.app.rns.api.RnsCore
import network.zamolxis.app.rns.api.RnsLxmf
import network.zamolxis.app.rns.api.model.ReceivedMessage
import network.zamolxis.app.rns.api.model.SenderVerification
import network.zamolxis.app.service.group.GroupChatManager
import network.zamolxis.app.service.pq.PqMessageSealer
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MessageCollector.
 * Tests notification behavior for messages that were persisted by ServicePersistenceManager.
 *
 * Note: MessageCollector no longer persists messages itself - all persistence happens in
 * ServicePersistenceManager which enforces privacy settings like "block unknown senders".
 * MessageCollector only shows notifications for messages that exist in the database.
 */
class MessageCollectorTest {
    private lateinit var rnsCore: RnsCore
    private lateinit var rnsLxmf: RnsLxmf
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var peerIconDao: PeerIconDao
    private lateinit var pqMessageSealer: PqMessageSealer
    private lateinit var groupChatManager: GroupChatManager
    private lateinit var messageCollector: MessageCollector

    // Use extraBufferCapacity to ensure emissions aren't dropped before collector is ready
    private lateinit var messageFlow: MutableSharedFlow<ReceivedMessage>

    private val testSourceHash = ByteArray(16) { it.toByte() }
    private val testDestHash = ByteArray(16) { (it + 16).toByte() }
    private val testSourceHashHex = testSourceHash.joinToString("") { "%02x".format(it) }

    /** Senders the user was actually told about, in arrival order. */
    private val notifiedSenders = mutableListOf<String>()

    /** Identities the collector pushed back into the stack. */
    private val restoredIdentities = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        rnsCore = mockk()
        rnsLxmf = mockk()
        conversationRepository = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        identityRepository = mockk()
        notificationHelper = mockk()
        peerIconDao = mockk()
        pqMessageSealer = mockk()
        // Group routing is covered by GroupChatManagerTest; here the manager is
        // a pass-through so the existing message-path expectations stay unchanged.
        groupChatManager = mockk()
        coEvery { groupChatManager.handleIncoming(any(), any(), any(), any()) } just Runs
        // Stubbed to the behaviour these tests already assumed: messages arrive with
        // their content unchanged and nothing is sealed, so the existing expectations
        // still describe what is being tested. arg(3) is fallbackContent — the
        // parameter list gained ourDestinationHash ahead of it when the AAD binding
        // was introduced.
        coEvery {
            pqMessageSealer.processIncoming(any(), any(), any(), any(), any(), any())
        } answers {
            PqMessageSealer.Incoming(content = arg(3), protection = PqProtection.NONE)
        }

        // Explicit stubs for notificationHelper (suspend function)
        coEvery { notificationHelper.notifyMessageReceived(any(), any(), any(), any(), any()) } answers {
            notifiedSenders += firstArg<String>()
        }

        // Explicit stubs for peerIconDao
        coEvery { peerIconDao.getIcon(any()) } returns null

        messageFlow = MutableSharedFlow(extraBufferCapacity = 10)

        // Mock protocol flows
        every { rnsLxmf.observeMessages() } returns messageFlow
        every { rnsCore.observeAnnounces() } returns flowOf() // Empty flow for announces

        // Mock conversation repository default behaviors
        // Note: getMessageById is no longer called - MessageCollector trusts broadcasts
        coEvery { conversationRepository.getPeerPublicKey(any()) } returns null
        coEvery { conversationRepository.updatePeerPublicKey(any(), any()) } just Runs
        coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs
        coEvery { conversationRepository.getConversation(any()) } returns null
        coEvery { conversationRepository.updatePeerName(any(), any()) } just Runs
        coEvery { conversationRepository.getMessageById(any()) } returns null

        // Mock announce repository
        coEvery { announceRepository.getAnnounce(any()) } returns null

        // Nobody is a saved contact unless a test says so. The collector asks this
        // of every inbound message, to catch a sender whose signature it should
        // have been able to check and was not — see `mayAttribute`.
        coEvery { contactRepository.getContact(any()) } returns null

        // Recorded rather than only verified, so the attribution tests can assert
        // on what actually reached the user instead of on a call having happened.
        notifiedSenders.clear()
        restoredIdentities.clear()
        coEvery { rnsCore.restorePeerIdentities(any()) } answers {
            restoredIdentities += firstArg<List<Pair<String, ByteArray>>>()
            Result.success(1)
        }

        // Mock getReceivedMessageIds for pre-seeding (empty by default)
        coEvery { conversationRepository.getReceivedMessageIds(since = any()) } returns emptyList()

        // Mock identity repository - return a mock active identity matching test destination
        coEvery { identityRepository.getActiveIdentitySync() } returns
            mockk {
                every { destinationHash } returns testDestHash.joinToString("") { "%02x".format(it) }
                // The post-quantum layer needs both: the identity hash to find our key
                // pair, and the destination hash because that is the half of the AAD
                // the sender could reconstruct.
                every { identityHash } returns "test-identity"
            }

        messageCollector =
            MessageCollector(
                rnsCore = rnsCore,
                rnsLxmf = rnsLxmf,
                conversationRepository = conversationRepository,
                announceRepository = announceRepository,
                contactRepository = contactRepository,
                identityRepository = identityRepository,
                notificationHelper = notificationHelper,
                peerIconDao = peerIconDao,
                pqMessageSealer = pqMessageSealer,
                // No announced fingerprints: these tests cover ordinary announce and
                // message handling, not post-quantum capability discovery.
                pqKeyRepository =
                    mockk<network.zamolxis.app.data.repository.PqKeyRepository>().also {
                        coEvery { it.recordAnnouncedFingerprint(any(), any()) } just Runs
                    },
                groupChatManager = groupChatManager,
            )
    }

    @After
    fun tearDown() {
        messageCollector.stopCollecting()
        clearAllMocks()
    }

    // ========== De-duplication Tests ==========
    // Note: Blocking tests are handled at the EventHandler/ServicePersistenceManager level.
    // MessageCollector only receives broadcasts for messages that were already persisted.

    @Test
    fun `processMessage shows notification for broadcast message`() =
        runBlocking {
            // Given: A message broadcast from EventHandler (already persisted by service)
            val testMessage =
                ReceivedMessage(
                    messageHash = "persisted_message",
                    content = "This was persisted",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // Mock that message already exists in database (persisted by ServicePersistenceManager)
            // isRead = false means the user hasn't seen it yet, so notification should fire
            coEvery { conversationRepository.getMessageById("persisted_message") } returns
                mockk {
                    every { isRead } returns false
                    // Not sealed: this row is a finished message, so the collector
                    // treats it as a duplicate rather than unfinished work.
                    every { pqStatus } returns null
                    // The notification preview now comes from the stored row rather
                    // than the wire message, so a sealed duplicate shows its real
                    // text instead of an empty content slot.
                    every { content } returns "This was persisted"
                }

            // When: Start collecting and emit message
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)

            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should be shown
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = any(),
                    isUnreadable = any(),
                )
            }

            // And: No persistence should be attempted (service already persisted)
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(
                    peerHash = any(),
                    peerName = any(),
                    message = any(),
                    peerPublicKey = any(),
                )
            }
        }

    @Test
    fun `a row the service could not open is completed rather than treated as a duplicate`() =
        runBlocking {
            // The service process persists what arrives. It holds no hybrid key
            // material, so a sealed message lands there as ciphertext with an empty
            // content slot and pqStatus = UNOPENED. If the collector treated that as
            // a duplicate, the user would be left with a permanently blank message —
            // which is what happened before this path existed.
            val sealedMessage =
                ReceivedMessage(
                    messageHash = "sealed_message",
                    content = "",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = """{"81": "deadbeef"}""",
                    publicKey = null,
                )

            coEvery { conversationRepository.getMessageById("sealed_message") } returns
                mockk {
                    every { isRead } returns false
                    every { content } returns ""
                    every { pqStatus } returns "UNOPENED"
                }
            coEvery {
                pqMessageSealer.processIncoming(any(), any(), any(), any(), any(), any())
            } returns
                PqMessageSealer.Incoming(
                    content = "opened at last",
                    protection = PqProtection.SEALED,
                )

            val savedMessage = slot<Message>()
            messageCollector.startCollecting()
            kotlinx.coroutines.delay(50)
            messageFlow.emit(sealedMessage)
            kotlinx.coroutines.delay(200)

            // The row is rewritten with the opened content, not skipped.
            coVerify(timeout = 2000) {
                conversationRepository.saveMessage(
                    peerHash = testSourceHashHex,
                    peerName = any(),
                    message = capture(savedMessage),
                    peerPublicKey = any(),
                )
            }
            assertEquals("opened at last", savedMessage.captured.content)
        }

    @Test
    fun `processMessage skips in-memory duplicate`() =
        runBlocking {
            // Given: A message broadcast from EventHandler
            val testMessage =
                ReceivedMessage(
                    messageHash = "duplicate_message",
                    content = "Hello world",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting and emit the same message twice
            val result =
                runCatching {
                    messageCollector.startCollecting()
                    kotlinx.coroutines.delay(50)

                    messageFlow.emit(testMessage)
                    kotlinx.coroutines.delay(200)
                }

            // Then: Operation should complete without throwing
            assertTrue("Message emission should complete without throwing", result.isSuccess)

            // First message should trigger notification
            coVerify(exactly = 1, timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = any(),
                    isUnreadable = any(),
                )
            }

            // Emit the same message again
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Second message should be skipped (in-memory cache) - still only 1 notification
            coVerify(exactly = 1, timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = any(),
                    isUnreadable = any(),
                )
            }
        }

    // ========== Lifecycle Tests ==========

    @Test
    fun `stopCollecting clears caches`() =
        runBlocking {
            // Given: Start collecting
            messageCollector.startCollecting()
            kotlinx.coroutines.delay(50)

            // When: Stop collecting
            messageCollector.stopCollecting()

            // Then: getStats should show cleared state
            val stats = messageCollector.getStats()
            assert(stats.contains("Known peers: 0"))
        }

    @Test
    fun `startCollecting is idempotent`() =
        runBlocking {
            // Given: Already started
            messageCollector.startCollecting()

            // When: Start again
            messageCollector.startCollecting()

            // Then: No exception, single collection running
            // This is primarily testing no crash occurs
        }

    // ========== Peer Name Tests ==========

    @Test
    fun `updatePeerName caches peer name`() =
        runBlocking {
            val peerHash = "test_peer_hash"

            // When
            val result = runCatching { messageCollector.updatePeerName(peerHash, "New Name") }

            // Wait for database update
            kotlinx.coroutines.delay(100)

            // Then: Function completed and database should be updated
            assertTrue("updatePeerName should complete without throwing", result.isSuccess)
            coVerify(timeout = 1000) {
                conversationRepository.updatePeerName(peerHash, "New Name")
            }
        }

    @Test
    fun `updatePeerName ignores blank names`() =
        runBlocking {
            val peerHash = "test_peer_hash"

            // When
            val result = runCatching { messageCollector.updatePeerName(peerHash, "") }

            // Wait
            kotlinx.coroutines.delay(100)

            // Then: Function completed and database should NOT be updated
            assertTrue("updatePeerName should complete without throwing", result.isSuccess)
            coVerify(exactly = 0) {
                conversationRepository.updatePeerName(any(), any())
            }
        }

    // ========== Pre-seed Dedup Tests ==========

    @Test
    fun `pre-seeded message IDs prevent duplicate notifications on restart`() =
        runBlocking {
            // Given: A message that was already in the DB from a previous session
            coEvery { conversationRepository.getReceivedMessageIds(since = any()) } returns listOf("already_notified_msg")

            val testMessage =
                ReceivedMessage(
                    messageHash = "already_notified_msg",
                    content = "Old message replayed",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting (pre-seeds IDs) and replay the message
            messageCollector.startCollecting()
            kotlinx.coroutines.delay(100)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Message was skipped entirely - counter didn't increment
            assertEquals(0, messageCollector.messagesCollected.value)

            // And no notification was shown (message was pre-seeded as already processed)
            coVerify(exactly = 0) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = any(),
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = any(),
                    isUnreadable = any(),
                )
            }
        }

    // ========== Notification for Pre-Persisted Messages Tests ==========

    @Test
    fun `processMessage uses favorite status from announce for notification`() =
        runBlocking {
            // Given: A message from a favorite peer
            val testMessage =
                ReceivedMessage(
                    messageHash = "favorite_msg",
                    content = "Message from favorite",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // Peer is a favorite
            coEvery { announceRepository.getAnnounce(testSourceHashHex) } returns
                mockk {
                    every { isFavorite } returns true
                }

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should be posted with isFavorite = true
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = true,
                    isUnreadable = any(),
                )
            }
        }

    @Test
    fun `processMessage handles announce lookup failure gracefully for notifications`() =
        runBlocking {
            // Given: A message where announce lookup fails
            val testMessage =
                ReceivedMessage(
                    messageHash = "announce_error_msg",
                    content = "Announce lookup will fail",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // Announce lookup throws exception
            coEvery { announceRepository.getAnnounce(testSourceHashHex) } throws RuntimeException("DB error")

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should still be posted with isFavorite = false (fail-safe default)
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = false,
                    isUnreadable = any(),
                )
            }
        }

    @Test
    fun `processMessage uses cached peer name for notification`() =
        runBlocking {
            // Given: Update peer name cache first
            messageCollector.updatePeerName(testSourceHashHex, "Cached Peer Name")
            kotlinx.coroutines.delay(100)

            val testMessage =
                ReceivedMessage(
                    messageHash = "cached_name_msg",
                    content = "Test with cached name",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should use the cached peer name
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = "Cached Peer Name",
                    messagePreview = any(),
                    isFavorite = any(),
                    isUnreadable = any(),
                )
            }
        }

    @Test
    fun `processMessage truncates long message preview for notification`() =
        runBlocking {
            // Given: A message with content longer than 100 characters
            val longContent = "A".repeat(200)
            val testMessage =
                ReceivedMessage(
                    messageHash = "long_content_msg",
                    content = longContent,
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification preview should be truncated to 100 characters
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = "A".repeat(100),
                    isFavorite = any(),
                    isUnreadable = any(),
                )
            }
        }

    // ========== Sender attribution ==========

    /**
     * A saved contact is exactly someone whose messages carry their name in the
     * UI, and whose public key we hold — so their signature is always checkable.
     * A message from that address that nobody could check is therefore either an
     * impersonation or a broken identity cache, and neither may be rendered
     * under the contact's name.
     */
    @Test
    fun `an unverified message from a saved contact is refused`() =
        runBlocking {
            savedContact(publicKey = ByteArray(32) { it.toByte() })

            emitAndSettle(receivedMessage("unverified_from_contact", SenderVerification.SOURCE_UNKNOWN))

            assertEquals("nothing may reach the user under that contact's name", emptyList<String>(), notifiedSenders)
        }

    /** The cache-loss version of that case fixes itself: their next message verifies. */
    @Test
    fun `refusing an unverified contact message puts their key back into the stack`() =
        runBlocking {
            val key = ByteArray(32) { it.toByte() }
            savedContact(publicKey = key)

            emitAndSettle(receivedMessage("unverified_from_contact", SenderVerification.SOURCE_UNKNOWN))

            assertEquals(listOf(testSourceHashHex to key), restoredIdentities)
        }

    @Test
    fun `a verified message from a saved contact is delivered`() =
        runBlocking {
            savedContact(publicKey = ByteArray(32))

            emitAndSettle(receivedMessage("verified_from_contact", SenderVerification.VERIFIED))

            assertEquals(listOf(testSourceHashHex), notifiedSenders)
            assertTrue("a verified sender needs no repair", restoredIdentities.isEmpty())
        }

    /**
     * The case the guard must not break. Someone whose announce we have never
     * heard cannot be verified by anyone, and refusing them would mean no one
     * could ever write to us first.
     */
    @Test
    fun `an unverified message from someone we hold no key for is delivered`() =
        runBlocking {
            emitAndSettle(receivedMessage("unverified_stranger", SenderVerification.SOURCE_UNKNOWN))

            assertEquals(listOf(testSourceHashHex), notifiedSenders)
        }

    /** A contact row with no stored key leaves nothing to have checked against. */
    @Test
    fun `an unverified message from a contact we hold no key for is delivered`() =
        runBlocking {
            savedContact(publicKey = null)

            emitAndSettle(receivedMessage("unverified_keyless_contact", SenderVerification.SOURCE_UNKNOWN))

            assertEquals(listOf(testSourceHashHex), notifiedSenders)
        }

    /** A database that will not answer must not swallow mail. */
    @Test
    fun `a contact lookup failure lets the message through`() =
        runBlocking {
            coEvery { contactRepository.getContact(testSourceHashHex) } throws IllegalStateException("db gone")

            emitAndSettle(receivedMessage("unverified_db_down", SenderVerification.SOURCE_UNKNOWN))

            assertEquals(listOf(testSourceHashHex), notifiedSenders)
        }

    private fun savedContact(publicKey: ByteArray?) {
        coEvery { contactRepository.getContact(testSourceHashHex) } returns
            mockk { every { this@mockk.publicKey } returns publicKey }
    }

    private fun receivedMessage(
        hash: String,
        verification: SenderVerification,
    ): ReceivedMessage {
        // Already stored by the service process, so a delivered message shows a
        // notification and a refused one shows nothing — which is the difference
        // these tests read.
        coEvery { conversationRepository.getMessageById(hash) } returns
            mockk {
                every { isRead } returns false
                every { pqStatus } returns null
                every { content } returns "hello"
            }
        return ReceivedMessage(
            messageHash = hash,
            content = "hello",
            sourceHash = testSourceHash,
            destinationHash = testDestHash,
            timestamp = System.currentTimeMillis(),
            senderVerification = verification,
        )
    }

    private suspend fun emitAndSettle(message: ReceivedMessage) {
        messageCollector.startCollecting()
        kotlinx.coroutines.delay(50)
        messageFlow.emit(message)
        kotlinx.coroutines.delay(400)
    }
}
