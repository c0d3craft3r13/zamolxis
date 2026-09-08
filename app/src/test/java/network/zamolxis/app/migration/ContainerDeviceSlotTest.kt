package network.zamolxis.app.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The device slot and the attempt limit around it.
 *
 * A fake wrapper stands in for the Android Keystore, which Robolectric does not
 * provide: what matters here is the contract — a slot that only this wrapper
 * opens, and a destroy that is final — not that AES runs inside a TEE.
 */
@RunWith(RobolectricTestRunner::class)
class ContainerDeviceSlotTest {
    /** Wraps by XOR against a per-instance pad. Not secure; that is not the point. */
    private class FakeWrapper(
        private val pad: Byte = 0x5A,
    ) : DeviceKeyWrapper {
        var destroyed = false
            private set
        var available = true

        override fun wrap(dataKey: ByteArray): ByteArray? = if (!available) null else ByteArray(dataKey.size) { (dataKey[it].toInt() xor pad.toInt()).toByte() }

        override fun unwrap(blob: ByteArray): ByteArray? =
            if (destroyed || !available) null else ByteArray(blob.size) { (blob[it].toInt() xor pad.toInt()).toByte() }

        override fun destroy() {
            destroyed = true
        }
    }

    private val cheap = MigrationContainer.Argon2Cost(memoryKib = 512, iterations = 1, parallelism = 1)
    private val password = MigrationContainer.Unlock.Password("a password worth typing")
    private val payload = "identities and messages".toByteArray()

    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        preferences =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences("container-guard-test", Context.MODE_PRIVATE)
        preferences.edit().clear().apply()
    }

    @Test
    fun `a device slot opens the container on this device`() {
        val wrapper = FakeWrapper()
        val unlock = MigrationContainer.Unlock.Device(wrapper)

        val container = MigrationContainer.seal(payload, listOf(unlock), cheap)

        assertArrayEquals(payload, MigrationContainer.open(container, unlock))
    }

    /**
     * The whole claim of a device slot: the file is worthless without the
     * hardware that made it. A different wrapper is a different device.
     */
    @Test
    fun `another device cannot open the device slot`() {
        val here = MigrationContainer.Unlock.Device(FakeWrapper(pad = 0x11))
        val elsewhere = MigrationContainer.Unlock.Device(FakeWrapper(pad = 0x22))

        val container = MigrationContainer.seal(payload, listOf(here), cheap)

        assertThrows(InvalidExportFileException::class.java) {
            MigrationContainer.open(container, elsewhere)
        }
    }

    /**
     * Destroying the key is what an attempt limit is for. It must close the
     * slot for every copy, not just the one on this phone — which it does,
     * because what is gone is the key, not a file.
     */
    @Test
    fun `destroying the key closes the slot in copies that were already taken`() {
        val wrapper = FakeWrapper()
        val unlock = MigrationContainer.Unlock.Device(wrapper)
        val container = MigrationContainer.seal(payload, listOf(unlock), cheap)
        val copyTakenEarlier = container.copyOf()

        wrapper.destroy()

        assertThrows(WrongPasswordException::class.java) {
            MigrationContainer.open(copyTakenEarlier, unlock)
        }
    }

    /** A container should never have only a device slot, but if it does, say so rather than crash. */
    @Test
    fun `a device without a usable hardware key refuses to build the slot`() {
        val wrapper = FakeWrapper().apply { available = false }

        assertThrows(InvalidExportFileException::class.java) {
            MigrationContainer.seal(payload, listOf(MigrationContainer.Unlock.Device(wrapper)), cheap)
        }
    }

    /** The reason a device slot is safe to add: the password still opens the file anywhere. */
    @Test
    fun `a password still opens a container that also carries a device slot`() {
        val wrapper = FakeWrapper()
        val container =
            MigrationContainer.seal(payload, listOf(password, MigrationContainer.Unlock.Device(wrapper)), cheap)

        wrapper.destroy()

        assertArrayEquals(payload, MigrationContainer.open(container, password))
    }

    // ── the attempt limit ────────────────────────────────────────────────────

    @Test
    fun `failures count down and the key survives until the limit`() {
        val wrapper = FakeWrapper()
        val guard = ContainerAttemptGuard(preferences, wrapper, limit = 3)

        assertEquals(ContainerAttemptGuard.Outcome.Continue(2), guard.recordFailure())
        assertEquals(ContainerAttemptGuard.Outcome.Continue(1), guard.recordFailure())
        assertFalse(wrapper.destroyed)
    }

    @Test
    fun `the last failure destroys the hardware key`() {
        val wrapper = FakeWrapper()
        val guard = ContainerAttemptGuard(preferences, wrapper, limit = 3)

        repeat(2) { guard.recordFailure() }

        assertEquals(ContainerAttemptGuard.Outcome.Exhausted, guard.recordFailure())
        assertTrue(wrapper.destroyed)
    }

    /** Getting in clears the record; yesterday's typos are not held against anyone. */
    @Test
    fun `a success forgets the failures`() {
        val wrapper = FakeWrapper()
        val guard = ContainerAttemptGuard(preferences, wrapper, limit = 3)
        guard.recordFailure()

        guard.recordSuccess()

        assertEquals(3, guard.remaining())
        assertEquals(0, guard.failures)
    }

    /** The count survives a force-stop, or it would be cleared by killing the app. */
    @Test
    fun `the count is remembered across instances`() {
        ContainerAttemptGuard(preferences, FakeWrapper(), limit = 5).recordFailure()

        assertEquals(4, ContainerAttemptGuard(preferences, FakeWrapper(), limit = 5).remaining())
    }

    @Test
    fun `the warning appears only once the end is near`() {
        val guard = ContainerAttemptGuard(preferences, FakeWrapper(), limit = 10)

        assertFalse("ten attempts left is not worth a warning", guard.shouldWarn())
        repeat(7) { guard.recordFailure() }

        assertTrue("three attempts left should warn", guard.shouldWarn())
    }

    @Test
    fun `the local copy is left alone unless the option is on`() {
        val file = File.createTempFile("export", ".zamolxis").apply { writeBytes(payload) }
        val guard = ContainerAttemptGuard(preferences, FakeWrapper(), limit = 1)

        guard.recordFailure(localCopy = file)

        assertTrue("default must not delete the user's backup", file.exists())
        file.delete()
    }

    @Test
    fun `the local copy goes when the option is on and the limit is reached`() {
        val file = File.createTempFile("export", ".zamolxis").apply { writeBytes(payload) }
        val guard = ContainerAttemptGuard(preferences, FakeWrapper(), limit = 1).apply { erasesLocalCopy = true }

        guard.recordFailure(localCopy = file)

        assertFalse(file.exists())
    }
}
