package network.zamolxis.app.rns.host.di

import android.content.Context
import androidx.room.Room
import network.zamolxis.app.data.db.ZamolxisDatabase
import network.zamolxis.app.data.di.DatabaseModule

/**
 * Manual database provider for the :reticulum service process.
 *
 * Hilt doesn't work across process boundaries, so we create the database manually
 * in the service process. This allows the service to persist announces and messages
 * directly to the database even when the app process is killed.
 *
 * Uses [enableMultiInstanceInvalidation] to handle cross-process database access safely.
 */
object ServiceDatabaseProvider {
    @Volatile
    private var INSTANCE: ZamolxisDatabase? = null

    fun getDatabase(context: Context): ZamolxisDatabase =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: createDatabase(context).also { INSTANCE = it }
        }

    private fun createDatabase(context: Context): ZamolxisDatabase =
        Room
            .databaseBuilder(
                context.applicationContext,
                ZamolxisDatabase::class.java,
                DatabaseModule.DATABASE_NAME,
            ).addMigrations(
                ZamolxisDatabase.MIGRATION_1_2,
                ZamolxisDatabase.MIGRATION_2_3,
                ZamolxisDatabase.MIGRATION_3_4,
                ZamolxisDatabase.MIGRATION_4_5,
                ZamolxisDatabase.MIGRATION_5_6,
            )
            .enableMultiInstanceInvalidation()
            .addCallback(DatabaseModule.DURABILITY_CALLBACK)
            .build()

    fun close() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
