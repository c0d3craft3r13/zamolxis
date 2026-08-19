package network.zamolxis.app.data.crypto

/**
 * Wraps and unwraps a secret of arbitrary length with device-bound protection.
 *
 * Exists so callers can depend on the narrow capability they need instead of on
 * [IdentityKeyEncryptor] as a whole, and so tests can substitute a stand-in —
 * Robolectric provides no real AndroidKeyStore, and a test that cannot construct
 * the encryptor ends up not covering the logic around it either.
 */
interface SecretBlobEncryptor {
    /**
     * Protect [plainData] so it can be stored at rest.
     *
     * @throws IllegalArgumentException if [plainData] is empty
     */
    fun encryptBlobWithDeviceKey(plainData: ByteArray): ByteArray

    /**
     * Recover data produced by [encryptBlobWithDeviceKey].
     *
     * @throws CorruptedKeyException if the blob is malformed, of the wrong kind,
     *   or cannot be decrypted — typically because the device-bound key did not
     *   survive a restore onto different hardware.
     */
    fun decryptBlobWithDeviceKey(encryptedData: ByteArray): ByteArray
}
