package app.thebox.reminder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderPlanner
import app.thebox.data.di.ApplicationScope
import app.thebox.data.repo.PlaylistRepository
import app.thebox.playback.TimeBaseLookup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import app.thebox.data.di.StartupGate
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Qualifier
import javax.inject.Singleton

/** True while `ProcessLifecycleOwner` is at least STARTED (§3.11.3). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppForeground

/** Reminder runtime bindings (docs/design/m3-guide.md §1.5; owner WP-D). */
@Module
@InstallIn(SingletonComponent::class)
object ReminderModule {
    @Provides @Singleton @AppForeground
    fun foreground(@ApplicationScope scope: CoroutineScope): StateFlow<Boolean> {
        val state = MutableStateFlow(false)
        // Lifecycle observers must be added on the main thread.
        scope.launch(Dispatchers.Main.immediate) {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(LifecycleEventObserver { _, _ ->
                state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            })
        }
        return state.asStateFlow()
    }

    @Provides @Singleton
    fun scheduler(
        book: ReminderBook,
        planner: ReminderPlanner,
        settings: GuideSettingsSource,
        @AppForeground foreground: StateFlow<Boolean>,
        @ApplicationScope scope: CoroutineScope,
        gate: StartupGate,
    ): ReminderSchedulerImpl {
        // (M3 code review 2026-10-14, O-7) No reminder work until the process is STARTED and the first frame has drawn.
        val ready = combine(foreground, gate.isOpen) { fg, open -> fg && open }
            .stateIn(scope, SharingStarted.Eagerly, foreground.value && gate.isOpen.value)
        return ReminderSchedulerImpl(book, planner, settings, ready, scope, System::currentTimeMillis)
    }

    @Provides
    fun alerts(impl: ReminderSchedulerImpl): ReminderAlerts = impl

    @Provides @Singleton
    fun timeBaseLookup(playlists: PlaylistRepository): TimeBaseLookup =
        TimeBaseLookup { playlistId -> playlists.get(playlistId)?.let { playlists.timeBase(it) } }

    // (M4 commit 0, docs/design/m4-player-ux.md §1.3) The CatchupLauncher binding moved to app.thebox.playback.ArchiveModule.
}
