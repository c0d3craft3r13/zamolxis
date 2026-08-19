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
 * Validates the full released migration chain (2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8) in one pass.
 *
 * Each hop has its own test; this one proves they compose. It matters most for the
 * oldest installs — someone who has not opened the app since v2 runs every migration
 * back to back on a database full of their messages, and that path gets exercised far
 * less in practice than the latest hop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationChainTo8SchemaTest {
    private val databaseName = migrationDbPath("pq-pending-full-chain-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `full chain 2 to 8 matches exported Room version 8 schema`() {
        helper.createDatabase(databaseName, 2).close()
        helper
            .runMigrationsAndValidate(
                databaseName,
                8,
                true,
                ZamolxisDatabase.MIGRATION_2_3,
                ZamolxisDatabase.MIGRATION_3_4,
                ZamolxisDatabase.MIGRATION_4_5,
                ZamolxisDatabase.MIGRATION_5_6,
                ZamolxisDatabase.MIGRATION_6_7,
                ZamolxisDatabase.MIGRATION_7_8,
            )
            .close()
    }
}
