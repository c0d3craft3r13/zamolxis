package network.zamolxis.app.rns.host.di

import android.content.Context
import network.zamolxis.app.data.db.ZamolxisDatabase
import network.zamolxis.app.data.db.ZamolxisDatabaseFactory

/**
 * Manual database provider for the :reticulum service process.
 *
 * Hilt doesn't work across process boundaries, so we create the database manually
 * in the service process. This allows the service to persist announces and messages
 * directly to the database even when the app process is killed.
 *
 * How it is opened — name, migrations, multi-instance invalidation, durability
 * pragmas — is [ZamolxisDatabaseFactory]'s business, not this object's. This file
 * used to spell all of that out a second time and fell behind the schema: it
 * registered migrations only up to 6 while the database was at 9, so a service
 * that opened a not-yet-upgraded database first hit a missing migration path.
 */
object ServiceDatabaseProvider {
    @Volatile
    private var INSTANCE: ZamolxisDatabase? = null

    fun getDatabase(context: Context): ZamolxisDatabase =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: ZamolxisDatabaseFactory.create(context).also { INSTANCE = it }
        }

    fun close() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
