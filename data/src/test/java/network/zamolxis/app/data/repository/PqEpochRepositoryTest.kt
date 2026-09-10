package network.zamolxis.app.data.repository

import android.app.Application
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import network.zamolxis.app.data.db.dao.PqEpochDao
import network.zamolxis.app.data.db.entity.PqEpochEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bookkeeping around a post-quantum epoch.
 *
 * Two failures matter here and they are not symmetric. Handing out a counter
 * twice reuses a message key and its nonce, which is the end of AES-GCM's
 * guarantees — so the counter tests are about a number that must only ever go
 * up. Dropping a root too early makes mail that was always going to arrive late
 * arrive unreadable — so the retention tests are about a row that must outlive
 * the thirty days a propagation node may hold a message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PqEpochRepositoryTest {
    private val us = "our-identity"
    private val peer = "peer-hash"
    private val root = ByteArray(32) { it.toByte() }
    private val dao = FakeEpochDao()
    private val encryptor = RecordingEncryptor()
    private val repository = PqEpochRepository(dao, encryptor)

    private val day = 24L * 60 * 60 * 1000

    // ── counters ─────────────────────────────────────────────────────────────

    @Test
    fun `there is nothing to continue before an epoch is opened`() =
        runTest {
            assertNull(repository.reserveOutbound(us, peer))
        }

    /** The opening is counter zero, so the first continuation is one. */
    @Test
    fun `the first message after the opening takes counter one`() =
        runTest {
            repository.startOutbound(us, peer, "epoch-a", root)

            assertEquals(1, repository.reserveOutbound(us, peer)?.counter)
        }

    @Test
    fun `no counter is ever handed out twice`() =
        runTest {
            repository.startOutbound(us, peer, "epoch-a", root)

            val handed = (1..50).map { repository.reserveOutbound(us, peer)?.counter }

            assertEquals("every reservation must succeed", 50, handed.filterNotNull().size)
            assertEquals("and every one must be distinct", 50, handed.toSet().size)
            assertEquals((1..50).toList(), handed)
        }

    @Test
    fun `opening a new epoch leaves only one to seal with`() =
        runTest {
            repository.startOutbound(us, peer, "epoch-a", root)
            repository.reserveOutbound(us, peer)

            repository.startOutbound(us, peer, "epoch-b", ByteArray(32) { 9 })

            assertEquals("epoch-b", repository.reserveOutbound(us, peer)?.epochId)
            assertEquals(1, dao.outboundCount(us, peer))
        }

    // ── when an epoch is done ────────────────────────────────────────────────

    @Test
    fun `an epoch stops being usable once it has carried its share`() =
        runTest {
            repository.startOutbound(us, peer, "epoch-a", root)
            repeat(PqEpochRepository.MESSAGES_PER_EPOCH - 1) { repository.reserveOutbound(us, peer) }

            assertNull("the caller must open a fresh epoch instead", repository.reserveOutbound(us, peer))
        }

    @Test
    fun `an epoch stops being usable once it is old, however quiet`() =
        runTest {
            val opened = 1_000_000L
            repository.startOutbound(us, peer, "epoch-a", root, now = opened)

            assertNotNull(repository.reserveOutbound(us, peer, now = opened + PqEpochRepository.EPOCH_LIFETIME_MS - 1))
            assertNull(repository.reserveOutbound(us, peer, now = opened + PqEpochRepository.EPOCH_LIFETIME_MS))
        }

    /**
     * A clock that went backwards must not make an epoch look young again. The
     * age test is one half of what bounds a counter's run, and a device whose
     * time was corrected backwards is an ordinary event, not an attack.
     */
    @Test
    fun `a clock that moved backwards retires the epoch rather than extending it`() =
        runTest {
            repository.startOutbound(us, peer, "epoch-a", root, now = 5_000_000L)

            assertNull(repository.reserveOutbound(us, peer, now = 4_000_000L))
        }

    // ── receiving ────────────────────────────────────────────────────────────

    @Test
    fun `a root a peer opened reads back`() =
        runTest {
            repository.acceptInbound(us, peer, "their-epoch", root)

            assertArrayEquals(root, repository.inboundRoot(us, peer, "their-epoch"))
        }

    @Test
    fun `an epoch we never held reads as absent rather than failing`() =
        runTest {
            repository.acceptInbound(us, peer, "their-epoch", root)

            assertNull(repository.inboundRoot(us, peer, "some-other-epoch"))
            assertNull(repository.inboundRoot(us, "someone-else", "their-epoch"))
        }

    /** Inbound and outbound epochs are separate even when they share an id. */
    @Test
    fun `the two directions do not see each other`() =
        runTest {
            repository.startOutbound(us, peer, "same-id", root)

            assertNull(repository.inboundRoot(us, peer, "same-id"))
        }

    // ── retention ────────────────────────────────────────────────────────────

    /**
     * The number that matters: a propagation node holds mail for thirty days, so
     * a root dropped before then turns ordinary late delivery into a message
     * nobody can read.
     */
    @Test
    fun `a root outlives the thirty days a message may spend in flight`() =
        runTest {
            assertTrue(
                "retention of ${PqEpochRepository.ROOT_RETENTION_MS}ms must clear thirty days",
                PqEpochRepository.ROOT_RETENTION_MS > 30 * day,
            )
        }

    @Test
    fun `pruning keeps what is still needed and drops what is not`() =
        runTest {
            val now = 100L * day
            repository.acceptInbound(us, peer, "recent", root, now = now - 10 * day)
            repository.acceptInbound(us, peer, "ancient", root, now = now - 40 * day)

            repository.prune(now)

            assertNotNull(repository.inboundRoot(us, peer, "recent", now))
            assertNull(repository.inboundRoot(us, peer, "ancient", now))
        }

    /**
     * Pruning hangs off epoch creation rather than a timer, so this is the test
     * that the retention window is actually enforced rather than merely written
     * down.
     */
    @Test
    fun `opening an epoch clears out roots nothing can still need`() =
        runTest {
            val now = 100L * day
            repository.acceptInbound(us, peer, "ancient", root, now = now - 40 * day)

            repository.startOutbound(us, peer, "fresh", root, now = now)

            assertNull(repository.inboundRoot(us, peer, "ancient", now))
        }

    /** Reading a root marks it in use, so an active conversation is never pruned. */
    @Test
    fun `reading a root keeps it alive`() =
        runTest {
            val now = 100L * day
            repository.acceptInbound(us, peer, "old-but-used", root, now = now - 40 * day)
            repository.inboundRoot(us, peer, "old-but-used", now)

            repository.prune(now)

            assertNotNull(repository.inboundRoot(us, peer, "old-but-used", now))
        }

    // ── at rest ──────────────────────────────────────────────────────────────

    /**
     * A root opens every message of its epoch. The database is encrypted already;
     * this is the second wrap, and the test is here because "we wrapped it" is the
     * kind of claim that survives the code that made it true.
     */
    @Test
    fun `a root is never written down in the clear`() =
        runTest {
            repository.startOutbound(us, peer, "epoch-a", root)

            val stored =
                dao.rows.values
                    .single()
                    .encryptedRoot
            assertFalse("the stored blob must not be the root", stored.contentEquals(root))
            assertTrue("and it must have gone through the wrapper", encryptor.wrapped)
        }

    /**
     * A wrap that will not come back — the Keystore key did not survive a device
     * restore — is an epoch that is gone. Saying so lets the send path open a
     * fresh one instead of failing the message.
     */
    @Test
    fun `an unreadable root is reported as absent, not thrown`() =
        runTest {
            repository.acceptInbound(us, peer, "their-epoch", root)
            encryptor.broken = true

            assertNull(repository.inboundRoot(us, peer, "their-epoch"))
        }
}

/** Wraps by prefixing a marker, so a stored blob is visibly not the plaintext. */
private class RecordingEncryptor : SecretBlobEncryptor {
    var wrapped = false
    var broken = false

    override fun encryptBlobWithDeviceKey(data: ByteArray): ByteArray {
        wrapped = true
        return byteArrayOf(MARKER) + data
    }

    override fun decryptBlobWithDeviceKey(encryptedData: ByteArray): ByteArray {
        check(!broken) { "keystore key is gone" }
        return encryptedData.copyOfRange(1, encryptedData.size)
    }

    private companion object {
        const val MARKER: Byte = 0x5E
    }
}

private class FakeEpochDao : PqEpochDao {
    val rows = mutableMapOf<String, PqEpochEntity>()

    private fun key(e: PqEpochEntity) = "${e.identityHash}|${e.peerHash}|${e.outbound}|${e.epochId}"

    fun outboundCount(
        identityHash: String,
        peerHash: String,
    ): Int = rows.values.count { it.identityHash == identityHash && it.peerHash == peerHash && it.outbound }

    override suspend fun currentOutbound(
        identityHash: String,
        peerHash: String,
    ): PqEpochEntity? =
        rows.values
            .filter { it.identityHash == identityHash && it.peerHash == peerHash && it.outbound }
            .maxByOrNull { it.createdTimestamp }

    override suspend fun inbound(
        identityHash: String,
        peerHash: String,
        epochId: String,
    ): PqEpochEntity? =
        rows.values.firstOrNull {
            it.identityHash == identityHash && it.peerHash == peerHash && !it.outbound && it.epochId == epochId
        }

    override suspend fun upsert(epoch: PqEpochEntity) {
        rows[key(epoch)] = epoch
    }

    override suspend fun deleteOutbound(
        identityHash: String,
        peerHash: String,
    ) {
        rows.values
            .filter { it.identityHash == identityHash && it.peerHash == peerHash && it.outbound }
            .toList()
            .forEach { rows.remove(key(it)) }
    }

    override suspend fun advance(
        identityHash: String,
        peerHash: String,
        epochId: String,
        now: Long,
    ) {
        val existing =
            rows.values.firstOrNull {
                it.identityHash == identityHash && it.peerHash == peerHash && it.outbound && it.epochId == epochId
            } ?: return
        rows[key(existing)] =
            existing.copy(
                nextCounter = existing.nextCounter + 1,
                messageCount = existing.messageCount + 1,
                lastUsedTimestamp = now,
            )
    }

    override suspend fun touchInbound(
        identityHash: String,
        peerHash: String,
        epochId: String,
        now: Long,
    ) {
        inbound(identityHash, peerHash, epochId)?.let { rows[key(it)] = it.copy(lastUsedTimestamp = now) }
    }

    override suspend fun deleteOlderThan(before: Long): Int {
        val stale = rows.values.filter { it.lastUsedTimestamp < before }.toList()
        stale.forEach { rows.remove(key(it)) }
        return stale.size
    }

    override suspend fun count(): Int = rows.size
}
