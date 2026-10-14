package app.thebox.playback

// Commit-0 stub (docs/design/m2-playback-core.md §1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.InMemoryPlaybackSettings
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.api.PlaybackSourceProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** `:player`'s provider interfaces over the M1 repositories (§2.4.2). */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindingsModule {
    @Binds @Singleton abstract fun sourceProvider(impl: DataPlaybackSourceProvider): PlaybackSourceProvider
    @Binds @Singleton abstract fun channelMemory(impl: RoomChannelMemory): ChannelMemory
    @Binds abstract fun settings(impl: InMemoryPlaybackSettings): PlaybackSettingsSource
}
