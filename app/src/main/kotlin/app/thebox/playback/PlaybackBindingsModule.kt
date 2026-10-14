package app.thebox.playback

import app.thebox.data.net.HttpModule
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.InMemoryPlaybackSettings
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.api.PlaybackSourceProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** `:player`'s provider interfaces over the M1 repositories (§2.4.2). */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindingsModule {
    @Binds @Singleton abstract fun sourceProvider(impl: DataPlaybackSourceProvider): PlaybackSourceProvider
    @Binds @Singleton abstract fun channelMemory(impl: RoomChannelMemory): ChannelMemory

    companion object {
        /** In memory in M2 (M5 binds DataStore); `defaultUserAgent` comes from `HttpModule.DEFAULT_USER_AGENT`. */
        @Provides @Singleton
        fun settings(impl: InMemoryPlaybackSettings): PlaybackSettingsSource =
            impl.apply { update { it.copy(defaultUserAgent = HttpModule.DEFAULT_USER_AGENT) } }
    }
}
