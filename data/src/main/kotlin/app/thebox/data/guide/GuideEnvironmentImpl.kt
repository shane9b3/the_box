package app.thebox.data.guide

import app.thebox.core.epg.EpgStore
import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.GuideDataWindowSource
import app.thebox.core.guide.SyncFeed
import app.thebox.core.guide.SyncFeedState
import app.thebox.data.di.ApplicationScope
import app.thebox.data.repo.DataSettings
import app.thebox.data.repo.SyncStatus
import app.thebox.data.repo.SyncStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** [SyncFeed] over `SyncStatusRepository` (docs/design/m3-guide.md §2.4a, review B7): the guide's sync chip input. */
@Singleton
class SyncFeedImpl @Inject constructor(
    repo: SyncStatusRepository,
    store: EpgStore,
    @ApplicationScope scope: CoroutineScope,
) : SyncFeed {
    /** (owner finding 2026-11-01) The generation's built-at, so the chip can say which guide is on screen. */
    private val builtAt: Flow<Long?> = store.info.map { it?.builtAtMs }.distinctUntilChanged()

    override val state: StateFlow<SyncFeedState> = combine(repo.status, builtAt, ::toFeed)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), toFeed(repo.status.value, store.info.value?.builtAtMs))

    companion object {
        /**
         * [SyncStatus.failures] is newest first (`RefreshLogDao.observeUnacknowledgedFailures`, started_at DESC):
         * the first row's code, target and start time feed the chip's reason (`syncChipReason` in :core).
         */
        fun toFeed(s: SyncStatus, guideBuiltAtMs: Long? = null): SyncFeedState {
            val newest = s.failures.firstOrNull()
            return SyncFeedState(
                busy = s.busy,
                progress = s.progress,
                unacknowledgedFailures = s.failures.size,
                lastFailureCode = newest?.errorCode,
                lastFailureTarget = newest?.targetType,
                lastFailureAtMs = newest?.startedAt,
                guideBuiltAtMs = guideBuiltAtMs,
            )
        }
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
