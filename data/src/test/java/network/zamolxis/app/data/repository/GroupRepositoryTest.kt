package network.zamolxis.app.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.data.db.DatabaseTransactionRunner
import network.zamolxis.app.data.db.ZamolxisDatabase
import network.zamolxis.app.data.db.entity.LocalIdentityEntity
import network.zamolxis.app.data.model.GroupMessageStatus
import network.zamolxis.app.data.model.GroupRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs against a real in-memory database rather than fake DAOs: what is
 * interesting here is the SQL — the (groupId, msgId) insert-ignore dedup, the
 * sticky-terminal-state guard on delivery receipts, the aggregate rollup and
 * the multi-table delete — none of which a hand-written fake would test.
 *
 * The transaction runner is a pass-through. Room's `withTransaction` needs the
 * database's own coroutine context, which is exactly the seam
 * [DatabaseTransactionRunner] exists to make substitutable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GroupRepositoryTest {
    private lateinit var database: ZamolxisDatabase
    private lateinit var repository: GroupRepository

    private val identityHash = "aa".repeat(16)
    private val myHash = "bb".repeat(16)
    private val memberA = "cc".repeat(16)
    private val memberB = "dd".repeat(16)
    private val groupId = "ee".repeat(16)

    @Before
    fun setup() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database =
                Room
                    .inMemoryDatabaseBuilder(context, ZamolxisDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            database.localIdentityDao().insert(
                LocalIdentityEntity(
                    identityHash = identityHash,
                    displayName = "me",
                    destinationHash = myHash,
                    filePath = "/dev/null",
                    createdTimestamp = 1L,
                    lastUsedTimestamp = 1L,
                    isActive = true,
                ),
            )
            repository =
                GroupRepository(
                    groupDao = database.groupDao(),
                    groupMemberDao = database.groupMemberDao(),
                    groupMessageDao = database.groupMessageDao(),
                    groupMessageStatusDao = database.groupMessageStatusDao(),
                    localIdentityDao = database.localIdentityDao(),
                    transactionRunner = PassThroughTransactionRunner,
                )
        }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun createGroup() =
        repository.createLocalGroup(
            groupId = groupId,
            name = "Test group",
            createdBy = myHash,
            createdAt = 1_000L,
            members = listOf(myHash to GroupRole.ADMIN, memberA to GroupRole.MEMBER),
        )

    // ---------------------------------------------------------------- creation

    @Test
    fun `createLocalGroup stores the group scoped to the active identity with its roster`() =
        runTest {
            createGroup()

            val group = repository.getGroup(groupId)
            assertEquals("Test group", group?.name)
            assertEquals(identityHash, group?.identityHash)
            assertEquals(
                setOf(myHash, memberA),
                repository.getActiveMembers(groupId).map { it.memberHash }.toSet(),
            )
            assertTrue(repository.isActiveMember(groupId, myHash))
        }

    // ------------------------------------------------------------ incoming dedup

    @Test
    fun `a replayed message is ignored and does not bump the unread count twice`() =
        runTest {
            createGroup()

            val first = repository.saveIncomingMessage(groupId, "msg-1", memberA, "hi", 10L)
            val second = repository.saveIncomingMessage(groupId, "msg-1", memberA, "hi", 10L)

            assertTrue(first)
            assertFalse(second)
            assertEquals(1, repository.getGroup(groupId)?.unreadCount)
            assertEquals("hi", repository.getGroup(groupId)?.lastMessage)
        }

    @Test
    fun `markGroupRead clears the unread count`() =
        runTest {
            createGroup()
            repository.saveIncomingMessage(groupId, "msg-1", memberA, "hi", 10L)

            repository.markGroupRead(groupId)

            assertEquals(0, repository.getGroup(groupId)?.unreadCount)
        }

    // ------------------------------------------------------------ delivery state

    @Test
    fun `a delivered leg is never dragged back to sent by a late callback`() =
        runTest {
            createGroup()
            repository.saveOutgoingMessage(groupId, "msg-1", "hello", 20L, listOf(memberA))
            repository.setMemberStatus("msg-1", memberA, GroupMessageStatus.SENT, lxmfHash = "ff01")

            assertTrue(repository.updateStatusByLxmfHash("ff01", GroupMessageStatus.DELIVERED))
            // A "sent" receipt arriving after the delivery proof must not win.
            assertFalse(repository.updateStatusByLxmfHash("ff01", GroupMessageStatus.SENT))

            val statuses = database.groupMessageStatusDao().getStatusesForMessage("msg-1")
            assertEquals(GroupMessageStatus.DELIVERED.name, statuses.single().status)
        }

    @Test
    fun `the aggregate stays pending until every leg has been sent`() =
        runTest {
            createGroup()
            repository.saveOutgoingMessage(groupId, "msg-1", "hello", 20L, listOf(memberA, memberB))

            repository.setMemberStatus("msg-1", memberA, GroupMessageStatus.SENT, lxmfHash = "ff01")
            assertEquals(GroupMessageStatus.PENDING.name, aggregateStatus())

            repository.setMemberStatus("msg-1", memberB, GroupMessageStatus.SENT, lxmfHash = "ff02")
            assertEquals(GroupMessageStatus.SENT.name, aggregateStatus())

            repository.updateStatusByLxmfHash("ff01", GroupMessageStatus.DELIVERED)
            repository.updateStatusByLxmfHash("ff02", GroupMessageStatus.DELIVERED)
            assertEquals(GroupMessageStatus.DELIVERED.name, aggregateStatus())
        }

    private suspend fun aggregateStatus(): String? =
        database
            .groupMessageStatusDao()
            .observeAggregateStatuses(groupId)
            .first()
            .firstOrNull { it.msgId == "msg-1" }
            ?.status

    // -------------------------------------------------------------- roster sync

    @Test
    fun `applyMembersSync adds newcomers, soft-leaves the absent and keeps existing roles`() =
        runTest {
            createGroup()

            // memberA is gone, memberB arrives, and the sender demotes us — which
            // must not stick, existing rows keep the role they already had.
            repository.applyMembersSync(
                groupId,
                listOf(myHash to GroupRole.MEMBER, memberB to GroupRole.MEMBER),
                now = 2_000L,
            )

            val active = repository.getActiveMembers(groupId).associateBy { it.memberHash }
            assertEquals(setOf(myHash, memberB), active.keys)
            assertEquals(GroupRole.ADMIN.name, active.getValue(myHash).role)
            assertFalse(repository.isActiveMember(groupId, memberA))
        }

    @Test
    fun `a re-added member keeps the addedAt of their first join`() =
        runTest {
            createGroup()
            repository.markMemberLeft(groupId, memberA, now = 2_000L)

            repository.applyMembersSync(
                groupId,
                listOf(myHash to GroupRole.ADMIN, memberA to GroupRole.MEMBER),
                now = 3_000L,
            )

            val rejoined = repository.getActiveMembers(groupId).single { it.memberHash == memberA }
            assertEquals(1_000L, rejoined.addedAt)
            assertNull(rejoined.leftAt)
        }

    // ------------------------------------------------------------------ deletion

    @Test
    fun `deleteGroup removes the group with its roster, history and fan-out rows`() =
        runTest {
            createGroup()
            repository.saveIncomingMessage(groupId, "in-1", memberA, "hi", 10L)
            repository.saveOutgoingMessage(groupId, "msg-1", "hello", 20L, listOf(memberA))
            repository.setMemberStatus("msg-1", memberA, GroupMessageStatus.SENT, lxmfHash = "ff01")

            repository.deleteGroup(groupId)

            assertNull(repository.getGroup(groupId))
            assertTrue(repository.getActiveMembers(groupId).isEmpty())
            assertNull(database.groupMessageDao().getMessage(groupId, "in-1"))
            // The status table has no foreign key, so this is the case that would
            // silently leak rows if deleteGroup forgot it.
            assertTrue(database.groupMessageStatusDao().getStatusesForMessage("msg-1").isEmpty())
        }

    /** Runs the block as-is; transaction semantics are Room's business, not this test's. */
    private object PassThroughTransactionRunner : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
}
