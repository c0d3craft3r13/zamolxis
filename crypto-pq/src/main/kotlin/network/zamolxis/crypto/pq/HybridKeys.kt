package network.zamolxis.crypto.pq

/**
 * A recipient's public key material for the hybrid content layer.
 *
 * Both halves are needed: the X25519 key alone leaves traffic open to a future
 * quantum adversary who recorded it today, and the ML-KEM key alone stakes
 * everything on a comparatively young lattice assumption. An attacker has to
 * break both to read the message.
 *
 * @property x25519 raw 32-byte X25519 public key
 * @property mlKem raw ML-KEM-768 public key ([HybridKem.ML_KEM_PUBLIC_KEY_BYTES] bytes)
 */
public data class HybridPublicKey(
    val x25519: ByteArray,
    val mlKem: ByteArray,
) {
    init {
        require(x25519.size == HybridKem.X25519_KEY_BYTES) {
            "X25519 public key must be ${HybridKem.X25519_KEY_BYTES} bytes, got ${x25519.size}"
        }
        require(mlKem.size == HybridKem.ML_KEM_PUBLIC_KEY_BYTES) {
            "ML-KEM public key must be ${HybridKem.ML_KEM_PUBLIC_KEY_BYTES} bytes, got ${mlKem.size}"
        }
    }

    // data class equals/hashCode compare ByteArray by identity, which silently
    // breaks key lookups; compare contents instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HybridPublicKey) return false
        return x25519.contentEquals(other.x25519) && mlKem.contentEquals(other.mlKem)
    }

    override fun hashCode(): Int = 31 * x25519.contentHashCode() + mlKem.contentHashCode()
}

/**
 * A local identity's hybrid key pair. The private halves never leave the device
 * and are never placed on the wire.
 */
public class HybridKeyPair(
    public val publicKey: HybridPublicKey,
    internal val x25519Private: ByteArray,
    internal val mlKemPrivate: ByteArray,
) {
    /**
     * Overwrite the private key material in place.
     *
     * Best-effort only: the JVM may already have copied these arrays during GC,
     * so this narrows the window rather than closing it.
     */
    public fun destroy() {
        x25519Private.fill(0)
        mlKemPrivate.fill(0)
    }
}

/** Raised when a sealed message cannot be opened. Carries no detail about why. */
public class HybridKemException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
