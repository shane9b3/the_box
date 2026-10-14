package app.thebox.player.controller

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import android.os.SystemClock
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.playback.StreamUrlBuilder
import app.thebox.core.playback.diag.PlaybackEventLogImpl
import app.thebox.core.playback.resolve.StreamUrlBuilderImpl
import app.thebox.player.api.PlaybackController
import app.thebox.player.broker.StreamBrokerRegistryImpl
import app.thebox.player.datasource.TransportFactoryImpl
import app.thebox.player.engine.MediaSourceBuilder
import app.thebox.player.engine.PlayerFactory
import app.thebox.player.internal.MediaSourceBuilderApi
import app.thebox.player.internal.PlayerFactoryApi
import app.thebox.player.internal.StreamBrokerRegistry
import app.thebox.player.internal.StreamUrlResolver
import app.thebox.player.internal.TransportFactory
import app.thebox.player.resolve.StreamUrlResolverImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `:player` bindings (§2.8). Needs from the graph: `@ApplicationContext Context`, the unqualified `OkHttpClient`
 * (`:data`), and `PlaybackSourceProvider`/`ChannelMemory`/`PlaybackSettingsSource` (bound in `:app`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackModule {
    @Binds abstract fun controller(impl: PlaybackControllerImpl): PlaybackController
    @Binds abstract fun resolver(impl: StreamUrlResolverImpl): StreamUrlResolver
    @Binds abstract fun brokers(impl: StreamBrokerRegistryImpl): StreamBrokerRegistry
    @Binds abstract fun transport(impl: TransportFactoryImpl): TransportFactory
    @Binds abstract fun playerFactory(impl: PlayerFactory): PlayerFactoryApi
    @Binds abstract fun mediaSources(impl: MediaSourceBuilder): MediaSourceBuilderApi

    companion object {
        @Provides fun monoClock(): MonoClock = MonoClock { SystemClock.elapsedRealtime() }

        @Provides fun streamUrlBuilder(): StreamUrlBuilder = StreamUrlBuilderImpl()

        @Provides @Singleton fun eventLog(clock: MonoClock): PlaybackEventLog = PlaybackEventLogImpl(clock)
    }
}
