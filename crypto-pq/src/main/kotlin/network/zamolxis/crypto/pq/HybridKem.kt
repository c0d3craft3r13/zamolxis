package network.zamolxis.crypto.pq

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid X25519 + ML-KEM-768 sealing for message *content*.
 *
 * ## Why this exists
 *
 * Reticulum encrypts everything in transit, but with classical elliptic-curve
 * cryptography. That is sound today and worthless against an adversary who
 * records ciphertext now and decrypts it once a cryptographically relevant
 * quantum computer exists — a realistic posture for anyone whose traffic is
 * worth archiving. This layer sits *above* Reticulum: it seals the payload
 * before LXMF ever sees it, so the transport, the routing and the wider
 * Reticulum network are untouched and remain fully interoperable.
 *
 * ## Construction
 *
 * Nothing here implements a primitive. ML-KEM-768 (FIPS 203), X25519, HKDF and
 * AES-GCM all come from vetted implementations; this class only composes them,
 * following the established hybrid-KEM pattern also used by TLS hybrid key
 * exchange:
 *
 *  1. encapsulate to the recipient's ML-KEM key      -> `ct_pq`, `ss_pq`
 *  2. ephemeral X25519 against the recipient's key   -> `epk`,   `ss_x`
 *  3. `key = HKDF-SHA256(ikm = ss_x || ss_pq, salt, info = transcript)`
 *  4. AES-256-GCM under `key`
 *
 * Both shared secrets feed the KDF, so the payload stays secret unless *both*
 * X25519 and ML-KEM are broken. The full transcript — version, ephemeral key,
 * KEM ciphertext and both recipient public keys — is bound into `info`, which
 * stops components from different sessions being mixed and ties the derived
 * key to the intended recipient.
 *
 * ## What this does and does not give you
 *
 * It gives confidentiality and integrity of the content against a
 * harvest-now-decrypt-later adversary. It is *not* authenticated as to sender:
 * anyone holding the recipient's public key can produce a valid sealed message.
 * Sender authenticity continues to come from the LXMF/Reticulum layer, which
 * signs with the sender's identity. It also provides no forward secrecy for the
 * ML-KEM half, since that key is long-lived — rotating it is the mitigation.
 *
 * ## Cost
 *
 * The overhead is [OVERHEAD_BYTES] bytes per message, dominated by the 1088-byte
 * ML-KEM ciphertext. On broadband links this is noise. On a LoRa interface at
 * ~1 kbps it is several seconds of extra airtime, so callers should decide per
 * transport whether to seal rather than sealing unconditionally.
 *
 * Instances are stateless and safe to share between threads.
 */
public class HybridKem(
    private val random: SecureRandom = SecureRandom(),
) {
    /** Generate a fresh hybrid key pair for a local identity. */
    public fun generateKeyPair(): HybridKeyPair {
        val x25519Private = X25519PrivateKeyParameters(random)

        val kemGenerator = MLKEMKeyPairGenerator()
        kemGenerator.init(MLKEMKeyGenerationParameters(random, MLKEMParameters.ml_kem_768))
        val kemPair = kemGenerator.generateKeyPair()

        return HybridKeyPair(
            publicKey =
                HybridPublicKey(
                    x25519 = x25519Private.generatePublicKey().encoded,
                    mlKem = (kemPair.public as MLKEMPublicKeyParameters).encoded,
                ),
            x25519Private = x25519Private.encoded,
            mlKemPrivate = (kemPair.private as MLKEMPrivateKeyParameters).encoded,
        )
    }

    /**
     * Seal [plaintext] to [recipient].
     *
     * @param aad additional authenticated data — covered by the GCM tag but not
     *   encrypted. Bind context here (message id, destination hash) so a sealed
     *   payload cannot be replayed into a different message.
     * @return the wire blob described in [OVERHEAD_BYTES]
     */
    public fun seal(
        recipient: HybridPublicKey,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val kemPublic = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, recipient.mlKem)
        val encapsulated = MLKEMGenerator(random).generateEncapsulated(kemPublic)
        val kemCiphertext = encapsulated.encapsulation
        val kemSecret = encapsulated.secret

        val ephemeralPrivate = X25519PrivateKeyParameters(random)
        val ephemeralPublic = ephemeralPrivate.generatePublicKey().encoded
        val x25519Secret = agree(ephemeralPrivate, X25519PublicKeyParameters(recipient.x25519, 0))

        val key =
            deriveKey(
                x25519Secret = x25519Secret,
                kemSecret = kemSecret,
                ephemeralPublic = ephemeralPublic,
                kemCiphertext = kemCiphertext,
                recipient = recipient,
            )

        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed =
            try {
                aesGcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)
            } finally {
                key.fill(0)
                x25519Secret.fill(0)
                kemSecret.fill(0)
            }

        return byteArrayOf(WIRE_VERSION) + ephemeralPublic + kemCiphertext + nonce + sealed
    }

    /**
     * Open a blob produced by [seal].
     *
     * @throws HybridKemException if the blob is malformed, was sealed to another
     *   recipient, or was altered in any way. The message is intentionally
     *   uninformative — distinguishing "bad tag" from "wrong key" leaks to an
     *   attacker probing with modified ciphertexts.
     */
    public fun open(
        keyPair: HybridKeyPair,
        wire: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val parts = parseWire(wire)

        // One catch-all, on purpose. Every downstream failure — a KEM ciphertext
        // that decapsulates to the wrong secret, a bad GCM tag, malformed key
        // material — must be indistinguishable to the caller. Reporting *which*
        // step failed hands an attacker an oracle to probe with modified
        // ciphertexts, which is exactly how padding-oracle attacks work.
        @Suppress("TooGenericExceptionCaught")
        return try {
            decrypt(keyPair, parts, aad)
        } catch (e: Exception) {
            throw HybridKemException("Cannot open sealed message", e)
        }
    }

    private fun parseWire(wire: ByteArray): SealedParts {
        if (wire.size < OVERHEAD_BYTES) {
            throw HybridKemException("Sealed message is truncated")
        }
        if (wire[0] != WIRE_VERSION) {
            throw HybridKemException("Unsupported sealed-message version")
        }

        var offset = 1
        val ephemeralPublic = wire.copyOfRange(offset, offset + X25519_KEY_BYTES)
        offset += X25519_KEY_BYTES
        val kemCiphertext = wire.copyOfRange(offset, offset + ML_KEM_CIPHERTEXT_BYTES)
        offset += ML_KEM_CIPHERTEXT_BYTES
        val nonce = wire.copyOfRange(offset, offset + NONCE_BYTES)
        offset += NONCE_BYTES

        return SealedParts(
            ephemeralPublic = ephemeralPublic,
            kemCiphertext = kemCiphertext,
            nonce = nonce,
            body = wire.copyOfRange(offset, wire.size),
        )
    }

    private fun decrypt(
        keyPair: HybridKeyPair,
        parts: SealedParts,
        aad: ByteArray,
    ): ByteArray {
        val kemSecret =
            MLKEMExtractor(
                MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, keyPair.mlKemPrivate),
            ).extractSecret(parts.kemCiphertext)

        val x25519Secret =
            agree(
                X25519PrivateKeyParameters(keyPair.x25519Private, 0),
                X25519PublicKeyParameters(parts.ephemeralPublic, 0),
            )

        val key =
            deriveKey(
                x25519Secret = x25519Secret,
                kemSecret = kemSecret,
                ephemeralPublic = parts.ephemeralPublic,
                kemCiphertext = parts.kemCiphertext,
                recipient = keyPair.publicKey,
            )

        return try {
            aesGcm(Cipher.DECRYPT_MODE, key, parts.nonce, aad).doFinal(parts.body)
        } finally {
            key.fill(0)
            x25519Secret.fill(0)
            kemSecret.fill(0)
        }
    }

    private class SealedParts(
        val ephemeralPublic: ByteArray,
        val kemCiphertext: ByteArray,
        val nonce: ByteArray,
        val body: ByteArray,
    )

    private fun agree(
        privateKey: X25519PrivateKeyParameters,
        publicKey: X25519PublicKeyParameters,
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        return ByteArray(agreement.agreementSize).also { agreement.calculateAgreement(publicKey, it, 0) }
    }

    /**
     * HKDF-SHA256 over both shared secrets, with the whole transcript bound into
     * `info`. Concatenating the secrets as input keying material is the standard
     * hybrid combiner: the result is as strong as the stronger of the two.
     */
    private fun deriveKey(
        x25519Secret: ByteArray,
        kemSecret: ByteArray,
        ephemeralPublic: ByteArray,
        kemCiphertext: ByteArray,
        recipient: HybridPublicKey,
    ): ByteArray {
        val info =
            byteArrayOf(WIRE_VERSION) +
                ephemeralPublic +
                kemCiphertext +
                recipient.x25519 +
                recipient.mlKem
        val output = ByteArray(AES_KEY_BYTES)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(x25519Secret + kemSecret, HKDF_SALT, info))
            generateBytes(output, 0, output.size)
        }
        return output
    }

    private fun aesGcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            if (aad.isNotEmpty()) updateAAD(aad)
        }

    public companion object {
        /** Raw X25519 key length. */
        public const val X25519_KEY_BYTES: Int = 32

        /** ML-KEM-768 public key length (FIPS 203). */
        public const val ML_KEM_PUBLIC_KEY_BYTES: Int = 1184

        /** ML-KEM-768 ciphertext length (FIPS 203). */
        public const val ML_KEM_CIPHERTEXT_BYTES: Int = 1088

        internal const val NONCE_BYTES: Int = 12
        internal const val AES_KEY_BYTES: Int = 32
        internal const val GCM_TAG_BITS: Int = 128
        internal const val WIRE_VERSION: Byte = 1

        /**
         * Bytes added to the plaintext on the wire:
         * version(1) + ephemeral X25519(32) + ML-KEM ciphertext(1088) +
         * nonce(12) + GCM tag(16).
         */
        public const val OVERHEAD_BYTES: Int =
            1 + X25519_KEY_BYTES + ML_KEM_CIPHERTEXT_BYTES + NONCE_BYTES + (GCM_TAG_BITS / 8)

        /**
         * Domain separator. Any independent protocol reusing HKDF with these
         * inputs derives a different key, so secrets cannot cross protocols.
         */
        private val HKDF_SALT: ByteArray = "zamolxis/hybrid-kem/v1".toByteArray(Charsets.US_ASCII)
    }
}
