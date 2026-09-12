package network.zamolxis.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import network.zamolxis.app.data.database.InterfaceDatabase
import network.zamolxis.app.data.database.dao.InterfaceDao
import network.zamolxis.app.data.db.ZamolxisDatabase
import network.zamolxis.app.data.db.ZamolxisDatabaseFactory
import network.zamolxis.app.data.repository.ConversationRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.repository.InterfaceRepository
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.rns.api.RnsCore
import network.zamolxis.app.rns.api.RnsTransportAdmin
import network.zamolxis.app.service.AutoAnnounceManager
import network.zamolxis.app.service.IdentityResolutionManager
import network.zamolxis.app.service.InterfaceConfigManager
import network.zamolxis.app.service.MessageCollector
import network.zamolxis.app.service.PropagationNodeManager
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the application-level coroutine scope.
 */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ApplicationScope

/**
 * Qualifier for the default coroutine dispatcher (testable alternative to hardcoding Dispatchers.Default).
 */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class DefaultDispatcher

/**
 * Qualifier for the IO coroutine dispatcher (testable alternative to hardcoding Dispatchers.IO).
 */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class IoDispatcher

/**
 * Hilt module for providing the Interface database and related DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object InterfaceDatabaseModule {
    /**
     * Provides an application-level coroutine scope for database initialization.
     */
    @ApplicationScope
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob())

    /**
     * Provides the default dispatcher for background work that needs to be off the Main thread.
     */
    @DefaultDispatcher
    @Provides
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides the IO dispatcher for disk/network-bound work.
     */
    @IoDispatcher
    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** The on-disk name; shared by the opener and the plaintext conversion. */
    private const val INTERFACE_DATABASE_NAME = "interface_database"

    /**
     * Provides the Interface database singleton.
     */
    @Provides
    @Singleton
    fun provideInterfaceDatabase(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
        database: Provider<InterfaceDatabase>,
    ): InterfaceDatabase {
        // Encrypted at rest, for the same reason the message database is. Each row's
        // `configJson` is a serialized InterfaceConfig, and that carries the IFAC
        // network name and passphrase — the credential that admits a node to a private
        // mesh. Left in plain SQLite it is readable from a seized phone once Android's
        // file-based encryption has been unlocked even once, which is the state a phone
        // taken from someone is usually in.
        // Loads SQLCipher, takes the device passphrase, and converts a file left
        // plaintext by an install that predates encryption — all before Room opens it.
        val passphrase = ZamolxisDatabaseFactory.prepareEncrypted(context, INTERFACE_DATABASE_NAME)

        return Room
            .databaseBuilder(
                context,
                InterfaceDatabase::class.java,
                INTERFACE_DATABASE_NAME,
            ).addCallback(InterfaceDatabase.Callback(context, database, applicationScope))
            // `clearPassphrase = false` for the reason ZamolxisDatabaseFactory gives:
            // SQLCipher zeroes the array it is handed, which breaks Room's reopens.
            .openHelperFactory(SupportOpenHelperFactory(passphrase, null, false))
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    /**
     * Provides the InterfaceDao from the database.
     */
    @Provides
    fun provideInterfaceDao(database: InterfaceDatabase): InterfaceDao = database.interfaceDao()

    /**
     * Provides the InterfaceConfigManager for applying configuration changes.
     */
    @Suppress("LongParameterList") // Hilt DI requires all dependencies as parameters
    @Provides
    @Singleton
    fun provideInterfaceConfigManager(
        @ApplicationContext context: Context,
        rnsCore: RnsCore,
        rnsTransportAdmin: RnsTransportAdmin,
        interfaceRepository: InterfaceRepository,
        identityRepository: IdentityRepository,
        identityKeyProvider: network.zamolxis.app.data.crypto.IdentityKeyProvider,
        conversationRepository: ConversationRepository,
        contactRepository: network.zamolxis.app.data.repository.ContactRepository,
        messageCollector: MessageCollector,
        database: ZamolxisDatabase,
        settingsRepository: SettingsRepository,
        autoAnnounceManager: AutoAnnounceManager,
        identityResolutionManager: IdentityResolutionManager,
        propagationNodeManager: PropagationNodeManager,
        transportObserver: network.zamolxis.app.service.manager.InterfaceTransportObserver,
        @ApplicationScope applicationScope: CoroutineScope,
    ): InterfaceConfigManager =
        InterfaceConfigManager(
            context = context,
            rnsCore = rnsCore,
            rnsTransportAdmin = rnsTransportAdmin,
            interfaceRepository = interfaceRepository,
            identityRepository = identityRepository,
            identityKeyProvider = identityKeyProvider,
            conversationRepository = conversationRepository,
            contactRepository = contactRepository,
            messageCollector = messageCollector,
            database = database,
            settingsRepository = settingsRepository,
            autoAnnounceManager = autoAnnounceManager,
            identityResolutionManager = identityResolutionManager,
            propagationNodeManager = propagationNodeManager,
            transportObserver = transportObserver,
            applicationScope = applicationScope,
        )
}
