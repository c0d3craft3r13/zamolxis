package network.zamolxis.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration5To6SchemaTest {
    private val databaseName = migrationDbPath("call-history-blocking-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 6 schema`() {
        helper.createDatabase(databaseName, 5).close()
        helper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            ZamolxisDatabase.MIGRATION_5_6,
        ).close()
    }

}
