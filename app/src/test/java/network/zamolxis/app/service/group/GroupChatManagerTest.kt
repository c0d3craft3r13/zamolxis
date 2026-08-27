package network.zamolxis.app.service.group

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import network.zamolxis.app.data.db.entity.GroupEntity
import network.zamolxis.app.data.db.entity.GroupMemberEntity
import network.zamolxis.app.data.model.GroupMessageStatus
import network.zamolxis.app.data.model.GroupRole
import network.zamolxis.app.data.model.PqProtection
import network.zamolxis.app.data.repository.AnnounceRepository
import network.zamolxis.app.data.repository.GroupRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.notifications.NotificationHelper
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.rns.api.RnsLxmf
import network.zamolxis.app.rns.api.model.Identity
import network.zamolxis.app.rns.api.model.MessageReceipt
import network.zamolxis.app.rns.api.util.toHex
import network.zamolxis.app.service.pq.PqMessageSealer
import network.zamolxis.crypto.pq.PlainReason
import network.zamolxis.crypto.pq.PqMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [GroupChatManager] — fan-out shape, per-recipient status
 * recording, and the receive-side trust rules for control frames.
 */
class GroupChatManagerTest {
    private val myDestHash = "aa".repeat(16)
    private val myIdentityHash = "bb".repeat(16)
    private val memberA = "11".repeat(16)
    private val memberB = "22".repeat(16)
    private val memberC = "33".repeat(16)
    private val groupId = "44".repeat(16)

    private lateinit var groupRepository: GroupRepository
    private lateinit var rnsLxmf: RnsLxmf
    private lateinit var identityRepository: IdentityRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var pqMessageSealer: PqMessageSealer
    private lateinit var notificationHelper: NotificationHelper

    private lateinit var manager: GroupChatManager

    /** Distinct receipt hash per send, so per-recipient lxmfHash assertions can tell legs apart. */
    private var sendCounter = 0

    @Before
    fun setup() {
        groupRepository = mockk()
        rnsLxmf = mockk()
        identityRepository = mockk()
        settingsRepository = mockk()
        announceRepository = mockk()
        pqMessageSealer = mockk()
        notificationHelper = mockk()
        sendCounter = 0

        coEvery { identityRepository.getActiveIdentitySync() } returns
            mockk {
                every { destinationHash } returns myDestHash
                every { identityHash } returns myIdentityHash
            }
        coEvery { rnsLxmf.getLxmfIdentity() } returns
            Result.success(Identity(hash = ByteArray(16) { 1 }, publicKey = ByteArray(64) { 2 }, privateKey = null))
        coEvery { settingsRepository.getPostQuantumMode() } returns PqMode.OPPORTUNISTIC
        coEvery { settingsRepository.getTryPropagationOnFail() } returns true
        every { announceRepository.getRecentInterfaceSightings(any()) } returns flowOf(emptyList())
        coEvery { pqMessageSealer.onSendSucceeded(any(), any(), any()) } just Runs
        coEvery { notificationHelper.notifyMessageReceived(any(), any(), any(), any(), any()) } just Runs

        // Default: every leg seals successfully and every send succeeds.
        coEvery { pqMessageSealer.prepareOutgoing(any(), any(), any(), any(), any(), any()) } answers {
            PqMessageSealer.Outgoing.Sealed(
                wire = PqMessageSealer.WirePayload(content = "", extraFields = mapOf(0x51 to byteArrayOf(9))),
                protection = PqProtection.SEALED,
            )
        }
        coEvery {
            rnsLxmf.sendLxmfMessageWithMethod(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } answers {
            sendCounter++
            Result.success(
                MessageReceipt(
                    messageHash = byteArrayOf(sendCounter.toByte()),
                    timestamp = 1L,
                    destinationHash = ByteArray(16),
                ),
            )
        }

        manager =
            GroupChatManager(
                groupRepository = groupRepository,
                rnsLxmf = rnsLxmf,
                identityRepository = identityRepository,
                settingsRepository = settingsRepository,
                announceRepository = announceRepository,
                pqMessageSealer = pqMessageSealer,
                notificationHelper = notificationHelper,
            )
    }

    @After
    fun tearDown() = clearAllMocks()

    // ========== Fan-out ==========

    @Test
    fun `sendGroupMessage fans out to all three members with per-recipient sealing`() =
        runBlocking {
            stubMembership(memberA, memberB, memberC)

            val result = manager.sendGroupMessage(groupId, "hello group")

            assertTrue(result.isSuccess)
            val msgId = result.getOrThrow()
            coVerify(exactly = 1) {
                groupRepository.saveOutgoingMessage(groupId, msgId, "hello group", any(), listOf(memberA, memberB, memberC))
            }
            coVerify(exactly = 3) {
                rnsLxmf.sendLxmfMessageWithMethod(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
            // One sealing call per recipient, keyed by that recipient's hash.
            coVerify(exactly = 1) { pqMessageSealer.prepareOutgoing(myIdentityHash, myDestHash, memberA, any(), any(), any()) }
            coVerify(exactly = 1) { pqMessageSealer.prepareOutgoing(myIdentityHash, myDestHash, memberB, any(), any(), any()) }
            coVerify(exactly = 1) { pqMessageSealer.prepareOutgoing(myIdentityHash, myDestHash, memberC, any(), any(), any()) }
            // One LXMF send per member, addressed to that member.
            listOf(memberA, memberB, memberC).forEach { member ->
                coVerify(exactly = 1) {
                    rnsLxmf.sendLxmfMessageWithMethod(
                        match<ByteArray> { it.toHex() == member },
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
                coVerify(exactly = 1) {
                    groupRepository.setMemberStatus(msgId, member, GroupMessageStatus.SENT, match { !it.isNullOrEmpty() }, null)
                }
            }
        }

    @Test
    fun `sendGroupMessage records FAILED for a refused member and SENT with lxmfHash for the rest`() =
        runBlocking {
            stubMembership(memberA, memberB)
            // Declared after the general setup stub, so it wins for memberB.
            coEvery { pqMessageSealer.prepareOutgoing(any(), any(), memberB, any(), any(), any()) } returns
                PqMessageSealer.Outgoing.Refused(PlainReason.PEER_KEY_NOT_YET_KNOWN)

            val result = manager.sendGroupMessage(groupId, "hello group")

            assertTrue(result.isSuccess)
            val msgId = result.getOrThrow()
            // The refused member never reaches the transport.
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(
                    match<ByteArray> { it.toHex() == memberB },
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
            coVerify(exactly = 1) {
                groupRepository.setMemberStatus(
                    msgId,
                    memberB,
                    GroupMessageStatus.FAILED,
                    null,
                    match { it != null && it.contains("PEER_KEY_NOT_YET_KNOWN") },
                )
            }
            coVerify(exactly = 1) {
                groupRepository.setMemberStatus(msgId, memberA, GroupMessageStatus.SENT, match { !it.isNullOrEmpty() }, null)
            }
        }

    @Test
    fun `createGroup refuses a roster over the member ceiling and sends nothing`() =
        runBlocking {
            val tooMany = (1..GroupChatManager.MAX_GROUP_MEMBERS).map { "%032x".format(it) }

            val result = manager.createGroup("Big group", tooMany)

            // The ceiling counts us in, so MAX_GROUP_MEMBERS others is one too many.
            assertTrue(result.isFailure)
            coVerify(exactly = 0) { groupRepository.createLocalGroup(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    // ========== Receive: ordinary messages ==========

    @Test
    fun `handleIncoming duplicate does not notify twice`() =
        runBlocking {
            val msgId = UUID.randomUUID().toString()
            val envelope = GroupWireCodec.GroupEnvelope(gid = groupId, mid = msgId)
            coEvery { groupRepository.isActiveMember(groupId, memberA) } returns true
            coEvery { groupRepository.isActiveMember(groupId, myDestHash) } returns true
            coEvery { groupRepository.saveIncomingMessage(groupId, msgId, memberA, "hi", 42L) } returns true andThen false
            coEvery { groupRepository.getGroup(groupId) } returns groupEntity()

            manager.handleIncoming(envelope, memberA, "hi", 42L)
            manager.handleIncoming(envelope, memberA, "hi", 42L)

            // Both copies reach the store — the (gid, mid) insertIgnore dedup is
            // what rejects the second — but the notification only fires for the
            // copy that was actually inserted.
            coVerify(exactly = 2) { groupRepository.saveIncomingMessage(groupId, msgId, memberA, "hi", 42L) }
            val notifiedKeys = mutableListOf<String>()
            coVerify(exactly = 1) {
                notificationHelper.notifyMessageReceived(capture(notifiedKeys), any(), any(), any(), any())
            }
            // The notification carries the synthetic group key, not the sender's
            // 1:1 destination hash, so a tap opens the group chat.
            assertEquals(
                listOf(GroupChatManager.GROUP_NOTIFICATION_KEY_PREFIX + groupId),
                notifiedKeys,
            )
        }

    @Test
    fun `a message for a group we have left is dropped`() =
        runBlocking {
            val envelope = GroupWireCodec.GroupEnvelope(gid = groupId, mid = UUID.randomUUID().toString())
            coEvery { groupRepository.isActiveMember(groupId, memberA) } returns true
            // Our own row is soft-left; the sender's roster is still stale.
            coEvery { groupRepository.isActiveMember(groupId, myDestHash) } returns false
            var stored = false
            coEvery { groupRepository.saveIncomingMessage(any(), any(), any(), any(), any()) } answers {
                stored = true
                true
            }
            var notified = false
            coEvery { notificationHelper.notifyMessageReceived(any(), any(), any(), any(), any()) } answers { notified = true }

            manager.handleIncoming(envelope, memberA, "hi", 42L)

            assertFalse("a group we left must not keep collecting history", stored)
            assertFalse("a group we left must not keep notifying", notified)
        }

    // ========== Receive: control frames ==========

    @Test
    fun `MEMBERS_SYNC from a non-admin on a known group is ignored`() =
        runBlocking {
            coEvery { groupRepository.getGroup(groupId) } returns groupEntity()
            coEvery { groupRepository.getActiveMembers(groupId) } returns
                listOf(
                    memberEntity(myDestHash, GroupRole.ADMIN),
                    memberEntity(memberA, GroupRole.MEMBER),
                )
            var syncApplied = false
            coEvery { groupRepository.applyMembersSync(any(), any(), any()) } answers { syncApplied = true }
            var renamed = false
            coEvery { groupRepository.updateGroupName(any(), any()) } answers { renamed = true }
            val body =
                GroupWireCodec.GroupBody.MembersSync(
                    name = "hijacked",
                    createdBy = myDestHash,
                    createdAt = 1L,
                    members =
                        listOf(
                            GroupWireCodec.MemberEntry(myDestHash, GroupRole.ADMIN.name),
                            GroupWireCodec.MemberEntry(memberA, GroupRole.MEMBER.name),
                            GroupWireCodec.MemberEntry(memberC, GroupRole.MEMBER.name),
                        ),
                )

            manager.handleIncoming(
                GroupWireCodec.GroupEnvelope(groupId, UUID.randomUUID().toString(), GroupWireCodec.GroupCtl.MEMBERS_SYNC, body),
                memberA,
                "",
                1L,
            )

            assertFalse("a non-admin must not be able to rewrite the roster", syncApplied)
            // And the attempted rename from the rogue roster was never applied.
            assertFalse("a non-admin must not be able to rename the group", renamed)
        }

    @Test
    fun `MEMBERS_SYNC for an unknown group from a stranger who did not list us is dropped`() =
        runBlocking {
            coEvery { groupRepository.getGroup(groupId) } returns null
            var created = false
            coEvery { groupRepository.createLocalGroup(any(), any(), any(), any(), any()) } answers { created = true }
            var syncApplied = false
            coEvery { groupRepository.applyMembersSync(any(), any(), any()) } answers { syncApplied = true }
            val body =
                GroupWireCodec.GroupBody.MembersSync(
                    name = "not your group",
                    createdBy = memberA,
                    createdAt = 1L,
                    members =
                        listOf(
                            GroupWireCodec.MemberEntry(memberA, GroupRole.ADMIN.name),
                            GroupWireCodec.MemberEntry(memberB, GroupRole.MEMBER.name),
                        ),
                )

            manager.handleIncoming(
                GroupWireCodec.GroupEnvelope(groupId, UUID.randomUUID().toString(), GroupWireCodec.GroupCtl.MEMBERS_SYNC, body),
                memberA,
                "",
                1L,
            )

            assertFalse("a roster that does not name us must not enroll this device", created)
            // Nothing else touched the store either — the frame is dropped whole.
            assertFalse("the dropped frame must not reach the roster either", syncApplied)
        }

    @Test
    fun `MEMBER_LEFT marks only the sender as left`() =
        runBlocking {
            coEvery { groupRepository.markMemberLeft(any(), any(), any()) } just Runs

            manager.handleIncoming(
                GroupWireCodec.GroupEnvelope(groupId, UUID.randomUUID().toString(), GroupWireCodec.GroupCtl.MEMBER_LEFT),
                memberA,
                "",
                1L,
            )

            val markedLeft = slot<String>()
            coVerify(exactly = 1) { groupRepository.markMemberLeft(groupId, capture(markedLeft), any()) }
            coVerify(exactly = 0) { groupRepository.markMemberLeft(groupId, memberB, any()) }
            coVerify(exactly = 0) { groupRepository.markMemberLeft(groupId, myDestHash, any()) }
            // Only the sender's own membership ends — nobody can kick others.
            assertEquals(memberA, markedLeft.captured)
        }

    // ========== Helpers ==========

    private fun stubMembership(vararg members: String) {
        coEvery { groupRepository.isActiveMember(groupId, myDestHash) } returns true
        coEvery { groupRepository.getActiveMembers(groupId) } returns
            listOf(memberEntity(myDestHash, GroupRole.ADMIN)) +
            members.map { memberEntity(it, GroupRole.MEMBER) }
        coEvery { groupRepository.saveOutgoingMessage(any(), any(), any(), any(), any()) } just Runs
        coEvery { groupRepository.setMemberStatus(any(), any(), any(), any(), any()) } just Runs
    }

    private fun memberEntity(
        hash: String,
        role: GroupRole,
    ) = GroupMemberEntity(groupId = groupId, memberHash = hash, role = role.name, addedAt = 1L)

    private fun groupEntity() =
        GroupEntity(
            groupId = groupId,
            identityHash = myIdentityHash,
            name = "Test group",
            createdBy = myDestHash,
            createdAt = 1L,
        )
}
