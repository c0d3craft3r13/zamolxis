package network.zamolxis.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lock's security properties, stated as tests.
 *
 * The expensive part is deliberate: each verification runs 600k rounds of
 * PBKDF2, so this class is slower than most. That cost is the feature — it is
 * what stands between a seized phone and the ten thousand guesses a four-digit
 * PIN is worth.
 */
@RunWith(RobolectricTestRunner::class)
class AppLockRepositoryTest {
    private lateinit var repository: AppLockRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = AppLockRepository(context)
        repository.clearAll()
    }

    @Test
    fun `starts unconfigured`() =
        runTest {
            assertFalse(repository.isConfigured)
            assertFalse(repository.hasDuressPin)
        }

    @Test
    fun `unlock pin opens the app`() =
        runTest {
            assertTrue(repository.setUnlockPin("1234"))

            assertEquals(PinVerdict.UNLOCK, repository.verify("1234"))
        }

    @Test
    fun `wrong pin is refused and counted`() =
        runTest {
            repository.setUnlockPin("1234")

            assertEquals(PinVerdict.WRONG, repository.verify("9999"))
            assertEquals(1, repository.failedAttempts)
            assertEquals(PinVerdict.WRONG, repository.verify("8888"))
            assertEquals(2, repository.failedAttempts)
        }

    @Test
    fun `a correct entry clears the failure count`() =
        runTest {
            repository.setUnlockPin("1234")
            repository.verify("9999")
            assertEquals(1, repository.failedAttempts)

            repository.verify("1234")

            assertEquals(0, repository.failedAttempts)
        }

    @Test
    fun `duress pin is recognised as itself`() =
        runTest {
            repository.setUnlockPin("1234")
            assertTrue(repository.setDuressPin("5678"))

            assertEquals(PinVerdict.DURESS, repository.verify("5678"))
        }

    /**
     * A duress entry must not look like a failed attempt either — a counter
     * that ticks up is a record that someone tried something, which is exactly
     * what the duress path is supposed to leave none of.
     */
    @Test
    fun `duress entry leaves no failure trace`() =
        runTest {
            repository.setUnlockPin("1234")
            repository.setDuressPin("5678")

            repository.verify("5678")

            assertEquals(0, repository.failedAttempts)
        }

    /**
     * The two PINs must differ, in both directions. One PIN that unlocks *and*
     * wipes would destroy the user's data the first ordinary time they opened
     * the app.
     */
    @Test
    fun `duress pin cannot equal the unlock pin`() =
        runTest {
            repository.setUnlockPin("1234")

            assertFalse(repository.setDuressPin("1234"))
            assertFalse(repository.hasDuressPin)
        }

    @Test
    fun `unlock pin cannot be changed to the duress pin`() =
        runTest {
            repository.setUnlockPin("1234")
            repository.setDuressPin("5678")

            assertFalse(repository.setUnlockPin("5678"))

            // The old unlock PIN still works, so a refused change leaves the user
            // locked out of nothing.
            assertEquals(PinVerdict.UNLOCK, repository.verify("1234"))
        }

    @Test
    fun `a duress pin cannot be set before an unlock pin`() =
        runTest {
            assertFalse(repository.setDuressPin("5678"))
            assertFalse(repository.hasDuressPin)
        }

    @Test
    fun `too short is refused`() =
        runTest {
            assertFalse(repository.setUnlockPin("123"))
            assertFalse(repository.isConfigured)
        }

    @Test
    fun `too long is refused`() =
        runTest {
            assertFalse(repository.setUnlockPin("1234567890123"))
            assertFalse(repository.isConfigured)
        }

    @Test
    fun `non-digits are refused`() =
        runTest {
            assertFalse(repository.setUnlockPin("12a4"))
            assertFalse(repository.isConfigured)
        }

    @Test
    fun `removing the duress pin leaves the unlock pin working`() =
        runTest {
            repository.setUnlockPin("1234")
            repository.setDuressPin("5678")

            repository.clearDuressPin()

            assertFalse(repository.hasDuressPin)
            assertEquals(PinVerdict.UNLOCK, repository.verify("1234"))
            // The old duress PIN is now merely wrong — it must not still wipe.
            assertEquals(PinVerdict.WRONG, repository.verify("5678"))
        }

    @Test
    fun `clearing everything turns the lock off`() =
        runTest {
            repository.setUnlockPin("1234")
            repository.setDuressPin("5678")

            repository.clearAll()

            assertFalse(repository.isConfigured)
            assertEquals(PinVerdict.WRONG, repository.verify("1234"))
            assertEquals(PinVerdict.WRONG, repository.verify("5678"))
        }

    /**
     * Two installs with the same PIN must not produce the same stored value:
     * a shared hash would let someone who obtained one device's preferences
     * recognise the same PIN on another.
     */
    @Test
    fun `the same pin salts differently each time it is set`() =
        runTest {
            repository.setUnlockPin("1234")
            val first = storedUnlockHash()

            repository.setUnlockPin("1234")
            val second = storedUnlockHash()

            assertTrue(first != null && second != null)
            assertTrue("hash should be salted per assignment", first != second)
            assertEquals(PinVerdict.UNLOCK, repository.verify("1234"))
        }

    private fun storedUnlockHash(): String? =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("zamolxis_app_lock", Context.MODE_PRIVATE)
            .getString("unlock_hash", null)
}
