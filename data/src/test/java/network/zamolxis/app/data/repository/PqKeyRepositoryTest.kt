package network.zamolxis.app.data.repository

import android.app.Application
import java.security.SecureRandom
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.data.crypto.CorruptedKeyException
import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import network.zamolxis.app.data.db.dao.PqKeyDao
import network.zamolxis.app.data.db.entity.LocalPqKeyEntity
import network.zamolxis.app.data.db.entity.PeerPqKeyEntity
import network.zamolxis.app.data.db.entity.PqKeyDeliveryEntity
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.HybridKeyCodec
import network.zamolxis.crypto.pq.PeerPqSupport
import network.zamolxis.crypto.pq.PqKeyExchange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Uses an in-memory fake DAO rather than mocks: the interesting behaviour here is
 * how rows change across a sequence of calls, which a verify-style mock cannot
 * express.
 *
 * The Keystore is faked too. Robolectric's AndroidKeyStore is not a real one, and
 * the point of these tests is the trust logic, not the platform's AES.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PqKeyRepositoryTest {
    private val kem = HybridKem(SecureRandom())
    private lateinit var dao: FakePqKeyDao
    private lateinit var repository: PqKeyRepository

    private val identity = "identity-a"
    private val peer = "peer-b"

    @Before
    fun setUp() {
        dao = FakePqKeyDao()
        repository = PqKeyRepository(dao, FakeEncryptor(), kem)
    }

    // ------------------------------------------------------------- our own key

    @Test
    fun `our key pair is generated once and reused`() =
        runTest {
            val first = repository.ourKeyPair(identity)
            val second = repository.ourKeyPair(identity)

            assertNotNull(first)
            assertEquals(first!!.publicKey, second!!.publicKey)
            assertEquals(1, dao.localKeys.size)
        }

    @Test
    fun `a restored key pair can still open messages sealed to the stored public key`() =
        runTest {
            val stored = repository.ourKeyPair(identity)!!
            val sealed = kem.seal(stored.publicKey, "round trip".toByteArray())

            // Fresh repository, same rows — the path a restart takes.
            val reloaded = PqKeyRepository(dao, FakeEncryptor(), kem).ourKeyPair(identity)!!

            assertArrayEquals("round trip".toByteArray(), kem.open(reloaded, sealed))
        }

    @Test
    fun `separate identities get separate key pairs`() =
        runTest {
            val a = repository.ourKeyPair("identity-a")!!.publicKey
            val b = repository.ourKeyPair("identity-b")!!.publicKey

            assertTrue(a != b)
        }

    @Test
    fun `an unreadable stored key pair yields null rather than a silent replacement`() =
        runTest {
            repository.ourKeyPair(identity)
            dao.localKeys[identity] = dao.localKeys[identity]!!.copy(encryptedKeyPair = ByteArray(20))

            // Regenerating here would orphan every peer already holding the old key.
            assertNull(repository.ourKeyPair(identity))
        }

    @Test
    fun `our fingerprint matches our public key`() =
        runTest {
            val publicKey = repository.ourPublicKey(identity)!!

            assertArrayEquals(HybridKeyCodec.fingerprint(publicKey), repository.ourFingerprint(identity))
        }

    // -------------------------------------------------------------- peer state

    @Test
    fun `an unknown peer is unsupported`() =
        runTest {
            assertEquals(PeerPqSupport.UNSUPPORTED, repository.supportFor(identity, peer))
        }

    @Test
    fun `a peer that only announced is awaiting its key`() =
        runTest {
            val peerKey = kem.generateKeyPair().publicKey
            repository.recordAnnouncedFingerprint(peer, HybridKeyCodec.fingerprint(peerKey))

            assertEquals(PeerPqSupport.ADVERTISED_KEY_MISSING, repository.supportFor(identity, peer))
        }

    @Test
    fun `a peer becomes ready once its key is accepted`() =
        runTest {
            val peerKey = kem.generateKeyPair().publicKey
            repository.recordAnnouncedFingerprint(peer, HybridKeyCodec.fingerprint(peerKey))

            repository.acceptIncomingKey(identity, peer, peerKey)

            assertEquals(PeerPqSupport.KEY_KNOWN, repository.supportFor(identity, peer))
        }

    // --------------------------------------------------------- trust decisions

    @Test
    fun `a key contradicting the announced fingerprint is not stored`() =
        runTest {
            val honest = kem.generateKeyPair().publicKey
            val attacker = kem.generateKeyPair().publicKey
            repository.recordAnnouncedFingerprint(peer, HybridKeyCodec.fingerprint(honest))

            val outcome = repository.acceptIncomingKey(identity, peer, attacker)

            assertEquals(PqKeyExchange.KeyAcceptance.FingerprintMismatch, outcome)
            assertNull(dao.peerKeys[peer]?.publicKey)
            assertEquals(PeerPqSupport.ADVERTISED_KEY_MISSING, repository.supportFor(identity, peer))
        }

    @Test
    fun `a changed key is flagged and never overwrites the stored one`() =
        runTest {
            val original = kem.generateKeyPair().publicKey
            val replacement = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, original)

            val outcome = repository.acceptIncomingKey(identity, peer, replacement)

            assertEquals(PqKeyExchange.KeyAcceptance.ChangedKey, outcome)
            assertArrayEquals(HybridKeyCodec.encode(original), dao.peerKeys[peer]?.publicKey)
            assertTrue(dao.peerKeys[peer]!!.keyChangeUnresolved)
        }

    @Test
    fun `sealing stops while a key change is unresolved`() =
        runTest {
            val original = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, original)
            repository.acceptIncomingKey(identity, peer, kem.generateKeyPair().publicKey)

            // A conversation the user believes is protected, while an attacker may
            // hold the key, is worse than one that is openly unprotected.
            assertEquals(PeerPqSupport.UNSUPPORTED, repository.supportFor(identity, peer))
        }

    @Test
    fun `re-sending the same key is a no-op`() =
        runTest {
            val peerKey = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, peerKey)

            val outcome = repository.acceptIncomingKey(identity, peer, peerKey)

            assertEquals(PqKeyExchange.KeyAcceptance.AlreadyKnown, outcome)
            assertEquals(false, dao.peerKeys[peer]!!.keyChangeUnresolved)
        }

    @Test
    fun `an announce cannot erase a key we already accepted`() =
        runTest {
            val peerKey = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, peerKey)

            // Announces are unauthenticated at this layer; one must not be able to
            // force the conversation back down to classical crypto.
            repository.recordAnnouncedFingerprint(peer, ByteArray(HybridKeyCodec.FINGERPRINT_BYTES))

            assertArrayEquals(HybridKeyCodec.encode(peerKey), dao.peerKeys[peer]?.publicKey)
        }

    // ------------------------------------------------- resolving a key change

    @Test
    fun `the offered key is kept aside so a genuine rotation can be accepted`() =
        runTest {
            val original = kem.generateKeyPair().publicKey
            val replacement = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, original)

            repository.acceptIncomingKey(identity, peer, replacement)

            // Discarding it would be a dead end: the peer stops attaching its key
            // once it believes we hold one, so it would never be offered again.
            assertArrayEquals(HybridKeyCodec.encode(replacement), dao.peerKeys[peer]?.pendingPublicKey)
        }

    @Test
    fun `accepting promotes the pending key and resumes sealing`() =
        runTest {
            val original = kem.generateKeyPair().publicKey
            val replacement = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, original)
            repository.acceptIncomingKey(identity, peer, replacement)

            repository.resolveKeyChange(peer, accept = true)

            assertEquals(PeerPqSupport.KEY_KNOWN, repository.supportFor(identity, peer))
            assertArrayEquals(HybridKeyCodec.encode(replacement), dao.peerKeys[peer]?.publicKey)
            assertNull(dao.peerKeys[peer]?.pendingPublicKey)
        }

    @Test
    fun `rejecting discards the offer and keeps the original key`() =
        runTest {
            val original = kem.generateKeyPair().publicKey
            val replacement = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, original)
            repository.acceptIncomingKey(identity, peer, replacement)

            repository.resolveKeyChange(peer, accept = false)

            assertEquals(PeerPqSupport.KEY_KNOWN, repository.supportFor(identity, peer))
            assertArrayEquals(HybridKeyCodec.encode(original), dao.peerKeys[peer]?.publicKey)
            assertNull(dao.peerKeys[peer]?.pendingPublicKey)
        }

    @Test
    fun `a rejected key never becomes usable`() =
        runTest {
            val original = kem.generateKeyPair().publicKey
            val impostor = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, original)
            repository.acceptIncomingKey(identity, peer, impostor)

            repository.resolveKeyChange(peer, accept = false)

            // The whole point of the flow: the impostor's key must not end up
            // sealing anything, now or later.
            val usable = repository.peerState(identity, peer).knownKey
            assertEquals(original, usable)
        }

    @Test
    fun `both fingerprints are offered for out-of-band comparison`() =
        runTest {
            val original = kem.generateKeyPair().publicKey
            val replacement = kem.generateKeyPair().publicKey
            repository.acceptIncomingKey(identity, peer, original)
            repository.acceptIncomingKey(identity, peer, replacement)

            val (trusted, pending) = repository.keyChangeFingerprints(peer)!!

            assertArrayEquals(HybridKeyCodec.fingerprint(original), trusted)
            assertArrayEquals(HybridKeyCodec.fingerprint(replacement), pending)
        }

    @Test
    fun `there are no fingerprints to show without a pending change`() =
        runTest {
            repository.acceptIncomingKey(identity, peer, kem.generateKeyPair().publicKey)

            assertNull(repository.keyChangeFingerprints(peer))
            assertEquals(false, repository.hasUnresolvedKeyChange(peer))
        }

    @Test
    fun `an unresolved change is reported for the peer`() =
        runTest {
            repository.acceptIncomingKey(identity, peer, kem.generateKeyPair().publicKey)
            repository.acceptIncomingKey(identity, peer, kem.generateKeyPair().publicKey)

            assertEquals(true, repository.hasUnresolvedKeyChange(peer))
        }

    // ---------------------------------------------------------- key delivery

    @Test
    fun `our key is attached until delivery is recorded`() =
        runTest {
            assertTrue(PqKeyExchange.shouldAttachOurKey(repository.peerState(identity, peer)))

            repository.markOurKeyDelivered(identity, peer)

            assertTrue(!PqKeyExchange.shouldAttachOurKey(repository.peerState(identity, peer)))
        }

    @Test
    fun `delivery is tracked per identity, not per peer`() =
        runTest {
            repository.markOurKeyDelivered("identity-a", peer)

            // identity-b has a different key pair, so the peer does not have it yet.
            assertTrue(PqKeyExchange.shouldAttachOurKey(repository.peerState("identity-b", peer)))
        }

    // ---------------------------------------------------- fingerprint mismatch

    @Test
    fun `a key contradicting the announced fingerprint is recorded, not just rejected`() =
        runTest {
            val announced = kem.generateKeyPair().publicKey
            repository.recordAnnouncedFingerprint(peer, HybridKeyCodec.fingerprint(announced))

            repository.acceptIncomingKey(identity, peer, kem.generateKeyPair().publicKey)

            // Rejecting silently used to be the whole response, which left the one
            // event meaning "someone altered this in transit" visible only in logcat.
            assertTrue(repository.hasFingerprintMismatch(peer))
            // And the offered key is still not usable for sealing.
            assertNull(repository.peerState(identity, peer).knownKey)
        }

    @Test
    fun `acknowledging a mismatch clears it`() =
        runTest {
            val announced = kem.generateKeyPair().publicKey
            repository.recordAnnouncedFingerprint(peer, HybridKeyCodec.fingerprint(announced))
            repository.acceptIncomingKey(identity, peer, kem.generateKeyPair().publicKey)

            repository.acknowledgeFingerprintMismatch(peer)

            assertFalse(repository.hasFingerprintMismatch(peer))
        }

    @Test
    fun `a matching key records no mismatch`() =
        runTest {
            val bobKey = kem.generateKeyPair().publicKey
            repository.recordAnnouncedFingerprint(peer, HybridKeyCodec.fingerprint(bobKey))

            repository.acceptIncomingKey(identity, peer, bobKey)

            assertFalse(repository.hasFingerprintMismatch(peer))
            assertEquals(bobKey, repository.peerState(identity, peer).knownKey)
        }

    // -------------------------------------------------------------- rotation

    @Test
    fun `rotation replaces the key pair`() =
        runTest {
            val original = repository.ourKeyPair(identity)!!.publicKey

            val rotated = repository.rotateOurKeyPair(identity)

            assertNotNull(rotated)
            assertNotEquals(original, rotated)
            assertEquals(rotated, repository.ourKeyPair(identity)!!.publicKey)
        }

    @Test
    fun `rotation makes our key attachable to peers again`() =
        runTest {
            repository.ourKeyPair(identity)
            repository.markOurKeyDelivered(identity, peer)
            assertFalse(PqKeyExchange.shouldAttachOurKey(repository.peerState(identity, peer)))

            repository.rotateOurKeyPair(identity)

            // Every peer still believes it holds the old key. Without clearing the
            // delivery records the replacement would never be sent, and those
            // conversations would seal to a key this device no longer has.
            assertTrue(PqKeyExchange.shouldAttachOurKey(repository.peerState(identity, peer)))
        }

    @Test
    fun `rotation leaves other identities alone`() =
        runTest {
            val otherBefore = repository.ourKeyPair("identity-b")!!.publicKey
            repository.ourKeyPair(identity)

            repository.rotateOurKeyPair(identity)

            assertEquals(otherBefore, repository.ourKeyPair("identity-b")!!.publicKey)
        }
}

/** Keystore stand-in: Robolectric has no real AndroidKeyStore to exercise. */
private class FakeEncryptor : SecretBlobEncryptor {
    override fun encryptBlobWithDeviceKey(plainData: ByteArray): ByteArray =
        byteArrayOf(WRAPPED_MARKER) + plainData

    override fun decryptBlobWithDeviceKey(encryptedData: ByteArray): ByteArray {
        if (encryptedData.isEmpty() || encryptedData[0] != WRAPPED_MARKER) {
            throw CorruptedKeyException("not a wrapped blob")
        }
        return encryptedData.copyOfRange(1, encryptedData.size)
    }

    private companion object {
        const val WRAPPED_MARKER: Byte = 0x7A
    }
}

/** In-memory PqKeyDao. */
private class FakePqKeyDao : PqKeyDao {
    val localKeys = mutableMapOf<String, LocalPqKeyEntity>()
    val peerKeys = mutableMapOf<String, PeerPqKeyEntity>()
    private val deliveries = mutableSetOf<Pair<String, String>>()

    override suspend fun getLocalKey(identityHash: String) = localKeys[identityHash]

    override suspend fun upsertLocalKey(key: LocalPqKeyEntity) {
        localKeys[key.identityHash] = key
    }

    override suspend fun getPeerKey(peerHash: String) = peerKeys[peerHash]

    override fun observePeerKey(peerHash: String) = throw UnsupportedOperationException()

    override suspend fun upsertPeerKey(key: PeerPqKeyEntity) {
        peerKeys[key.peerHash] = key
    }

    override suspend fun flagKeyChange(
        peerHash: String,
        offered: ByteArray,
        now: Long,
    ) {
        peerKeys[peerHash]?.let {
            peerKeys[peerHash] =
                it.copy(
                    keyChangeUnresolved = true,
                    pendingPublicKey = offered,
                    updatedTimestamp = now,
                )
        }
    }

    // Mirrors the SQL: promotion only happens when a pending key is actually there.
    override suspend fun acceptPendingKey(
        peerHash: String,
        now: Long,
    ) {
        peerKeys[peerHash]?.let {
            val pending = it.pendingPublicKey ?: return
            peerKeys[peerHash] =
                it.copy(
                    publicKey = pending,
                    pendingPublicKey = null,
                    keyChangeUnresolved = false,
                    updatedTimestamp = now,
                )
        }
    }

    override suspend fun rejectPendingKey(
        peerHash: String,
        now: Long,
    ) {
        peerKeys[peerHash]?.let {
            peerKeys[peerHash] =
                it.copy(
                    pendingPublicKey = null,
                    keyChangeUnresolved = false,
                    updatedTimestamp = now,
                )
        }
    }

    override suspend fun clearFingerprintMismatch(
        peerHash: String,
        now: Long,
    ) {
        peerKeys[peerHash]?.let {
            peerKeys[peerHash] = it.copy(fingerprintMismatchTimestamp = null, updatedTimestamp = now)
        }
    }

    override fun observeUnresolvedKeyChanges() = throw UnsupportedOperationException()

    override suspend fun hasDeliveredOurKey(
        identityHash: String,
        peerHash: String,
    ) = (identityHash to peerHash) in deliveries

    override suspend fun recordDelivery(delivery: PqKeyDeliveryEntity) {
        deliveries += delivery.identityHash to delivery.peerHash
    }

    override suspend fun clearDeliveriesFor(identityHash: String) {
        deliveries.removeAll { it.first == identityHash }
    }
}
