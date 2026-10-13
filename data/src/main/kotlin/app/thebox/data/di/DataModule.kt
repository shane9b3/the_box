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
}
