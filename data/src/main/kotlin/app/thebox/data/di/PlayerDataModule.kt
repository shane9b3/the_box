package app.thebox.data.di

import app.thebox.core.catchup.archive.CatchupProbeStore
import app.thebox.core.epg.EpgStore
import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.full.feed.ProgrammeFeedImpl
import app.thebox.data.player.CatchupProbeStoreImpl
import app.thebox.data.player.InMemoryPlayerSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Player data (docs/design/m4-player-ux.md §1.4, §2.7; owner WP-C). Commit 0 binds the stubs so `:app` compiles;
 * nothing constructs them at app start until WP-D wires the player.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerDataModule {
    @Binds abstract fun probeStore(impl: CatchupProbeStoreImpl): CatchupProbeStore
    @Binds @Singleton abstract fun settings(impl: InMemoryPlayerSettings): PlayerSettingsSource

    companion object {
        @Provides @Singleton fun programmeFeed(store: EpgStore): ProgrammeFeed = ProgrammeFeedImpl(store)
    }
}
