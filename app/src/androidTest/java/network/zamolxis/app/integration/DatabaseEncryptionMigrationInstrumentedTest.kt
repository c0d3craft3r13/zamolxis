package network.zamolxis.app.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import network.zamolxis.app.data.db.PlaintextDatabaseMigration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the plaintext-to-encrypted conversion for real.
 *
 * The unit tests cover which files the migration decides to touch; this covers the
 * part that needs SQLCipher's native library — that the exported database holds the
 * same rows, that Room's `user_version` survives (without it Room would treat the
 * converted database as brand new and rebuild the schema over the user's messages),
 * and that the result cannot be read without the passphrase.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    private val passphrase = ByteArray(32) { (it * 7 + 3).toByte() }
    private val schemaVersion = 9

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "migration-test/messages.db")
        databaseFile.parentFile?.mkdirs()
        cleanUpFiles()
    }

    @After
    fun tearDown() {
        cleanUpFiles()
    }

    private fun cleanUpFiles() {
        databaseFile.parentFile?.listFiles()?.forEach { it.delete() }
    }

    /** A plaintext database standing in for one written before encryption shipped. */
    private fun writeLegacyDatabase(rows: List<Pair<Long, String>>) {
        val db =
            SQLiteDatabase.openOrCreateDatabase(
                databaseFile.absolutePath,
                null as SQLiteDatabase.CursorFactory?,
            )
        db.use {
            it.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, content TEXT)")
            rows.forEach { (id, content) ->
                it.execSQL("INSERT INTO messages (id, content) VALUES (?, ?)", arrayOf<Any>(id, content))
            }
            it.execSQL("PRAGMA user_version = $schemaVersion")
        }
    }

    private fun readRows(db: SQLiteDatabase): List<Pair<Long, String>> =
        db.rawQuery("SELECT id, content FROM messages ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(0) to cursor.getString(1))
                }
            }
        }

    @Test
    fun migrationPreservesRowsAndSchemaVersion() {
        val rows = listOf(1L to "first message", 2L to "second message", 3L to "третье сообщение")
        writeLegacyDatabase(rows)
        assertTrue("Precondition: the database starts out unencrypted", PlaintextDatabaseMigration.isPlaintextSqlite(databaseFile))

        val migrated = PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase)

        assertTrue("The migration should report that it converted the database", migrated)
        assertFalse("The database must no longer be readable as plaintext", PlaintextDatabaseMigration.isPlaintextSqlite(databaseFile))

        val encrypted =
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                passphrase,
                null as SQLiteDatabase.CursorFactory?,
                SQLiteDatabase.OPEN_READONLY,
                null,
            )
        encrypted.use {
            assertEquals("Every row must survive the export", rows, readRows(it))
            assertEquals(
                "user_version must carry over, or Room rebuilds the schema over the user's messages",
                schemaVersion,
                it.version,
            )
        }
    }

    @Test
    fun migratedDatabaseCannotBeOpenedWithoutThePassphrase() {
        writeLegacyDatabase(listOf(1L to "secret"))

        PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase)

        val failure =
            runCatching {
                SQLiteDatabase.openDatabase(
                    databaseFile.absolutePath,
                    ByteArray(32) { 0 },
                    null as SQLiteDatabase.CursorFactory?,
                    SQLiteDatabase.OPEN_READONLY,
                    null,
                ).use { readRows(it) }
            }.exceptionOrNull()

        assertNotNull("The wrong passphrase must not open the database", failure)
    }

    @Test
    fun migrationLeavesNoPlaintextBehind() {
        writeLegacyDatabase(listOf(1L to "leave no trace"))

        PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase)

        val leftovers =
            databaseFile.parentFile?.listFiles()?.map { it.name }.orEmpty()
                .filter { it != databaseFile.name }
        assertTrue("The plaintext copy and its journals must be gone, found: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun runningTheMigrationTwiceIsHarmless() {
        val rows = listOf(1L to "only once")
        writeLegacyDatabase(rows)

        assertTrue(PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase))
        assertFalse(
            "An already encrypted database must not be re-encrypted",
            PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase),
        )

        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            passphrase,
            null as SQLiteDatabase.CursorFactory?,
            SQLiteDatabase.OPEN_READONLY,
            null,
        ).use {
            assertEquals(rows, readRows(it))
        }
    }
}
