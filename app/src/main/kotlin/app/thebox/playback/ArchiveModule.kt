package app.thebox.playback

import app.thebox.core.catchup.CatchupUrlBuilder
import app.thebox.core.catchup.archive.ArchivePlanner
import app.thebox.core.catchup.archive.ArchivePlannerImpl
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.CatchupProbeStore
import app.thebox.core.full.ProgrammeFeed
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.PlaybackController
import app.thebox.ui.playback.CatchupLauncher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * Catch-up engine bindings (docs/design/m4-player-ux.md §1.3, §2.7; owner WP-B): the pure planner, the one
 * [ArchivePlayback] session (main-confined on the application scope, §2.6) and the M3 launcher adapter over it.
 */
@Module
@InstallIn(SingletonComponent::class)
object ArchiveModule {
    @Provides fun planner(): ArchivePlanner = ArchivePlannerImpl()

    @Provides @Singleton
    fun playback(
        controller: PlaybackController,
        targets: ArchiveTargets,
        builder: CatchupUrlBuilder,
        timeBases: TimeBaseLookup,
        probeStore: CatchupProbeStore,
        feed: ProgrammeFeed,
        planner: ArchivePlanner,
        @ApplicationScope appScope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): ArchivePlayback = ArchivePlaybackImpl(
        controller = controller,
        targets = targets,
        builder = builder,
        timeBases = timeBases,
        probeStore = probeStore,
        feed = feed,
        planner = planner,
        scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        io = io,
        clock = System::currentTimeMillis,
    )

    @Provides @Singleton
    fun catchupLauncher(archive: ArchivePlayback): CatchupLauncher = CatchupLauncherImpl(archive)
}
