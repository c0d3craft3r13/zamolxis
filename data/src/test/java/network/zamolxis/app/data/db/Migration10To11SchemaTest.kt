package network.zamolxis.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Post-quantum epochs, and the column recording what a peer can read.
 *
 * The schema check is the usual one. The rest of this file is about the default
 * on `protocolVersion`: every row that already exists belongs to a peer that
 * never declared anything, and the migration has to leave them saying "the older
 * format" rather than nothing. A row that came out of this migration claiming
 * more than the peer can do would have us sealing in a format they cannot open,
 * and on their side that looks like a message that never arrived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration10To11SchemaTest {
    private val databaseName = migrationDbPath("pq-epochs-room-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 11 schema`() {
        helper.createDatabase(databaseName, 10).close()
        helper
            .runMigrationsAndValidate(
                databaseName,
                11,
                true,
                ZamolxisDatabase.MIGRATION_10_11,
            ).close()
    }

    @Test
    fun `a peer stored before the column reads as the older format`() {
        helper.createDatabase(databaseName, 10).use { old ->
            old.execSQL(
                "INSERT INTO peer_pq_keys (peerHash, publicKey, announcedFingerprint, " +
                    "keyChangeUnresolved, pendingPublicKey, fingerprintMismatchTimestamp, updatedTimestamp) " +
                    "VALUES ('deadbeef', NULL, NULL, 0, NULL, NULL, 1)",
            )
        }

        helper
            .runMigrationsAndValidate(databaseName, 11, true, ZamolxisDatabase.MIGRATION_10_11)
            .use { migrated ->
                migrated.query("SELECT protocolVersion FROM peer_pq_keys WHERE peerHash = 'deadbeef'").use {
                    assertTrue("the peer row must survive the migration", it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
            }
    }

    @Test
    fun `the epochs table starts empty rather than absent`() {
        helper.createDatabase(databaseName, 10).close()

        helper
            .runMigrationsAndValidate(databaseName, 11, true, ZamolxisDatabase.MIGRATION_10_11)
            .use { migrated ->
                migrated.query("SELECT COUNT(*) FROM pq_epochs").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
    }
}
