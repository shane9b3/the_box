package app.thebox.data.repo

import app.thebox.core.common.SourceError
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.refresh.RefreshReport

/** Why a refresh or import ran (refresh_log.trigger). */
enum class Trigger { PERIODIC, APP_START, MANUAL, ONBOARDING, MAPPING, PLAYLIST_CHANGED }

/** Result of one playlist refresh: [report] when the run got that far, [error] when it failed. */
data class RefreshResult(val report: RefreshReport?, val error: SourceError?) {
    val ok: Boolean get() = error == null
}

/** Result of one EPG build or patch; [report] is null when there was nothing to do. */
data class EpgRunResult(val report: EpgImportReport?, val error: SourceError?, val skipped: Boolean = false)

/** The worker-facing side of RefreshRepository (a seam for work-testing with fakes). */
interface PlaylistRefresher {
    suspend fun refresh(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean = false, progress: (String) -> Unit = {}): RefreshResult
}

/** The worker-facing side of EpgRepository. */
interface EpgImporter {
    suspend fun build(trigger: Trigger, force: Boolean = false, progress: (String) -> Unit = {}): EpgRunResult
    suspend fun patch(progress: (String) -> Unit = {}): EpgRunResult
}

/** Enqueues EPG work; implemented by RefreshScheduler (breaks the repository → WorkManager dependency for tests). */
interface EpgWorkRequester {
    fun importEpgNow(trigger: Trigger)
    fun patchEpg()
}
