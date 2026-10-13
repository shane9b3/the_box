package app.thebox.data.repo

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.di.ApplicationScope
import app.thebox.data.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** The S15 sync chip: what is running, the latest progress line, and failures not yet acknowledged. */
data class SyncStatus(
    val playlistsRefreshing: Set<Long> = emptySet(),
    val epgImporting: Boolean = false,
    val epgPatching: Boolean = false,
    val progress: String? = null,
    val failures: List<RefreshLogEntity> = emptyList(),
) {
    val busy: Boolean get() = playlistsRefreshing.isNotEmpty() || epgImporting || epgPatching
}

/** Combines WorkManager state (tag [RefreshScheduler.TAG_SYNC]) with refresh_log failures and worker progress. */
@Singleton
class SyncStatusRepository @Inject constructor(
    workManager: WorkManager,
    private val logDao: RefreshLogDao,
    @ApplicationScope scope: CoroutineScope,
) {
    private val progress = MutableStateFlow<String?>(null)

    val status: StateFlow<SyncStatus> = combine(
        workManager.getWorkInfosFlow(WorkQuery.fromTags(RefreshScheduler.TAG_SYNC)),
        logDao.observeUnacknowledgedFailures(),
        progress,
    ) { infos, failures, line -> fold(infos, failures, line) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    /** Workers forward throttled progress lines ("Downloading 120 MB", "Matching 1,912 of 2,140"). */
    fun reportProgress(line: String?) {
        progress.value = line
    }

    suspend fun acknowledge(logId: Long) = logDao.acknowledge(logId)
    suspend fun acknowledgeAll() = logDao.acknowledgeAll()

    companion object {
        fun fold(infos: List<WorkInfo>, failures: List<RefreshLogEntity>, line: String?): SyncStatus {
            val running = infos.filter { it.state == WorkInfo.State.RUNNING }
            val playlists = running.flatMap { it.tags }.mapNotNull { it.removePrefix(RefreshScheduler.TAG_PLAYLIST_PREFIX).takeIf { t -> t != it }?.toLongOrNull() }.toSet()
            return SyncStatus(
                playlistsRefreshing = playlists,
                epgImporting = running.any { RefreshScheduler.TAG_EPG_IMPORT in it.tags },
                epgPatching = running.any { RefreshScheduler.TAG_EPG_PATCH in it.tags },
                progress = if (running.isEmpty()) null else line,
                failures = failures,
            )
        }
    }
}
