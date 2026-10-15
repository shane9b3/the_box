package app.thebox.core.catchup.archive

import app.thebox.core.model.TimeBase

/** WP-B stub (docs/design/m4-player-ux.md §1.3, §3.8): every pure decision of the archive session. */
class ArchivePlannerImpl : ArchivePlanner {
    override fun frames(timeBase: TimeBase, channelCorrectionHours: Double?, epgShiftMin: Int): ArchiveFrames = TODO("WP-B")
    override fun liveWindow(frames: ArchiveFrames, archiveDays: Int, deviceNowMs: Long): LiveWindow = TODO("WP-B")
    override fun alignStart(variant: String, targetMs: Long, frames: ArchiveFrames): Long = TODO("WP-B")
    override fun programmeRequest(startMs: Long, stopMs: Long?, live: LiveWindow): RequestPlan = TODO("WP-B")
    override fun seek(targetMs: Long, wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): SeekPlan = TODO("WP-B")
    override fun onProgress(wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): ContinuePlan = TODO("WP-B")
    override fun resumeFromPause(pausedAtMs: Long, pausedForMs: Long, live: LiveWindow): ContinuePlan = TODO("WP-B")
    override fun onNotInArchive(req: ActiveRequest, live: LiveWindow, firstMediaSeen: Boolean, variantIndex: Int, variantCount: Int, lagRetriesUsed: Int): ProbePlan =
        TODO("WP-B")
    override fun startOffset(targetMs: Long, requestStartArchiveMs: Long, frames: ArchiveFrames): Long = TODO("WP-B")

    companion object {
        /** A seek target within this of the playhead is a no-op (§3.8.4). */
        const val SEEK_NOOP_MS = 1_000L
        const val END_GUARD_MS = 5_000L
        /** User seeks within this of the live edge go live (S1). */
        const val LIVE_SNAP_MS = 30_000L
        /** Continuations within this of the live edge go live (S1). */
        const val CONTINUE_SNAP_MS = 5_000L
        /** Re-request this long before the request's end (§3.8.5). */
        const val CONTINUE_LEAD_MS = 3_000L
        /** Live pauses at least this long resume from the archive (§3.8.7). */
        const val PAUSE_MIN_FOR_ARCHIVE_MS = 3_000L
        const val ARCHIVE_LAG_GUARD_MS = 120_000L
        const val ARCHIVE_LAG_STEP_MS = 60_000L
        /** = the builder's `EDGE_MARGIN_SEC` (§3.8.1). */
        const val EDGE_MARGIN_MS = 60_000L
        /** Not built in M4 (D10 trigger, §7 item 5). */
        const val XC_OPEN_EXTRA_SEC = 0
    }
}
