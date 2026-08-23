package network.zamolxis.app.data.db

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Converts an existing unencrypted message database to an encrypted one.
 *
 * Installs that predate encryption have a plain SQLite file on disk. SQLCipher
 * cannot open it with a key, and there is no in-place switch — the contents have to
 * be copied into a new, keyed database via `sqlcipher_export`. This runs once,
 * before Room opens anything, and does nothing on installs that are already
 * encrypted or have no database yet.
 *
 * What happens if the process dies partway through is the whole design here:
 *
 *  1. Export into a side file. Dying here leaves the original untouched; the next
 *     open sees a plaintext database again and retries.
 *  2. Move the original aside to a backup name, and delete its WAL and shared-memory
 *     files — they belong to the plaintext database, and leaving them beside an
 *     encrypted file of the same name is how you corrupt both.
 *  3. Move the encrypted file into place.
 *  4. Delete the backup.
 *
 * The only window where no database sits at its own path is between 2 and 3, and
 * [restoreInterruptedMigration] closes it: a backup with no database beside it is
 * moved back on the next open.
 */
object PlaintextDatabaseMigration {
    private const val TAG = "Zamolxis/DB"

    /**
     * The 16-byte magic every unencrypted SQLite file starts with: the string
     * "SQLite format 3" and a terminating zero byte.
     */
    private val SQLITE_MAGIC = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

    private const val EXPORT_SUFFIX = ".encrypting"
    private const val BACKUP_SUFFIX = ".plaintext-backup"

    /**
     * True if [file] is an unencrypted SQLite database.
     *
     * An encrypted database has no readable header — its first page is ciphertext —
     * so the magic is exactly what tells the two apart. A file too short to hold the
     * magic is not a database worth converting either way.
     */
    fun isPlaintextSqlite(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_MAGIC.size) return false
        val header = ByteArray(SQLITE_MAGIC.size)
        file.inputStream().use { stream ->
            if (stream.read(header) != header.size) return false
        }
        return header.contentEquals(SQLITE_MAGIC)
    }

    /**
     * Put back a database that was moved aside by an interrupted migration.
     *
     * @return true if a database was restored.
     */
    fun restoreInterruptedMigration(databaseFile: File): Boolean {
        val backup = File(databaseFile.path + BACKUP_SUFFIX)
        if (!backup.exists()) return false

        if (databaseFile.exists()) {
            // The move completed and only the cleanup didn't. Whatever is at the
            // database path now is the current database; the backup is stale.
            backup.delete()
            return false
        }

        Log.w(TAG, "Restoring database left behind by an interrupted encryption migration")
        return backup.renameTo(databaseFile)
    }

    /**
     * Encrypt [databaseFile] in place under [passphrase] if it is still plaintext.
     *
     * @return true if a database was converted.
     */
    fun migrateIfNeeded(
        databaseFile: File,
        passphrase: ByteArray,
    ): Boolean {
        restoreInterruptedMigration(databaseFile)
        if (!isPlaintextSqlite(databaseFile)) return false

        Log.i(TAG, "Encrypting the existing message database")
        val exported = File(databaseFile.path + EXPORT_SUFFIX)
        // A leftover from an attempt that died mid-export is half a database.
        exported.delete()

        exportToEncrypted(databaseFile, exported, passphrase)

        val backup = File(databaseFile.path + BACKUP_SUFFIX)
        check(databaseFile.renameTo(backup)) { "Could not move the plaintext database aside" }
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()

        if (!exported.renameTo(databaseFile)) {
            // Put the original back rather than leave no database at all.
            backup.renameTo(databaseFile)
            error("Could not move the encrypted database into place")
        }

        backup.delete()
        Log.i(TAG, "Message database encrypted")
        return true
    }

    private fun exportToEncrypted(
        plaintextFile: File,
        encryptedFile: File,
        passphrase: ByteArray,
    ) {
        SqlCipherNative.ensureLoaded()
        val plaintext =
            SQLiteDatabase.openDatabase(
                plaintextFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        try {
            // The passphrase binds as a blob, the same shape SupportOpenHelperFactory
            // hands to SQLCipher when it opens the result — pass it any other way and
            // the database ends up encrypted under a key nothing can reopen.
            plaintext.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf<Any>(encryptedFile.absolutePath, passphrase),
            )
            plaintext.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
            // sqlcipher_export copies schema and rows, not the user_version Room reads
            // to decide which migrations to run. Without this the fresh database looks
            // like version 0 and Room tries to build the schema from scratch.
            plaintext.execSQL("PRAGMA encrypted.user_version = ${plaintext.version}")
            plaintext.execSQL("DETACH DATABASE encrypted")
        } finally {
            plaintext.close()
        }
    }
}
