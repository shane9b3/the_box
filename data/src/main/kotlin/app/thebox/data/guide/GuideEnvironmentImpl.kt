package app.thebox.data.guide

import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.GuideDataWindowSource
import app.thebox.core.guide.SyncFeed
import app.thebox.core.guide.SyncFeedState
import app.thebox.data.di.ApplicationScope
import app.thebox.data.repo.DataSettings
import app.thebox.data.repo.SyncStatus
import app.thebox.data.repo.SyncStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** [SyncFeed] over `SyncStatusRepository` (docs/design/m3-guide.md §2.4a, review B7): the guide's sync chip input. */
@Singleton
class SyncFeedImpl @Inject constructor(
    repo: SyncStatusRepository,
    @ApplicationScope scope: CoroutineScope,
) : SyncFeed {
    override val state: StateFlow<SyncFeedState> = repo.status
        .map(::toFeed)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), toFeed(repo.status.value))

    companion object {
        fun toFeed(s: SyncStatus) = SyncFeedState(busy = s.busy, progress = s.progress, unacknowledgedFailures = s.failures.size)
    }
}

/** [GuideDataWindowSource] over `DataSettings` (epgPastDays / epgFutureDays; §2.4a, B7). */
@Singleton
class GuideDataWindowSourceImpl @Inject constructor(
    settings: DataSettings,
    @ApplicationScope scope: CoroutineScope,
) : GuideDataWindowSource {
    override val window: StateFlow<GuideDataWindow> = settings.settings
        .map { GuideDataWindow(it.epgPastDays, it.epgFutureDays) }
        .stateIn(scope, SharingStarted.Eagerly, settings.current.let { GuideDataWindow(it.epgPastDays, it.epgFutureDays) })
}
