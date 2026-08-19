package network.zamolxis.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v7 → v8 adds `peer_pq_keys.pendingPublicKey`.
 *
 * Unlike the migration before it this one ALTERs an existing table, so validating
 * the result against Room's derived schema matters more: a column added with the
 * wrong type or nullability would surface on a user's device during upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration7To8SchemaTest {
    private val DATABASE_NAME = migrationDbPath("pq-pending-key-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 8 schema`() {
        helper.createDatabase(DATABASE_NAME, 7).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            8,
            true,
            ZamolxisDatabase.MIGRATION_7_8,
        ).close()
    }
}
