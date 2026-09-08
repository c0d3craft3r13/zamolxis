package network.zamolxis.app.migration

import android.content.SharedPreferences
import java.io.File

/**
 * Counts failed attempts to open a container and, at the limit, closes the door.
 *
 * This is BlackBerry's rule — ten wrong passwords and the data goes — put where
 * it can actually be enforced. On the device, this app is the one asking for the
 * password, so it can count; and what it destroys at the limit is the
 * hardware-backed key, which no copy of the container anywhere can be opened
 * through afterwards.
 *
 * ## What it honestly cannot do
 *
 * It cannot govern a copy on someone else's machine. There the attacker runs
 * their own reader and this counter is not in the room. Nothing put inside a
 * file can change that, which is why the password slot is stretched with
 * Argon2id and the recovery slot is 256 bits of randomness: those hold up where
 * counting does not.
 *
 * ## Why the countdown is visible
 *
 * The owner is the one most likely to mistype, and under pressure they will
 * mistype more, not less. BlackBerry showed "3 attempts remaining" for exactly
 * this reason. Someone guessing learns nothing from the warning — they already
 * know they are guessing — while the owner gets the chance to stop and think.
 */
class ContainerAttemptGuard(
    private val preferences: SharedPreferences,
    private val wrapper: DeviceKeyWrapper,
    private val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        /** BlackBerry's number, and a reasonable one: far past a typo, far short of a search. */
        const val DEFAULT_LIMIT = 10

        /** Failures before the remaining count is worth showing. Below this it is just noise. */
        const val WARN_BELOW = 4

        private const val KEY_FAILURES = "container_failed_attempts"
        private const val KEY_ERASE_LOCAL = "container_erase_local_copy"
    }

    /** What the caller should do next. */
    sealed interface Outcome {
        /** Still openable. [remaining] is worth showing once it gets low. */
        data class Continue(
            val remaining: Int,
        ) : Outcome

        /** The limit was reached: the device slot is gone for good. */
        data object Exhausted : Outcome
    }

    /**
     * Whether reaching the limit should also delete the copy on this device.
     *
     * Off by default and deliberately modest in what it claims: the container is
     * encrypted, so blocks left behind on flash are ciphertext either way. It
     * removes a target, not a secret, and it does nothing about copies
     * elsewhere.
     */
    var erasesLocalCopy: Boolean
        get() = preferences.getBoolean(KEY_ERASE_LOCAL, false)
        set(value) = preferences.edit().putBoolean(KEY_ERASE_LOCAL, value).apply()

    val failures: Int
        get() = preferences.getInt(KEY_FAILURES, 0)

    /** Attempts left before the device slot is destroyed. */
    fun remaining(): Int = (limit - failures).coerceAtLeast(0)

    /** Whether [remaining] is low enough that the user should be told. */
    fun shouldWarn(): Boolean = remaining() in 1..WARN_BELOW

    /** A container opened. Nothing is held against the user afterwards. */
    fun recordSuccess() {
        preferences.edit().remove(KEY_FAILURES).apply()
    }

    /**
     * A container refused the key.
     *
     * @param localCopy deleted when the limit is reached and [erasesLocalCopy]
     *   is on. Other copies are not this method's business and never were.
     */
    fun recordFailure(localCopy: File? = null): Outcome {
        val count = failures + 1
        preferences.edit().putInt(KEY_FAILURES, count).apply()

        if (count < limit) return Outcome.Continue(limit - count)

        wrapper.destroy()
        if (erasesLocalCopy) localCopy?.takeIf { it.exists() }?.delete()
        return Outcome.Exhausted
    }
}
