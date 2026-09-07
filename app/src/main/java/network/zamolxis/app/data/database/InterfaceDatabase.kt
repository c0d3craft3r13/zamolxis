package network.zamolxis.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import network.zamolxis.app.data.config.ConfigFileParser
import network.zamolxis.app.data.database.dao.InterfaceDao
import network.zamolxis.app.data.database.entity.InterfaceEntity
import network.zamolxis.app.data.model.TcpCommunityServer
import network.zamolxis.app.data.model.TcpCommunityServers
import network.zamolxis.app.rns.api.model.InterfaceConfig
import network.zamolxis.app.rns.api.model.toJsonString
import org.json.JSONObject
import java.io.File
import javax.inject.Provider

/**
 * Room database for storing Reticulum network interface configurations.
 */
@Database(
    entities = [InterfaceEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class InterfaceDatabase : RoomDatabase() {
    abstract fun interfaceDao(): InterfaceDao

    /**
     * Callback to populate the database with default data on creation.
     * Imports interfaces from existing config file if present, otherwise creates default.
     */
    class Callback(
        private val context: Context,
        private val database: Provider<InterfaceDatabase>,
        private val applicationScope: CoroutineScope,
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            // Populate database synchronously using raw SQL to avoid transaction conflicts
            // Room's onCreate() runs in a transaction, so we can't use DAO suspend methods
            // which try to create their own transactions
            populateDatabaseDirect(db)
        }

        /**
         * Populate database directly using raw SQL inserts.
         * This is necessary because onCreate() runs inside a transaction,
         * and we can't use Room's DAO suspend methods which create their own transactions.
         */
        private fun populateDatabaseDirect(db: SupportSQLiteDatabase) {
            // Check if Reticulum config file exists
            val configFile = File(context.filesDir, "reticulum/config")

            if (configFile.exists()) {
                android.util.Log.i("InterfaceDatabase", "Found existing config file, importing interfaces...")
                try {
                    val interfaces = ConfigFileParser.parseConfigFile(configFile)

                    if (interfaces.isEmpty()) {
                        android.util.Log.w("InterfaceDatabase", "Config file exists but no interfaces found, creating default")
                        insertDefaultInterfacesDirect(db)
                    } else {
                        interfaces.forEachIndexed { index, config ->
                            insertInterfaceDirect(db, config, index)
                        }
                        android.util.Log.d("InterfaceDatabase", "Imported ${interfaces.size} interface(s) from config file")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InterfaceDatabase", "Error importing config file, creating default", e)
                    insertDefaultInterfacesDirect(db)
                }
            } else {
                android.util.Log.i("InterfaceDatabase", "No config file found, creating default AutoInterface")
                insertDefaultInterfacesDirect(db)
            }
        }

        /**
         * Insert default interfaces using raw SQL.
         */
        private fun insertDefaultInterfacesDirect(db: SupportSQLiteDatabase) {
            // Insert AutoInterface
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf<Any>(
                    "Auto Discovery",
                    "AutoInterface",
                    // enabled=true
                    1,
                    """{"group_id":"","discovery_scope":"link","mode":"full"}""",
                    0,
                ),
            )

            // Insert AndroidBLE
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf<Any>(
                    "Bluetooth LE",
                    "AndroidBLE",
                    // enabled=true
                    1,
                    """{"device_name":"Reticulum-Android","max_connections":7,"mode":"full"}""",
                    1,
                ),
            )

            // Seed EVERY measured bootstrap hub, not one. A single seed is a single
            // point of failure for a fresh install: rns.beleth.net accepted the
            // connection, delivered no announces and dropped the client every ~82
            // seconds, and an install that only ever saw it reached the network never
            // — an empty Network tab with nothing on screen to explain it. Redundancy
            // here is the cheapest possible fix; the hubs are `bootstrap_only`, so RNS
            // detaches them once enough discovered interfaces are up.
            TcpCommunityServers.bootstrapServers.forEachIndexed { index, server ->
                db.execSQL(
                    """
                    INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                    VALUES (?, ?, ?, ?, ?)
                """,
                    arrayOf<Any>(
                        server.name,
                        "TCPClient",
                        // enabled=true
                        1,
                        bootstrapConfigJson(server),
                        BOOTSTRAP_DISPLAY_ORDER_BASE + index,
                    ),
                )
            }
        }

        /**
         * Insert interface using raw SQL.
         */
        private fun insertInterfaceDirect(
            db: SupportSQLiteDatabase,
            config: InterfaceConfig,
            displayOrder: Int,
        ) {
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf<Any>(
                    config.name,
                    config.typeName,
                    if (config.enabled) 1 else 0,
                    config.toJsonString(),
                    displayOrder,
                ),
            )
        }

        /**
         * Import interfaces from existing config file, or create default AutoInterface.
         * This enables migration from manual config files to database-driven configuration.
         */
        private suspend fun populateDatabase(interfaceDao: InterfaceDao) {
            // Check if Reticulum config file exists
            val configFile = File(context.filesDir, "reticulum/config")

            if (configFile.exists()) {
                // Import from existing config file
                android.util.Log.i("InterfaceDatabase", "Found existing config file, importing interfaces...")

                try {
                    val interfaces = ConfigFileParser.parseConfigFile(configFile)

                    if (interfaces.isEmpty()) {
                        android.util.Log.w("InterfaceDatabase", "Config file exists but no interfaces found, creating default")
                        createDefaultInterface(interfaceDao)
                    } else {
                        interfaces.forEachIndexed { index, config ->
                            val entity = configToEntity(config, index)
                            interfaceDao.insertInterface(entity)
                        }
                        android.util.Log.d("InterfaceDatabase", "Imported ${interfaces.size} interface(s) from config file")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InterfaceDatabase", "Error importing config file, creating default", e)
                    createDefaultInterface(interfaceDao)
                }
            } else {
                // No config file, create default AutoInterface
                android.util.Log.i("InterfaceDatabase", "No config file found, creating default AutoInterface")
                createDefaultInterface(interfaceDao)
            }
        }

        /**
         * Create default interface configurations.
         * Creates AutoInterface, AndroidBLE, and TCPClient interfaces by default.
         */
        private suspend fun createDefaultInterface(interfaceDao: InterfaceDao) {
            val defaultAutoInterface =
                InterfaceEntity(
                    name = "Auto Discovery",
                    type = "AutoInterface",
                    enabled = true,
                    configJson =
                        """
                        {
                            "group_id": "",
                            "discovery_scope": "link",
                            "mode": "full"
                        }
                        """.trimIndent(),
                    displayOrder = 0,
                )

            val defaultBleInterface =
                InterfaceEntity(
                    name = "Bluetooth LE",
                    type = "AndroidBLE",
                    enabled = true,
                    configJson =
                        """
                        {
                            "device_name": "Reticulum-Android",
                            "max_connections": 7,
                            "mode": "roaming"
                        }
                        """.trimIndent(),
                    displayOrder = 1,
                )

            // Every measured bootstrap hub, not one — see the note in
            // populateDatabaseDirect for what a single seed cost us, and
            // TcpCommunityServers for the measurements behind the list.
            val bootstrapInterfaces =
                TcpCommunityServers.bootstrapServers.mapIndexed { index, server ->
                    InterfaceEntity(
                        name = server.name,
                        type = "TCPClient",
                        enabled = true,
                        configJson = bootstrapConfigJson(server),
                        displayOrder = BOOTSTRAP_DISPLAY_ORDER_BASE + index,
                    )
                }

            interfaceDao.insertInterface(defaultAutoInterface)
            interfaceDao.insertInterface(defaultBleInterface)
            bootstrapInterfaces.forEach { interfaceDao.insertInterface(it) }
        }

        /**
         * Convert InterfaceConfig to InterfaceEntity.
         */
        private fun configToEntity(
            config: InterfaceConfig,
            displayOrder: Int,
        ): InterfaceEntity =
            InterfaceEntity(
                name = config.name,
                type = config.typeName,
                enabled = config.enabled,
                configJson = config.toJsonString(),
                displayOrder = displayOrder,
            )

        companion object {
            /**
             * Bootstrap hubs sort after AutoInterface (0) and AndroidBLE (1).
             */
            const val BOOTSTRAP_DISPLAY_ORDER_BASE = 2

            /**
             * The one place a bootstrap hub's interface config is written.
             *
             * Both seed paths (raw SQL in [Callback.onCreate] and the DAO path) and the
             * top-up for existing installs go through here, so a hub can never end up
             * configured two subtly different ways. `bootstrap_only` is what lets RNS
             * drop these once enough discovered interfaces are connected.
             */
            fun bootstrapConfigJson(server: TcpCommunityServer): String =
                JSONObject()
                    .put("target_host", server.host)
                    .put("target_port", server.port)
                    .put("kiss_framing", false)
                    .put("mode", "full")
                    .put("bootstrap_only", true)
                    .toString()
        }
    }
}
