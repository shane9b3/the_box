package app.thebox.playback

import app.thebox.core.catchup.CatchupUrlBuilder
import app.thebox.core.catchup.archive.ArchivePlanner
import app.thebox.core.catchup.archive.ArchivePlannerImpl
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.PlaybackController
import app.thebox.ui.playback.CatchupLauncher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Catch-up engine bindings (docs/design/m4-player-ux.md §1.3, §2.7; owner WP-B). Commit 0 binds the stubs and moves the
 * M3 launcher binding here from `ReminderModule` unchanged; WP-B rewrites the launcher as an adapter over [ArchivePlayback].
 */
@Module
@InstallIn(SingletonComponent::class)
object ArchiveModule {
    @Provides fun planner(): ArchivePlanner = ArchivePlannerImpl()

    @Provides @Singleton fun playback(): ArchivePlayback = ArchivePlaybackImpl()

    @Provides @Singleton
    fun catchupLauncher(
        targets: ArchiveTargets,
        controller: PlaybackController,
        builder: CatchupUrlBuilder,
        timeBases: TimeBaseLookup,
    ): CatchupLauncher = CatchupLauncherImpl(targets, controller, builder, timeBases, System::currentTimeMillis)
}
