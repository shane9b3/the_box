package app.thebox.core.catchup.archive

import app.thebox.core.catchup.CatchupVariants
import app.thebox.core.model.TimeBase
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Every pure decision of the archive session (docs/design/m4-player-ux.md §3.8). No clock, no I/O: the orchestrator
 * (`ArchivePlaybackImpl`) passes every instant in. All times are programme-frame epoch ms unless a name says otherwise
 * (§3.8.1).
 *
 * Alignment: [seek], [onProgress] and [resumeFromPause] return the **unaligned** start (they do not know the frames or,
 * while probing, the variant); the orchestrator applies [alignStart] with the variant it builds for, so a probe of
 * `xc-path-sec` after `xc-path` is not floored needlessly. [startOffset] then restores the exact target when the
 * request is seekable.
 */
class ArchivePlannerImpl : ArchivePlanner {

    override fun frames(timeBase: TimeBase, channelCorrectionHours: Double?, epgShiftMin: Int): ArchiveFrames =
        ArchiveFrames(
            // TimeBase's own rounding (TimeBase.correction): whole seconds, so the frames and the builder never disagree (N2).
            correctionMs = Duration.ofSeconds(((channelCorrectionHours ?: timeBase.correctionHours) * 3600).roundToLong()).toMillis(),
            skewMs = timeBase.clockSkew.toMillis(),
            displayShiftMs = epgShiftMin * MINUTE_MS,
        )

    override fun liveWindow(frames: ArchiveFrames, archiveDays: Int, deviceNowMs: Long): LiveWindow {
        val edge = frames.liveEdge(deviceNowMs)
        // The builder's window: corrected start ≥ panel now − days (days ≥ 1), plus its edge margin (§3.8.1).
        return LiveWindow(liveEdgeMs = edge, archiveFromMs = edge - archiveDays.coerceAtLeast(1) * DAY_MS + EDGE_MARGIN_MS)
    }

    override fun alignStart(variant: String, targetMs: Long, frames: ArchiveFrames): Long {
        if (!isMinuteVariant(variant)) return targetMs
        // Floor in the archive frame, where the token is formatted (N2): a fractional-minute correction or the builder's
        // edge-margin start would unalign a programme-frame floor.
        return frames.programmeOf(Math.floorDiv(frames.archiveOf(targetMs), MINUTE_MS) * MINUTE_MS)
    }

    override fun programmeRequest(startMs: Long, stopMs: Long?, live: LiveWindow): RequestPlan =
        // A programme that has not ended on the panel's clock is still airing: open-ended (§3.8.9). The target is the
        // start; the orchestrator moves it to the padded media origin once the builder has applied paddingBefore.
        RequestPlan(startMs = startMs, stopMs = stopMs?.takeIf { it <= live.liveEdgeMs }, padBefore = true, targetMs = startMs)

    override fun seek(targetMs: Long, wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): SeekPlan {
        val t = targetMs.coerceIn(live.archiveFromMs, maxOf(live.archiveFromMs, live.liveEdgeMs))
        if (abs(t - wallMs) < SEEK_NOOP_MS) return SeekPlan.None
        // P-S1 = U-L3 (L-6): the snap is direction-aware. Only a target later than the playhead snaps to live; a backward
        // step inside the 30 s zone requests the archive (it used to jump forward to live).
        if (t > wallMs && t >= live.liveEdgeMs - LIVE_SNAP_MS) return SeekPlan.Live
        val window = nativeWindow(req, pos)
        if (window != null && t >= window.first && t <= window.last) return SeekPlan.Native(t - req.originMs)
        return SeekPlan.Rerequest(RequestPlan(startMs = t, stopMs = null, padBefore = false, targetMs = t))
    }

    override fun onProgress(wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): ContinuePlan {
        val finite = req.endMs != null || pos.durationMs != null
        val atEnd = pos.ended || (finite && remaining(wallMs, req, pos) <= CONTINUE_LEAD_MS)
        if (!atEnd) return ContinuePlan.Keep
        // S1: a continuation snaps to live only within CONTINUE_SNAP_MS; it never drops a shallow time-shift.
        if (live.liveEdgeMs - wallMs <= CONTINUE_SNAP_MS) return ContinuePlan.Live
        return ContinuePlan.Rerequest(RequestPlan(startMs = wallMs, stopMs = null, padBefore = false, targetMs = wallMs))
    }

    override fun resumeFromPause(pausedAtMs: Long, pausedForMs: Long, live: LiveWindow): ContinuePlan {
        if (pausedForMs < PAUSE_MIN_FOR_ARCHIVE_MS) return ContinuePlan.Live
        val at = pausedAtMs.coerceAtLeast(live.archiveFromMs)
        return ContinuePlan.Rerequest(RequestPlan(startMs = at, stopMs = null, padBefore = false, targetMs = at))
    }

    override fun onNotInArchive(
        req: ActiveRequest,
        live: LiveWindow,
        firstMediaSeen: Boolean,
        variantIndex: Int,
        variantCount: Int,
        lagRetriesUsed: Int,
    ): ProbePlan {
        // 1. Archive lag near the edge first (§3.8.7): never a variant failure (S3). A stepped-back request is still part
        // of the lag sequence, so the guard widens by the steps already taken.
        val guard = ARCHIVE_LAG_GUARD_MS + lagRetriesUsed.toLong() * ARCHIVE_LAG_STEP_MS
        if (live.liveEdgeMs - req.originMs <= guard) {
            return if (lagRetriesUsed < 1) ProbePlan.StepBack(ARCHIVE_LAG_STEP_MS) else ProbePlan.LiveNotReady
        }
        // 2. The variant worked earlier in this session (a hole, an expired token): the orchestrator's mid-play retry
        // rule applies, never another variant.
        if (firstMediaSeen) return ProbePlan.Fail
        // 3. Next variant with the same window, 4. exhausted.
        if (variantIndex + 1 < variantCount) return ProbePlan.NextVariant(variantIndex + 1)
        return ProbePlan.Fail
    }

    override fun startOffset(targetMs: Long, requestStartArchiveMs: Long, frames: ArchiveFrames): Long =
        (targetMs - frames.programmeOf(requestStartArchiveMs)).coerceAtLeast(0)

    /** The native-seek window in the programme frame (§3.8.4 rule 3), or null when the request can only be re-requested. */
    private fun nativeWindow(req: ActiveRequest, pos: PositionView): LongRange? {
        val from = req.nativeFromMs
        val to = req.nativeToMs
        if (from != null && to != null) return from..to
        val duration = pos.durationMs ?: return null
        if (!pos.isSeekable) return null
        val end = req.originMs + duration - END_GUARD_MS
        return if (end < req.originMs) null else req.originMs..end
    }

    private fun remaining(wallMs: Long, req: ActiveRequest, pos: PositionView): Long {
        val duration = pos.durationMs
        if (duration != null) return duration - pos.positionMs
        return (req.endMs ?: Long.MAX_VALUE) - wallMs
    }

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

        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 86_400_000L

        /** Variants whose start token has minute resolution (`TokenFormatter.XC_MINUTES`, §3.8.3). */
        fun isMinuteVariant(variant: String): Boolean = variant == CatchupVariants.XC_PATH || variant == CatchupVariants.XC_PHP
    }
}
