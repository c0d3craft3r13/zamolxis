package network.zamolxis.app.migration

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [DeviceKeyWrapper] backed by the Android Keystore.
 *
 * The key is generated inside the Keystore and marked non-exportable, so it
 * exists only as a handle: the bytes never reach this process, let alone a
 * copied file. That is what makes a device slot mean what it says — a container
 * carrying one can be opened on this phone and, with the file alone, on no
 * other. Not "ten attempts elsewhere". None.
 *
 * ## Why this is the slot that can actually self-destruct
 *
 * A counter written into a file is advice; the attacker runs their own reader
 * and ignores it. A Keystore entry is different. [destroy] deletes the key
 * itself, and no copy of the container — not the one on this phone, not one
 * taken last week — can be opened through this slot again. The destruction is
 * of the way in, not of one file, which is the only version of the idea that
 * survives the attacker having made a copy first.
 *
 * ## What it does not do
 *
 * Nothing here protects the container on someone else's machine. The password
 * and recovery slots do that, and a container should always carry one of them,
 * or losing the phone loses the backup.
 */
class KeystoreDeviceKeyWrapper(
    private val alias: String = DEFAULT_ALIAS,
) : DeviceKeyWrapper {
    companion object {
        private const val TAG = "KeystoreDeviceKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** One key for all of this install's containers; [destroy] closes every device slot at once. */
        const val DEFAULT_ALIAS = "zamolxis_container_device_key"

        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val KEY_BITS = 256
    }

    override fun wrap(dataKey: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.iv + cipher.doFinal(dataKey)
        }.onFailure {
            // A device without a usable Keystore simply gets no device slot; the
            // export still happens, with the slots that do work.
            Log.w(TAG, "Could not wrap with the hardware key: ${it.message}")
        }.getOrNull()

    override fun unwrap(blob: ByteArray): ByteArray? {
        if (blob.size <= NONCE_BYTES) return null
        return runCatching {
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, blob, 0, NONCE_BYTES),
            )
            cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        }.getOrNull()
    }

    /**
     * Delete the wrapping key.
     *
     * Irreversible by design and by hardware: the key material was never
     * outside the Keystore, so there is nothing anywhere to restore it from.
     */
    override fun destroy() {
        runCatching {
            keyStore().takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
            Log.i(TAG, "Hardware-backed container key destroyed")
        }.onFailure { Log.e(TAG, "Could not destroy the hardware key", it) }
    }

    /** Whether a device slot could be opened at all right now. */
    fun exists(): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey =
        existingKey() ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(KEY_BITS)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
}
