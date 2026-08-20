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
import network.zamolxis.app.data.model.PqProtection
import network.zamolxis.app.data.repository.PqKeyRepository
import network.zamolxis.app.rns.api.util.LxmfFields
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.HybridKeyCodec
import network.zamolxis.crypto.pq.LinkCost
import network.zamolxis.crypto.pq.PlainReason
import network.zamolxis.crypto.pq.PqEnvelope
import network.zamolxis.crypto.pq.PqKeyExchange
import network.zamolxis.crypto.pq.PqMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // Named wrappers so every call site reads as the conversation direction it is,
    // and so the AAD pair stays consistent without repeating it 40 times. In these
    // tests an identity's hash doubles as its destination hash.
    private suspend fun aliceSends(
        content: String,
        mode: PqMode = PqMode.OPPORTUNISTIC,
        link: LinkCost = LinkCost.CHEAP,
        peer: String = bobId,
        payload: SealedPayload = SealedPayload(content),
    ) = alice.prepareOutgoing(
        identityHash = aliceId,
        ourDestinationHash = aliceId,
        peerHash = peer,
        payload = payload,
        mode = mode,
        linkCost = link,
    )

    private suspend fun bobSends(
        content: String,
        mode: PqMode = PqMode.OPPORTUNISTIC,
        link: LinkCost = LinkCost.CHEAP,
    ) = bob.prepareOutgoing(
        identityHash = bobId,
        ourDestinationHash = bobId,
        peerHash = aliceId,
        payload = SealedPayload(content),
        mode = mode,
        linkCost = link,
    )

    private suspend fun bobReceives(
        fields: Map<Int, ByteArray>,
        fallback: String = "",
        hasUnsealedAttachments: Boolean = false,
    ) = bob.processIncoming(
        identityHash = bobId,
        ourDestinationHash = bobId,
        peerHash = aliceId,
        fallbackContent = fallback,
        fields = fields,
        hasUnsealedAttachments = hasUnsealedAttachments,
    )

    private suspend fun aliceReceives(
        fields: Map<Int, ByteArray>,
        fallback: String = "",
        hasUnsealedAttachments: Boolean = false,
    ) = alice.processIncoming(
        identityHash = aliceId,
        ourDestinationHash = aliceId,
        peerHash = bobId,
        fallbackContent = fallback,
        fields = fields,
        hasUnsealedAttachments = hasUnsealedAttachments,
    )

    /** An attachment big enough to fall outside the sealable limit. */
    private fun oversizedImage() =
        SealedPayload(
            content = "caption",
            image =
                SealedPayload.Image(
                    format = "png",
                    bytes = ByteArray((PqMessageSealer.MAX_SEALABLE_ATTACHMENT_BYTES + 1).toInt()),
                ),
        )

    // ------------------------------------------------------------ first contact

    @Test
    fun `the opening message is plain but carries our key`() =
        runTest {
            val plain = aliceSends("hello") as PqMessageSealer.Outgoing.Plain

            assertEquals("hello", plain.wire.content)
            assertEquals(PlainReason.PEER_UNSUPPORTED, plain.reason)
            assertTrue(plain.wire.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY))
        }

    @Test
    fun `a full exchange ends with both directions sealed`() =
        runTest {
            // 1. Alice opens; Bob takes in her key.
            val first = aliceSends("hello") as PqMessageSealer.Outgoing.Plain
            alice.onSendSucceeded(aliceId, bobId, first)
            val atBob = bobReceives(first.wire.extraFields, fallback = first.wire.content)
            assertEquals("hello", atBob.content)
            assertEquals(PqProtection.NONE, atBob.protection)

            // 2. Bob replies — sealed, and carrying his own key.
            val second = bobSends("hi back") as PqMessageSealer.Outgoing.Sealed
            bob.onSendSucceeded(bobId, aliceId, second)
            val atAlice = aliceReceives(second.wire.extraFields)
            assertEquals("hi back", atAlice.content)
            assertEquals(PqProtection.SEALED, atAlice.protection)

            // 3. Alice now seals too, and stops attaching her key.
            val third = aliceSends("sealed now") as PqMessageSealer.Outgoing.Sealed
            assertFalse(third.wire.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY))
            assertEquals("sealed now", bobReceives(third.wire.extraFields).content)
        }

    @Test
    fun `sealed messages carry no plaintext in the content slot`() =
        runTest {
            establishExchange()

            val sealed = aliceSends("ATTACKATDAWN") as PqMessageSealer.Outgoing.Sealed

            assertEquals("", sealed.wire.content)
            val blob = sealed.wire.extraFields[PqEnvelope.FIELD_SEALED_CONTENT]!!
            assertFalse(String(blob, Charsets.ISO_8859_1).contains("ATTACKATDAWN"))
        }

    // ------------------------------------------------------ direction binding

    @Test
    fun `a sealed payload does not open in the other direction`() =
        runTest {
            establishExchange()
            val sealed = aliceSends("for bob only") as PqMessageSealer.Outgoing.Sealed

            // Bob's own key pair, Bob's own ciphertext — but reflected back as though
            // Bob had sent it to Alice. The AAD binds sender and recipient, so this
            // must not open even though the KEM half would succeed.
            val reflected =
                bob.processIncoming(
                    identityHash = bobId,
                    ourDestinationHash = aliceId,
                    peerHash = bobId,
                    fallbackContent = "",
                    fields = sealed.wire.extraFields,
                )

            assertEquals(PqProtection.UNOPENED, reflected.protection)
        }

    // ------------------------------------------------------------------- modes

    @Test
    fun `off never seals and never attaches a key`() =
        runTest {
            establishExchange()

            val plain = aliceSends("plain", mode = PqMode.OFF) as PqMessageSealer.Outgoing.Plain

            assertEquals(PlainReason.DISABLED_BY_USER, plain.reason)
            assertEquals("plain", plain.wire.content)
        }

    @Test
    fun `opportunistic spares an expensive link the overhead`() =
        runTest {
            establishExchange()

            val outgoing = aliceSends("over lora", link = LinkCost.EXPENSIVE)

            assertEquals(
                PlainReason.LINK_TOO_EXPENSIVE,
                (outgoing as PqMessageSealer.Outgoing.Plain).reason,
            )
        }

    @Test
    fun `required seals even over an expensive link`() =
        runTest {
            establishExchange()

            val outgoing = aliceSends("must be sealed", mode = PqMode.REQUIRED, link = LinkCost.EXPENSIVE)

            assertTrue(outgoing is PqMessageSealer.Outgoing.Sealed)
        }

    @Test
    fun `required refuses rather than sending readable to a plain peer`() =
        runTest {
            val outgoing = aliceSends("secret", mode = PqMode.REQUIRED, peer = "stranger")

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
                    for (attachments in listOf(false, true)) {
                        val outgoing =
                            aliceSends(
                                "x",
                                mode = PqMode.REQUIRED,
                                link = link,
                                peer = peer,
                                payload = if (attachments) oversizedImage() else SealedPayload("x"),
                            )
                        assertTrue(
                            "REQUIRED leaked a plain send to $peer over $link (attachments=$attachments)",
                            outgoing !is PqMessageSealer.Outgoing.Plain,
                        )
                    }
                }
            }
        }

    // -------------------------------------------------------------- attachments

    @Test
    fun `an image goes inside the seal and comes back out intact`() =
        runTest {
            establishExchange()
            val bytes = ByteArray(4096) { (it % 253).toByte() }

            val sealed =
                aliceSends(
                    "caption",
                    payload = SealedPayload("caption", image = SealedPayload.Image("png", bytes)),
                ) as PqMessageSealer.Outgoing.Sealed

            // Nothing recognisable is left on the wire: no image argument, no
            // plaintext content, and the bytes do not appear in the blob.
            assertNull(sealed.wire.imageData)
            assertEquals("", sealed.wire.content)
            assertEquals(PqProtection.SEALED, sealed.protection)

            val incoming = bobReceives(sealed.wire.extraFields)
            assertEquals("caption", incoming.content)
            val image = incoming.unsealedFields[LxmfFields.FIELD_IMAGE] as List<*>
            assertEquals("png", image[0])
            assertArrayEquals(bytes, image[1] as ByteArray)
        }

    @Test
    fun `files and a voice note go inside the seal`() =
        runTest {
            establishExchange()

            val sealed =
                aliceSends(
                    "",
                    payload =
                        SealedPayload(
                            content = "",
                            files = listOf(SealedPayload.FileAttachment("notes.txt", byteArrayOf(1, 2))),
                            audio = SealedPayload.Audio(LxmfFields.AM_OPUS_OGG, byteArrayOf(3, 4)),
                        ),
                ) as PqMessageSealer.Outgoing.Sealed

            assertNull(sealed.wire.fileAttachments)
            assertNull(sealed.wire.audio)

            val incoming = bobReceives(sealed.wire.extraFields)
            val files = incoming.unsealedFields[LxmfFields.FIELD_FILE_ATTACHMENTS] as List<*>
            assertEquals("notes.txt", (files.single() as List<*>)[0])
            val audio = incoming.unsealedFields[LxmfFields.FIELD_AUDIO] as List<*>
            assertEquals(LxmfFields.AM_OPUS_OGG, audio[0])
        }

    @Test
    fun `a reply quote is sealed rather than left in the clear`() =
        runTest {
            establishExchange()

            val sealed =
                aliceSends(
                    "agreed",
                    payload = SealedPayload("agreed", replyQuote = "the original text"),
                ) as PqMessageSealer.Outgoing.Sealed

            // The quote is the content of an earlier message; publishing it beside
            // the ciphertext would undo that message's protection.
            assertNull(sealed.wire.replyQuote)
            val blob = sealed.wire.extraFields[PqEnvelope.FIELD_SEALED_CONTENT]!!
            assertFalse(String(blob, Charsets.ISO_8859_1).contains("the original text"))

            val incoming = bobReceives(sealed.wire.extraFields)
            assertArrayEquals(
                "the original text".toByteArray(),
                incoming.unsealedFields[LxmfFields.FIELD_REPLY_QUOTE] as ByteArray,
            )
        }

    @Test
    fun `an attachment too large to seal travels outside it, and says so`() =
        runTest {
            establishExchange()

            val sealed = aliceSends("caption", payload = oversizedImage()) as PqMessageSealer.Outgoing.Sealed

            // The text still gets the layer; the photo goes as it always did, and the
            // message is labelled for what it is rather than as fully protected.
            assertEquals(PqProtection.SEALED_PARTIAL, sealed.protection)
            assertNotNull(sealed.wire.imageData)
            assertEquals("", sealed.wire.content)
            assertEquals(
                "caption",
                bobReceives(sealed.wire.extraFields, hasUnsealedAttachments = true).content,
            )
        }

    @Test
    fun `required refuses an attachment too large to seal`() =
        runTest {
            establishExchange()

            val outgoing = aliceSends("photo", mode = PqMode.REQUIRED, payload = oversizedImage())

            assertEquals(
                PlainReason.ATTACHMENT_NOT_SEALABLE,
                (outgoing as PqMessageSealer.Outgoing.Refused).reason,
            )
        }

    @Test
    fun `an attachment left outside the seal is reported as partial on receive`() =
        runTest {
            establishExchange()
            val sealed = aliceSends("with photo", payload = oversizedImage()) as PqMessageSealer.Outgoing.Sealed

            val incoming = bobReceives(sealed.wire.extraFields, hasUnsealedAttachments = true)

            assertEquals(PqProtection.SEALED_PARTIAL, incoming.protection)
        }

    @Test
    fun `an attachment at the limit is still sealed`() =
        runTest {
            establishExchange()
            val atLimit =
                SealedPayload(
                    content = "",
                    image =
                        SealedPayload.Image(
                            "png",
                            ByteArray(PqMessageSealer.MAX_SEALABLE_ATTACHMENT_BYTES.toInt()),
                        ),
                )

            val sealed = aliceSends("", payload = atLimit) as PqMessageSealer.Outgoing.Sealed

            assertEquals(PqProtection.SEALED, sealed.protection)
            assertNull(sealed.wire.imageData)
        }

    // -------------------------------------------------------------- key trouble

    @Test
    fun `a substituted key is reported and stops sealing`() =
        runTest {
            val bobKey = bobRepo.ourPublicKey(bobId)!!
            aliceRepo.recordAnnouncedFingerprint(bobId, HybridKeyCodec.fingerprint(bobKey))

            // An attacker's key arrives instead of Bob's.
            val attacker = kem.generateKeyPair().publicKey
            val incoming = aliceReceives(PqEnvelope.keyOnlyFields(attacker), fallback = "hi")

            assertEquals(PqKeyExchange.KeyAcceptance.FingerprintMismatch, incoming.keyProblem)
            assertTrue(aliceSends("reply") is PqMessageSealer.Outgoing.Plain)
        }

    @Test
    fun `a substituted key is recorded so the user can be told`() =
        runTest {
            val bobKey = bobRepo.ourPublicKey(bobId)!!
            aliceRepo.recordAnnouncedFingerprint(bobId, HybridKeyCodec.fingerprint(bobKey))

            aliceReceives(PqEnvelope.keyOnlyFields(kem.generateKeyPair().publicKey))

            // Used to be a log line only, which meant nobody ever saw the one event
            // that says the announce or the message was altered in transit.
            assertTrue(aliceRepo.hasFingerprintMismatch(bobId))
            aliceRepo.acknowledgeFingerprintMismatch(bobId)
            assertFalse(aliceRepo.hasFingerprintMismatch(bobId))
        }

    @Test
    fun `a changed key is reported and stops sealing`() =
        runTest {
            establishExchange()

            val replacement = kem.generateKeyPair().publicKey
            val incoming = aliceReceives(PqEnvelope.keyOnlyFields(replacement), fallback = "hi")

            assertEquals(PqKeyExchange.KeyAcceptance.ChangedKey, incoming.keyProblem)
            assertTrue(aliceSends("x") is PqMessageSealer.Outgoing.Plain)
        }

    @Test
    fun `required refuses once a key change is unresolved`() =
        runTest {
            establishExchange()
            aliceReceives(PqEnvelope.keyOnlyFields(kem.generateKeyPair().publicKey))

            assertTrue(aliceSends("x", mode = PqMode.REQUIRED) is PqMessageSealer.Outgoing.Refused)
        }

    @Test
    fun `rotating our key makes it attachable to peers again`() =
        runTest {
            establishExchange()
            // Bob believes he holds Alice's key, so she has stopped attaching it.
            assertFalse(
                (aliceSends("x") as PqMessageSealer.Outgoing.Sealed)
                    .wire
                    .extraFields
                    .containsKey(PqEnvelope.FIELD_SENDER_KEY),
            )

            val rotated = aliceRepo.rotateOurKeyPair(aliceId)
            assertNotNull(rotated)

            // Without clearing delivery records the replacement would never reach
            // anyone, and every peer would keep sealing to a key Alice no longer has.
            assertTrue(
                (aliceSends("x") as PqMessageSealer.Outgoing.Sealed)
                    .wire
                    .extraFields
                    .containsKey(PqEnvelope.FIELD_SENDER_KEY),
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
                        val sealedByIndicator = alice.isConversationSealed(aliceId, peer, mode, link)
                        val sent = aliceSends("x", mode = mode, link = link, peer = peer)
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
            assertFalse(alice.isConversationSealed(aliceId, bobId, PqMode.OPPORTUNISTIC, LinkCost.CHEAP))
        }

    @Test
    fun `the indicator goes off while a key change is unresolved`() =
        runTest {
            establishExchange()
            assertTrue(alice.isConversationSealed(aliceId, bobId, PqMode.OPPORTUNISTIC, LinkCost.CHEAP))

            aliceReceives(PqEnvelope.keyOnlyFields(kem.generateKeyPair().publicKey))

            assertFalse(alice.isConversationSealed(aliceId, bobId, PqMode.OPPORTUNISTIC, LinkCost.CHEAP))
        }

    // ---------------------------------------------------------------- incoming

    @Test
    fun `an ordinary message passes through untouched`() =
        runTest {
            val incoming = aliceReceives(emptyMap(), fallback = "just text")

            assertEquals("just text", incoming.content)
            assertEquals(PqProtection.NONE, incoming.protection)
            assertNull(incoming.keyProblem)
        }

    @Test
    fun `a sealed message we cannot open is kept as unopened, not dropped`() =
        runTest {
            // Sealed to someone else entirely.
            val stranger = kem.generateKeyPair().publicKey
            val fields = PqEnvelope.fieldsFor(kem.seal(stranger, "not for you".toByteArray()), null)

            val incoming = aliceReceives(fields)

            // Dropping it would leave the sender holding a delivery proof for a
            // message the recipient never learns exists.
            assertEquals(PqProtection.UNOPENED, incoming.protection)
            assertEquals("", incoming.content)
        }

    @Test
    fun `a tampered sealed message is reported as unopened`() =
        runTest {
            establishExchange()
            val sealed = aliceSends("intact") as PqMessageSealer.Outgoing.Sealed
            val blob = sealed.wire.extraFields[PqEnvelope.FIELD_SEALED_CONTENT]!!.copyOf()
            blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()

            val incoming = bobReceives(mapOf(PqEnvelope.FIELD_SEALED_CONTENT to blob))

            assertEquals(PqProtection.UNOPENED, incoming.protection)
        }

    @Test
    fun `a malformed sender key does not read as no key`() =
        runTest {
            val incoming =
                aliceReceives(mapOf(PqEnvelope.FIELD_SENDER_KEY to ByteArray(9)), fallback = "text")

            assertEquals("text", incoming.content)
            assertEquals(PqProtection.NONE, incoming.protection)
            // The peer stays un-established rather than being recorded as plain.
            assertNull(aliceDao.peerKeys[bobId]?.publicKey)
        }

    @Test
    fun `a sealed message still opens when the attached sender key is corrupt`() =
        runTest {
            establishExchange()
            val sealed = aliceSends("still readable") as PqMessageSealer.Outgoing.Sealed

            // The payload is sealed to Bob's key; a mangled sender-key field is a
            // separate concern and must not cost him the message.
            val fields = sealed.wire.extraFields + mapOf(PqEnvelope.FIELD_SENDER_KEY to ByteArray(11))

            val incoming = bobReceives(fields)

            assertEquals("still readable", incoming.content)
            assertEquals(PqProtection.SEALED, incoming.protection)
        }

    @Test
    fun `unicode content survives the round trip`() =
        runTest {
            establishExchange()
            val message = "Здравей, свят — 🕊 مرحبا"

            val sealed = aliceSends(message) as PqMessageSealer.Outgoing.Sealed

            assertEquals(message, bobReceives(sealed.wire.extraFields).content)
        }

    /** Runs the two-message handshake so both sides hold each other's key. */
    private suspend fun establishExchange() {
        val first = aliceSends("hello") as PqMessageSealer.Outgoing.Plain
        alice.onSendSucceeded(aliceId, bobId, first)
        bobReceives(first.wire.extraFields, fallback = first.wire.content)

        val second = bobSends("hi") as PqMessageSealer.Outgoing.Sealed
        bob.onSendSucceeded(bobId, aliceId, second)
        aliceReceives(second.wire.extraFields, fallback = second.wire.content)
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
