package network.zamolxis.app.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import network.zamolxis.app.data.db.ZamolxisDatabase
import network.zamolxis.app.data.db.ZamolxisDatabaseFactory
import network.zamolxis.app.data.crypto.IdentityKeyEncryptor
import network.zamolxis.app.data.crypto.SecretBlobEncryptor
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.app.data.db.dao.AnnounceDao
import network.zamolxis.app.data.db.dao.BlockedPeerDao
import network.zamolxis.app.data.db.dao.ContactDao
import network.zamolxis.app.data.db.dao.ConversationDao
import network.zamolxis.app.data.db.dao.CustomThemeDao
import network.zamolxis.app.data.db.dao.DraftDao
import network.zamolxis.app.data.db.dao.InterfaceFirstSeenDao
import network.zamolxis.app.data.db.dao.LocalIdentityDao
import network.zamolxis.app.data.db.dao.MessageDao
import network.zamolxis.app.data.db.dao.OfflineMapRegionDao
import network.zamolxis.app.data.db.dao.PeerActivityDao
import network.zamolxis.app.data.db.dao.PeerIconDao
import network.zamolxis.app.data.db.dao.PeerIdentityDao
import network.zamolxis.app.data.db.dao.PqKeyDao
import network.zamolxis.app.data.db.dao.ReceivedLocationDao
import network.zamolxis.app.data.db.dao.RmspServerDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // Hilt modules have one @Provides per DAO
object DatabaseModule {
    const val DATABASE_NAME = ZamolxisDatabaseFactory.DATABASE_NAME

    @Provides
    @Singleton
    fun provideZamolxisDatabase(
        @ApplicationContext context: Context,
    ): ZamolxisDatabase = ZamolxisDatabaseFactory.create(context)

    @Provides
    fun provideConversationDao(database: ZamolxisDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: ZamolxisDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideAnnounceDao(database: ZamolxisDatabase): AnnounceDao = database.announceDao()

    @Provides
    fun providePeerIdentityDao(database: ZamolxisDatabase): PeerIdentityDao = database.peerIdentityDao()

    @Provides
    fun providePeerActivityDao(database: ZamolxisDatabase): PeerActivityDao = database.peerActivityDao()

    @Provides
    fun providePeerIconDao(database: ZamolxisDatabase): PeerIconDao = database.peerIconDao()

    @Provides
    fun provideContactDao(database: ZamolxisDatabase): ContactDao = database.contactDao()

    @Provides
    fun provideCustomThemeDao(database: ZamolxisDatabase): CustomThemeDao = database.customThemeDao()

    @Provides
    fun provideLocalIdentityDao(database: ZamolxisDatabase): LocalIdentityDao = database.localIdentityDao()

    @Provides
    fun provideReceivedLocationDao(database: ZamolxisDatabase): ReceivedLocationDao = database.receivedLocationDao()

    @Provides
    fun provideOfflineMapRegionDao(database: ZamolxisDatabase): OfflineMapRegionDao = database.offlineMapRegionDao()

    @Provides
    fun provideRmspServerDao(database: ZamolxisDatabase): RmspServerDao = database.rmspServerDao()

    @Provides
    fun provideDraftDao(database: ZamolxisDatabase): DraftDao = database.draftDao()

    @Provides
    fun provideBlockedPeerDao(database: ZamolxisDatabase): BlockedPeerDao = database.blockedPeerDao()

    @Provides
    fun provideInterfaceFirstSeenDao(database: ZamolxisDatabase): InterfaceFirstSeenDao = database.interfaceFirstSeenDao()

    @Provides
    fun providePqKeyDao(database: ZamolxisDatabase): PqKeyDao = database.pqKeyDao()

    /**
     * The hybrid post-quantum engine.
     *
     * A singleton because it is stateless and thread-safe, and because each
     * instance otherwise seeds its own [java.security.SecureRandom] — pointless
     * work on a device where one properly seeded source is what you want.
     */
    @Provides
    @Singleton
    fun provideHybridKem(): HybridKem = HybridKem()

    /** The Keystore-backed encryptor, behind the narrow interface its callers need. */
    @Provides
    @Singleton
    fun provideSecretBlobEncryptor(encryptor: IdentityKeyEncryptor): SecretBlobEncryptor = encryptor

    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // This IS the DI provider for the IO dispatcher
    fun provideIODispatcher(): CoroutineDispatcher = Dispatchers.IO
}
