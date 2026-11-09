package app.thebox.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.thebox.core.common.BoxLog
import app.thebox.data.repo.EpgImporter
import app.thebox.data.repo.SyncStatusRepository
import app.thebox.data.repo.Trigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Downloads every active XMLTV source and rebuilds the guide (§3.10). Runs as a dataSync foreground service
 * where allowed: on API 31+ a worker started from the background gets
 * ForegroundServiceStartNotAllowedException (an IllegalStateException), so [setForeground] is wrapped and the
 * import continues inside WorkManager's 10-minute window (B4).
 */
@HiltWorker
class EpgImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: EpgImporter,
    private val notifications: WorkNotifications,
    private val status: SyncStatusRepository?,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(WorkNotifications.ID_EPG_IMPORT, "Updating TV guide")

    override suspend fun doWork(): Result {
        tryForeground()
        val writes = ProgressWrites()
        val trigger = runCatching { Trigger.valueOf(inputData.getString(KEY_TRIGGER) ?: "") }.getOrDefault(Trigger.PERIODIC)
        val r = importer.build(trigger, force = trigger == Trigger.MANUAL) { line ->
            status?.reportProgress(line)
            if (writes.shouldWrite(line)) setProgressAsync(workDataOf(PlaylistRefreshWorker.KEY_PROGRESS to line))
        }
        status?.reportProgress(null)
        val error = r.error ?: return Result.success(workDataOf(KEY_SKIPPED to r.skipped))
        return WorkResults.forError(error, runAttemptCount)
    }

    /** True when the worker runs as a foreground service. */
    internal suspend fun tryForeground(): Boolean = try {
        setForeground(getForegroundInfo())
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        BoxLog.w(TAG, "foreground service not allowed; importing in the background")
        false
    }

    companion object {
        private const val TAG = "EpgImportWorker"
        const val KEY_TRIGGER = "trigger"
        const val KEY_SKIPPED = "skipped"
    }
}
