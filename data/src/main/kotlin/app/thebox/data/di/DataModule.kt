package app.thebox.data.di

import app.thebox.core.catchup.CatchupResolver
import app.thebox.core.catchup.CatchupResolverImpl
import app.thebox.core.catchup.CatchupUrlBuilder
import app.thebox.core.catchup.CatchupUrlBuilderImpl
import app.thebox.core.catchup.TimeBaseFactory
import app.thebox.core.catchup.TimeBaseFactoryImpl
import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.m3u.M3uParserImpl
import app.thebox.core.m3u.M3uPlaylistParser
import app.thebox.core.refresh.PlaylistDiffer
import app.thebox.core.refresh.PlaylistDifferImpl
import app.thebox.core.refresh.RemapPlanner
import app.thebox.core.refresh.RemapPlannerImpl
import app.thebox.core.refresh.StableKeyDeriver
import app.thebox.core.xtream.XtreamCatalogDecoder
import app.thebox.core.xtream.XtreamCatalogDecoderImpl
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.SourceFetcher
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.net.OkHttpSourceFetcher
import app.thebox.data.security.TinkCredentialStore
import app.thebox.data.xtream.XtreamClient
import android.content.Context
import androidx.work.WorkManager
import app.thebox.data.repo.DataSettings
import app.thebox.data.repo.EpgImporter
import app.thebox.data.repo.EpgRepository
import app.thebox.data.repo.EpgWorkRequester
import app.thebox.data.repo.InMemoryDataSettings
import app.thebox.data.repo.PlaylistRefresher
import app.thebox.data.repo.RefreshRepository
import app.thebox.data.security.AeadProvider
import app.thebox.data.security.KeystoreAeadProvider
import app.thebox.data.work.RefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Named
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the :core contract implementations (constructed directly: :core has no Hilt annotations) and the
 * :data implementations (§3.10). WP-D owns this file after commit 0.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreBindingsModule {
    @Provides @Singleton fun m3uParser(): M3uPlaylistParser = M3uParserImpl()
    @Provides @Singleton fun stableKeyDeriver(): StableKeyDeriver = StableKeyDeriverImpl()
    @Provides @Singleton fun playlistDiffer(deriver: StableKeyDeriver): PlaylistDiffer = PlaylistDifferImpl(deriver)
    @Provides @Singleton fun remapPlanner(): RemapPlanner = RemapPlannerImpl()
    @Provides @Singleton fun xtreamDecoder(): XtreamCatalogDecoder = XtreamCatalogDecoderImpl()
    @Provides @Singleton fun catchupResolver(): CatchupResolver = CatchupResolverImpl()
    @Provides @Singleton fun catchupUrlBuilder(): CatchupUrlBuilder = CatchupUrlBuilderImpl()
    @Provides @Singleton fun timeBaseFactory(): TimeBaseFactory = TimeBaseFactoryImpl()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton abstract fun xtreamApi(impl: XtreamClient): XtreamApi
    @Binds @Singleton abstract fun sourceFetcher(impl: OkHttpSourceFetcher): SourceFetcher
    @Binds @Singleton abstract fun credentialStore(impl: TinkCredentialStore): CredentialStore
    // M5 commit 0 (docs/design/m5-setup-settings.md §2.12 item 8): the same store holds the PIN verifier and passphrase.
    @Binds @Singleton abstract fun secureBlobStore(impl: TinkCredentialStore): app.thebox.data.contracts.SecureBlobStore
    @Binds @Singleton abstract fun keyResetFlag(impl: app.thebox.data.security.PrefsKeyResetFlag): app.thebox.data.security.KeyResetFlag
    @Binds @Singleton abstract fun dataSettings(impl: InMemoryDataSettings): DataSettings
    @Binds abstract fun playlistRefresher(impl: RefreshRepository): PlaylistRefresher
    @Binds abstract fun epgImporter(impl: EpgRepository): EpgImporter
    @Binds abstract fun epgWorkRequester(impl: RefreshScheduler): EpgWorkRequester

    companion object {
        @Provides fun wallClock(): WallClock = WallClock.SYSTEM

        @Provides @Singleton
        fun aeadProvider(@ApplicationContext context: Context): AeadProvider = KeystoreAeadProvider(context)

        @Provides
        fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

        /** `cacheDir/playlists`: the last downloaded playlist (`<id>.src`) and Xtream categories. */
        @Provides @Named(RefreshRepository.PLAYLIST_CACHE_DIR)
        fun playlistCacheDir(@ApplicationContext context: Context): File = File(context.cacheDir, "playlists")
    }
}
