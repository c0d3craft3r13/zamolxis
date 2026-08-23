package network.zamolxis.app.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.zamolxis.app.data.db.PlaintextDatabaseMigration
import network.zamolxis.app.data.db.ZamolxisDatabase
import network.zamolxis.app.data.db.ZamolxisDatabaseFactory
import network.zamolxis.app.data.db.entity.LocalIdentityEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the real database the way the app does, on a device, and checks it is
 * actually encrypted.
 *
 * This is the integration the unit tests cannot reach: Room has to drive SQLCipher's
 * `SupportOpenHelperFactory`, the passphrase has to come back identical on a second
 * open — a wrong or re-derived one fails here and nowhere else — and what lands on
 * disk has to be ciphertext.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseOpenInstrumentedTest {
    private lateinit var context: Context
    private var database: ZamolxisDatabase? = null

    private val identityHash = "encryption_probe_identity_hash_01"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { database?.localIdentityDao()?.delete(identityHash) }
        }
        database?.close()
        database = null
    }

    private fun probeIdentity() =
        LocalIdentityEntity(
            identityHash = identityHash,
            displayName = "Encryption probe",
            destinationHash = "encryption_probe_destination_hash",
            filePath = "/probe",
            keyData = null,
            createdTimestamp = 1_700_000_000_000,
            lastUsedTimestamp = 1_700_000_000_000,
            isActive = false,
        )

    @Test
    fun roomOpensTheEncryptedDatabaseAndRowsSurviveAReopen() {
        val first = ZamolxisDatabaseFactory.create(context)
        runBlocking { first.localIdentityDao().insert(probeIdentity()) }
        first.close()

        // A second open has to unwrap the same passphrase from the Keystore. If it
        // minted a new one, or wrapped it differently, SQLCipher rejects the file.
        val second = ZamolxisDatabaseFactory.create(context).also { database = it }
        val stored = runBlocking { second.localIdentityDao().getAllIdentitiesSync() }

        val probe = stored.firstOrNull { it.identityHash == identityHash }
        assertNotNull("The row written before the reopen must still be readable", probe)
        assertEquals("Encryption probe", probe?.displayName)
    }

    @Test
    fun theDatabaseFileOnDiskIsNotReadableAsPlaintext() {
        database = ZamolxisDatabaseFactory.create(context)
        runBlocking { database?.localIdentityDao()?.insert(probeIdentity()) }

        val file = context.getDatabasePath(ZamolxisDatabaseFactory.DATABASE_NAME)

        assertTrue("Precondition: the database file exists", file.exists())
        assertFalse(
            "The database must not carry a readable SQLite header",
            PlaintextDatabaseMigration.isPlaintextSqlite(file),
        )
        assertFalse(
            "The display name must not be findable in the raw file",
            file.readBytes().contains("Encryption probe".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun thePassphraseIsStoredWrappedRatherThanInTheClear() {
        val store = ZamolxisDatabaseFactory.keyStore(context)
        val passphrase = store.loadOrCreate()

        assertEquals(32, passphrase.size)
        val keyFile = context.filesDir.resolve("zamolxis_database.key")
        assertTrue("The wrapped passphrase should be on disk", keyFile.exists())
        assertFalse(
            "The passphrase itself must never be written out",
            keyFile.readBytes().contains(passphrase),
        )
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
