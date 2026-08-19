package network.zamolxis.app.data.db

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Absolute path, inside the app's `databases/` directory, for a migration-test database.
 *
 * `MigrationTestHelper` is configured with whatever string `createDatabase()` is handed,
 * but the SQLite driver underneath resolves the database it actually opens to an absolute
 * path. Under Robolectric those two disagree when a bare name is passed, and the open
 * fails with:
 *
 *     This driver is configured to open a database named '<name>' but '<abs path>'
 *
 * Passing the absolute path up front keeps both sides identical. This is a test-harness
 * concern only — production opens the database by name through Room as usual, and the
 * migrations themselves are untouched.
 */
internal fun migrationDbPath(name: String): String =
    File(
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("probe")
            .parentFile,
        name,
    ).absolutePath
