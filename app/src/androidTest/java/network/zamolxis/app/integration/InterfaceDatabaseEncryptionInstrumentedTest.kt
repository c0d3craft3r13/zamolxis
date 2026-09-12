package network.zamolxis.app.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import network.zamolxis.app.data.db.PlaintextDatabaseMigration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The interface database must not be readable off a seized phone.
 *
 * Each row's `configJson` is a serialized interface configuration, and for TCP and
 * RNode interfaces that includes the IFAC network name and passphrase — the
 * credential that admits a node to a private mesh. Someone who reads it does not
 * need to break anything afterwards; they join as a member.
 *
 * App-private storage is not enough on its own. Android's file-based encryption
 * protects it while the device has never been unlocked, and a phone taken from
 * someone has almost always been unlocked at least once, which is when forensic
 * tooling reads the lot.
 *
 * So this covers the conversion end to end with the real native library: an install
 * that predates encryption keeps its interfaces, the file stops being readable
 * SQLite, and the passphrase in it cannot be recovered without the key.
 */
@RunWith(AndroidJUnit4::class)
class InterfaceDatabaseEncryptionInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    private val passphrase = ByteArray(32) { (it * 11 + 5).toByte() }

    /** Stands in for a real row: an interface whose config carries an IFAC secret. */
    private val secret = "correct-horse-battery-staple"
    private val configJson = """{"target_host":"h.invalid","target_port":4242,"passphrase":"$secret"}"""

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "interface-encryption-test/interface_database")
        databaseFile.parentFile?.mkdirs()
        cleanUpFiles()
    }

    @After
    fun tearDown() = cleanUpFiles()

    private fun cleanUpFiles() {
        databaseFile.parentFile?.listFiles()?.forEach { it.delete() }
    }

    private fun writeLegacyDatabase() {
        SQLiteDatabase
            .openOrCreateDatabase(databaseFile.absolutePath, null as SQLiteDatabase.CursorFactory?)
            .use { db ->
                db.execSQL("CREATE TABLE interfaces (id INTEGER PRIMARY KEY, name TEXT, configJson TEXT)")
                db.execSQL(
                    "INSERT INTO interfaces (id, name, configJson) VALUES (?, ?, ?)",
                    arrayOf<Any>(1L, "Home TCP", configJson),
                )
                db.execSQL("PRAGMA user_version = 1")
            }
    }

    private fun readConfigJson(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT configJson FROM interfaces ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    @Test
    fun anInstallFromBeforeEncryptionKeepsItsInterfaces() {
        writeLegacyDatabase()

        val converted = PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase)

        assertTrue("a plaintext database must be converted", converted)
        SQLiteDatabase
            .openOrCreateDatabase(databaseFile.absolutePath, passphrase, null, null)
            .use { db ->
                assertEquals(listOf(configJson), readConfigJson(db))
                assertEquals("Room's schema version must survive", 1, db.version)
            }
    }

    /**
     * The property that matters. Before the conversion the passphrase is sitting in
     * a file anyone can read; after it, the file has no readable structure at all.
     */
    @Test
    fun theIfacPassphraseStopsBeingReadableOnDisk() {
        writeLegacyDatabase()
        assertTrue(
            "the fixture must really contain the secret to begin with",
            databaseFile.readBytes().toString(Charsets.ISO_8859_1).contains(secret),
        )

        PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase)

        assertFalse(
            "the IFAC passphrase must not be recoverable by reading the file",
            databaseFile.readBytes().toString(Charsets.ISO_8859_1).contains(secret),
        )
        assertFalse(
            "and the file must no longer be plain SQLite",
            PlaintextDatabaseMigration.isPlaintextSqlite(databaseFile),
        )
    }

    @Test
    fun aDatabaseThatIsAlreadyEncryptedIsLeftAlone() {
        writeLegacyDatabase()
        PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase)

        val convertedAgain = PlaintextDatabaseMigration.migrateIfNeeded(databaseFile, passphrase)

        assertFalse("a second open must not convert anything", convertedAgain)
        SQLiteDatabase
            .openOrCreateDatabase(databaseFile.absolutePath, passphrase, null, null)
            .use { db -> assertEquals(listOf(configJson), readConfigJson(db)) }
    }
}
