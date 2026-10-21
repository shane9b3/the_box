package app.thebox.playback

import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.catchup.CatchupUrlBuilderImpl
import app.thebox.core.catchup.archive.ArchiveFrames
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlannerImpl
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.full.LocalBufferInfo
import app.thebox.core.full.fakes.FakeCatchupProbeStore
import app.thebox.core.full.fakes.FakeProgrammeFeed
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TimeRanges
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.xtream.XtreamStreamRef
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.CatchupTargetResult
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.LocalSeekResult
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.VideoInfo
import app.thebox.player.api.ZapList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.ZoneId

private fun unusedLocalController(): PlaybackController = Proxy.newProxyInstance(
    PlaybackController::class.java.classLoader, arrayOf(PlaybackController::class.java),
) { _, m, _ -> throw AssertionError("unexpected PlaybackController.${m.name}") } as PlaybackController

/**
 * A buffered live cycle as WP-B's controller reports it (docs/design/m6-timeshift.md §7.2, §16.2): `localBuffer` in
 * mono times, `seekLocal` / `jumpToLiveLocal` start a reader session under a new tuneId (Connecting at once), `pause`
 * and `resume` stay in the same session. Tests drive first media with [firstMedia].
 */
private class BufferedController : PlaybackController by unusedLocalController() {
    val flow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> get() = flow
    val videoFlow = MutableStateFlow(VideoInfo())
    override val video: StateFlow<VideoInfo> get() = videoFlow
    val local = MutableStateFlow<LocalBufferState?>(null)
    override val localBuffer: StateFlow<LocalBufferState?> get() = local
    val noticeFlow = MutableSharedFlow<PlaybackNotice>(extraBufferCapacity = 8)
    override val notices: SharedFlow<PlaybackNotice> get() = noticeFlow

    var nextTuneId = 100L
    val calls = ArrayList<String>()
    val localSeeks = ArrayList<Pair<Long, Int>>()
    val archiveTunes = ArrayList<ArchiveTune>()
    var localMono: Long? = null
    var seekResult: ((Long) -> LocalSeekResult)? = null

    fun np(identity: ChannelIdentity, archive: Boolean) = NowPlaying(++nextTuneId, identity, null, archive, StreamFormat.TS)
    val target: NowPlaying get() = flow.value.target!!

    fun liveOn(identity: ChannelIdentity) { flow.value = PlaybackState.Playing(np(identity, archive = false)) }
    fun firstMedia(paused: Boolean = false) { flow.value = PlaybackState.Playing(target, paused = paused) }

    override fun position(): PositionSnapshot = PositionSnapshot(0, 0, null, false, null, localMonoMs = localMono)

    override fun pause() {
        calls += "pause"
        val st = flow.value
        if (st is PlaybackState.Playing) flow.value = st.copy(paused = true)
    }

    override fun resume() {
        calls += "resume"
        val st = flow.value
        if (st is PlaybackState.Playing) flow.value = st.copy(paused = false)
    }

    override fun seekLocal(targetMonoMs: Long, direction: Int): LocalSeekResult {
        calls += "seekLocal"
        localSeeks += targetMonoMs to direction
        flow.value = PlaybackState.Connecting(np(target.identity!!, archive = false), ConnectPhase.CONNECTING, 1, 0)
        return seekResult?.invoke(targetMonoMs) ?: LocalSeekResult.Started(targetMonoMs)
    }

    override fun jumpToLiveLocal(): Boolean {
        calls += "jumpToLiveLocal"
        flow.value = PlaybackState.Connecting(np(target.identity!!, archive = false), ConnectPhase.CONNECTING, 1, 0)
        return true
    }

    override fun tuneArchive(tune: ArchiveTune) {
        calls += "tuneArchive"
        archiveTunes += tune
        flow.value = PlaybackState.Connecting(np(tune.identity, archive = true), ConnectPhase.RESOLVING, 1, 0)
    }

    /** (Fix pass B2) The controller's local fallback for a gap hand-off that cannot happen. */
    override fun skipGapLocal(): Boolean { calls += "skipGapLocal"; return true }

    override fun tune(target: TuneTarget, zapList: ZapList?) {
        calls += "tune"
        flow.value = PlaybackState.Connecting(np((target as TuneTarget.Channel).identity, archive = false), ConnectPhase.RESOLVING, 1, 0)
    }
}

/**
 * `LocalTimelineBackend` and the LOCAL source of `ArchivePlaybackImpl` (docs/design/m6-timeshift.md §9.2, §11.2,
 * §17.3; A-B4, A-S11): the P-frame mapping and its inverse with a correction and a skew, the held-back buffer until the
 * frames are known, the status fields, local pause/resume/seek/live, the router's provider hand-off and the gap hand-off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalTimelineBackendTest {
    private val base = 1_760_000_000_000L
    /** mono = wall − this (the controller's snapshot conversion, A-S9). */
    private val wallMinusMono = base - 5_000_000L
    private val noArchive: GuideChannel = GuideFixtures.channel(3, archiveDays = 0)
    private val withArchive: GuideChannel = GuideFixtures.channel(4, archiveDays = 3)
    private val correction = -3_600_000L
    private val skew = 90_000L

    private inner class Rig(
        val ts: TestScope, archive: Boolean, framesGate: CompletableDeferred<Unit>? = null,
        /** (Fix pass B2) The channel vanished: the catch-up context load fails. */
        val targetGone: Boolean = false,
        /** (Fix pass B2) The builder refuses every variant: no request can be built. */
        val buildRefused: Boolean = false,
    ) {
        val controller = BufferedController()
        val channel = if (archive) withArchive else noArchive
        val dispatcher = UnconfinedTestDispatcher(ts.testScheduler)
        val scope = CoroutineScope(ts.backgroundScope.coroutineContext + dispatcher)
        fun now(): Long = base + ts.testScheduler.currentTime
        val timeBase = TimeBase(
            serverZone = ZoneId.of("UTC"), clockSkew = Duration.ofMillis(skew), correctionHours = correction / 3_600_000.0,
            paddingBefore = Duration.ZERO, paddingAfter = Duration.ZERO,
        )
        val target = CatchupTargetResult.Found(
            CatchupTarget(
                "http://panel.example:8080/live/user/pass/101.ts", emptyMap(),
                Catchup(if (archive) CatchupMode.XC else CatchupMode.OFF, days = if (archive) 3 else 0, correctionHours = null),
                XtreamStreamRef("http://panel.example:8080", "user", "pass", 101, "ts"),
            ),
        )
        val archivePlayback = ArchivePlaybackImpl(
            controller = controller,
            targets = object : ArchiveTargets {
                override suspend fun catchupTarget(identity: ChannelIdentity) = if (targetGone) CatchupTargetResult.ChannelGone else target
            },
            builder = if (!buildRefused) CatchupUrlBuilderImpl() else object : app.thebox.core.catchup.CatchupUrlBuilder by CatchupUrlBuilderImpl() {
                override fun build(target: CatchupTarget, timeBase: TimeBase, window: app.thebox.core.catchup.ArchiveWindow, variant: String?) =
                    app.thebox.core.catchup.CatchupBuild.Unsupported("test")
            },
            timeBases = TimeBaseLookup { framesGate?.await(); timeBase },
            probeStore = FakeCatchupProbeStore(),
            feed = FakeProgrammeFeed(),
            planner = ArchivePlannerImpl(),
            scope = scope,
            io = dispatcher,
            clock = { now() },
        )
        val statuses = ArrayList<ArchiveStatus>()
        val status: ArchiveStatus get() = archivePlayback.status.value
        val info: LocalBufferInfo? get() = archivePlayback.localBuffer.value

        init {
            scope.launch { archivePlayback.status.collect { statuses += it } }
            controller.liveOn(channel.identity)
        }

        /** 20 min saved; the playhead (and the reader) at the live edge minus 2 s. */
        fun buffer(
            available: Boolean = true, stop: RecordStop? = null, notSaved: TimeRanges = TimeRanges.EMPTY, identity: ChannelIdentity = channel.identity,
            cycleId: Long = 1, holdMs: Long? = null, stoppedAtMono: Long? = null,
        ) {
            val mono = now() - wallMinusMono
            controller.localMono = mono - 2_000
            controller.local.value = LocalBufferState(
                identity = identity, available = available, off = if (available) null else BufferOff.SETTING_OFF,
                fromMonoMs = mono - 20 * 60_000L, liveMonoMs = mono, atMonoMs = mono - 2_000, wallMinusMonoMs = wallMinusMono,
                saving = available && stop == null, stop = stop, notSavedMono = notSaved, windowMs = 30 * 60_000L,
                pauseRemainingMs = if (available) 17 * 60_000L else null, cycleId = cycleId, holdMs = holdMs, stoppedAtMonoMs = stoppedAtMono,
            )
        }

        /** P of a mono time with this rig's frames (§9.2); a channel without catch-up has no correction. */
        fun p(mono: Long): Long = mono + wallMinusMono + skew + (if (channel.archiveDays > 0) correction else 0L)
    }

    /** §9.2 (A-B4): P = mono + wallMinusMono + skew + correction; the inverse maps a P target back to mono. */
    @Test fun programmeFrameMappingAndItsInverse() = runTest {
        val r = Rig(this, archive = true)
        r.buffer()
        val info = r.info!!
        val mono = r.now() - wallMinusMono
        assertTrue(info.available)
        assertEquals(r.p(mono - 20 * 60_000L), info.fromMs)
        assertEquals(r.now() + skew + correction, info.atMs)
        val back = LocalTimelineBackend(r.controller, { ArchiveFrames(correction, skew, 0) }, r.scope, { r.now() })
        back.start()
        runCurrent()
        for (x in listOf(0L, mono, mono - 600_000L)) assertEquals(x, back.toMono(back.toProgramme(x)))
        assertEquals(mono + wallMinusMono + skew + correction, back.toProgramme(mono))
        // Without catch-up: correction 0, the playlist's skew.
        val n = Rig(this, archive = false)
        n.buffer()
        assertEquals(n.p(mono - 20 * 60_000L), n.info!!.fromMs)
    }

    /** §7.1 (A-B4 confirmation): an available buffer is held back until the frames are known; a reason is not. */
    @Test fun heldBackUntilTheFramesAreKnown() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, archive = false, framesGate = gate)
        r.buffer()
        assertNull(r.info)
        gate.complete(Unit)
        runCurrent()
        assertTrue(r.info!!.available)
        r.buffer(available = false)
        assertEquals(BufferOff.SETTING_OFF, r.info!!.off)
        r.controller.local.value = null
        assertNull(r.info)
    }

    /** §11.2: pause in place: `controller.pause()`, status PAUSED, LOCAL, the P playhead, the buffer's fields. */
    @Test fun pauseResumeStatusFields() = runTest {
        val r = Rig(this, archive = false)
        r.buffer()
        r.archivePlayback.pauseLive(r.channel)
        assertEquals(listOf("pause"), r.controller.calls)
        val s = r.status
        assertEquals(ArchivePhase.PAUSED, s.phase)
        assertEquals(TimelineSource.LOCAL, s.source)
        assertTrue(s.timeShift)
        assertEquals(r.p(r.controller.localMono!!), s.wallMs)
        assertEquals(r.now() + skew, s.liveEdgeMs)
        assertEquals(r.info!!.fromMs, s.bufferFromMs)
        assertEquals("no catch-up: the bar starts at the buffer", s.bufferFromMs, s.archiveFromMs)
        assertEquals(17 * 60_000L, s.pauseRemainingMs)
        assertTrue(r.archivePlayback.status.value.phase == ArchivePhase.PAUSED && !s.advancing)
        // The recorder stops while paused: the stop reaches the status (the header line).
        advanceTimeBy(60_000); r.buffer(stop = RecordStop.HOLD_LIMIT)
        assertEquals(RecordStop.HOLD_LIMIT, r.status.savingStop)
        r.archivePlayback.resume()
        assertEquals("no re-tune, no archive request", listOf("pause", "resume"), r.controller.calls)
        assertEquals(ArchivePhase.PLAYING, r.status.phase)
        assertTrue(r.status.advancing)
    }

    /** §11.2 seekTo inside the buffer: `seekLocal(mono(t), dir)`, SEEKING, settled at the new reader's first media. */
    @Test fun seekInsideTheBufferIsLocal() = runTest {
        val r = Rig(this, archive = false)
        r.buffer()
        r.archivePlayback.pauseLive(r.channel)
        val mono = r.now() - wallMinusMono
        val t = r.p(mono - 10 * 60_000L)
        r.archivePlayback.seekTo(t, seq = 7)
        assertEquals(listOf(mono - 10 * 60_000L to -1), r.controller.localSeeks)
        assertEquals(ArchivePhase.SEEKING, r.status.phase)
        assertTrue(r.status.settledSeq < 7)
        r.controller.localMono = mono - 10 * 60_000L
        r.controller.firstMedia(paused = true)
        assertEquals(7L, r.status.settledSeq)
        assertEquals("a seek while paused stays paused (E26)", ArchivePhase.PAUSED, r.status.phase)
        assertEquals(t, r.status.wallMs)
        // Forward from there: ceil.
        r.archivePlayback.seekTo(t + 60_000, seq = 8)
        assertEquals(1, r.controller.localSeeks.last().second)
    }

    /** §9.1: older than the buffer without catch-up clamps to its start (floor); with catch-up it goes to the provider. */
    @Test fun olderThanTheBuffer() = runTest {
        val n = Rig(this, archive = false)
        n.buffer()
        n.archivePlayback.pauseLive(n.channel)
        val mono = n.now() - wallMinusMono
        n.archivePlayback.seekTo(n.p(mono - 40 * 60_000L), seq = 1)
        assertEquals(mono - 20 * 60_000L to -1, n.controller.localSeeks.last())
        assertTrue(n.controller.archiveTunes.isEmpty())

        val a = Rig(this, archive = true)
        a.buffer()
        a.archivePlayback.pauseLive(a.channel)
        runCurrent()
        a.archivePlayback.seekTo(a.p(mono - 40 * 60_000L), seq = 1)
        runCurrent()
        assertEquals(1, a.controller.archiveTunes.size)
        assertTrue(a.controller.localSeeks.isEmpty())
        assertEquals(TimelineSource.PROVIDER, a.status.source)
        assertEquals(ArchivePhase.SEEKING, a.status.phase)
    }

    /** §11.2 jumpToLive: `jumpToLiveLocal()`, SEEKING, then IDLE at the live reader's first media. */
    @Test fun backToLive() = runTest {
        val r = Rig(this, archive = false)
        r.buffer()
        r.archivePlayback.pauseLive(r.channel)
        r.archivePlayback.jumpToLive()
        assertTrue("jumpToLiveLocal" in r.controller.calls)
        assertEquals(ArchivePhase.SEEKING, r.status.phase)
        r.controller.firstMedia()
        assertEquals(ArchivePhase.IDLE, r.status.phase)
    }

    /** §9.1 (U-B2, A-C5): the controller's LocalGap on a catch-up channel → the provider from the gap's start, no toast. */
    @Test fun gapHandOff() = runTest {
        val r = Rig(this, archive = true)
        r.buffer()
        r.archivePlayback.pauseLive(r.channel)
        runCurrent()
        r.archivePlayback.resume()
        val mono = r.now() - wallMinusMono
        r.controller.noticeFlow.tryEmit(PlaybackNotice.LocalGap(mono - 5 * 60_000L, 4 * 60_000L, SegmentReason.GAP))
        runCurrent()
        assertEquals(1, r.controller.archiveTunes.size)
        assertEquals(TimelineSource.PROVIDER, r.status.source)
    }

    /**
     * Fix pass B2: the controller emits LocalGap and leaves the reader at the gap, so every hand-off that cannot happen
     * falls back to `skipGapLocal()`: no LOCAL session (a plain pause on a buffered cycle), the context load failed, or
     * no request could be built.
     */
    @Test fun gapHandOffFallsBackWhenTheProviderFails() = runTest {
        fun gap(r: Rig) {
            val mono = r.now() - wallMinusMono
            r.controller.noticeFlow.tryEmit(PlaybackNotice.LocalGap(mono - 5 * 60_000L, 4 * 60_000L, SegmentReason.GAP))
            runCurrent()
        }
        val none = Rig(this, archive = true)
        none.buffer()
        runCurrent()
        gap(none)
        assertTrue(none.controller.calls.toString(), "skipGapLocal" in none.controller.calls)
        assertTrue(none.controller.archiveTunes.isEmpty())

        for ((name, r) in listOf("ctx failed" to Rig(this, archive = true, targetGone = true), "built null" to Rig(this, archive = true, buildRefused = true))) {
            r.buffer()
            r.archivePlayback.pauseLive(r.channel)
            runCurrent()
            r.archivePlayback.resume()
            gap(r)
            assertTrue("$name: ${r.controller.calls}", "skipGapLocal" in r.controller.calls)
            assertTrue(name, r.controller.archiveTunes.isEmpty())
            assertEquals(name, TimelineSource.LOCAL, r.status.source)
        }
    }

    /** Fix pass S3: a seek into a not-saved range never floors to the RAP before it; without catch-up, the range's end. */
    @Test fun aSeekIntoANotSavedRangeLandsAtItsEnd() = runTest {
        val r = Rig(this, archive = false)
        val mono = r.now() - wallMinusMono
        r.buffer(notSaved = TimeRanges(longArrayOf(mono - 15 * 60_000L, mono - 5 * 60_000L)))
        r.archivePlayback.pauseLive(r.channel)
        r.archivePlayback.seekTo(r.p(mono - 10 * 60_000L), seq = 1)
        assertEquals(mono - 5 * 60_000L to 1, r.controller.localSeeks.last())
    }

    /** Fix pass S1, S2, S9: the window, the hold and the stop instant (P) and the cycle reach `LocalBufferInfo`. */
    @Test fun theBufferInfoCarriesWindowHoldStopAndCycle() = runTest {
        val r = Rig(this, archive = false)
        val mono = r.now() - wallMinusMono
        r.buffer(stop = RecordStop.HOLD_LIMIT, holdMs = 10 * 60_000L, stoppedAtMono = mono - 60_000L, cycleId = 7)
        runCurrent()
        val i = r.info!!
        assertEquals(30 * 60_000L, i.windowMs)
        assertEquals(10 * 60_000L, i.holdMs)
        assertEquals(r.p(mono - 60_000L), i.stoppedAtMs)
        assertEquals(7L, i.cycleId)
    }

    /** Fix pass S9: a new cycle of the same channel restarts the tune clock (REWIND_NOTHING_YET in the archive session). */
    @Test fun aNewCycleRestartsTheTuneClock() = runTest {
        val r = Rig(this, archive = false)
        r.buffer(cycleId = 1)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        r.buffer(cycleId = 1)
        // A minute into the cycle: a target older than the buffer clamps with START (no toast here: the machine says it).
        val mono = r.now() - wallMinusMono
        r.archivePlayback.pauseLive(r.channel)
        r.archivePlayback.seekTo(r.p(mono - 40 * 60_000L), seq = 1)
        assertEquals(mono - 20 * 60_000L to -1, r.controller.localSeeks.last())
        // Gone and back (a re-tune): the frames are looked up again and the new cycle's clock starts.
        r.controller.local.value = null
        runCurrent()
        assertEquals(null, r.info)
        r.buffer(cycleId = 2)
        runCurrent()
        assertTrue("the new cycle's buffer is published again", r.info?.available == true)
        assertEquals(2L, r.info!!.cycleId)
    }

    /** RW on live: local first (a LOCAL session landing 30 s back), never an archive request inside the buffer. */
    @Test fun rewindLiveIsLocal() = runTest {
        val r = Rig(this, archive = true)
        r.buffer()
        r.archivePlayback.rewindLive(r.channel, 30_000)
        runCurrent()
        assertTrue(r.controller.archiveTunes.isEmpty())
        val (target, dir) = r.controller.localSeeks.single()
        assertEquals(r.now() - wallMinusMono - 30_000, target)
        assertEquals(-1, dir)
        assertEquals(TimelineSource.LOCAL, r.status.source)
    }

    /** A zap (another identity) or a lost buffer ends the LOCAL session. */
    @Test fun zapOrLostBufferEndsIt() = runTest {
        val r = Rig(this, archive = false)
        r.buffer()
        r.archivePlayback.pauseLive(r.channel)
        r.controller.liveOn(withArchive.identity)
        assertEquals(ArchivePhase.IDLE, r.status.phase)

        val l = Rig(this, archive = false)
        l.buffer()
        l.archivePlayback.pauseLive(l.channel)
        l.buffer(available = false)
        assertEquals(ArchivePhase.IDLE, l.status.phase)
    }
}
