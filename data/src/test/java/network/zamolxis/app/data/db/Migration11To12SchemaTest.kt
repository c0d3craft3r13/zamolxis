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
 * Somewhere to keep a rotated-out post-quantum key pair.
 *
 * Purely additive, which is the point: an identity's live key material is what a
 * user loses irrecoverably, and a migration that rewrote any of it to add a
 * feature would be trading the thing being protected for the protection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration11To12SchemaTest {
    private val databaseName = migrationDbPath("retired-pq-keys-room-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 12 schema`() {
        helper.createDatabase(databaseName, 11).close()
        helper
            .runMigrationsAndValidate(
                databaseName,
                12,
                true,
                ZamolxisDatabase.MIGRATION_11_12,
            ).close()
    }

    @Test
    fun `the retired table starts empty rather than absent`() {
        helper.createDatabase(databaseName, 11).close()

        helper
            .runMigrationsAndValidate(databaseName, 12, true, ZamolxisDatabase.MIGRATION_11_12)
            .use { migrated ->
                migrated.query("SELECT COUNT(*) FROM retired_pq_keys").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
    }

    /**
     * Two pairs retired in the same millisecond have to be able to coexist. The
     * first version of this table was keyed on the retirement time, and a second
     * rotation inside the same tick replaced the first row — throwing away the
     * key that messages already in flight had been sealed to.
     */
    @Test
    fun `two keys retired at the same instant both survive`() {
        helper.createDatabase(databaseName, 11).close()

        helper
            .runMigrationsAndValidate(databaseName, 12, true, ZamolxisDatabase.MIGRATION_11_12)
            .use { migrated ->
                migrated.execSQL(
                    "INSERT INTO retired_pq_keys (identityHash, publicKeyHex, retiredTimestamp, encryptedKeyPair) " +
                        "VALUES ('id', 'aa', 1000, X'01')",
                )
                migrated.execSQL(
                    "INSERT INTO retired_pq_keys (identityHash, publicKeyHex, retiredTimestamp, encryptedKeyPair) " +
                        "VALUES ('id', 'bb', 1000, X'02')",
                )

                migrated.query("SELECT COUNT(*) FROM retired_pq_keys WHERE identityHash = 'id'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(2, it.getInt(0))
                }
            }
    }
}
