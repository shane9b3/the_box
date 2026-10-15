package app.thebox.core.full.fakes

import app.thebox.core.catchup.archive.ActiveRequest
import app.thebox.core.catchup.archive.ArchiveFrames
import app.thebox.core.catchup.archive.ArchivePlanner
import app.thebox.core.catchup.archive.ArchiveNotice
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStart
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.CatchupProbeStore
import app.thebox.core.catchup.archive.ContinuePlan
import app.thebox.core.catchup.archive.LiveWindow
import app.thebox.core.catchup.archive.PositionView
import app.thebox.core.catchup.archive.ProbePlan
import app.thebox.core.catchup.archive.RequestPlan
import app.thebox.core.catchup.archive.SeekPlan
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.full.ProgrammeSlice
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.model.TimeBase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ProgrammeFeed] over in-memory schedules keyed by `GuideChannel.identityHash` (display minutes, start order). Records
 * every call in [calls] (docs/design/m4-player-ux.md §1.8).
 */
class FakeProgrammeFeed : ProgrammeFeed {
    val schedules = HashMap<Long, List<ProgrammeInfo>>()
    val calls = ArrayList<String>()
    var closed = false

    fun put(channel: GuideChannel, programmes: List<ProgrammeInfo>) { schedules[channel.identityHash] = programmes.sortedBy { it.startMin } }
    private fun of(channel: GuideChannel): List<ProgrammeInfo> = schedules[channel.identityHash].orEmpty()

    override suspend fun range(channel: GuideChannel, fromMin: Int, toMin: Int, limit: Int): List<ProgrammeInfo> {
        calls += "range"
        return of(channel).filter { it.endMin > fromMin && it.startMin < toMin }.take(limit)
    }

    override suspend fun nowFor(channels: List<GuideChannel>, atMin: Int): List<ProgrammeInfo?> {
        calls += "nowFor"
        return channels.map { ch -> of(ch).firstOrNull { atMin >= it.startMin && atMin < it.endMin } }
    }

    override suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo? {
        calls += "at"
        return of(channel).firstOrNull { atMin >= it.startMin && atMin < it.endMin }
    }

    override suspend fun around(channel: GuideChannel, atMin: Int, fromMin: Int, before: Int, after: Int): ProgrammeSlice {
        calls += "around"
        val all = of(channel)
        val now = all.firstOrNull { atMin >= it.startMin && atMin < it.endMin }
        val earlier = all.filter { it.endMin > fromMin && it.startMin < atMin && it !== now }.takeLast(before)
        val later = all.filter { it.startMin >= atMin && it !== now }.take(after)
        val list = earlier + listOfNotNull(now) + later
        return ProgrammeSlice(list, if (now == null) -1 else earlier.size)
    }

    override fun close() { closed = true }
}

/** Scriptable [ArchivePlayback]: tests set [mutableStatus] and the results; every call is recorded in [calls]. */
class FakeArchivePlayback(initial: ArchiveStatus = ArchiveStatus()) : ArchivePlayback {
    data class Call(val name: String, val args: List<Any?>)
    val calls = ArrayList<Call>()
    private fun rec(name: String, vararg args: Any?) { calls += Call(name, args.toList()) }

    val mutableStatus = MutableStateFlow(initial)
    override val status: StateFlow<ArchiveStatus> = mutableStatus.asStateFlow()
    /** Push an [ArchiveNotice] to test the toast path. */
    val mutableNotices = kotlinx.coroutines.flow.MutableSharedFlow<ArchiveNotice>(extraBufferCapacity = 4)
    override val notices: kotlinx.coroutines.flow.Flow<ArchiveNotice> = mutableNotices
    var startResult: ArchiveStart = ArchiveStart.Started
    var variantLeft = true
    /** Every `seekTo(programmeMs, seq)` in order (B3). */
    val seeks = ArrayList<Pair<Long, Long>>()

    fun names(): List<String> = calls.map { it.name }

    override suspend fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int, timeShift: Boolean): ArchiveStart {
        rec("playProgramme", channel, info, rawEndMin, timeShift)
        return startResult
    }
    override suspend fun startOver(channel: GuideChannel): ArchiveStart { rec("startOver", channel); return startResult }
    override suspend fun rewindLive(channel: GuideChannel, backMs: Long): ArchiveStart { rec("rewindLive", channel, backMs); return startResult }
    override fun pauseLive(channel: GuideChannel) = rec("pauseLive", channel)
    override fun pause() = rec("pause")
    override fun resume() = rec("resume")
    override fun seekTo(programmeMs: Long, seq: Long) { seeks += programmeMs to seq; rec("seekTo", programmeMs, seq) }
    override fun jumpToLive() = rec("jumpToLive")
    override fun tryNextVariant(): Boolean { rec("tryNextVariant"); return variantLeft }
}

/**
 * Records every [ArchivePlanner] call and returns scripted plans (WP-B's `ArchivePlaybackImplTest`). [frames] defaults
 * to the real frame arithmetic of the contract (`ArchiveFrames`), the rest to the plain answers below.
 */
class FakeArchivePlanner : ArchivePlanner {
    data class Call(val name: String, val args: List<Any?>)
    val calls = ArrayList<Call>()
    private fun rec(name: String, vararg args: Any?) { calls += Call(name, args.toList()) }
    fun names(): List<String> = calls.map { it.name }

    var framesFn: (TimeBase, Double?, Int) -> ArchiveFrames = { tb, corrH, shift ->
        ArchiveFrames(
            correctionMs = java.time.Duration.ofSeconds(Math.round((corrH ?: tb.correctionHours) * 3600)).toMillis(),
            skewMs = tb.clockSkew.toMillis(),
            displayShiftMs = shift * 60_000L,
        )
    }
    var liveWindowFn: (ArchiveFrames, Int, Long) -> LiveWindow = { f, days, now ->
        val edge = f.liveEdge(now)
        LiveWindow(edge, edge - days * 86_400_000L)
    }
    var alignFn: (String, Long, ArchiveFrames) -> Long = { _, t, _ -> t }
    var programmeRequestFn: (Long, Long?, LiveWindow) -> RequestPlan = { s, e, _ -> RequestPlan(s, e, padBefore = true, targetMs = s) }
    var seekPlans = ArrayDeque<SeekPlan>()
    var continuePlans = ArrayDeque<ContinuePlan>()
    var resumePlans = ArrayDeque<ContinuePlan>()
    var probePlans = ArrayDeque<ProbePlan>()
    var startOffsetFn: (Long, Long, ArchiveFrames) -> Long = { t, reqStartA, f -> (f.archiveOf(t) - reqStartA).coerceAtLeast(0) }

    override fun frames(timeBase: TimeBase, channelCorrectionHours: Double?, epgShiftMin: Int): ArchiveFrames {
        rec("frames", timeBase, channelCorrectionHours, epgShiftMin)
        return framesFn(timeBase, channelCorrectionHours, epgShiftMin)
    }
    override fun liveWindow(frames: ArchiveFrames, archiveDays: Int, deviceNowMs: Long): LiveWindow {
        rec("liveWindow", frames, archiveDays, deviceNowMs)
        return liveWindowFn(frames, archiveDays, deviceNowMs)
    }
    override fun alignStart(variant: String, targetMs: Long, frames: ArchiveFrames): Long {
        rec("alignStart", variant, targetMs, frames)
        return alignFn(variant, targetMs, frames)
    }
    override fun programmeRequest(startMs: Long, stopMs: Long?, live: LiveWindow): RequestPlan {
        rec("programmeRequest", startMs, stopMs, live)
        return programmeRequestFn(startMs, stopMs, live)
    }
    override fun seek(targetMs: Long, wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): SeekPlan {
        rec("seek", targetMs, wallMs, req, pos, live)
        return seekPlans.removeFirstOrNull() ?: SeekPlan.None
    }
    override fun onProgress(wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): ContinuePlan {
        rec("onProgress", wallMs, req, pos, live)
        return continuePlans.removeFirstOrNull() ?: ContinuePlan.Keep
    }
    override fun resumeFromPause(pausedAtMs: Long, pausedForMs: Long, live: LiveWindow): ContinuePlan {
        rec("resumeFromPause", pausedAtMs, pausedForMs, live)
        return resumePlans.removeFirstOrNull() ?: ContinuePlan.Live
    }
    override fun onNotInArchive(req: ActiveRequest, live: LiveWindow, firstMediaSeen: Boolean, variantIndex: Int, variantCount: Int, lagRetriesUsed: Int): ProbePlan {
        rec("onNotInArchive", req, live, firstMediaSeen, variantIndex, variantCount, lagRetriesUsed)
        return probePlans.removeFirstOrNull() ?: ProbePlan.Fail
    }
    override fun startOffset(targetMs: Long, requestStartArchiveMs: Long, frames: ArchiveFrames): Long {
        rec("startOffset", targetMs, requestStartArchiveMs, frames)
        return startOffsetFn(targetMs, requestStartArchiveMs, frames)
    }
}

/** In-memory [CatchupProbeStore]; [writes] records every `setWinner`. */
class FakeCatchupProbeStore : CatchupProbeStore {
    val winners = HashMap<Long, String>()
    val writes = ArrayList<Pair<Long, String>>()
    override suspend fun winner(playlistId: Long): String? = winners[playlistId]
    override suspend fun setWinner(playlistId: Long, variant: String) { winners[playlistId] = variant; writes += playlistId to variant }
}
