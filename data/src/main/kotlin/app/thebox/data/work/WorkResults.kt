package app.thebox.data.work

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import app.thebox.core.common.SourceError
import app.thebox.data.net.NetErrors

/**
 * Worker result mapping (§6.6): network, 429, 5xx and a transient Keystore failure retry (exponential from
 * 30 s, at most [MAX_ATTEMPTS] runs); auth, format, shrink, disk and other credential errors fail at once.
 * The repositories have already written the refresh_log row in both cases.
 */
object WorkResults {
    const val MAX_ATTEMPTS = 3
    const val KEY_ERROR = "error"

    fun forError(error: SourceError, runAttemptCount: Int): ListenableWorker.Result =
        if (NetErrors.isTransient(error) && runAttemptCount + 1 < MAX_ATTEMPTS) {
            ListenableWorker.Result.retry()
        } else {
            ListenableWorker.Result.failure(workDataOf(KEY_ERROR to error.code))
        }
}
