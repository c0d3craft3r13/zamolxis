package network.zamolxis.crypto.pq

import java.security.MessageDigest

/**
 * Wire encoding for [HybridPublicKey], plus the short fingerprint used to refer
 * to a key without carrying it.
 *
 * Two forms exist because the full key is expensive to move. At 1216 bytes it
 * dwarfs a typical Reticulum announce (tens of bytes), and announces are
 * rebroadcast by every transport node on the mesh — so putting a full key in one
 * spends other people's airtime, not just yours. The fingerprint lets a peer
 * advertise *that* it has a hybrid key, and prove later that the key it hands
 * over is the same one it advertised.
 */
public object HybridKeyCodec {
    /** Version byte on the full-key encoding, so the format can change later. */
    private const val VERSION: Byte = 1

    /**
     * Fingerprint length in bytes. 16 bytes of SHA-256 leaves a 128-bit preimage
     * barrier and a 64-bit collision barrier — ample for binding a key to an
     * advertisement, and small enough to sit in an announce unnoticed.
     */
    public const val FINGERPRINT_BYTES: Int = 16

    /** Size of the full encoded key: version + X25519 + ML-KEM. */
    public const val ENCODED_BYTES: Int =
        1 + HybridKem.X25519_KEY_BYTES + HybridKem.ML_KEM_PUBLIC_KEY_BYTES

    /** Encode a public key for transmission. */
    public fun encode(key: HybridPublicKey): ByteArray = byteArrayOf(VERSION) + key.x25519 + key.mlKem

    /**
     * Decode a key produced by [encode].
     *
     * @throws HybridKemException if the input is the wrong length or version.
     *   A malformed key is always an error worth surfacing: silently treating a
     *   peer as PQ-incapable would downgrade the conversation without anyone
     *   noticing, which is precisely what an attacker stripping keys wants.
     */
    public fun decode(encoded: ByteArray): HybridPublicKey {
        if (encoded.size != ENCODED_BYTES) {
            throw HybridKemException(
                "Encoded hybrid key must be $ENCODED_BYTES bytes, got ${encoded.size}",
            )
        }
        if (encoded[0] != VERSION) {
            throw HybridKemException("Unsupported hybrid key encoding version")
        }

        val x25519End = 1 + HybridKem.X25519_KEY_BYTES
        return HybridPublicKey(
            x25519 = encoded.copyOfRange(1, x25519End),
            mlKem = encoded.copyOfRange(x25519End, ENCODED_BYTES),
        )
    }

    /**
     * Encode a whole key pair, private halves included, for storage at rest.
     *
     * The result contains secret material. Callers must wrap it — on Android
     * that means the Keystore, the same treatment the Reticulum identity key
     * gets — and must never transmit it.
     *
     * The ML-KEM private half is length-prefixed rather than assumed fixed:
     * FIPS 203 permits both a 64-byte seed and a 2400-byte expanded form, and
     * which one a provider hands back is its choice, not ours.
     */
    public fun encodeKeyPair(keyPair: HybridKeyPair): ByteArray {
        val mlKemPrivate = keyPair.mlKemPrivate
        return byteArrayOf(VERSION) +
            keyPair.x25519Private +
            intToBigEndian(mlKemPrivate.size) +
            mlKemPrivate +
            keyPair.publicKey.x25519 +
            keyPair.publicKey.mlKem
    }

    /**
     * Restore a key pair produced by [encodeKeyPair].
     *
     * @throws HybridKemException if the blob is truncated, of an unknown
     *   version, or carries an implausible length prefix.
     */
    public fun decodeKeyPair(encoded: ByteArray): HybridKeyPair =
        try {
            parseKeyPair(encoded)
        } catch (e: IllegalArgumentException) {
            // The checks below use require(), and HybridPublicKey validates its own
            // sizes the same way. Funnelling both into one exception type keeps
            // callers from having to know which layer objected.
            throw HybridKemException(e.message ?: "Malformed hybrid key pair", e)
        }

    private fun parseKeyPair(encoded: ByteArray): HybridKeyPair {
        val header = 1 + HybridKem.X25519_KEY_BYTES + Int.SIZE_BYTES
        require(encoded.size >= header) { "Encoded key pair is truncated" }
        require(encoded[0] == VERSION) { "Unsupported hybrid key pair encoding version" }

        var offset = 1
        val x25519Private = encoded.copyOfRange(offset, offset + HybridKem.X25519_KEY_BYTES)
        offset += HybridKem.X25519_KEY_BYTES

        val mlKemPrivateLength = bigEndianToInt(encoded, offset)
        offset += Int.SIZE_BYTES
        // Bound the prefix before allocating: a hostile or corrupt blob claiming
        // a huge length must not turn into an out-of-memory error.
        require(mlKemPrivateLength in 1..MAX_ML_KEM_PRIVATE_BYTES) {
            "Implausible ML-KEM private key length"
        }

        val remaining = encoded.size - offset
        val expected = mlKemPrivateLength + HybridKem.X25519_KEY_BYTES + HybridKem.ML_KEM_PUBLIC_KEY_BYTES
        require(remaining == expected) { "Encoded key pair has trailing or missing bytes" }

        val mlKemPrivate = encoded.copyOfRange(offset, offset + mlKemPrivateLength)
        offset += mlKemPrivateLength
        val x25519Public = encoded.copyOfRange(offset, offset + HybridKem.X25519_KEY_BYTES)
        offset += HybridKem.X25519_KEY_BYTES
        val mlKemPublic = encoded.copyOfRange(offset, encoded.size)

        return HybridKeyPair(
            publicKey = HybridPublicKey(x25519 = x25519Public, mlKem = mlKemPublic),
            x25519Private = x25519Private,
            mlKemPrivate = mlKemPrivate,
        )
    }

    /** Generous ceiling on the ML-KEM private half; the expanded form is 2400 bytes. */
    private const val MAX_ML_KEM_PRIVATE_BYTES: Int = 4096

    private fun intToBigEndian(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun bigEndianToInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /**
     * Short, stable identifier for a key: the first [FINGERPRINT_BYTES] of
     * SHA-256 over its full encoding.
     *
     * Covers both halves, so a peer cannot advertise one fingerprint and then
     * supply a key with a swapped X25519 or ML-KEM component.
     */
    public fun fingerprint(key: HybridPublicKey): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(encode(key))
            .copyOf(FINGERPRINT_BYTES)

    /**
     * Whether [key] is the key that [fingerprint] advertised.
     *
     * Compared in constant time. The comparison is not secret, but a
     * short-circuiting `contentEquals` on a security check is the kind of habit
     * that eventually gets applied to something that is.
     */
    public fun matchesFingerprint(
        key: HybridPublicKey,
        fingerprint: ByteArray,
    ): Boolean {
        val expected = fingerprint(key)
        if (expected.size != fingerprint.size) return false
        var difference = 0
        for (index in expected.indices) {
            difference = difference or (expected[index].toInt() xor fingerprint[index].toInt())
        }
        return difference == 0
    }
}
