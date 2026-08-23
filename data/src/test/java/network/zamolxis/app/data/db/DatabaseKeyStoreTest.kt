package network.zamolxis.app.data.db

import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DatabaseKeyStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /**
     * Stands in for the Keystore-backed encryptor, which Robolectric cannot provide.
     * Reversible and obviously not encryption — the point is only that the store
     * writes what the encryptor gave it and reads back what the encryptor returns.
     */
    private class ReversingEncryptor : SecretBlobEncryptor {
        var wrapCount = 0
            private set

        override fun encryptBlobWithDeviceKey(plainData: ByteArray): ByteArray {
            require(plainData.isNotEmpty())
            wrapCount++
            return byteArrayOf(WRAP_MARKER) + plainData.reversedArray()
        }

        override fun decryptBlobWithDeviceKey(encryptedData: ByteArray): ByteArray {
            check(encryptedData.first() == WRAP_MARKER) { "not a wrapped blob" }
            return encryptedData.drop(1).toByteArray().reversedArray()
        }

        companion object {
            const val WRAP_MARKER: Byte = 0x7f
        }
    }

    private fun store(encryptor: SecretBlobEncryptor = ReversingEncryptor()) =
        DatabaseKeyStore(folder.root, encryptor)

    @Test
    fun `first open generates a 256-bit passphrase`() {
        val passphrase = store().loadOrCreate()

        assertEquals(DatabaseKeyStore.PASSPHRASE_LENGTH, passphrase.size)
        assertFalse("A passphrase of zeroes means the RNG never ran", passphrase.all { it == 0.toByte() })
    }

    @Test
    fun `the passphrase survives across instances`() {
        val encryptor = ReversingEncryptor()
        val first = store(encryptor).loadOrCreate()

        val second = DatabaseKeyStore(folder.root, encryptor).loadOrCreate()

        assertArrayEquals("A second open must not mint a new passphrase", first, second)
        assertEquals("Only the first open should have wrapped anything", 1, encryptor.wrapCount)
    }

    @Test
    fun `the passphrase is not on disk in the clear`() {
        val passphrase = store().loadOrCreate()

        val onDisk = folder.root.resolve("zamolxis_database.key").readBytes()

        assertFalse(
            "The stored bytes must be the wrapped form, not the passphrase",
            onDisk.toList().windowed(passphrase.size).any { it.toByteArray().contentEquals(passphrase) },
        )
    }

    @Test
    fun `exists reports whether a database is expected to be encrypted`() {
        val subject = store()
        assertFalse(subject.exists())

        subject.loadOrCreate()

        assertTrue(subject.exists())
    }

    @Test
    fun `deleting the passphrase means the next open mints a new one`() {
        val encryptor = ReversingEncryptor()
        val subject = DatabaseKeyStore(folder.root, encryptor)
        val original = subject.loadOrCreate()

        subject.delete()

        assertFalse(subject.exists())
        assertFalse(subject.loadOrCreate().contentEquals(original))
    }

    @Test
    fun `an empty key file is treated as absent rather than as an empty passphrase`() {
        folder.root.resolve("zamolxis_database.key").createNewFile()

        val passphrase = store().loadOrCreate()

        assertEquals(DatabaseKeyStore.PASSPHRASE_LENGTH, passphrase.size)
    }

    @Test
    fun `concurrent first opens agree on one passphrase`() {
        val encryptor = ReversingEncryptor()
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)
        val results = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

        repeat(threads) {
            Thread {
                try {
                    start.await()
                    results += DatabaseKeyStore(folder.root, encryptor).loadOrCreate()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals("A thread failed: ${failure.get()}", null, failure.get())
        assertEquals("Every caller must see the same passphrase", 1, results.map { it.toList() }.toSet().size)
        assertEquals("The passphrase must be generated once", 1, encryptor.wrapCount)
    }
}
