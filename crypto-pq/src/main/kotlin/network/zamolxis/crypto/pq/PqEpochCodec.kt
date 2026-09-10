package network.zamolxis.crypto.pq

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The framing and key derivation behind [PqEpoch].
 *
 * Split out so the public object stays a description of the protocol rather than
 * a pile of byte arithmetic. Everything here is internal: an epoch root is as
 * powerful as the messages it opens, and nothing outside this module has a
 * reason to hold one.
 */

internal const val EPOCH_COUNTER_BYTES = 4

internal const val EPOCH_OPEN_HEADER_BYTES =
    1 + HybridKem.X25519_KEY_BYTES + HybridKem.ML_KEM_CIPHERTEXT_BYTES

internal const val EPOCH_CONTINUE_HEADER_BYTES =
    1 + PqEpoch.EPOCH_ID_BYTES + EPOCH_COUNTER_BYTES

internal val EPOCH_ID_SALT: ByteArray = "zamolxis/pq-epoch-id/v1".toByteArray(Charsets.US_ASCII)

internal val EPOCH_MESSAGE_SALT: ByteArray = "zamolxis/pq-epoch-message/v1".toByteArray(Charsets.US_ASCII)

/** The public half of an opening blob, once it has been checked. */
internal class EpochOpeningHeader(
    val ephemeralPublic: ByteArray,
    val kemCiphertext: ByteArray,
    val bytes: ByteArray,
    val body: ByteArray,
)

/** The public half of a continuation blob, once it has been checked. */
internal class EpochContinuationHeader(
    val epochId: ByteArray,
    val counter: Int,
    val bytes: ByteArray,
    val body: ByteArray,
)

/**
 * Split an opening blob, or refuse it.
 *
 * One exit rather than several: every rejection here means the same thing to the
 * caller — this is not an opening we can work with — and saying which check
 * failed would tell whoever is probing with altered blobs where to aim.
 */
internal fun openingHeaderOf(wire: ByteArray): EpochOpeningHeader {
    if (wire.size <= EPOCH_OPEN_HEADER_BYTES || wire[0] != PqEpoch.WIRE_OPEN) {
        throw HybridKemException("Not a usable epoch opening")
    }
    val x25519End = 1 + HybridKem.X25519_KEY_BYTES
    return EpochOpeningHeader(
        ephemeralPublic = wire.copyOfRange(1, x25519End),
        kemCiphertext = wire.copyOfRange(x25519End, EPOCH_OPEN_HEADER_BYTES),
        bytes = wire.copyOfRange(0, EPOCH_OPEN_HEADER_BYTES),
        body = wire.copyOfRange(EPOCH_OPEN_HEADER_BYTES, wire.size),
    )
}

/** Split a continuation blob and check it names [expectedEpochId], or refuse it. */
internal fun continuationHeaderOf(
    wire: ByteArray,
    expectedEpochId: ByteArray,
): EpochContinuationHeader {
    val header = peekContinuation(wire) ?: throw HybridKemException("Not a usable epoch continuation")
    if (!header.epochId.contentEquals(expectedEpochId)) {
        throw HybridKemException("Continuation belongs to a different epoch")
    }
    return header
}

/** Read a continuation's public header without judging it, or null if it is not one. */
internal fun peekContinuation(wire: ByteArray): EpochContinuationHeader? {
    if (wire.size <= EPOCH_CONTINUE_HEADER_BYTES || wire[0] != PqEpoch.WIRE_CONTINUE) return null
    return EpochContinuationHeader(
        epochId = wire.copyOfRange(1, 1 + PqEpoch.EPOCH_ID_BYTES),
        counter = wire.readBigEndian(1 + PqEpoch.EPOCH_ID_BYTES),
        bytes = wire.copyOfRange(0, EPOCH_CONTINUE_HEADER_BYTES),
        body = wire.copyOfRange(EPOCH_CONTINUE_HEADER_BYTES, wire.size),
    )
}

internal fun epochMessageKey(
    root: ByteArray,
    counter: Int,
): ByteArray = epochHkdf(root, EPOCH_MESSAGE_SALT, counter.toBigEndian(), PqEpoch.ROOT_BYTES)

/**
 * The nonce for a counter.
 *
 * Never transmitted — both ends compute it. Safe as a bare counter because the
 * key changes with it: a nonce need only be unique under one key, and here no
 * two messages share a key at all.
 */
internal fun epochNonce(counter: Int): ByteArray = ByteArray(HybridKem.NONCE_BYTES - EPOCH_COUNTER_BYTES) + counter.toBigEndian()

internal fun epochEncrypt(
    root: ByteArray,
    counter: Int,
    aad: ByteArray,
    plaintext: ByteArray,
): ByteArray = epochCrypt(Cipher.ENCRYPT_MODE, root, counter, aad, plaintext)

internal fun epochDecrypt(
    root: ByteArray,
    counter: Int,
    aad: ByteArray,
    body: ByteArray,
): ByteArray = epochCrypt(Cipher.DECRYPT_MODE, root, counter, aad, body)

private fun epochCrypt(
    mode: Int,
    root: ByteArray,
    counter: Int,
    aad: ByteArray,
    input: ByteArray,
): ByteArray {
    val key = epochMessageKey(root, counter)
    return try {
        Cipher
            .getInstance("AES/GCM/NoPadding")
            .apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(HybridKem.GCM_TAG_BITS, epochNonce(counter)))
                if (aad.isNotEmpty()) updateAAD(aad)
            }.doFinal(input)
    } finally {
        key.fill(0)
    }
}

internal fun epochHkdf(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray {
    val out = ByteArray(length)
    HKDFBytesGenerator(SHA256Digest()).apply {
        init(HKDFParameters(ikm, salt, info))
        generateBytes(out, 0, length)
    }
    return out
}

internal fun Int.toBigEndian(): ByteArray =
    byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        this.toByte(),
    )

internal fun ByteArray.readBigEndian(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 24) or
        ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or
        (this[at + 3].toInt() and 0xFF)
