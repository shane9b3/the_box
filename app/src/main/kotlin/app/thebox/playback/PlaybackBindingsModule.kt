package app.thebox.playback

import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.PlaybackSourceProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `:player`'s provider interfaces over the M1 repositories (§2.4.2). (M5, WP-A) `PlaybackSettingsSource` moved to
 * `app/.../settings/SettingsBindingsModule` (`PersistedPlaybackSettings`, which seeds `HttpModule.DEFAULT_USER_AGENT`
 * in memory and keeps it out of the store, S1).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindingsModule {
    @Binds @Singleton abstract fun sourceProvider(impl: DataPlaybackSourceProvider): PlaybackSourceProvider
    @Binds @Singleton abstract fun channelMemory(impl: RoomChannelMemory): ChannelMemory
}
