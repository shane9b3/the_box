package app.thebox.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.thebox.core.common.BoxLog
import app.thebox.data.repo.LibraryMaintenance
import app.thebox.data.repo.PlaylistRefresher
import app.thebox.data.repo.SyncStatusRepository
import app.thebox.data.repo.Trigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Refreshes one playlist (§3.10). Expedited for manual/onboarding refreshes: below API 31 WorkManager runs
 * expedited work as a foreground service and calls [getForegroundInfo] (B4; CoroutineWorker's default throws),
 * so it is overridden with the dataSync notification. Also runs the weekly orphan trim.
 */
@HiltWorker
class PlaylistRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refresher: PlaylistRefresher,
    private val notifications: WorkNotifications,
    private val maintenance: LibraryMaintenance?,
    private val status: SyncStatusRepository?,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(WorkNotifications.ID_PLAYLIST, "Updating playlist")

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_PLAYLIST_ID, -1)
        if (id < 0) return Result.failure()
        val trigger = runCatching { Trigger.valueOf(inputData.getString(KEY_TRIGGER) ?: "") }.getOrDefault(Trigger.PERIODIC)
        val force = inputData.getBoolean(KEY_FORCE_SHRINK, false)
        val result = refresher.refresh(id, trigger, force) { line ->
            status?.reportProgress(line)
            setProgressAsync(workDataOf(KEY_PROGRESS to line))
        }
        status?.reportProgress(null)
        try {
            maintenance?.runIfDue()
        } catch (e: Exception) {
            BoxLog.w(TAG, "maintenance failed", e)
        }
        val error = result.error ?: return Result.success(workDataOf(KEY_OUTCOME to (result.report?.outcome?.name ?: "SUCCESS")))
        return WorkResults.forError(error, runAttemptCount)
    }

    companion object {
        private const val TAG = "PlaylistRefreshWorker"
        const val KEY_PLAYLIST_ID = "playlist_id"
        const val KEY_TRIGGER = "trigger"
        const val KEY_FORCE_SHRINK = "force_shrink"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTCOME = "outcome"

        fun input(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean = false): Data =
            workDataOf(KEY_PLAYLIST_ID to playlistId, KEY_TRIGGER to trigger.name, KEY_FORCE_SHRINK to forceAcceptShrink)
    }
}
