package network.zamolxis.app.data.db

import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Holds the passphrase the message database is encrypted with.
 *
 * The passphrase is 32 random bytes, generated once on first open and stored
 * wrapped by [SecretBlobEncryptor] — that is, encrypted under a hardware-backed
 * Android Keystore key that never leaves the device. Nothing derives it from
 * anything the user types: the `:reticulum` service has to open this database to
 * store messages that arrive while the app is locked or not running at all, so a
 * key that needs a human present is not an option here.
 *
 * Both processes call this, and either may be the first to run after install, so
 * generation is serialised on a file lock rather than a JVM lock — a `synchronized`
 * block in one process says nothing to the other, and two processes each writing a
 * freshly generated passphrase would leave one of them unable to read the database
 * it just wrote.
 */
class DatabaseKeyStore(
    private val filesDir: File,
    private val encryptor: SecretBlobEncryptor,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        /** 256-bit passphrase, matching the AES key size SQLCipher derives. */
        const val PASSPHRASE_LENGTH = 32

        private const val KEY_FILE_NAME = "zamolxis_database.key"
        private const val LOCK_FILE_NAME = "zamolxis_database.key.lock"

        /**
         * Serialises callers inside this process before they reach the file lock.
         *
         * `FileChannel.lock()` is held by the JVM, not by the thread: a second
         * thread here would not wait, it would get an `OverlappingFileLockException`.
         * The file lock keeps the two processes apart; this keeps the threads of one
         * process apart.
         */
        private val PROCESS_LOCK = Any()
    }

    private val keyFile: File get() = File(filesDir, KEY_FILE_NAME)
    private val lockFile: File get() = File(filesDir, LOCK_FILE_NAME)

    /** True once a passphrase exists, i.e. the database is expected to be encrypted. */
    fun exists(): Boolean = keyFile.exists()

    /**
     * Return the passphrase, generating and storing one if this is the first open.
     *
     * @throws network.zamolxis.app.data.crypto.CorruptedKeyException if a stored
     *   passphrase cannot be unwrapped — the Keystore key is gone (restored onto
     *   different hardware, or cleared) and the database it protects cannot be read.
     */
    fun loadOrCreate(): ByteArray =
        withFileLock {
            readExisting() ?: generateAndStore()
        }

    /** Forget the passphrase. The database it opened becomes unreadable. */
    fun delete() {
        withFileLock {
            keyFile.delete()
        }
    }

    private fun readExisting(): ByteArray? {
        if (!keyFile.exists()) return null
        val wrapped = keyFile.readBytes()
        if (wrapped.isEmpty()) return null
        return encryptor.decryptBlobWithDeviceKey(wrapped)
    }

    private fun generateAndStore(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_LENGTH).also { random.nextBytes(it) }
        val wrapped = encryptor.encryptBlobWithDeviceKey(passphrase)

        // Write via a temp file and rename: a passphrase file that was half-written
        // when the process died would be indistinguishable from a corrupt one, and
        // would take the database with it.
        val temp = File(filesDir, "$KEY_FILE_NAME.tmp")
        temp.writeBytes(wrapped)
        // Clear the destination first. Reaching here means any existing file was
        // unusable (absent or empty), and `renameTo` is only defined to replace an
        // existing target on some platforms — it does on Android, it does not on
        // Windows, where the unit tests run.
        keyFile.delete()
        if (!temp.renameTo(keyFile)) {
            temp.delete()
            error("Could not store the database passphrase at ${keyFile.absolutePath}")
        }
        return passphrase
    }

    private fun <T> withFileLock(block: () -> T): T =
        synchronized(PROCESS_LOCK) {
            filesDir.mkdirs()
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.use { channel ->
                    val lock = channel.lock()
                    try {
                        block()
                    } finally {
                        lock.release()
                    }
                }
            }
        }
}
