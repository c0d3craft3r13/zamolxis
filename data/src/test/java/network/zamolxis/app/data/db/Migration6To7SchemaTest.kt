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
 * The v6 → v7 migration adds the post-quantum key tables.
 *
 * `runMigrationsAndValidate` compares the schema the hand-written SQL actually
 * produces against the one Room derived from the entities. Without it, a typo in
 * a column type or a missing index only surfaces on a user's device, mid-upgrade,
 * on a database holding their message history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration6To7SchemaTest {
    private val databaseName = migrationDbPath("pq-keys-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 7 schema`() {
        helper.createDatabase(databaseName, 6).close()
        helper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            ZamolxisDatabase.MIGRATION_6_7,
        ).close()
    }
}
