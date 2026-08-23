package network.zamolxis.app.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the decisions the migration makes about files. The export step itself needs
 * SQLCipher's native library and lives in
 * `app/src/androidTest/.../DatabaseEncryptionMigrationInstrumentedTest`.
 *
 * Getting these wrong is what loses messages: mistaking an encrypted database for a
 * plaintext one re-runs the conversion over ciphertext, and failing to put back a
 * database that was moved aside leaves the app with none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // for android.util.Log, which the migration writes to
class PlaintextDatabaseMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val magic = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

    private fun dbFile(): File = folder.root.resolve("zamolxis_database")

    private fun writePlaintextDatabase(file: File = dbFile()): File =
        file.apply { writeBytes(magic + ByteArray(512) { it.toByte() }) }

    private fun writeEncryptedDatabase(file: File = dbFile()): File =
        file.apply { writeBytes(ByteArray(512) { (it * 31 + 7).toByte() }) }

    @Test
    fun `recognises an unencrypted database`() {
        assertTrue(PlaintextDatabaseMigration.isPlaintextSqlite(writePlaintextDatabase()))
    }

    @Test
    fun `does not mistake an encrypted database for a plaintext one`() {
        assertFalse(PlaintextDatabaseMigration.isPlaintextSqlite(writeEncryptedDatabase()))
    }

    @Test
    fun `a missing file is not a plaintext database`() {
        assertFalse(PlaintextDatabaseMigration.isPlaintextSqlite(folder.root.resolve("absent")))
    }

    @Test
    fun `a directory is not a plaintext database`() {
        assertFalse(PlaintextDatabaseMigration.isPlaintextSqlite(folder.newFolder("adirectory")))
    }

    @Test
    fun `a file too short to hold the header is not a plaintext database`() {
        val truncated = dbFile().apply { writeBytes(magic.copyOf(4)) }

        assertFalse(PlaintextDatabaseMigration.isPlaintextSqlite(truncated))
    }

    @Test
    fun `an empty file is not a plaintext database`() {
        val empty = dbFile().apply { createNewFile() }

        assertFalse(PlaintextDatabaseMigration.isPlaintextSqlite(empty))
    }

    @Test
    fun `a database moved aside by an interrupted migration is put back`() {
        val database = dbFile()
        val backup = File(database.path + ".plaintext-backup")
        val contents = magic + "the only copy".toByteArray()
        backup.writeBytes(contents)

        assertTrue(PlaintextDatabaseMigration.restoreInterruptedMigration(database))

        assertTrue("The database must be back at its own path", database.exists())
        assertArrayEquals(contents, database.readBytes())
        assertFalse("The backup should not linger once restored", backup.exists())
    }

    @Test
    fun `a stale backup left by a completed migration is discarded`() {
        val database = writeEncryptedDatabase()
        val backup = File(database.path + ".plaintext-backup")
        backup.writeBytes(magic + "superseded".toByteArray())
        val encrypted = database.readBytes()

        assertFalse(
            "Nothing was restored: the migration had finished",
            PlaintextDatabaseMigration.restoreInterruptedMigration(database),
        )

        assertArrayEquals("The live database must not be overwritten by the backup", encrypted, database.readBytes())
        assertFalse(backup.exists())
    }

    @Test
    fun `nothing happens when there is no backup`() {
        assertFalse(PlaintextDatabaseMigration.restoreInterruptedMigration(dbFile()))
    }

    @Test
    fun `an already encrypted database is left alone`() {
        val database = writeEncryptedDatabase()
        val before = database.readBytes()

        // Reaches the plaintext check and stops there, so no native library is needed.
        assertFalse(PlaintextDatabaseMigration.migrateIfNeeded(database, ByteArray(32) { 1 }))

        assertArrayEquals(before, database.readBytes())
    }

    @Test
    fun `a fresh install with no database needs no migration`() {
        assertFalse(PlaintextDatabaseMigration.migrateIfNeeded(dbFile(), ByteArray(32) { 1 }))
    }
}
