package app.thebox.playback

import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.catchup.CatchupUrlBuilderImpl
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlannerImpl
import app.thebox.core.catchup.archive.ArchiveStart
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.ArchiveVisibility
import app.thebox.core.full.fakes.FakeCatchupProbeStore
import app.thebox.core.full.fakes.FakeProgrammeFeed
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.ErrorDetail
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.xtream.XtreamStreamRef
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.CatchupTargetResult
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.VideoInfo
import app.thebox.player.api.ZapList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun unusedController(): PlaybackController = Proxy.newProxyInstance(
    PlaybackController::class.java.classLoader, arrayOf(PlaybackController::class.java),
) { _, m, _ -> throw AssertionError("unexpected PlaybackController.${m.name}") } as PlaybackController

/**
 * Scripted controller (docs/design/m4-player-ux.md §5.2): `startCycle` emits Connecting synchronously with a new
 * tuneId, `seekTo` emits nothing (an in-buffer seek, B3), `position()` answers [pos]. Tests drive the rest of the state
 * flow by hand.
 */
private class ScriptedController : PlaybackController by unusedController() {
    val flow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> get() = flow
    val videoFlow = MutableStateFlow(VideoInfo())
    override val video: StateFlow<VideoInfo> get() = videoFlow

    var nextTuneId = 100L
    val archiveTunes = ArrayList<ArchiveTune>()
    val liveTunes = ArrayList<ChannelIdentity>()
    val seeks = ArrayList<Long>()
    val calls = ArrayList<String>()
    val aspectCalls = ArrayList<Pair<AspectMode, Boolean>>()
    var pos = PositionSnapshot(0, 0, null, false, null)
    /** What startCycle resets the aspect to (settings.defaultAspect). */
    var defaultAspect = AspectMode.FIT
    /** The tuneId that tuneArchive got, read by the test the way the session reads it. */
    var lastArchiveTuneId = 0L

    fun np(identity: ChannelIdentity, archive: Boolean) = NowPlaying(++nextTuneId, identity, null, archive, StreamFormat.TS)

    override fun tuneArchive(tune: ArchiveTune) {
        calls += "tuneArchive"
        archiveTunes += tune
        videoFlow.value = VideoInfo(aspectMode = defaultAspect)
        val n = np(tune.identity, archive = true)
        lastArchiveTuneId = n.tuneId
        flow.value = PlaybackState.Connecting(n, ConnectPhase.RESOLVING, 1, 0)
    }

    override fun tune(target: TuneTarget, zapList: ZapList?) {
        calls += "tune"
        val id = (target as TuneTarget.Channel).identity
        liveTunes += id
        videoFlow.value = VideoInfo(aspectMode = defaultAspect)
        flow.value = PlaybackState.Connecting(np(id, archive = false), ConnectPhase.RESOLVING, 1, 0)
    }

    override fun seekTo(positionMs: Long) { calls += "seekTo"; seeks += positionMs }
    override fun position(): PositionSnapshot = pos

    override fun pause() {
        calls += "pause"
        val st = flow.value
        if (st is PlaybackState.Playing) flow.value = st.copy(paused = true)
    }

    override fun resume() {
        calls += "resume"
        when (val st = flow.value) {
            is PlaybackState.Playing -> if (st.target.isArchive) flow.value = st.copy(paused = false)
                else flow.value = PlaybackState.Connecting(np(st.target.identity!!, false), ConnectPhase.RESOLVING, 1, 0)
            is PlaybackState.Stopped -> flow.value = PlaybackState.Connecting(
                np(st.target!!.identity!!, st.target!!.isArchive), ConnectPhase.RESOLVING, 1, 0,
            )
            else -> Unit
        }
    }

    override fun setAspectMode(mode: AspectMode, remember: Boolean) {
        aspectCalls += mode to remember
        videoFlow.update { it.copy(aspectMode = mode) }
    }

    // ---- script helpers
    val target: NowPlaying get() = flow.value.target!!
    fun playing(paused: Boolean = false, ended: Boolean = false) { flow.value = PlaybackState.Playing(target, paused = paused, ended = ended) }
    fun error(code: PlaybackErrorCode, http: Int? = null) {
        flow.value = PlaybackState.Error(target, PlaybackError(code, ErrorDetail(httpStatus = http, host = "panel.example:8080")))
    }
    fun liveOn(identity: ChannelIdentity) { flow.value = PlaybackState.Playing(np(identity, archive = false)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArchivePlaybackImplTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val segment = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").withZone(tokyo)

    /** Device clock at virtual time 0: deliberately not on a minute. */
    private val base = ms("2026-10-15T12:00:17.250Z")
    private val channel: GuideChannel = GuideFixtures.channel(3, archiveDays = 3)
    private val other: GuideChannel = GuideFixtures.channel(4, archiveDays = 3)

    private inner class Rig(
        val ts: TestScope,
        channelCorrH: Double? = null,
        playlistCorrH: Double = 0.0,
        skewSec: Long = 0,
        ext: String = "ts",
    ) {
        val controller = ScriptedController()
        val feed = FakeProgrammeFeed()
        val store = FakeCatchupProbeStore()
        var targetResult: CatchupTargetResult = CatchupTargetResult.Found(
            CatchupTarget(
                "http://panel.example:8080/live/user/pass/101.$ext", emptyMap(),
                Catchup(CatchupMode.XC, days = 3, correctionHours = channelCorrH),
                XtreamStreamRef("http://panel.example:8080", "user", "pass", 101, ext),
            ),
        )
        var timeBase: TimeBase? = TimeBase(
            serverZone = tokyo, clockSkew = Duration.ofSeconds(skewSec), correctionHours = playlistCorrH,
            paddingBefore = Duration.ofSeconds(60), paddingAfter = Duration.ofSeconds(300),
        )
        val dispatcher = UnconfinedTestDispatcher(ts.testScheduler)
        val scope = CoroutineScope(ts.backgroundScope.coroutineContext + dispatcher)
        val archive = ArchivePlaybackImpl(
            controller = controller,
            targets = object : ArchiveTargets {
                override suspend fun catchupTarget(identity: ChannelIdentity) = targetResult
            },
            builder = CatchupUrlBuilderImpl(),
            timeBases = TimeBaseLookup { timeBase },
            probeStore = store,
            feed = feed,
            planner = ArchivePlannerImpl(),
            scope = scope,
            io = dispatcher,
            clock = { now() },
        )
        val statuses = ArrayList<ArchiveStatus>()
        val notReady = ArrayList<ChannelIdentity>()

        init {
            scope.launch { archive.status.collect { statuses += it } }
            scope.launch { archive.liveNotReady.collect { notReady += it } }
        }

        fun now(): Long = base + ts.testScheduler.currentTime
        val status: ArchiveStatus get() = archive.status.value
        val urls: List<String> get() = controller.archiveTunes.map { it.request.url }
        val lastTune: ArchiveTune get() = controller.archiveTunes.last()

        fun advance(ms: Long) { ts.advanceTimeBy(ms); ts.runCurrent() }

        /** Position of the current request at device time [atMs] when it started at [startOffset] and plays 1:1. */
        fun at(positionMs: Long, durationMs: Long? = null, seekable: Boolean = durationMs != null, buffered: Long = 0) {
            controller.pos = PositionSnapshot(positionMs, buffered, durationMs, seekable, null)
        }

        /** What both machines would be shown for the current controller state (ArchiveVisibility, B1). */
        fun visible(): TunedState {
            val st = controller.flow.value
            val t = st.target
            val phase = when (st) {
                is PlaybackState.Connecting -> MiniPhase.CONNECTING
                is PlaybackState.Buffering -> MiniPhase.BUFFERING
                is PlaybackState.Playing -> if (st.paused) MiniPhase.PAUSED else MiniPhase.PLAYING
                is PlaybackState.Error -> MiniPhase.ERROR
                is PlaybackState.Stopped -> MiniPhase.STOPPED
                else -> MiniPhase.IDLE
            }
            val tuned = TunedState(
                identity = t?.identity, identityHash = t?.identity?.let(GuideChannel::hashOf) ?: 0, phase = phase,
                isArchive = t?.isArchive == true, error = (st as? PlaybackState.Error)?.error?.code,
                errorDetail = (st as? PlaybackState.Error)?.error, tuneId = t?.tuneId ?: 0,
            )
            return ArchiveVisibility.visible(tuned, status)
        }
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun minOf(iso: String) = (ms(iso) / 60_000).toInt()
    private fun info(startIso: String, endIso: String, title: String = startIso) =
        GuideFixtures.info(minOf(startIso), minOf(endIso), title)

    private fun xcPath(minutes: Long, startUtcMs: Long, ext: String = "ts") =
        "http://panel.example:8080/timeshift/user/pass/$minutes/${segment.format(Instant.ofEpochMilli(startUtcMs))}/101.$ext"
    private fun xcPhp(minutes: Long, startUtcMs: Long) =
        "http://panel.example:8080/streaming/timeshift.php?username=user&password=pass&stream=101&start=${segment.format(Instant.ofEpochMilli(startUtcMs))}&duration=$minutes"
    private fun xcPathSec(minutes: Long, startUtcMs: Long) =
        "http://panel.example:8080/timeshift/user/pass/$minutes/${DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm-ss").withZone(tokyo).format(Instant.ofEpochMilli(startUtcMs))}/101.ts"
    private fun ceilMin(ms: Long) = (ms + 59_999) / 60_000
    private fun floorMin(ms: Long) = Math.floorDiv(ms, 60_000L) * 60_000L

    private val p1 = info("2026-10-15T11:00:00Z", "2026-10-15T11:30:00Z", "P1")
    private val p2 = info("2026-10-15T11:30:00Z", "2026-10-15T12:00:00Z", "P2")
    private val p0 = info("2026-10-15T10:30:00Z", "2026-10-15T11:00:00Z", "P0")

    private fun Rig.startP1(): ArchiveStart {
        feed.put(channel, listOf(p0, p1, p2, info("2026-10-15T12:00:00Z", "2026-10-15T12:30:00Z", "P3")))
        controller.liveOn(other.identity)
        var r: ArchiveStart? = null
        ts.backgroundScope.launch(dispatcher) { r = archive.playProgramme(channel, p1, p1.rawStartMin + 30) }
        ts.runCurrent()
        return r!!
    }

    // ------------------------------------------------------------------ the main journey (§5.2)

    @Test
    fun startFirstMediaRescopeContinuationNativeSeekRerequestSeekLiveSnap() = runTest {
        val rig = Rig(this)
        with(rig) {
            assertEquals(ArchiveStart.Started, startP1())
            // First request: padded 60 s, closed (P1 ended), +300 s after; the panel zone formats it (Tokyo, UTC+9).
            val s0 = ms("2026-10-15T10:59:00Z")
            assertEquals(listOf(xcPath(36, s0)), urls)
            assertEquals(0L, lastTune.startOffsetMs)
            assertEquals(ArchivePhase.STARTING, status.phase)
            assertEquals(controller.target.tuneId, status.ownedTuneId)
            assertEquals(s0, status.wallMs)
            assertEquals("P1", status.programme?.title)
            assertEquals(1, status.attempt); assertEquals(3, status.attempts)

            // First media.
            at(0, 2_160_000)
            controller.playing()
            assertEquals(ArchivePhase.PLAYING, status.phase)
            assertTrue(status.advancing)

            // Boundary: 32 min into the file = 11:31, P2. The status re-scopes; no request.
            at(1_920_000, 2_160_000)
            advance(1_000)
            assertEquals("P2", status.programme?.title)
            assertEquals(s0 + 1_920_000, status.wallMs)
            assertEquals(1, urls.size)

            // Continuation: 3 s before the end of the 36-min file, open-ended from the wall, minute-aligned, offset kept.
            at(2_157_000, 2_160_000)
            advance(1_000)
            assertEquals(2, urls.size)
            val wall = s0 + 2_157_000 // 11:34:57
            val s1 = floorMin(wall)
            val nowSec = now() / 1000
            assertEquals(xcPath(ceilMin((nowSec - s1 / 1000) * 1000), s1), urls[1])
            assertEquals(wall - s1, lastTune.startOffsetMs)
            assertNull(lastTune.request.requestEnd)
            assertEquals(ArchivePhase.CONTINUING, status.phase)
            assertEquals(controller.target.tuneId, status.ownedTuneId)
            assertTrue("hand-off hidden", visible().handoff)

            // First media of the continuation; the file is now seekable with a duration.
            at(wall - s1, 1_520_000)
            controller.playing()
            assertEquals(ArchivePhase.PLAYING, status.phase)

            // Native seek (B3): controller.seekTo, no state emission, settles by polling.
            archive.seekTo(s1 + 300_000, seq = 1)
            assertEquals(listOf(300_000L), controller.seeks)
            assertEquals(ArchivePhase.SEEKING, status.phase)
            assertEquals(0L, status.settledSeq)
            at(300_000, 1_520_000); advance(250) // no progress yet
            assertEquals(ArchivePhase.SEEKING, status.phase)
            at(300_260, 1_520_000); advance(250)
            assertEquals(ArchivePhase.PLAYING, status.phase)
            assertEquals(1L, status.settledSeq)
            assertEquals(2, urls.size)

            // Re-request seek (before this request's origin): settles at first media of the issued tuneId.
            val t = s1 - 600_000 // 11:24
            archive.seekTo(t, seq = 2)
            assertEquals(3, urls.size)
            assertEquals(ArchivePhase.SEEKING, status.phase)
            assertEquals(1L, status.settledSeq)
            assertEquals(floorMin(t), lastTune.request.requestStart.toEpochMilli())
            controller.playing()
            assertEquals(2L, status.settledSeq)
            assertEquals(ArchivePhase.PLAYING, status.phase)

            // User seek to 10 s behind the edge snaps to live (30 s rule).
            archive.seekTo(now() - 10_000, seq = 3)
            assertEquals(listOf(channel.identity), controller.liveTunes)
            assertEquals(ArchivePhase.IDLE, status.phase)
            assertEquals(3L, status.settledSeq)
            assertEquals(3, urls.size)
        }
    }

    // ------------------------------------------------------------------ B1

    @Test
    fun ownedTuneIdIsReadInTheSameTurnAsTuneArchive() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            val owned = controller.lastArchiveTuneId
            // Every published non-IDLE status already carries the new cycle's tuneId: none owns an older one.
            val nonIdle = statuses.filter { it.phase != ArchivePhase.IDLE }
            assertTrue(nonIdle.isNotEmpty())
            nonIdle.forEach { assertEquals(owned, it.ownedTuneId) }
        }
    }

    /** The invariant: an owned Error the session does not act on is FAILED in the same turn (no runCurrent between). */
    @Test
    fun unactedErrorFailsInTheSameTurn() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            // The Error arrives before any status update for it.
            controller.error(PlaybackErrorCode.AUTH_REJECTED, 401)
            assertEquals(ArchivePhase.FAILED, status.phase)
            assertEquals(ArchiveFailure.PLAYBACK, status.failure)
            assertEquals(PlaybackErrorCode.AUTH_REJECTED, status.error?.code)
            assertEquals("the card is the controller's", MiniPhase.ERROR, visible().phase)
            assertEquals(1, urls.size)
        }
    }

    @Test
    fun actedErrorIsHiddenAndReplacedInTheSameTurn() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            val first = controller.target.tuneId
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 404)
            assertEquals(ArchivePhase.PROBING, status.phase)
            assertTrue(status.ownedTuneId > first)
            assertEquals(2, urls.size)
            assertNotEquals(MiniPhase.ERROR, visible().phase)
            // A VM that saw the Error before the session's status (status lagging one emission) hides it too.
            val lagging = statuses.last { it.phase == ArchivePhase.STARTING }
            val err = TunedState(identity = channel.identity, identityHash = channel.identityHash, phase = MiniPhase.ERROR, isArchive = true,
                error = PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, tuneId = first)
            assertNotEquals(MiniPhase.ERROR, ArchiveVisibility.visible(err, lagging).phase)
        }
    }

    // ------------------------------------------------------------------ S3 probing, persistence

    @Test
    fun probeTriggersAndWinner() = runTest {
        val s0 = ms("2026-10-15T10:59:00Z")
        for ((code, http) in listOf(
            PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE to 404,
            PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE to 400, // ladder row L13a
            PlaybackErrorCode.FORBIDDEN to 403, // after the ladder's L2–L6
            PlaybackErrorCode.SERVER_ERROR to 503,
            PlaybackErrorCode.WEB_PAGE_INSTEAD to 200,
            PlaybackErrorCode.EMPTY_RESPONSE to 200,
            PlaybackErrorCode.FORMAT_REFUSED to 406,
            PlaybackErrorCode.UNSUPPORTED_SOURCE to null,
        )) {
            val rig = Rig(this)
            with(rig) {
                startP1()
                controller.error(code, http)
                assertEquals("$code/$http", listOf(xcPath(36, s0), xcPhp(36, s0)), urls)
                assertEquals(ArchivePhase.PROBING, status.phase)
                assertEquals(2, status.attempt)
                assertTrue(store.writes.isEmpty())
                controller.playing()
                assertEquals(ArchivePhase.PLAYING, status.phase)
                assertEquals("winner after first media only", listOf(1L to "xc-php"), store.writes)
            }
        }
    }

    @Test
    fun nonVariantErrorsBeforeFirstMediaFail() = runTest {
        for (code in listOf(PlaybackErrorCode.CONNECTION_LIMIT, PlaybackErrorCode.AUTH_REJECTED, PlaybackErrorCode.ACCOUNT_EXPIRED,
            PlaybackErrorCode.CODEC_UNSUPPORTED, PlaybackErrorCode.CONNECT_FAILED, PlaybackErrorCode.DNS_FAILURE)) {
            val rig = Rig(this)
            with(rig) {
                startP1()
                controller.error(code)
                assertEquals("$code", ArchivePhase.FAILED, status.phase)
                assertEquals(ArchiveFailure.PLAYBACK, status.failure)
                assertEquals(1, urls.size)
            }
        }
    }

    @Test
    fun allVariantsFailIsNotInArchiveWithoutAWinner() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            val s0 = ms("2026-10-15T10:59:00Z")
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 404)
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 400)
            assertEquals(3, status.attempt)
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 404)
            assertEquals(listOf(xcPath(36, s0), xcPhp(36, s0), xcPathSec(36, s0)), urls)
            assertEquals(ArchivePhase.FAILED, status.phase)
            assertEquals(ArchiveFailure.NOT_IN_ARCHIVE, status.failure)
            assertEquals(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, status.error?.code)
            assertTrue(store.writes.isEmpty())
            assertFalse(archive.tryNextVariant())
        }
    }

    @Test
    fun storedWinnerGoesFirstAndIsNotRewritten() = runTest {
        val rig = Rig(this)
        with(rig) {
            store.winners[1L] = "xc-php"
            startP1()
            assertTrue(urls.single().contains("timeshift.php"))
            controller.playing()
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test
    fun tryNextVariantFromTheCardContinuesTheWalk() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            at(600_000, 2_160_000); advance(1_000)
            controller.error(PlaybackErrorCode.AUTH_REJECTED, 401)
            assertEquals(ArchivePhase.FAILED, status.phase)
            assertTrue(archive.tryNextVariant())
            assertEquals(ArchivePhase.PROBING, status.phase)
            // From the playhead (11:09:00, already on a minute for xc-php), open-ended.
            val wall = ms("2026-10-15T10:59:00Z") + 600_000
            assertEquals(xcPhp(ceilMin(now() - wall), wall), urls.last())
        }
    }

    // ------------------------------------------------------------------ lag (§3.8.7)

    @Test
    fun lagNearTheEdgeStepsBackOnceThenGoesLive() = runTest {
        val rig = Rig(this)
        with(rig) {
            controller.liveOn(channel.identity)
            var r: ArchiveStart? = null
            backgroundScope.launch(dispatcher) { r = archive.rewindLive(channel, 30_000) }
            runCurrent()
            assertEquals(ArchiveStart.Started, r)
            val s0 = floorMin(base - 30_000)
            assertEquals(xcPath(ceilMin(base - s0), s0), urls.single())
            assertEquals(base - 30_000 - s0, lastTune.startOffsetMs)
            assertTrue(status.timeShift)
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 404)
            // Same variant, 60 s earlier; target unchanged (the offset grows by the step).
            assertEquals(2, urls.size)
            assertEquals(xcPath(ceilMin(base - (s0 - 60_000)), s0 - 60_000), urls[1])
            assertEquals(base - 30_000 - (s0 - 60_000), lastTune.startOffsetMs)
            assertEquals(1, status.attempt)
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 404)
            assertEquals(listOf(channel.identity), controller.liveTunes)
            assertEquals(listOf(channel.identity), notReady)
            assertEquals(ArchivePhase.IDLE, status.phase)
            assertTrue(store.writes.isEmpty())
            assertTrue("never FAILED (no suspect ↺)", statuses.none { it.phase == ArchivePhase.FAILED })
        }
    }

    @Test
    fun a400NearTheEdgeIsAVariantFailureNotLag() = runTest {
        val rig = Rig(this)
        with(rig) {
            controller.liveOn(channel.identity)
            backgroundScope.launch(dispatcher) { archive.rewindLive(channel, 30_000) }
            runCurrent()
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 400)
            assertTrue(urls[1].contains("timeshift.php"))
            assertEquals(ArchivePhase.PROBING, status.phase)
            assertTrue(notReady.isEmpty())
        }
    }

    // ------------------------------------------------------------------ pause on live (§3.8.7, B2, N3)

    @Test
    fun shortLivePauseResumesLive() = runTest {
        val rig = Rig(this)
        with(rig) {
            controller.liveOn(channel.identity)
            at(0, buffered = 1_500)
            archive.pauseLive(channel)
            assertEquals(ArchivePhase.PAUSED_LIVE, status.phase)
            assertEquals(base - 1_500, status.wallMs)
            assertTrue(status.timeShift)
            advance(2_000)
            archive.resume()
            assertTrue(urls.isEmpty())
            assertEquals(listOf("pause", "resume"), controller.calls)
            assertEquals(ArchivePhase.IDLE, status.phase)
        }
    }

    /** B2 / I-M4-13b: the resume request starts at the pause instant on the panel's clock for every sign of the correction. */
    @Test
    fun longLivePauseResumesFromTheArchiveAtThePanelClock() = runTest {
        for (corr in listOf(-1.5, 0.0, 2.0)) for (skew in listOf(0L, 42L)) {
            val rig = Rig(this, channelCorrH = corr, skewSec = skew)
            with(rig) {
                controller.liveOn(channel.identity)
                at(0, buffered = 2_400)
                val pauseAt = now()
                archive.pauseLive(channel)
                val corrMs = (corr * 3_600_000).toLong()
                assertEquals("corr $corr", pauseAt + skew * 1000 + corrMs - 2_400, status.wallMs)
                advance(10_000)
                controller.flow.value = PlaybackState.Stopped(controller.target, StopReason.LIVE_PAUSE_TIMEOUT, resumable = true)
                assertEquals("the release keeps the session", ArchivePhase.PAUSED_LIVE, status.phase)
                advance(10_000)
                archive.resume()
                val panelPause = pauseAt + skew * 1000 - 2_400
                val start = floorMin(panelPause)
                val panelNow = now() + skew * 1000
                assertEquals("corr $corr skew $skew", xcPath(ceilMin(panelNow / 1000 * 1000 - start), start), urls.single())
                assertEquals(panelPause - start, lastTune.startOffsetMs)
                assertEquals(ArchivePhase.CONTINUING, status.phase)
                at(lastTune.startOffsetMs) // the controller sought to the offset before prepare
                controller.playing()
                assertEquals(ArchivePhase.PLAYING, status.phase)
                // The pill: about 20 s behind the live edge.
                val behind = status.liveEdgeMs - status.wallMs
                assertTrue("behind $behind", behind in 20_000L..23_000L)
            }
        }
    }

    // ------------------------------------------------------------------ ownership, mid-play, lifecycle

    @Test
    fun zapEndsTheSession() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            controller.tune(TuneTarget.Channel(other.identity))
            assertEquals(ArchivePhase.IDLE, status.phase)
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 404)
            assertEquals(1, urls.size)
            advance(5_000)
            assertEquals(1, urls.size)
        }
    }

    @Test
    fun midPlayRetryListAndReset() = runTest {
        val rig = Rig(this)
        with(rig) {
            val s0 = ms("2026-10-15T10:59:00Z")
            startP1()
            controller.playing()
            at(120_000, 2_160_000); advance(1_000)
            // A dropped connection after first media: one open-ended re-request from the playhead.
            controller.error(PlaybackErrorCode.STREAM_INTERRUPTED)
            assertEquals(2, urls.size)
            assertEquals(ArchivePhase.CONTINUING, status.phase)
            assertEquals(floorMin(s0 + 120_000), lastTune.request.requestStart.toEpochMilli())
            controller.playing()
            at(240_000, 600_000); advance(1_000)
            // Second failure within 5 min: no retry left.
            controller.error(PlaybackErrorCode.NO_DATA_TIMEOUT)
            assertEquals(ArchivePhase.FAILED, status.phase)
            assertEquals(2, urls.size)
        }
        val rig2 = Rig(this)
        with(rig2) {
            startP1()
            controller.playing()
            controller.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, 404) // a hole after first media
            assertEquals(2, urls.size)
            controller.playing()
            // 5 min of clean playback resets the budget (S3).
            for (i in 1..301) { at(i * 1_000L, 2_160_000); advance(1_000) }
            controller.error(PlaybackErrorCode.CONNECT_FAILED)
            assertEquals(3, urls.size)
            assertEquals(ArchivePhase.CONTINUING, status.phase)
        }
    }

    @Test
    fun authAfterFirstMediaFailsWithoutRetry() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            controller.error(PlaybackErrorCode.AUTH_REJECTED, 401)
            assertEquals(ArchivePhase.FAILED, status.phase)
            assertEquals(1, urls.size)
            // Archive Retry (resume on FAILED, §3.7 F5) re-requests at the playhead.
            archive.resume()
            assertEquals(2, urls.size)
            assertEquals(ArchivePhase.CONTINUING, status.phase)
        }
    }

    @Test
    fun lifecycleStopPausesAndTheRestartIsOwned() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            at(60_000, 2_160_000); advance(1_000)
            controller.flow.value = PlaybackState.Stopped(controller.target, StopReason.BACKGROUND, resumable = true)
            assertEquals(ArchivePhase.PAUSED, status.phase)
            advance(3_000)
            assertFalse(status.advancing)
            // The controller's lifecycle resume restarts the same request under a later tuneId.
            controller.resume()
            assertEquals(ArchivePhase.PAUSED, status.phase)
            controller.playing()
            assertEquals(ArchivePhase.PLAYING, status.phase)
            assertEquals(1, urls.size)
        }
    }

    // ------------------------------------------------------------------ S2

    @Test
    fun continuationKeepsUnrememberedAspect() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            controller.setAspectMode(AspectMode.FILL, remember = false) // the user's unremembered choice
            controller.aspectCalls.clear()
            at(2_157_000, 2_160_000); advance(1_000)
            assertEquals(2, urls.size)
            // startCycle reset FIT; the session re-applied FILL unremembered at once and at first media.
            assertEquals(AspectMode.FILL, controller.videoFlow.value.aspectMode)
            assertEquals(listOf(AspectMode.FILL to false), controller.aspectCalls)
            controller.videoFlow.update { it.copy(aspectMode = AspectMode.FIT) } // the prepare applied memory/default
            controller.playing()
            assertEquals(AspectMode.FILL, controller.videoFlow.value.aspectMode)
            assertTrue(controller.aspectCalls.all { !it.second })
        }
    }

    @Test
    fun rerequestWhilePausedStaysPaused() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            at(600_000, 2_160_000); advance(1_000)
            archive.pause()
            assertEquals(ArchivePhase.PAUSED, status.phase)
            archive.seekTo(ms("2026-10-15T10:40:00Z"), seq = 7)
            assertEquals(2, urls.size)
            assertEquals(ArchivePhase.SEEKING, status.phase)
            controller.calls.clear()
            controller.playing()
            assertEquals(listOf("pause"), controller.calls)
            assertEquals(ArchivePhase.PAUSED, status.phase)
            assertEquals(7L, status.settledSeq)
            assertTrue(controller.flow.value.let { it is PlaybackState.Playing && it.paused })
        }
    }

    @Test
    fun nativeSeekWithoutStateEmissionSettles() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            at(0, 2_160_000)
            controller.playing()
            archive.seekTo(ms("2026-10-15T11:20:00Z"), seq = 4)
            assertEquals(ArchivePhase.SEEKING, status.phase)
            // The position never advances (stuck in buffer, no state change): SEEK_SETTLE_MAX_MS ends it.
            at(1_260_000, 2_160_000)
            advance(4_900)
            assertEquals(ArchivePhase.SEEKING, status.phase)
            advance(250)
            assertEquals(ArchivePhase.PLAYING, status.phase)
            assertEquals(4L, status.settledSeq)
            assertEquals(1, urls.size)
        }
    }

    @Test
    fun archivePauseRebuildsAfterTenMinutes() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            at(300_000, 2_160_000); advance(1_000)
            archive.pause()
            advance(600_000)
            archive.resume()
            assertEquals(2, urls.size)
            assertEquals(ArchivePhase.CONTINUING, status.phase)
            val wall = ms("2026-10-15T10:59:00Z") + 300_000
            assertEquals(floorMin(wall), lastTune.request.requestStart.toEpochMilli())
        }
    }

    @Test
    fun shortArchivePauseIsAControllerResume() = runTest {
        val rig = Rig(this)
        with(rig) {
            startP1()
            controller.playing()
            archive.pause()
            advance(30_000)
            archive.resume()
            assertEquals(1, urls.size)
            assertEquals(ArchivePhase.PLAYING, status.phase)
            assertTrue(controller.calls.containsAll(listOf("pause", "resume")))
        }
    }

    // ------------------------------------------------------------------ correction literals, start over, sync failures

    /** EPG-anchored requests pass raw programme times; the builder subtracts the correction (padding 60/300). */
    @Test
    fun correctionShiftsEpgAnchoredRequests() = runTest {
        // +2 h: P1 (11:00–11:30 EPG) is 09:00–09:30 on the panel; padded 08:59, closed, 36 min.
        with(Rig(this, channelCorrH = 2.0)) {
            assertEquals(ArchiveStart.Started, startP1())
            assertEquals(xcPath(36, ms("2026-10-15T08:59:00Z")), urls.single())
            assertEquals(ms("2026-10-15T10:59:00Z"), status.wallMs) // programme frame
        }
        // −1.5 h (playlist value): 09:30–10:00 EPG is 11:00–11:30 on the panel; padded 10:59.
        with(Rig(this, playlistCorrH = -1.5)) {
            val early = info("2026-10-15T09:30:00Z", "2026-10-15T10:00:00Z")
            assertEquals(ArchiveStart.Started, archive.playProgramme(channel, early, early.rawStartMin + 30))
            assertEquals(xcPath(36, ms("2026-10-15T10:59:00Z")), urls.single())
            assertEquals(ms("2026-10-15T09:29:00Z"), status.wallMs)
            // P1 starts after the panel's now in the programme frame: refused, nothing tuned.
            assertEquals(ArchiveStart.Failed(ArchiveFailure.NO_PROGRAMME), archive.playProgramme(channel, p1, p1.rawStartMin + 30))
            assertEquals(1, urls.size)
        }
    }

    @Test
    fun startOverPlaysTheProgrammeOnNowOpenEnded() = runTest {
        val rig = Rig(this)
        with(rig) {
            val now = info("2026-10-15T11:45:00Z", "2026-10-15T12:15:00Z", "NOW")
            feed.put(channel, listOf(p1, now))
            controller.liveOn(channel.identity)
            var r: ArchiveStart? = null
            backgroundScope.launch(dispatcher) { r = archive.startOver(channel) }
            runCurrent()
            assertEquals(ArchiveStart.Started, r)
            val s = ms("2026-10-15T11:44:00Z")
            assertEquals(xcPath(ceilMin(base / 1000 * 1000 - s), s), urls.single())
            assertNull(lastTune.request.requestEnd)
            assertTrue(status.timeShift)
            assertEquals("NOW", status.programme?.title)
        }
        val empty = Rig(this)
        with(empty) {
            var r: ArchiveStart? = null
            backgroundScope.launch(dispatcher) { r = archive.startOver(channel) }
            runCurrent()
            assertEquals(ArchiveStart.Failed(ArchiveFailure.NO_PROGRAMME), r)
            assertTrue(urls.isEmpty())
            assertEquals(ArchivePhase.IDLE, status.phase)
        }
    }

    @Test
    fun negativeCorrectionRefusesTheProgrammeOnNow() = runTest {
        val rig = Rig(this, channelCorrH = -1.5)
        with(rig) {
            feed.put(channel, listOf(info("2026-10-15T11:45:00Z", "2026-10-15T12:15:00Z")))
            var r: ArchiveStart? = null
            backgroundScope.launch(dispatcher) { r = archive.startOver(channel) }
            runCurrent()
            assertEquals(ArchiveStart.Failed(ArchiveFailure.NO_PROGRAMME), r)
            assertTrue(urls.isEmpty())
        }
    }

    @Test
    fun synchronousFailures() = runTest {
        suspend fun Rig.play(i: ProgrammeInfo = p1): ArchiveStart = archive.playProgramme(channel, i, i.rawStartMin + 30)
        with(Rig(this)) {
            targetResult = CatchupTargetResult.ChannelGone
            assertEquals(ArchiveStart.Failed(ArchiveFailure.CHANNEL_GONE), play())
        }
        with(Rig(this)) {
            targetResult = CatchupTargetResult.CredentialsUnavailable
            assertEquals(ArchiveStart.Failed(ArchiveFailure.CREDENTIALS), play())
        }
        with(Rig(this)) {
            targetResult = CatchupTargetResult.Found((targetResult as CatchupTargetResult.Found).target.copy(catchup = Catchup.NONE))
            assertEquals(ArchiveStart.Failed(ArchiveFailure.NO_ARCHIVE), play())
        }
        with(Rig(this)) {
            timeBase = null
            assertEquals(ArchiveStart.Failed(ArchiveFailure.CHANNEL_GONE), play())
        }
        with(Rig(this)) {
            val old = info("2026-10-12T10:00:00Z", "2026-10-12T10:30:00Z")
            assertEquals(ArchiveStart.Failed(ArchiveFailure.OUTSIDE_ARCHIVE), play(old))
        }
        with(Rig(this)) {
            targetResult = CatchupTargetResult.Found((targetResult as CatchupTargetResult.Found).target.copy(xtream = null))
            assertEquals(ArchiveStart.Failed(ArchiveFailure.UNSUPPORTED_MODE), play())
            assertTrue(urls.isEmpty())
            assertEquals(ArchivePhase.IDLE, status.phase)
        }
    }
}
