package network.zamolxis.app.data.db

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import network.zamolxis.app.data.crypto.IdentityKeyEncryptor

/**
 * The single place [ZamolxisDatabase] is opened.
 *
 * Two processes open this database — the UI process through Hilt's
 * `DatabaseModule`, and `:reticulum` through `ServiceDatabaseProvider`, which
 * cannot use Hilt because it lives on the other side of a process boundary. They
 * used to build it independently, and drifted: the service registered migrations
 * 1→6 while the schema had moved on to 9, so whenever the service was the first
 * of the two to open a database still at v6, v7 or v8, Room found no migration
 * path and threw. Every open-time decision — name, migrations, invalidation,
 * durability — belongs here so the two processes cannot disagree again.
 */
object ZamolxisDatabaseFactory {
    const val DATABASE_NAME = "zamolxis_database"

    /**
     * Every migration [ZamolxisDatabase] declares, in one list.
     *
     * `ZamolxisDatabaseFactoryTest` checks this chain is unbroken from 1 to the
     * schema version, so a new migration that isn't added here fails the build
     * rather than a user's upgrade.
     */
    val MIGRATIONS: List<Migration> =
        listOf(
            ZamolxisDatabase.MIGRATION_1_2,
            ZamolxisDatabase.MIGRATION_2_3,
            ZamolxisDatabase.MIGRATION_3_4,
            ZamolxisDatabase.MIGRATION_4_5,
            ZamolxisDatabase.MIGRATION_5_6,
            ZamolxisDatabase.MIGRATION_6_7,
            ZamolxisDatabase.MIGRATION_7_8,
            ZamolxisDatabase.MIGRATION_8_9,
            ZamolxisDatabase.MIGRATION_9_10,
        )

    /**
     * Harden SQLite against process-kill-induced corruption.
     *
     * Background: the app runs two processes (main + `:reticulum`) that both open this
     * Room DB. If either is OOM-killed mid-write, the default `synchronous=NORMAL` in
     * WAL mode only fsyncs on checkpoint, which on some kernels/filesystems (f2fs on
     * older Android kernels) can leave torn WAL pages and produce `SQLITE_CORRUPT` on
     * next read. `synchronous=FULL` fsyncs on every commit, closing that window.
     *
     * Applied from [create], which both processes go through, so they agree on
     * journal mode and durability.
     *
     * Note: `onOpen` fires after Room has already run any pending migrations, so the
     * migration window itself still runs at `synchronous=NORMAL`. That's a narrow
     * residual risk (migrations execute once per schema bump, for seconds) accepted
     * for this patch; a full fix would require a custom `SupportSQLiteOpenHelper.Factory`.
     * `onCreate` is also overridden so first-install schema creation is durable.
     */
    val DURABILITY_CALLBACK: RoomDatabase.Callback =
        object : RoomDatabase.Callback() {
            private fun applyPragmas(db: SupportSQLiteDatabase) {
                // All four PRAGMAs return a result row (either the new value or the
                // activated mode). Android's SupportSQLiteDatabase rejects execSQL for
                // any statement that produces rows, so everything must go through
                // query() and close the cursor even if we don't care about the value.
                //
                // SQLite does not allow PRAGMA journal_mode or PRAGMA synchronous to be
                // changed while a transaction is active (it raises SQLITE_ERROR: "Safety
                // level may not be changed inside a transaction"). Room's InvalidationTracker
                // can invoke onCreate() from within an internal transaction, so we guard
                // these two PRAGMAs with an inTransaction() check. onOpen() is typically
                // called outside of a transaction in current Room versions, so the skipped
                // PRAGMAs get applied on the next open. The guard is also our defense if
                // a future Room version ever calls onOpen() transactionally — we'd just
                // log the skip instead of crashing.
                if (!db.inTransaction()) {
                    db.query("PRAGMA journal_mode=WAL").use { cursor ->
                        if (cursor.moveToFirst() && !cursor.getString(0).equals("wal", ignoreCase = true)) {
                            Log.e("Zamolxis/DB", "journal_mode=WAL not activated; mode=${cursor.getString(0)}")
                        }
                    }
                    db.query("PRAGMA synchronous=FULL").use {
                        /* drain row */ it.moveToFirst()
                    }
                } else {
                    Log.d(
                        "Zamolxis/DB",
                        "applyPragmas: inside transaction, skipping journal_mode and synchronous " +
                            "(will retry on next transaction-free callback)",
                    )
                }
                db.query("PRAGMA wal_autocheckpoint=100").use {
                    /* drain row */ it.moveToFirst()
                }
                db.query("PRAGMA busy_timeout=5000").use {
                    /* drain row */ it.moveToFirst()
                }
            }

            override fun onCreate(db: SupportSQLiteDatabase) = applyPragmas(db)

            override fun onOpen(db: SupportSQLiteDatabase) = applyPragmas(db)
        }

    /**
     * Open the database for this process, encrypted at rest.
     *
     * The passphrase comes from [DatabaseKeyStore] — device-bound, never derived from
     * anything the user types, because `:reticulum` has to store messages that arrive
     * while the app is locked. An install that predates encryption is converted first;
     * see [PlaintextDatabaseMigration].
     */
    fun create(context: Context): ZamolxisDatabase {
        SqlCipherNative.ensureLoaded()
        val appContext = context.applicationContext
        val passphrase = keyStore(appContext).loadOrCreate()
        PlaintextDatabaseMigration.migrateIfNeeded(appContext.getDatabasePath(DATABASE_NAME), passphrase)

        val builder =
            Room.databaseBuilder(
                appContext,
                ZamolxisDatabase::class.java,
                DATABASE_NAME,
            )
        // Added one at a time rather than spread into the vararg: the list is the
        // thing tests read, and a spread would copy it on every open for no gain.
        MIGRATIONS.forEach { builder.addMigrations(it) }
        return builder
            // `clearPassphrase = false`: SQLCipher zeroes the array it is given by
            // default, which is fine for a single open and fatal for the reopens Room
            // does after close() or when multi-instance invalidation reconnects.
            .openHelperFactory(SupportOpenHelperFactory(passphrase, null, false))
            .enableMultiInstanceInvalidation()
            .addCallback(DURABILITY_CALLBACK)
            .build()
    }

    /** The passphrase store for this app, keyed by the Keystore-backed encryptor. */
    fun keyStore(context: Context): DatabaseKeyStore = DatabaseKeyStore(context.applicationContext.filesDir, IdentityKeyEncryptor())
}
