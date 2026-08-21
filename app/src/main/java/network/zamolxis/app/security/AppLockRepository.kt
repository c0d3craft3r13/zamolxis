package network.zamolxis.app.security

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two PINs that guard the app, and the verdict for a PIN that is typed in.
 *
 * There are two because one is not enough for the situation this app is built
 * for. An unlock PIN protects against someone picking the phone up. It does
 * nothing at all against someone standing over you demanding you open it —
 * for that the only useful answer is a PIN that opens the app to nothing,
 * because there is nothing left.
 *
 * ## Why the wipe is silent
 *
 * Entering the duress PIN destroys the data and then shows what a fresh
 * install shows. No warning, no confirmation, no "data erased" notice: every
 * one of those tells the person watching that a second PIN exists, which
 * undoes the entire point and puts the user in more danger than before. The
 * observable behaviour of the duress PIN is indistinguishable from opening a
 * phone that never had anything on it.
 *
 * ## Why both PINs are always checked
 *
 * [verify] hashes the input against both stored verifiers and compares both
 * results, even after the first one matches. Returning early on the unlock
 * PIN would make the duress path measurably slower, and a timing difference
 * is exactly the kind of tell that gives away the existence of a second PIN.
 *
 * ## Why these are suspend functions
 *
 * Every entry point that touches a PIN runs 600k rounds of PBKDF2, twice —
 * seconds of work. Called from the main thread it freezes the app hard
 * enough for Android to offer to kill it, which is what the first device
 * run did. The cost is the point, so it moves off the main thread rather
 * than coming down.
 */
enum class PinVerdict {
    /** The everyday PIN. Unlocks the app. */
    UNLOCK,

    /** The duress PIN. Everything is destroyed, silently. */
    DURESS,

    /** Neither. Nothing happens beyond the attempt being counted. */
    WRONG,
}

@Singleton
class AppLockRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val PREFS_NAME = "zamolxis_app_lock"
            private const val KEY_UNLOCK_HASH = "unlock_hash"
            private const val KEY_UNLOCK_SALT = "unlock_salt"
            private const val KEY_DURESS_HASH = "duress_hash"
            private const val KEY_DURESS_SALT = "duress_salt"
            private const val KEY_FAILED_ATTEMPTS = "failed_attempts"

            /** Shortest PIN we accept. Four digits is the floor people expect. */
            const val MIN_PIN_LENGTH = 4

            /** Longest PIN we accept, so the pad stays usable. */
            const val MAX_PIN_LENGTH = 12

            private const val SALT_LENGTH = 32
            private const val KEY_LENGTH_BITS = 256

            /**
             * PBKDF2 rounds. High because a PIN is a tiny secret: the whole
             * keyspace of a 4-digit PIN is ten thousand guesses, so the only
             * thing standing between a seized phone and the answer is how long
             * each guess takes. Matches the identity encryptor's cost, which was
             * chosen against the same OWASP guidance.
             */
            private const val PBKDF2_ITERATIONS = 600_000
        }

        private val prefs: SharedPreferences
            get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Whether a PIN has been configured at all. */
        val isConfigured: Boolean
            get() = prefs.contains(KEY_UNLOCK_HASH)

        /** Whether a duress PIN has been configured alongside the unlock PIN. */
        val hasDuressPin: Boolean
            get() = prefs.contains(KEY_DURESS_HASH)

        /** Consecutive wrong entries since the last success. Drives the backoff. */
        var failedAttempts: Int
            get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
            private set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

        /**
         * Set or replace the unlock PIN.
         *
         * @return false when the PIN is too short, too long, or equal to the
         *   duress PIN — a single PIN that both unlocks and wipes would destroy
         *   the data on ordinary use.
         */
        suspend fun setUnlockPin(pin: String): Boolean =
            withContext(Dispatchers.Default) {
                if (!isAcceptable(pin)) {
                    false
                } else if (hasDuressPin && matches(pin, KEY_DURESS_HASH, KEY_DURESS_SALT)) {
                    false
                } else {
                    store(pin, KEY_UNLOCK_HASH, KEY_UNLOCK_SALT)
                    failedAttempts = 0
                    true
                }
            }

        /**
         * Set or replace the duress PIN.
         *
         * @return false when the PIN is unacceptable or equal to the unlock PIN.
         */
        suspend fun setDuressPin(pin: String): Boolean =
            withContext(Dispatchers.Default) {
                // Short-circuits, so the unlock verifier is only computed for a PIN
                // that could actually be stored. Unlike `verify`, nothing here is
                // observable by an attacker, so the timing does not need levelling.
                val acceptable =
                    isAcceptable(pin) &&
                        isConfigured &&
                        !matches(pin, KEY_UNLOCK_HASH, KEY_UNLOCK_SALT)
                if (acceptable) store(pin, KEY_DURESS_HASH, KEY_DURESS_SALT)
                acceptable
            }

        /** Remove the duress PIN, leaving the unlock PIN in place. */
        fun clearDuressPin() {
            prefs.edit().remove(KEY_DURESS_HASH).remove(KEY_DURESS_SALT).apply()
        }

        /** Remove both PINs. The app stops asking. */
        fun clearAll() {
            prefs.edit().clear().apply()
        }

        /**
         * Judge a typed PIN.
         *
         * Both verifiers are computed and compared before returning, so the
         * duress path takes the same time as the unlock path. See the class
         * note on why that matters.
         */
        suspend fun verify(pin: String): PinVerdict =
            withContext(Dispatchers.Default) {
                verifyBlocking(pin)
            }

        private fun verifyBlocking(pin: String): PinVerdict {
            if (!isConfigured) return PinVerdict.WRONG

            val unlockMatch = matches(pin, KEY_UNLOCK_HASH, KEY_UNLOCK_SALT)
            val duressMatch = hasDuressPin && matches(pin, KEY_DURESS_HASH, KEY_DURESS_SALT)

            return when {
                // Unlock wins a tie. A tie cannot happen — the setters refuse
                // equal PINs — but if one ever did, opening the app is the
                // failure that loses no data.
                unlockMatch -> {
                    failedAttempts = 0
                    PinVerdict.UNLOCK
                }
                duressMatch -> PinVerdict.DURESS
                else -> {
                    failedAttempts += 1
                    PinVerdict.WRONG
                }
            }
        }

        private fun isAcceptable(pin: String): Boolean =
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

        private fun store(
            pin: String,
            hashKey: String,
            saltKey: String,
        ) {
            val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
            val hash = derive(pin, salt)
            prefs
                .edit()
                .putString(hashKey, hash.toHex())
                .putString(saltKey, salt.toHex())
                .apply()
        }

        private fun matches(
            pin: String,
            hashKey: String,
            saltKey: String,
        ): Boolean {
            val storedHash = prefs.getString(hashKey, null)?.fromHex() ?: return false
            val salt = prefs.getString(saltKey, null)?.fromHex() ?: return false
            // MessageDigest.isEqual is the constant-time comparison on Android.
            return MessageDigest.isEqual(derive(pin, salt), storedHash)
        }

        /**
         * PBKDF2-HMAC-SHA256 via BouncyCastle rather than the platform
         * SecretKeyFactory: `PBKDF2WithHmacSHA256` only exists from API 26 and
         * this app supports 24, where the platform would silently leave us on
         * SHA-1. The provider is already a dependency for the post-quantum layer.
         */
        private fun derive(
            pin: String,
            salt: ByteArray,
        ): ByteArray {
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(pin.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERATIONS)
            return (generator.generateDerivedMacParameters(KEY_LENGTH_BITS) as KeyParameter).key
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.fromHex(): ByteArray? =
            runCatching {
                chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.getOrNull()
    }
