// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.3.
package app.thebox.core.catchup.archive

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.model.TimeBase
import app.thebox.core.playback.PlaybackError
import kotlinx.coroutines.flow.StateFlow

enum class ArchivePhase {
    /** No archive session (live playback or nothing). */
    IDLE,
    /** First request built and tuned; no first media yet. */
    STARTING,
    /** A variant failed with CATCHUP_NOT_IN_ARCHIVE before first media; the next one is being tried. */
    PROBING,
    PLAYING,
    PAUSED,
    /** A native seek or a re-request for a seek is in flight. */
    SEEKING,
    /** A continuation re-request (end of request, resume after pause, mid-play failure) is in flight. */
    CONTINUING,
    /** Live paused on an archive-capable channel; the controller froze (and later released) the live session. */
    PAUSED_LIVE,
    /** Terminal for this session: [ArchiveStatus.failure] says why. */
    FAILED,
}

enum class ArchiveFailure { CHANNEL_GONE, NO_ARCHIVE, OUTSIDE_ARCHIVE, UNSUPPORTED_MODE, CREDENTIALS, NOT_IN_ARCHIVE, NO_PROGRAMME, PLAYBACK }

/**
 * One snapshot of the archive session. Times are **programme-frame** epoch ms (raw EPG UTC; §3.8.1). [wallMs] and
 * [liveEdgeMs] are valid at [atMs] (device wall clock); [wallMs] advances 1:1 while [advancing], [liveEdgeMs] always.
 */
data class ArchiveStatus(
    val phase: ArchivePhase = ArchivePhase.IDLE,
    val channel: GuideChannel? = null,
    val identityHash: Long = 0,
    /** The programme containing [wallMs] (re-scoped at boundaries without a new request). */
    val programme: ProgrammeInfo? = null,
    val wallMs: Long = 0,
    val atMs: Long = 0,
    val advancing: Boolean = false,
    val liveEdgeMs: Long = 0,
    /** Oldest playable instant: liveEdge − days, plus the builder's edge margin. */
    val archiveFromMs: Long = 0,
    /** Display = programme frame + this (channel EPG shift); for labels only. */
    val displayShiftMs: Long = 0,
    /** The current request can seek natively (§3.8.4). */
    val seekable: Boolean = false,
    /** Started from live (pause, rewind, start over): the pill shows −MM:SS, not CATCH-UP. */
    val timeShift: Boolean = false,
    val variant: String? = null,
    /** 1-based variant attempt and the number of variants for this target. */
    val attempt: Int = 0,
    val attempts: Int = 0,
    val failure: ArchiveFailure? = null,
    /** The controller's error when [failure] == PLAYBACK or NOT_IN_ARCHIVE. */
    val error: PlaybackError? = null,
    // (reviewed 2026-10-15, F2)
    /** tuneId of the newest controller cycle this session issued (set before tuneArchive); later tuneIds are ours too. */
    val ownedTuneId: Long = 0,
    /** The last seek sequence number whose seek has settled (native: playing again; re-request: first media). */
    val settledSeq: Long = 0,
)

sealed interface ArchiveStart {
    data object Started : ArchiveStart
    data class Failed(val reason: ArchiveFailure) : ArchiveStart
}

/** The catch-up engine (§3.8). Main-safe; suspend functions switch to IO internally. One session at a time. */
interface ArchivePlayback {
    val status: StateFlow<ArchiveStatus>
    /** Catch-up from the programme's start (guide Watch / From start, Search, info bar OK on a past programme). */
    suspend fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int, timeShift: Boolean = false): ArchiveStart
    /** Start over the programme airing now on [channel] (Red, quick bar). */
    suspend fun startOver(channel: GuideChannel): ArchiveStart
    /** Live → time-shift [backMs] behind the live edge (RW on live). */
    suspend fun rewindLive(channel: GuideChannel, backMs: Long): ArchiveStart
    /** Live pause on an archive-capable channel: freezes through the controller and records the pause point. */
    fun pauseLive(channel: GuideChannel)
    fun pause()
    /** Resumes an archive pause, or plays from the live-pause point (§3.8.7). */
    fun resume()
    /** [programmeMs] is clamped to [archiveFromMs, liveEdge]; within LIVE_SNAP_MS of the edge it goes live. [status].settledSeq reaches [seq] when the seek has settled, wherever the playhead landed. */
    fun seekTo(programmeMs: Long, seq: Long)
    fun jumpToLive()
    /** Error card "Try another catch-up format": next variant from the current playhead; false when none is left. */
    fun tryNextVariant(): Boolean
}

/** playlist.catchup_probe_winner (M1 §2.1.2). Main-safe. */
interface CatchupProbeStore {
    suspend fun winner(playlistId: Long): String?
    suspend fun setWinner(playlistId: Long, variant: String)
}

/** Converts between the three time frames of one channel (§3.8.1). */
data class ArchiveFrames(
    /** Duration.ofSeconds((correctionHours × 3600).roundToLong()).toMillis(), TimeBase's rounding (N2); programme = archive + correction. */
    val correctionMs: Long,
    /** TimeBase.clockSkew in ms (panel − device). */
    val skewMs: Long,
    /** GuideChannel.epgShiftMin × 60,000; display = programme + shift. */
    val displayShiftMs: Long,
) {
    fun programmeOf(archiveMs: Long): Long = archiveMs + correctionMs
    fun archiveOf(programmeMs: Long): Long = programmeMs - correctionMs
    /**
     * The panel's now in the programme frame (archive-frame now + correction). Live-anchored instants (pause point,
     * RW and time-shift targets) are taken from it; the builder subtracts the correction again, so their requests start
     * at the panel's own clock. The builder's "not started" check compares the corrected start with the panel's now.
     */
    fun liveEdge(deviceNowMs: Long): Long = deviceNowMs + skewMs + correctionMs
}

/** The request being played, in the programme frame. */
data class ActiveRequest(
    val variant: String,
    /** requestStart (archive frame) + correction. */
    val originMs: Long,
    /** requestEnd + correction; null = open-ended. */
    val endMs: Long?,
    /** nativeSeekRange converted to programme-frame ms; null when the builder gave none. */
    val nativeFromMs: Long?,
    val nativeToMs: Long?,
)

/** PositionSnapshot reduced to what the planner needs. */
data class PositionView(val positionMs: Long, val durationMs: Long?, val isSeekable: Boolean, val ended: Boolean)

data class LiveWindow(val liveEdgeMs: Long, val archiveFromMs: Long)

/** How to build the next request. [startMs]/[stopMs] are programme frame; the builder applies correction itself. */
data class RequestPlan(
    val startMs: Long,
    /** null = open-ended. */
    val stopMs: Long?,
    /** false for every request except the first of a programme (no paddingBefore, §3.8.3). */
    val padBefore: Boolean,
    /** The playhead the user expects; the controller seeks there when the request is seekable (startOffsetMs). */
    val targetMs: Long,
)

sealed interface SeekPlan {
    data class Native(val positionMs: Long) : SeekPlan
    data class Rerequest(val plan: RequestPlan) : SeekPlan
    data object Live : SeekPlan
    /** The target equals the playhead within SEEK_NOOP_MS. */
    data object None : SeekPlan
}

sealed interface ContinuePlan {
    data object Keep : ContinuePlan
    data class Rerequest(val plan: RequestPlan) : ContinuePlan
    data object Live : ContinuePlan
}

sealed interface ProbePlan {
    /** Try variant [index] of the target's list with the same window. */
    data class NextVariant(val index: Int) : ProbePlan
    /** Archive lag near the live edge (§3.8.7): retry once [stepBackMs] earlier with the same variant. */
    data class StepBack(val stepBackMs: Long) : ProbePlan
    /** Lag retry used up near the edge: go live with ARCHIVE_NOT_READY. */
    data object LiveNotReady : ProbePlan
    data object Fail : ProbePlan
}

/** Every pure decision of the archive session (§3.8). Implemented by ArchivePlannerImpl (WP-B). */
interface ArchivePlanner {
    fun frames(timeBase: TimeBase, channelCorrectionHours: Double?, epgShiftMin: Int): ArchiveFrames
    fun liveWindow(frames: ArchiveFrames, archiveDays: Int, deviceNowMs: Long): LiveWindow
    /**
     * Start instant to request for [targetMs] with [variant]: for xc-path/xc-php `programmeOf(floorToMinute(archiveOf(t)))`
     * (aligned in the archive frame, where the token is formatted; reviewed 2026-10-15, N2), else unchanged.
     */
    fun alignStart(variant: String, targetMs: Long, frames: ArchiveFrames): Long
    /** First request of a programme ([stopMs] null while it is still airing). */
    fun programmeRequest(startMs: Long, stopMs: Long?, live: LiveWindow): RequestPlan
    fun seek(targetMs: Long, wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): SeekPlan
    /** Called on every poll and on `ended`. */
    fun onProgress(wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): ContinuePlan
    /** Resume after a live pause at [pausedAtMs] (§3.8.7). */
    fun resumeFromPause(pausedAtMs: Long, pausedForMs: Long, live: LiveWindow): ContinuePlan
    fun onNotInArchive(req: ActiveRequest, live: LiveWindow, firstMediaSeen: Boolean, variantIndex: Int, variantCount: Int, lagRetriesUsed: Int): ProbePlan
    /** startOffsetMs for tuneArchive given the built request (archive-frame requestStart). */
    fun startOffset(targetMs: Long, requestStartArchiveMs: Long, frames: ArchiveFrames): Long
}
