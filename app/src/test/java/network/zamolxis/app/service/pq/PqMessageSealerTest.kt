package network.zamolxis.app.service.pq

import android.app.Application
import java.security.SecureRandom
import kotlinx.coroutines.test.runTest
import network.zamolxis.app.data.crypto.CorruptedKeyException
import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import network.zamolxis.app.data.db.dao.PqKeyDao
import network.zamolxis.app.data.db.entity.LocalPqKeyEntity
import network.zamolxis.app.data.db.entity.PeerPqKeyEntity
import network.zamolxis.app.data.db.entity.PqKeyDeliveryEntity
import network.zamolxis.app.data.repository.PqKeyRepository
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.HybridKeyCodec
import network.zamolxis.crypto.pq.LinkCost
import network.zamolxis.crypto.pq.PlainReason
import network.zamolxis.crypto.pq.PqEnvelope
import network.zamolxis.crypto.pq.PqKeyExchange
import network.zamolxis.crypto.pq.PqMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PqMessageSealerTest {
    private val kem = HybridKem(SecureRandom())

    private lateinit var aliceDao: FakePqKeyDao
    private lateinit var bobDao: FakePqKeyDao
    private lateinit var alice: PqMessageSealer
    private lateinit var bob: PqMessageSealer
    private lateinit var aliceRepo: PqKeyRepository
    private lateinit var bobRepo: PqKeyRepository

    private val aliceId = "alice-identity"
    private val bobId = "bob-identity"

    @Before
    fun setUp() {
        aliceDao = FakePqKeyDao()
        bobDao = FakePqKeyDao()
        aliceRepo = PqKeyRepository(aliceDao, FakeEncryptor(), kem)
        bobRepo = PqKeyRepository(bobDao, FakeEncryptor(), kem)
        alice = PqMessageSealer(aliceRepo, kem)
        bob = PqMessageSealer(bobRepo, kem)
    }

    // ------------------------------------------------------------ first contact

    @Test
    fun `the opening message is plain but carries our key`() =
        runTest {
            val outgoing =
                alice.prepareOutgoing(aliceId, bobId, "hello", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)

            val plain = outgoing as PqMessageSealer.Outgoing.Plain
            assertEquals("hello", plain.content)
            assertEquals(PlainReason.PEER_UNSUPPORTED, plain.reason)
            assertTrue(plain.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY))
        }

    @Test
    fun `a full exchange ends with both directions sealed`() =
        runTest {
            // 1. Alice opens; Bob takes in her key.
            val first =
                alice.prepareOutgoing(aliceId, bobId, "hello", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    as PqMessageSealer.Outgoing.Plain
            alice.onSendSucceeded(aliceId, bobId, first)
            val atBob = bob.processIncoming(bobId, aliceId, first.content, first.extraFields)!!
            assertEquals("hello", atBob.content)
            assertFalse(atBob.wasSealed)

            // 2. Bob replies — sealed, and carrying his own key.
            val second =
                bob.prepareOutgoing(bobId, aliceId, "hi back", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    as PqMessageSealer.Outgoing.Sealed
            bob.onSendSucceeded(bobId, aliceId, second)
            val atAlice = alice.processIncoming(aliceId, bobId, second.content, second.extraFields)!!
            assertEquals("hi back", atAlice.content)
            assertTrue(atAlice.wasSealed)

            // 3. Alice now seals too, and stops attaching her key.
            val third =
                alice.prepareOutgoing(aliceId, bobId, "sealed now", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    as PqMessageSealer.Outgoing.Sealed
            assertFalse(third.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY))
            assertEquals("sealed now", bob.processIncoming(bobId, aliceId, "", third.extraFields)!!.content)
        }

    @Test
    fun `sealed messages carry no plaintext in the content slot`() =
        runTest {
            establishExchange()

            val sealed =
                alice.prepareOutgoing(aliceId, bobId, "ATTACKATDAWN", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    as PqMessageSealer.Outgoing.Sealed

            assertEquals("", sealed.content)
            val blob = sealed.extraFields[PqEnvelope.FIELD_SEALED_CONTENT]!!
            assertFalse(String(blob, Charsets.ISO_8859_1).contains("ATTACKATDAWN"))
        }

    // ------------------------------------------------------------------- modes

    @Test
    fun `off never seals and never attaches a key`() =
        runTest {
            establishExchange()

            val outgoing =
                alice.prepareOutgoing(aliceId, bobId, "plain", PqMode.OFF, LinkCost.CHEAP)

            val plain = outgoing as PqMessageSealer.Outgoing.Plain
            assertEquals(PlainReason.DISABLED_BY_USER, plain.reason)
            assertEquals("plain", plain.content)
        }

    @Test
    fun `opportunistic spares an expensive link the overhead`() =
        runTest {
            establishExchange()

            val outgoing =
                alice.prepareOutgoing(aliceId, bobId, "over lora", PqMode.OPPORTUNISTIC, LinkCost.EXPENSIVE)

            assertEquals(
                PlainReason.LINK_TOO_EXPENSIVE,
                (outgoing as PqMessageSealer.Outgoing.Plain).reason,
            )
        }

    @Test
    fun `required seals even over an expensive link`() =
        runTest {
            establishExchange()

            val outgoing =
                alice.prepareOutgoing(aliceId, bobId, "must be sealed", PqMode.REQUIRED, LinkCost.EXPENSIVE)

            assertTrue(outgoing is PqMessageSealer.Outgoing.Sealed)
        }

    @Test
    fun `required refuses rather than sending readable to a plain peer`() =
        runTest {
            val outgoing =
                alice.prepareOutgoing(aliceId, "stranger", "secret", PqMode.REQUIRED, LinkCost.CHEAP)

            assertEquals(
                PlainReason.PEER_UNSUPPORTED,
                (outgoing as PqMessageSealer.Outgoing.Refused).reason,
            )
        }

    @Test
    fun `required never produces a plain send`() =
        runTest {
            establishExchange()

            for (peer in listOf(bobId, "stranger")) {
                for (link in LinkCost.entries) {
                    val outgoing = alice.prepareOutgoing(aliceId, peer, "x", PqMode.REQUIRED, link)
                    assertTrue(
                        "REQUIRED leaked a plain send to $peer over $link",
                        outgoing !is PqMessageSealer.Outgoing.Plain,
                    )
                }
            }
        }

    // -------------------------------------------------------------- key trouble

    @Test
    fun `a substituted key is reported and stops sealing`() =
        runTest {
            val bobKey = bobRepo.ourPublicKey(bobId)!!
            aliceRepo.recordAnnouncedFingerprint(bobId, HybridKeyCodec.fingerprint(bobKey))

            // An attacker's key arrives instead of Bob's.
            val attacker = kem.generateKeyPair().publicKey
            val incoming =
                alice.processIncoming(aliceId, bobId, "hi", PqEnvelope.keyOnlyFields(attacker))!!

            assertEquals(PqKeyExchange.KeyAcceptance.FingerprintMismatch, incoming.keyProblem)
            val outgoing =
                alice.prepareOutgoing(aliceId, bobId, "reply", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
            assertTrue(outgoing is PqMessageSealer.Outgoing.Plain)
        }

    @Test
    fun `a changed key is reported and stops sealing`() =
        runTest {
            establishExchange()

            val replacement = kem.generateKeyPair().publicKey
            val incoming =
                alice.processIncoming(aliceId, bobId, "hi", PqEnvelope.keyOnlyFields(replacement))!!

            assertEquals(PqKeyExchange.KeyAcceptance.ChangedKey, incoming.keyProblem)
            assertTrue(
                alice.prepareOutgoing(aliceId, bobId, "x", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    is PqMessageSealer.Outgoing.Plain,
            )
        }

    @Test
    fun `required refuses once a key change is unresolved`() =
        runTest {
            establishExchange()
            alice.processIncoming(aliceId, bobId, "hi", PqEnvelope.keyOnlyFields(kem.generateKeyPair().publicKey))

            assertTrue(
                alice.prepareOutgoing(aliceId, bobId, "x", PqMode.REQUIRED, LinkCost.CHEAP)
                    is PqMessageSealer.Outgoing.Refused,
            )
        }

    // ------------------------------------------------- indicator agrees with send

    @Test
    fun `the indicator matches what the send path actually does`() =
        runTest {
            establishExchange()

            // The badge exists to tell the user whether this conversation is
            // protected. If it can disagree with the sender for any combination of
            // inputs, it is worse than absent — it claims safety that is not there.
            for (peer in listOf(bobId, "stranger")) {
                for (mode in PqMode.entries) {
                    for (link in LinkCost.entries) {
                        val sealedByIndicator =
                            alice.isConversationSealed(aliceId, peer, mode, link)
                        val sent = alice.prepareOutgoing(aliceId, peer, "x", mode, link)
                        val sealedBySend = sent is PqMessageSealer.Outgoing.Sealed

                        assertEquals(
                            "indicator disagreed with send for peer=$peer mode=$mode link=$link",
                            sealedBySend,
                            sealedByIndicator,
                        )
                    }
                }
            }
        }

    @Test
    fun `the indicator is off before any key exchange`() =
        runTest {
            assertFalse(
                alice.isConversationSealed(aliceId, bobId, PqMode.OPPORTUNISTIC, LinkCost.CHEAP),
            )
        }

    @Test
    fun `the indicator goes off while a key change is unresolved`() =
        runTest {
            establishExchange()
            assertTrue(alice.isConversationSealed(aliceId, bobId, PqMode.OPPORTUNISTIC, LinkCost.CHEAP))

            alice.processIncoming(aliceId, bobId, "hi", PqEnvelope.keyOnlyFields(kem.generateKeyPair().publicKey))

            assertFalse(
                alice.isConversationSealed(aliceId, bobId, PqMode.OPPORTUNISTIC, LinkCost.CHEAP),
            )
        }

    // ---------------------------------------------------------------- incoming

    @Test
    fun `an ordinary message passes through untouched`() =
        runTest {
            val incoming = alice.processIncoming(aliceId, bobId, "just text", emptyMap())!!

            assertEquals("just text", incoming.content)
            assertFalse(incoming.wasSealed)
            assertNull(incoming.keyProblem)
        }

    @Test
    fun `a sealed message we cannot open yields null rather than gibberish`() =
        runTest {
            // Sealed to someone else entirely.
            val stranger = kem.generateKeyPair().publicKey
            val fields = PqEnvelope.fieldsFor(kem.seal(stranger, "not for you".toByteArray()), null)

            assertNull(alice.processIncoming(aliceId, bobId, "", fields))
        }

    @Test
    fun `a tampered sealed message yields null`() =
        runTest {
            establishExchange()
            val sealed =
                alice.prepareOutgoing(aliceId, bobId, "intact", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    as PqMessageSealer.Outgoing.Sealed
            val blob = sealed.extraFields[PqEnvelope.FIELD_SEALED_CONTENT]!!.copyOf()
            blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()

            assertNull(
                bob.processIncoming(bobId, aliceId, "", mapOf(PqEnvelope.FIELD_SEALED_CONTENT to blob)),
            )
        }

    @Test
    fun `a malformed sender key does not read as no key`() =
        runTest {
            val incoming =
                alice.processIncoming(aliceId, bobId, "text", mapOf(PqEnvelope.FIELD_SENDER_KEY to ByteArray(9)))!!

            assertEquals("text", incoming.content)
            assertFalse(incoming.wasSealed)
            // The peer stays un-established rather than being recorded as plain.
            assertNull(aliceDao.peerKeys[bobId]?.publicKey)
        }

    @Test
    fun `a sealed message still opens when the attached sender key is corrupt`() =
        runTest {
            establishExchange()
            val sealed =
                alice.prepareOutgoing(aliceId, bobId, "still readable", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    as PqMessageSealer.Outgoing.Sealed

            // The payload is sealed to Bob's key; a mangled sender-key field is a
            // separate concern and must not cost him the message.
            val fields =
                sealed.extraFields + mapOf(PqEnvelope.FIELD_SENDER_KEY to ByteArray(11))

            val incoming = bob.processIncoming(bobId, aliceId, "", fields)!!

            assertEquals("still readable", incoming.content)
            assertTrue(incoming.wasSealed)
        }

    @Test
    fun `unicode content survives the round trip`() =
        runTest {
            establishExchange()
            val message = "Здравей, свят — 🕊 مرحبا"

            val sealed =
                alice.prepareOutgoing(aliceId, bobId, message, PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                    as PqMessageSealer.Outgoing.Sealed

            assertEquals(message, bob.processIncoming(bobId, aliceId, "", sealed.extraFields)!!.content)
        }

    /** Runs the two-message handshake so both sides hold each other's key. */
    private suspend fun establishExchange() {
        val first =
            alice.prepareOutgoing(aliceId, bobId, "hello", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                as PqMessageSealer.Outgoing.Plain
        alice.onSendSucceeded(aliceId, bobId, first)
        bob.processIncoming(bobId, aliceId, first.content, first.extraFields)

        val second =
            bob.prepareOutgoing(bobId, aliceId, "hi", PqMode.OPPORTUNISTIC, LinkCost.CHEAP)
                as PqMessageSealer.Outgoing.Sealed
        bob.onSendSucceeded(bobId, aliceId, second)
        alice.processIncoming(aliceId, bobId, second.content, second.extraFields)
    }
}

private class FakeEncryptor : SecretBlobEncryptor {
    override fun encryptBlobWithDeviceKey(plainData: ByteArray): ByteArray = byteArrayOf(MARKER) + plainData

    override fun decryptBlobWithDeviceKey(encryptedData: ByteArray): ByteArray {
        if (encryptedData.isEmpty() || encryptedData[0] != MARKER) {
            throw CorruptedKeyException("not a wrapped blob")
        }
        return encryptedData.copyOfRange(1, encryptedData.size)
    }

    private companion object {
        const val MARKER: Byte = 0x7A
    }
}

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
