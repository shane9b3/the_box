package app.thebox.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.thebox.data.repo.EpgImporter
import app.thebox.data.repo.SyncStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Imports programmes for new mappings into the current generation from the cached XMLTV files (§2.5.6).
 * No input: the refs come from EpgMatchRepository (no ids travel in WorkManager Data, §2.6).
 */
@HiltWorker
class EpgPatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: EpgImporter,
    private val notifications: WorkNotifications,
    private val status: SyncStatusRepository?,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(WorkNotifications.ID_EPG_PATCH, "Adding guide data")

    override suspend fun doWork(): Result {
        val r = importer.patch { line -> status?.reportProgress(line) }
        status?.reportProgress(null)
        val error = r.error ?: return Result.success(workDataOf(EpgImportWorker.KEY_SKIPPED to r.skipped))
        return WorkResults.forError(error, runAttemptCount)
    }
}
