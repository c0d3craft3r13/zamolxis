package network.zamolxis.app.viewmodel

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.zamolxis.app.data.db.dao.GroupMessageAggregate
import network.zamolxis.app.data.db.dao.GroupMessageDao
import network.zamolxis.app.data.db.entity.ContactStatus
import network.zamolxis.app.data.db.entity.GroupEntity
import network.zamolxis.app.data.db.entity.GroupMemberEntity
import network.zamolxis.app.data.db.entity.GroupMessageEntity
import network.zamolxis.app.data.db.entity.LocalIdentityEntity
import network.zamolxis.app.data.model.EnrichedContact
import network.zamolxis.app.data.model.GroupRole
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.data.repository.GroupRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.notifications.NotificationHelper
import network.zamolxis.app.service.ActiveConversationManager
import network.zamolxis.app.service.group.GroupChatManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the mapping the group screens actually depend on: sender/member names
 * resolved from contacts, the per-message fan-out rollup folded into the glyph
 * tokens the shared status indicator understands, and the "who am I" flag on
 * the member list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val groupId = "ee".repeat(16)
    private val myHash = "bb".repeat(16)
    private val memberA = "cc".repeat(16)

    private lateinit var groupRepository: GroupRepository
    private lateinit var groupChatManager: GroupChatManager
    private lateinit var contactRepository: ContactRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var groupMessageDao: GroupMessageDao
    private lateinit var activeConversationManager: ActiveConversationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var viewModel: GroupChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        groupRepository = mockk()
        groupChatManager = mockk()
        contactRepository = mockk()
        identityRepository = mockk()
        groupMessageDao = mockk()
        activeConversationManager = mockk()
        notificationHelper = mockk()

        every { contactRepository.getEnrichedContacts() } returns flowOf(listOf(contact(memberA, "Alice")))
        every { identityRepository.activeIdentity } returns MutableStateFlow(localIdentity())
        every { groupRepository.observeGroup(groupId) } returns flowOf(group())
        every { groupRepository.observeMembers(groupId) } returns
            flowOf(
                listOf(
                    member(myHash, GroupRole.ADMIN),
                    member(memberA, GroupRole.MEMBER),
                ),
            )
        every { activeConversationManager.setActive(any()) } returns Unit
        every { notificationHelper.cancelNotificationForConversation(any()) } returns Unit
        coEvery { groupRepository.markGroupRead(groupId) } returns Unit

        viewModel =
            GroupChatViewModel(
                groupRepository = groupRepository,
                groupChatManager = groupChatManager,
                contactRepository = contactRepository,
                identityRepository = identityRepository,
                groupMessageDao = groupMessageDao,
                activeConversationManager = activeConversationManager,
                notificationHelper = notificationHelper,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an incoming bubble is labelled with the sender's contact name`() =
        runTest(testDispatcher) {
            every { groupMessageDao.observeMessages(groupId) } returns
                flowOf(listOf(message("m1", memberA, isFromMe = false)))
            every { groupRepository.observeAggregateStatuses(groupId) } returns flowOf(emptyList())

            viewModel.openGroup(groupId)

            viewModel.messages.test {
                advanceUntilIdle()
                val rendered = expectMostRecentItem()
                assertEquals("Alice", rendered.single().senderName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `our own bubble carries no sender label and folds the fan-out rollup into a glyph token`() =
        runTest(testDispatcher) {
            every { groupMessageDao.observeMessages(groupId) } returns
                flowOf(listOf(message("m1", myHash, isFromMe = true)))
            every { groupRepository.observeAggregateStatuses(groupId) } returns
                flowOf(listOf(GroupMessageAggregate(msgId = "m1", status = "DELIVERED")))

            viewModel.openGroup(groupId)

            viewModel.messages.test {
                advanceUntilIdle()
                val rendered = expectMostRecentItem()
                val bubble = rendered.single()
                assertEquals(null, bubble.senderName)
                assertEquals("delivered", bubble.aggregateStatus)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a message with no status rows yet reads as pending`() =
        runTest(testDispatcher) {
            every { groupMessageDao.observeMessages(groupId) } returns
                flowOf(listOf(message("m1", myHash, isFromMe = true)))
            every { groupRepository.observeAggregateStatuses(groupId) } returns
                flowOf(listOf(GroupMessageAggregate(msgId = "m1", status = "PENDING")))

            viewModel.openGroup(groupId)

            viewModel.messages.test {
                advanceUntilIdle()
                val rendered = expectMostRecentItem()
                assertEquals("pending", rendered.single().aggregateStatus)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the member list marks us and falls back to a truncated hash for strangers`() =
        runTest(testDispatcher) {
            every { groupMessageDao.observeMessages(groupId) } returns flowOf(emptyList())
            every { groupRepository.observeAggregateStatuses(groupId) } returns flowOf(emptyList())

            viewModel.openGroup(groupId)

            viewModel.members.test {
                advanceUntilIdle()
                val rendered = expectMostRecentItem()
                val me = rendered.single { it.memberHash == myHash }
                val alice = rendered.single { it.memberHash == memberA }
                assertTrue(me.isMe)
                assertEquals(GroupRole.ADMIN, me.role)
                // No contact row for ourselves, so the label degrades to a hash prefix.
                assertEquals(myHash.take(16), me.displayName)
                assertEquals("Alice", alice.displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `only reachable contacts are offered as group members`() =
        runTest(testDispatcher) {
            every { contactRepository.getEnrichedContacts() } returns
                flowOf(
                    listOf(
                        contact(memberA, "Alice"),
                        contact("dd".repeat(16), "No key", publicKey = null),
                        contact("ff".repeat(16), "Unresolved", status = ContactStatus.PENDING_IDENTITY),
                    ),
                )

            viewModel.sendableContacts.test {
                advanceUntilIdle()
                val rendered = expectMostRecentItem()
                assertEquals(listOf("Alice"), rendered.map { it.displayName })
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---------------------------------------------------------------- fixtures

    private fun group() =
        GroupEntity(
            groupId = groupId,
            identityHash = "aa".repeat(16),
            name = "Test group",
            createdBy = myHash,
            createdAt = 1L,
        )

    private fun member(
        hash: String,
        role: GroupRole,
    ) = GroupMemberEntity(groupId = groupId, memberHash = hash, role = role.name, addedAt = 1L)

    private fun message(
        msgId: String,
        senderHash: String,
        isFromMe: Boolean,
    ) = GroupMessageEntity(
        groupId = groupId,
        msgId = msgId,
        senderHash = senderHash,
        content = "hello",
        timestamp = 10L,
        receivedAt = 10L,
        isFromMe = isFromMe,
    )

    private fun localIdentity() =
        LocalIdentityEntity(
            identityHash = "aa".repeat(16),
            displayName = "me",
            destinationHash = myHash,
            filePath = "/dev/null",
            createdTimestamp = 1L,
            lastUsedTimestamp = 1L,
            isActive = true,
        )

    private fun contact(
        hash: String,
        name: String,
        publicKey: ByteArray? = ByteArray(64),
        status: ContactStatus = ContactStatus.ACTIVE,
    ) = EnrichedContact(
        destinationHash = hash,
        publicKey = publicKey,
        displayName = name,
        customNickname = null,
        announceName = name,
        lastSeenTimestamp = null,
        hops = null,
        isOnline = false,
        hasConversation = false,
        unreadCount = 0,
        lastMessageTimestamp = null,
        notes = null,
        tags = null,
        addedTimestamp = 1L,
        addedVia = "MANUAL",
        isPinned = false,
        status = status,
    )
}
