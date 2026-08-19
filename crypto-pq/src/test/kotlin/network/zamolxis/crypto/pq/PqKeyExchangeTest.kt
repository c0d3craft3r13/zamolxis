package network.zamolxis.crypto.pq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PqKeyExchangeTest {
    private val kem = HybridKem(SecureRandom())

    private fun state(
        knownKey: HybridPublicKey? = null,
        announcedFingerprint: ByteArray? = null,
        ourKeyDelivered: Boolean = false,
    ) = PqKeyExchange.PeerState(knownKey, announcedFingerprint, ourKeyDelivered)

    // --------------------------------------------------------------- support

    @Test
    fun `a peer with no announcement is unsupported`() {
        assertEquals(PeerPqSupport.UNSUPPORTED, PqKeyExchange.support(state()))
    }

    @Test
    fun `an advertised fingerprint without a key means the key is still missing`() {
        val fingerprint = HybridKeyCodec.fingerprint(kem.generateKeyPair().publicKey)

        assertEquals(
            PeerPqSupport.ADVERTISED_KEY_MISSING,
            PqKeyExchange.support(state(announcedFingerprint = fingerprint)),
        )
    }

    @Test
    fun `a stored key means the peer is ready`() {
        val key = kem.generateKeyPair().publicKey
        assertEquals(PeerPqSupport.KEY_KNOWN, PqKeyExchange.support(state(knownKey = key)))
    }

    // ------------------------------------------------------- attaching our key

    @Test
    fun `our key is attached until it has been delivered`() {
        assertTrue(PqKeyExchange.shouldAttachOurKey(state(ourKeyDelivered = false)))
        assertFalse(PqKeyExchange.shouldAttachOurKey(state(ourKeyDelivered = true)))
    }

    // ------------------------------------------------- accepting a peer's key

    @Test
    fun `a first key matching the advertised fingerprint is accepted`() {
        val peerKey = kem.generateKeyPair().publicKey
        val fingerprint = HybridKeyCodec.fingerprint(peerKey)

        assertEquals(
            PqKeyExchange.KeyAcceptance.Accepted,
            PqKeyExchange.acceptIncomingKey(peerKey, state(announcedFingerprint = fingerprint)),
        )
    }

    @Test
    fun `a first key is accepted when the peer never announced a fingerprint`() {
        val peerKey = kem.generateKeyPair().publicKey

        assertEquals(
            PqKeyExchange.KeyAcceptance.Accepted,
            PqKeyExchange.acceptIncomingKey(peerKey, state()),
        )
    }

    @Test
    fun `a key contradicting the advertised fingerprint is rejected`() {
        val honest = kem.generateKeyPair().publicKey
        val attacker = kem.generateKeyPair().publicKey

        assertEquals(
            PqKeyExchange.KeyAcceptance.FingerprintMismatch,
            PqKeyExchange.acceptIncomingKey(
                attacker,
                state(announcedFingerprint = HybridKeyCodec.fingerprint(honest)),
            ),
        )
    }

    @Test
    fun `a half-swapped key is rejected against the fingerprint`() {
        val honest = kem.generateKeyPair().publicKey
        val attacker = kem.generateKeyPair().publicKey
        val advertised = HybridKeyCodec.fingerprint(honest)

        // Keep the honest X25519 half so the classical agreement still succeeds,
        // and swap the ML-KEM half — the shape of a downgrade attempt.
        val spliced = HybridPublicKey(x25519 = honest.x25519, mlKem = attacker.mlKem)

        assertEquals(
            PqKeyExchange.KeyAcceptance.FingerprintMismatch,
            PqKeyExchange.acceptIncomingKey(spliced, state(announcedFingerprint = advertised)),
        )
    }

    @Test
    fun `re-sending the same key is recognised, not treated as a change`() {
        val peerKey = kem.generateKeyPair().publicKey

        assertEquals(
            PqKeyExchange.KeyAcceptance.AlreadyKnown,
            PqKeyExchange.acceptIncomingKey(peerKey, state(knownKey = peerKey)),
        )
    }

    @Test
    fun `an equal key arriving as a fresh copy is still recognised`() {
        val peerKey = kem.generateKeyPair().publicKey
        val overTheWire = HybridKeyCodec.decode(HybridKeyCodec.encode(peerKey))

        assertEquals(
            PqKeyExchange.KeyAcceptance.AlreadyKnown,
            PqKeyExchange.acceptIncomingKey(overTheWire, state(knownKey = peerKey)),
        )
    }

    @Test
    fun `a different key from a known peer is surfaced, never silently replaced`() {
        val original = kem.generateKeyPair().publicKey
        val replacement = kem.generateKeyPair().publicKey

        assertEquals(
            PqKeyExchange.KeyAcceptance.ChangedKey,
            PqKeyExchange.acceptIncomingKey(replacement, state(knownKey = original)),
        )
    }

    @Test
    fun `fingerprint mismatch outranks a key change`() {
        val original = kem.generateKeyPair().publicKey
        val attacker = kem.generateKeyPair().publicKey

        // The peer holds one key, announced another, and now offers a third.
        // The fingerprint check must fire first — it is the stronger signal.
        assertEquals(
            PqKeyExchange.KeyAcceptance.FingerprintMismatch,
            PqKeyExchange.acceptIncomingKey(
                attacker,
                state(
                    knownKey = original,
                    announcedFingerprint = HybridKeyCodec.fingerprint(original),
                ),
            ),
        )
    }

    @Test
    fun `no incoming key is ever accepted against a mismatched fingerprint`() {
        val advertised = HybridKeyCodec.fingerprint(kem.generateKeyPair().publicKey)

        repeat(20) {
            val offered = kem.generateKeyPair().publicKey
            for (known in listOf(null, offered, kem.generateKeyPair().publicKey)) {
                assertEquals(
                    PqKeyExchange.KeyAcceptance.FingerprintMismatch,
                    PqKeyExchange.acceptIncomingKey(
                        offered,
                        state(knownKey = known, announcedFingerprint = advertised),
                    ),
                )
            }
        }
    }
}
