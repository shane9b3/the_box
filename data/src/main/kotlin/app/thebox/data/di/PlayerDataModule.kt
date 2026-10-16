package app.thebox.data.di

import app.thebox.core.catchup.archive.CatchupProbeStore
import app.thebox.core.epg.EpgStore
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.full.feed.ProgrammeFeedImpl
import app.thebox.data.player.CatchupProbeStoreImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Player data (docs/design/m4-player-ux.md §1.4, §2.7; owner WP-C). The feed is a singleton: it owns the one
 * `box-player-epg` thread and its reader for the life of the process (constructed lazily, on first injection).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerDataModule {
    @Binds abstract fun probeStore(impl: CatchupProbeStoreImpl): CatchupProbeStore
    // (M5, WP-A) PlayerSettingsSource → PersistedPlayerSettings, bound in data/.../settings/SettingsDataModule.

    companion object {
        @Provides @Singleton fun programmeFeed(store: EpgStore): ProgrammeFeed = ProgrammeFeedImpl(store)
    }
}
