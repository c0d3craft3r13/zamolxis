package network.zamolxis.app.migration

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The slots of a v3 container: the several different ways the one data key can
 * be locked away, and how each is opened again.
 *
 * Separate from [MigrationContainer] because the container is about the file —
 * its layout, its stream — while this is about what protects it, which is the
 * part worth arguing over.
 */

internal const val SLOT_PASSWORD: Byte = 1
internal const val SLOT_RECOVERY: Byte = 2
internal const val SLOT_DEVICE: Byte = 3

private const val SALT_BYTES = 16
private const val WRAPPED_KEY_BYTES = MigrationContainer.KEY_BYTES + GCM_TAG_BYTES
private const val PASSWORD_PARAMS_BYTES = SALT_BYTES + Int.SIZE_BYTES + 1 + 1 + GCM_NONCE_BYTES
private const val BYTE_MASK = 0xFF
private val HKDF_INFO = "zamolxis-container-v3-recovery".toByteArray()

/**
 * A key that cannot leave the device it was made on.
 *
 * This is the slot BlackBerry's attempt limit actually needed: enforcement in
 * hardware rather than in a counter an attacker can decline to run. The wrapped
 * data key is in the file, but the key that wraps it lives in the Android
 * Keystore and never comes out — so on any other device the slot is not
 * "ten attempts", it is no attempts at all.
 *
 * [destroy] is what makes the limit real. Deleting the file only removes one
 * copy; deleting the Keystore entry removes the *way in*, permanently, for
 * every copy that exists.
 */
interface DeviceKeyWrapper {
    /** Wrap the data key, or null when this device offers no such key. */
    fun wrap(dataKey: ByteArray): ByteArray?

    /** Unwrap, or null when the key is gone, refused, or this is the wrong device. */
    fun unwrap(blob: ByteArray): ByteArray?

    /** Destroy the wrapping key. Every container that used it becomes unopenable by this slot. */
    fun destroy()
}

internal class Slot(
    val type: Byte,
    val body: ByteArray,
)

internal fun slotTypeOf(unlock: MigrationContainer.Unlock): Byte =
    when (unlock) {
        is MigrationContainer.Unlock.Password -> SLOT_PASSWORD
        is MigrationContainer.Unlock.Recovery -> SLOT_RECOVERY
        is MigrationContainer.Unlock.Device -> SLOT_DEVICE
    }

internal fun buildSlot(
    unlock: MigrationContainer.Unlock,
    dataKey: ByteArray,
    cost: MigrationContainer.Argon2Cost,
    random: SecureRandom,
): Slot {
    // The hardware key does its own wrapping; there is nothing for us to salt
    // or stretch, and nothing readable to authenticate.
    if (unlock is MigrationContainer.Unlock.Device) {
        val blob =
            unlock.wrapper.wrap(dataKey)
                ?: throw InvalidExportFileException("This device offers no hardware-backed key")
        return Slot(SLOT_DEVICE, blob)
    }

    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)

    val params =
        when (unlock) {
            is MigrationContainer.Unlock.Password ->
                ByteArrayOutputStream()
                    .apply {
                        write(salt)
                        writeIntBe(cost.memoryKib)
                        write(cost.iterations)
                        write(cost.parallelism)
                        write(nonce)
                    }.toByteArray()

            is MigrationContainer.Unlock.Recovery ->
                ByteArrayOutputStream()
                    .apply {
                        write(salt)
                        write(nonce)
                    }.toByteArray()
        }

    val type = slotTypeOf(unlock)
    val kek = deriveKek(unlock, salt, cost)
    try {
        val cipher = Cipher.getInstance(CONTAINER_CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(slotAad(type, params))
        val wrapped = cipher.doFinal(dataKey)
        return Slot(type, params + wrapped)
    } finally {
        kek.fill(0)
    }
}

/**
 * Try one slot against one key, returning the data key or null.
 *
 * Null rather than an exception because a container legitimately holds
 * slots this key was never meant to open; only exhausting all of them is
 * a failure.
 *
 * A failed authentication tag is the expected outcome for every slot this
 * key was not meant to open, and is deliberately indistinguishable from a
 * wrong password: nothing about which it was may reach the caller.
 */
@Suppress("SwallowedException")
internal fun tryUnwrap(
    slot: Slot,
    unlock: MigrationContainer.Unlock,
): ByteArray? {
    if (unlock is MigrationContainer.Unlock.Device) {
        return if (slot.type == SLOT_DEVICE) unlock.wrapper.unwrap(slot.body) else null
    }
    val parsed = parseSlot(slot, unlock) ?: return null
    val kek = deriveKek(unlock, parsed.salt, parsed.cost)
    return try {
        val cipher = Cipher.getInstance(CONTAINER_CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, parsed.nonce))
        cipher.updateAAD(slotAad(slot.type, parsed.params))
        cipher.doFinal(parsed.wrapped)
    } catch (_: javax.crypto.AEADBadTagException) {
        null
    } finally {
        kek.fill(0)
    }
}

private class ParsedSlot(
    val params: ByteArray,
    val wrapped: ByteArray,
    val salt: ByteArray,
    val nonce: ByteArray,
    val cost: MigrationContainer.Argon2Cost,
)

/**
 * Split a slot body into its parts, or return null when it is not a slot
 * this key could open — wrong kind, or a body whose length does not match
 * what that kind requires.
 */
private fun parseSlot(
    slot: Slot,
    unlock: MigrationContainer.Unlock,
): ParsedSlot? {
    val expectedType = slotTypeOf(unlock)
    val paramsLength = slot.body.size - WRAPPED_KEY_BYTES
    if (slot.type != expectedType || paramsLength <= 0) return null

    val params = slot.body.copyOfRange(0, paramsLength)
    val wrapped = slot.body.copyOfRange(paramsLength, slot.body.size)
    val salt = params.copyOfRange(0, SALT_BYTES)

    return when (unlock) {
        is MigrationContainer.Unlock.Password -> {
            if (params.size != PASSWORD_PARAMS_BYTES) {
                null
            } else {
                var at = SALT_BYTES
                val memoryKib = readIntBe(params, at)
                at += Int.SIZE_BYTES
                val iterations = params[at++].toInt() and BYTE_MASK
                val parallelism = params[at++].toInt() and BYTE_MASK
                ParsedSlot(
                    params = params,
                    wrapped = wrapped,
                    salt = salt,
                    nonce = params.copyOfRange(at, at + GCM_NONCE_BYTES),
                    cost = MigrationContainer.Argon2Cost(memoryKib, iterations, parallelism),
                )
            }
        }

        // Handled before parseSlot is reached; a device slot has no params.
        is MigrationContainer.Unlock.Device -> null

        is MigrationContainer.Unlock.Recovery ->
            if (params.size != SALT_BYTES + GCM_NONCE_BYTES) {
                null
            } else {
                ParsedSlot(
                    params = params,
                    wrapped = wrapped,
                    salt = salt,
                    nonce = params.copyOfRange(SALT_BYTES, params.size),
                    cost = MigrationContainer.DEFAULT_COST,
                )
            }
    }
}

private fun deriveKek(
    unlock: MigrationContainer.Unlock,
    salt: ByteArray,
    cost: MigrationContainer.Argon2Cost,
): ByteArray =
    when (unlock) {
        is MigrationContainer.Unlock.Password -> argon2id(unlock.password, salt, cost)
        // A 256-bit random key has nothing to stretch: HKDF is there to
        // bind it to this container's salt and give a clean 32-byte key,
        // not to make anything expensive.
        is MigrationContainer.Unlock.Recovery -> hkdf(unlock.key, salt)
        // Never reached: a device slot is wrapped by hardware, not by a KEK
        // derived here. Present so the compiler keeps this exhaustive.
        is MigrationContainer.Unlock.Device -> ByteArray(MigrationContainer.KEY_BYTES)
    }

private fun argon2id(
    password: String,
    salt: ByteArray,
    cost: MigrationContainer.Argon2Cost,
): ByteArray {
    val params =
        Argon2Parameters
            .Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(cost.memoryKib)
            .withIterations(cost.iterations)
            .withParallelism(cost.parallelism)
            .build()
    val generator = Argon2BytesGenerator().apply { init(params) }
    return ByteArray(MigrationContainer.KEY_BYTES).also { generator.generateBytes(password.toCharArray(), it) }
}

private fun hkdf(
    ikm: ByteArray,
    salt: ByteArray,
): ByteArray {
    val generator = HKDFBytesGenerator(SHA256Digest())
    generator.init(HKDFParameters(ikm, salt, HKDF_INFO))
    return ByteArray(MigrationContainer.KEY_BYTES).also { generator.generateBytes(it, 0, it.size) }
}
