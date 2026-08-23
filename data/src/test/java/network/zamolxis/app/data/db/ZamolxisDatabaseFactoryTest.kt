package network.zamolxis.app.data.db

import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The UI process and `:reticulum` both open this database, and for a while they
 * each listed the migrations themselves. The service's list stopped at 6 while the
 * schema reached 9, so a service that opened a not-yet-upgraded database first
 * found no migration path and threw. There is one list now; these tests are what
 * keeps a newly written migration from being declared and never registered.
 */
class ZamolxisDatabaseFactoryTest {
    /**
     * The schema version, read from the schemas Room exports rather than from the
     * `@Database` annotation — that one has binary retention and is not readable at
     * runtime. The exported files are also the independent source of truth here: they
     * are written by the annotation processor, not by the code under test.
     */
    private val schemaVersion: Int =
        File("schemas/network.zamolxis.app.data.db.ZamolxisDatabase")
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .maxOrNull()
            ?: error("No exported Room schemas found; expected them under data/schemas/")

    /** Every `MIGRATION_x_y` declared on the database's companion, found by name. */
    private fun declaredMigrations(): List<Migration> =
        ZamolxisDatabase.Companion::class.java.methods
            .filter { it.name.startsWith("getMIGRATION_") && it.parameterCount == 0 }
            .map { it.invoke(ZamolxisDatabase.Companion) as Migration }

    @Test
    fun `registered migrations form an unbroken chain to the current schema version`() {
        val steps = ZamolxisDatabaseFactory.MIGRATIONS.sortedBy { it.startVersion }

        assertEquals(
            "A migration chain reaching version $schemaVersion needs ${schemaVersion - 1} steps",
            schemaVersion - 1,
            steps.size,
        )
        steps.forEachIndexed { index, migration ->
            val expectedStart = index + 1
            assertEquals("Gap in the migration chain", expectedStart, migration.startVersion)
            assertEquals("Migration ${migration.startVersion} must land on the next version", expectedStart + 1, migration.endVersion)
        }
        assertEquals(
            "The chain must end at the schema version",
            schemaVersion,
            steps.last().endVersion,
        )
    }

    @Test
    fun `every declared migration is registered`() {
        val registered = ZamolxisDatabaseFactory.MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()

        val missing =
            declaredMigrations()
                .map { it.startVersion to it.endVersion }
                .filterNot { it in registered }

        assertTrue(
            "Declared on ZamolxisDatabase but missing from ZamolxisDatabaseFactory.MIGRATIONS: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `no version is migrated twice`() {
        val starts = ZamolxisDatabaseFactory.MIGRATIONS.map { it.startVersion }

        assertEquals("Two migrations starting at the same version", starts.size, starts.toSet().size)
    }

    @Test
    fun `the database name is the one the wipe path and both processes agree on`() {
        assertEquals("zamolxis_database", ZamolxisDatabaseFactory.DATABASE_NAME)
    }
}
