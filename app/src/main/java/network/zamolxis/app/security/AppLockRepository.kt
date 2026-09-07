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
 * ## Why there is a lockout on top of the PBKDF2 cost
 *
 * 600k rounds cost roughly a second per guess on a phone, which sounds like a
 * lot until you multiply it by the ten thousand guesses a 4-digit PIN is
 * worth: an afternoon. [lockoutRemainingMs] adds a growing refusal after four
 * consecutive failures, capping at half an hour, which turns that afternoon
 * into most of a year. The duress PIN is exempt — see [verify].
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

    /**
     * Too many wrong entries; the app refuses to judge this one at all.
     *
     * Returned for anything but the duress PIN while a lockout is running,
     * including the correct unlock PIN. The attempt is not counted — a guess
     * that could never have opened the app is not a guess, and counting it
     * would let an attacker extend the lockout forever and leave the owner
     * permanently shut out of their own phone.
     */
    LOCKED_OUT,
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
            private const val KEY_LOCKOUT_UNTIL = "lockout_until"
            private const val KEY_LOCKOUT_STARTED = "lockout_started"

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

            /**
             * Wrong entries allowed before the lockout starts.
             *
             * Four, because people mistype PINs and the first few failures are
             * almost always the owner's own thumbs, not an attack.
             */
            const val FREE_ATTEMPTS = 4

            /**
             * How long the app refuses to judge a PIN after the 5th, 6th, …
             * consecutive failure, in milliseconds. The last entry repeats.
             *
             * PBKDF2 alone is not a rate limit. 600k rounds cost about a second
             * on a phone, so an attacker with a finger and patience walks the
             * whole ten-thousand-key space of a 4-digit PIN in a few hours.
             * With this ladder they get one usable guess per window, and at the
             * 30-minute cap that is 48 a day — the same space now takes most of
             * a year.
             */
            private val LOCKOUT_LADDER_MS =
                longArrayOf(
                    30_000L,
                    60_000L,
                    120_000L,
                    300_000L,
                    900_000L,
                    1_800_000L,
                )
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
         * Milliseconds left before a PIN will be judged again; zero when none.
         *
         * Persisted as a wall-clock deadline rather than kept in memory, because
         * the alternative is a lockout that a force-stop clears — and force-stop
         * is available to anyone holding the phone.
         *
         * ## Clock tampering
         *
         * Wall-clock is the only timebase Android offers that survives both a
         * process death and a reboot: `elapsedRealtime` restarts at zero when the
         * phone does. So the deadline can be escaped by moving the system clock
         * forward, which needs leaving the app and entering Settings. Moving it
         * *backwards* is handled — a clock now earlier than the moment the
         * lockout began is a clock that was moved, and the full duration is
         * served again rather than trusted away. This is a speed bump on the
         * physical-access path, not a seal; the seal is the duress PIN.
         */
        fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long {
            val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
            if (until == 0L) return 0L
            val started = prefs.getLong(KEY_LOCKOUT_STARTED, 0L)
            if (now < started) return until - started
            return (until - now).coerceAtLeast(0L)
        }

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
                    clearLockout()
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
            prefs
                .edit()
                .remove(KEY_DURESS_HASH)
                .remove(KEY_DURESS_SALT)
                .apply()
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
            val lockedOut = lockoutRemainingMs() > 0L

            return when {
                // The duress PIN is honoured even mid-lockout, and deliberately
                // before the lockout is consulted. The lockout exists to slow an
                // attacker guessing; it must never be the thing that stands
                // between someone being made to unlock their phone and the one
                // PIN that helps them. An attacker who guesses their way into
                // the duress PIN gets the wipe, which is the correct outcome.
                duressMatch -> PinVerdict.DURESS
                lockedOut -> PinVerdict.LOCKED_OUT
                // Unlock wins a tie. A tie cannot happen — the setters refuse
                // equal PINs — but if one ever did, opening the app is the
                // failure that loses no data.
                unlockMatch -> {
                    clearLockout()
                    failedAttempts = 0
                    PinVerdict.UNLOCK
                }
                else -> {
                    val attempts = failedAttempts + 1
                    failedAttempts = attempts
                    startLockoutFor(attempts)
                    PinVerdict.WRONG
                }
            }
        }

        /**
         * Begin the lockout this many consecutive failures has earned, if any.
         *
         * Rewritten on every failure past the threshold rather than extended, so
         * the deadline always reflects the current rung of the ladder.
         */
        private fun startLockoutFor(attempts: Int) {
            if (attempts <= FREE_ATTEMPTS) return
            val rung = (attempts - FREE_ATTEMPTS - 1).coerceAtMost(LOCKOUT_LADDER_MS.lastIndex)
            val now = System.currentTimeMillis()
            prefs
                .edit()
                .putLong(KEY_LOCKOUT_STARTED, now)
                .putLong(KEY_LOCKOUT_UNTIL, now + LOCKOUT_LADDER_MS[rung])
                .apply()
        }

        private fun clearLockout() {
            prefs
                .edit()
                .remove(KEY_LOCKOUT_UNTIL)
                .remove(KEY_LOCKOUT_STARTED)
                .apply()
        }

        private fun isAcceptable(pin: String): Boolean = pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

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
