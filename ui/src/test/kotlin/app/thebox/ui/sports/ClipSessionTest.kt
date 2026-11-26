package app.thebox.ui.sports

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.ClipStream
import app.thebox.core.sports.hub.FakeClipResolver
import app.thebox.core.sports.hub.ResumePoint
import app.thebox.player.api.ClipTune
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.StopReason
import app.thebox.player.api.TextTrackOption
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.ui.shelltest.FakePlaybackController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.6, §10.2, A-B2, R-N1, R-N3; WP-5) `ClipSession` against a recording
 * controller, the archive fake and a scripted resolver: the snapshot (live, provider catch-up with the playhead re-read
 * after a 10 s lookup, local time-shift, nothing), ownership by tune id (an intent tune, a lifecycle stop), Back and a
 * second OK during the lookup, the expired-URL re-resolve, the queue, and `close()` ending without a resume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipSessionTest {
    private val game = GameId(League.NHL, "2026020382")
    private val x = GuideFixtures.identity(1)
    private val y = GuideFixtures.identity(2)
    private fun goal(id: Long) = ClipRef(id, ClipKind.GOAL, game)
    private fun recap(id: Long) = ClipRef(id, ClipKind.RECAP, game)

    private class Controller(val base: FakePlaybackController = FakePlaybackController()) : PlaybackController by base {
        val clips = ArrayList<ClipTune>()
        var nextTuneId = 100L
        var positionMs = 0L
        var durationMs: Long? = 46_000L
        val seeks = ArrayList<Long>()
        val textSelections = ArrayList<TrackRef?>()
        override val state get() = base.state
        override val tracks get() = base.tracks
        /** The app is away: [playClip] answers [PlaybackController.CLIP_REFUSED_AWAY], as the real controller does. */
        var away = false
        override fun playClip(tune: ClipTune): Long {
            clips += tune
            if (away) return PlaybackController.CLIP_REFUSED_AWAY
            val id = nextTuneId
            nextTuneId += 10
            base.state.value = PlaybackState.Connecting(clipNp(id, tune.title), app.thebox.player.api.ConnectPhase.CONNECTING, 0, 0)
            return id
        }
        override fun position() = PositionSnapshot(positionMs, 0, durationMs, true, null)
        override fun seekTo(positionMs: Long) { seeks += positionMs; base.seekTo(positionMs) }
        override fun selectTextTrack(track: TrackRef?, remember: Boolean) { textSelections += track }
        fun clipNp(id: Long, title: String = "clip") = NowPlaying(id, null, title, isArchive = false, format = StreamFormat.HLS, isClip = true)
    }

    /** Answers `stream()` after [delayMs] (the resolve's network time), or waits for [gate]. */
    private class SlowResolver(val delayMs: Long = 0L) : ClipResolver {
        val calls = ArrayList<ClipRef>()
        /** (NFL hub P3) The `fresh` flag of each call, in the order of [calls]. */
        val fresh = ArrayList<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        val answers = HashMap<Long, ClipResult<Pair<ClipInfo, ClipStream>>>()
        override suspend fun info(ref: ClipRef): ClipResult<ClipInfo> = ClipResult.Ok(ClipInfo(ref, "Title ${ref.videoId}", null, 46_000, null, null))
        override suspend fun stream(ref: ClipRef, fresh: Boolean): ClipResult<Pair<ClipInfo, ClipStream>> {
            calls += ref
            this.fresh += fresh
            gate?.await()
            if (delayMs > 0) delay(delayMs)
            return answers[ref.videoId] ?: FakeClipResolver.okStream(ref)
        }
    }

    private class Rig(scope: TestScope, val resolver: ClipResolver = SlowResolver(), archiveStatus: ArchiveStatus = ArchiveStatus()) {
        val controller = Controller()
        val archive = FakeArchivePlayback(archiveStatus)
        var now = 1_000_000L
        val session = ClipSession(controller, archive, resolver, scope.backgroundScope, nowMs = { now })
        val effects = ArrayList<SportsEffect>()
        /** `ui.showing` at the moment each effect arrived (A-S2: false first). */
        val showingAtEffect = ArrayList<Boolean>()
        val failures = ArrayList<ClipSession.Failure>()
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                session.effects.collect { effects += it; showingAtEffect += session.ui.value.showing }
            }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { session.failures.collect { failures += it } }
        }
        fun live(id: ChannelIdentityAlias, tuneId: Long = 5) {
            controller.base.state.value = PlaybackState.Playing(NowPlaying(tuneId, id, "Live", isArchive = false, format = StreamFormat.TS))
        }
    }

    @Test
    fun liveSnapshotResumesTheChannelAndHidesTheLayerFirst() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        assertEquals(1, rig.controller.clips.size)
        assertTrue(rig.session.ui.value.showing)
        assertEquals(100L, rig.session.ui.value.tuneId)
        assertEquals(recap(7), rig.session.lastPlayed.value)
        rig.session.back()
        runCurrent()
        assertEquals(listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Live(x), ownerTuneId = 100)), rig.effects)
        assertEquals(listOf(false), rig.showingAtEffect)
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
        assertFalse("retry() is never used on a clip", "retry" in rig.controller.base.calls)
    }

    @Test
    fun providerCatchUpSnapshotRereadsThePlayheadAfterA10sLookup() = runTest {
        val ch = GuideFixtures.channel(3, archiveDays = 3)
        val prog = GuideFixtures.info(1_000, 1_090, shiftMin = 30)
        val rig = Rig(
            this, SlowResolver(delayMs = 10_000),
            ArchiveStatus(
                phase = ArchivePhase.PLAYING, channel = ch, programme = prog, wallMs = 60_000_000L, atMs = 1_000_000L,
                advancing = true, timeShift = true, source = TimelineSource.PROVIDER, ownedTuneId = 40,
            ),
        )
        rig.controller.base.state.value = PlaybackState.Playing(NowPlaying(41, ch.identity, "Catch-up", isArchive = true, format = StreamFormat.TS))
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.now += 10_000 // the catch-up keeps playing during the lookup
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, rig.controller.clips.size)
        rig.session.back()
        runCurrent()
        val point = (rig.effects.single() as SportsEffect.Resume).point as ResumePoint.Archive
        assertEquals(ch, point.channel)
        assertEquals(prog, point.programme)
        assertEquals("rawEndMin = rawStart + (end − start)", 970 + 90, point.rawEndMin)
        assertTrue(point.timeShift)
        assertEquals("wallMs + (now − atMs) at playClip, not before the lookup", 60_010_000L, point.playheadMs)
    }

    @Test
    fun localTimeShiftResumesAtLive() = runTest {
        val ch = GuideFixtures.channel(1)
        val rig = Rig(this, archiveStatus = ArchiveStatus(phase = ArchivePhase.PLAYING, channel = ch, programme = GuideFixtures.info(0, 60), source = TimelineSource.LOCAL))
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.session.back()
        runCurrent()
        assertEquals(listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Live(x), ownerTuneId = 100)), rig.effects)
    }

    @Test
    fun nothingTunedResumesNothing() = runTest {
        val rig = Rig(this)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.session.back()
        runCurrent()
        assertEquals(listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Nothing, ownerTuneId = 100)), rig.effects)
    }

    @Test
    fun anIntentTuneMidClipEndsTheSessionWithoutResuming() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100))
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Connecting(NowPlaying(111, y, "Y", false, StreamFormat.TS), app.thebox.player.api.ConnectPhase.CONNECTING, 0, 0)
        runCurrent()
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
        assertFalse(rig.session.ui.value.showing)
        rig.session.back()
        runCurrent()
        assertTrue("no resume over the user's own tune", rig.effects.isEmpty())
    }

    @Test
    fun aLifecycleStopMidClipEndsWithoutResuming() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Stopped(rig.controller.clipNp(100), StopReason.BACKGROUND, resumable = false)
        runCurrent()
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
        assertFalse(rig.session.ui.value.showing)
        assertTrue(rig.effects.isEmpty())
    }

    /** (Sports Hub code review 2026-11-25, H1) Home or standby during the lookup: no playClip, Idle, no resume. */
    @Test
    fun homeDuringTheLookupNeverPlaysAndResumesNothing() = runTest {
        val resolver = SlowResolver().apply { gate = CompletableDeferred() }
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(goal(1), goal(2)), 0)
        runCurrent()
        assertTrue(rig.session.state.value is ClipSession.State.Resolving)
        rig.controller.base.state.value = PlaybackState.Stopped(
            NowPlaying(5, x, "Live", isArchive = false, format = StreamFormat.TS), StopReason.BACKGROUND, resumable = true,
        )
        runCurrent()
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
        resolver.gate!!.complete(Unit)
        runCurrent()
        assertTrue("no playClip after Home", rig.controller.clips.isEmpty())
        assertTrue("no resume: the controller resumes the live channel itself on return", rig.effects.isEmpty())
        assertTrue("no toast", rig.failures.isEmpty())
    }

    /** (H1) The stop lands in the same frame as the answer, before the collector ran: still nothing plays. */
    @Test
    fun aStandbyStopNotYetCollectedStillBlocksThePlay() = runTest {
        val resolver = SlowResolver().apply { gate = CompletableDeferred() }
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Stopped(
            NowPlaying(5, x, "Live", isArchive = false, format = StreamFormat.TS), StopReason.STANDBY, resumable = true,
        )
        resolver.gate!!.complete(Unit)
        runCurrent()
        assertTrue(rig.controller.clips.isEmpty())
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
        assertTrue(rig.effects.isEmpty())
    }

    /** (H1) A lifecycle stop that was already there when OK was pressed (back from standby, no auto-resume) plays. */
    @Test
    fun anEarlierLifecycleStopDoesNotBlockAClip() = runTest {
        val rig = Rig(this)
        rig.controller.base.state.value = PlaybackState.Stopped(
            NowPlaying(5, x, "Live", isArchive = false, format = StreamFormat.TS), StopReason.STANDBY, resumable = true,
        )
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        assertEquals(1, rig.controller.clips.size)
        assertTrue(rig.session.state.value is ClipSession.State.Playing)
    }

    @Test
    fun backDuringTheLookupNeverPlaysAndResumesNothing() = runTest {
        val resolver = SlowResolver().apply { gate = CompletableDeferred() }
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        assertTrue(rig.session.state.value is ClipSession.State.Resolving)
        rig.session.back()
        resolver.gate!!.complete(Unit)
        runCurrent()
        assertTrue("no playClip after Back", rig.controller.clips.isEmpty())
        assertTrue("the live channel was never interrupted", rig.effects.isEmpty())
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
    }

    @Test
    fun aSecondOkDuringTheLookupPlaysOnlyTheSecondClip() = runTest {
        val rig = Rig(this, SlowResolver(delayMs = 5_000))
        rig.live(x)
        rig.session.play(listOf(goal(1)), 0)
        runCurrent()
        advanceTimeBy(2_000)
        rig.session.play(listOf(goal(2)), 0)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf("Clip 2"), rig.controller.clips.map { it.title })
        rig.session.back()
        runCurrent()
        assertEquals(listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Live(x), ownerTuneId = 100)), rig.effects)
    }

    @Test
    fun anExpiredLinkIsResolvedOnceMoreAtThePlayheadThenShowsTheCard() = runTest {
        val resolver = SlowResolver()
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.positionMs = 12_000
        rig.controller.base.state.value = PlaybackState.Error(rig.controller.clipNp(100), PlaybackError(PlaybackErrorCode.CLIP_EXPIRED))
        runCurrent()
        assertEquals(2, resolver.calls.size)
        assertEquals(12_000L, rig.controller.clips.last().startMs)
        assertEquals(110L, rig.session.ui.value.tuneId)
        rig.controller.base.state.value = PlaybackState.Error(rig.controller.clipNp(110), PlaybackError(PlaybackErrorCode.CLIP_EXPIRED))
        runCurrent()
        assertEquals("one re-resolve per clip", 2, resolver.calls.size)
        assertTrue(rig.session.state.value is ClipSession.State.Failed)
        assertTrue("the card sits over the clip", rig.session.ui.value.showing)
        // Try again re-resolves (never controller.retry()).
        rig.session.retry()
        runCurrent()
        assertEquals(3, resolver.calls.size)
        assertFalse("retry" in rig.controller.base.calls)
    }

    @Test
    fun otherFailuresShowTheCardAndBackResumes() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Error(rig.controller.clipNp(100), PlaybackError(PlaybackErrorCode.STREAM_INTERRUPTED))
        runCurrent()
        assertTrue(rig.session.state.value is ClipSession.State.Failed)
        rig.session.back()
        runCurrent()
        assertEquals(listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Live(x), ownerTuneId = 100)), rig.effects)
    }

    @Test
    fun theNextGoalAutoPlaysAfterUpNextAndTheQueueEndResumes() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(goal(1), goal(2)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), paused = true, ended = true)
        runCurrent()
        assertEquals("Title 2", (rig.session.state.value as ClipSession.State.Playing).upNext)
        assertEquals(1, rig.controller.clips.size)
        advanceTimeBy(SportsConstants.HUB_UP_NEXT_MS + 1)
        runCurrent()
        assertEquals(2, rig.controller.clips.size)
        assertEquals(goal(2), rig.session.lastPlayed.value)
        assertTrue(rig.effects.isEmpty())
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(110), paused = true, ended = true)
        runCurrent()
        assertEquals("the end of the queue resumes the snapshot", listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Live(x), ownerTuneId = 110)), rig.effects)
    }

    @Test
    fun aRecapEndsTheSessionWithResume() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), ended = true)
        runCurrent()
        assertEquals(1, rig.effects.size)
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
    }

    @Test
    fun aFailedLookupToastsAndLeavesTheChannelAlone() = runTest {
        val resolver = SlowResolver().apply { answers[7] = ClipResult.Geo; answers[8] = ClipResult.Failed(FailKind.BAD_BODY) }
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.session.play(listOf(recap(8)), 0)
        runCurrent()
        assertEquals(listOf(ClipSession.Failure.GEO, ClipSession.Failure.NOT_PLAYABLE), rig.failures)
        assertTrue(rig.controller.clips.isEmpty())
        assertTrue(rig.effects.isEmpty())
    }

    /**
     * (Verification review MED-2) The player refuses the clip because the app is away (Home, a standby grace): no toast,
     * and in a queue no resume (a tune during the standby grace would play in the background).
     */
    @Test
    fun aClipRefusedBecauseTheAppIsAwayEndsQuietly() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(goal(1), goal(2)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), paused = true, ended = true)
        runCurrent()
        rig.controller.away = true
        advanceTimeBy(SportsConstants.HUB_UP_NEXT_MS + 1)
        runCurrent()
        assertEquals("the second clip was asked for and refused", 2, rig.controller.clips.size)
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
        assertFalse(rig.session.ui.value.showing)
        assertTrue("no toast: nothing failed", rig.failures.isEmpty())
        assertTrue("no resume while the app is away", rig.effects.isEmpty())
    }

    /** (Verification review L-a) A wait of more than a minute is not "Try again in a minute". */
    @Test
    fun aLongRetryAfterIsTryAgainLater() = runTest {
        val resolver = SlowResolver().apply {
            answers[7] = ClipResult.Failed(FailKind.HTTP_429, retryAfterS = 600)
            answers[8] = ClipResult.Failed(FailKind.HTTP_429, retryAfterS = 30)
            answers[9] = ClipResult.Failed(FailKind.HTTP_429)
        }
        val rig = Rig(this, resolver)
        rig.live(x)
        for (id in 7L..9L) {
            rig.session.play(listOf(recap(id)), 0)
            runCurrent()
        }
        assertEquals(listOf(ClipSession.Failure.LATER, ClipSession.Failure.UNAVAILABLE, ClipSession.Failure.UNAVAILABLE), rig.failures)
    }

    /** (Verification review L-e) The resume carries the tune that owned the player when the clip ended. */
    @Test
    fun theResumeCarriesTheOwnerAtTheEnd() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), ended = true)
        runCurrent()
        assertEquals(100L, (rig.effects.single() as SportsEffect.Resume).ownerTuneId)
    }

    @Test
    fun closeEndsWithoutResuming() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.session.close()
        runCurrent()
        assertTrue("R-N1: no effect from onCleared", rig.effects.isEmpty())
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
        assertFalse(rig.session.ui.value.showing)
    }

    @Test
    fun closeDuringTheLookupCancelsIt() = runTest {
        val rig = Rig(this, SlowResolver(delayMs = 10_000))
        rig.live(x)
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.session.close()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(rig.controller.clips.isEmpty())
        assertTrue(rig.effects.isEmpty())
    }

    @Test
    fun seekPauseAndCaptionsGoToTheController() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.controller.base.tracks.value = TrackInfo(text = listOf(TextTrackOption(TrackRef(3, "t", 0), "en", null, "captions", selected = false)))
        rig.session.play(listOf(recap(7)), 0)
        runCurrent()
        rig.controller.positionMs = 20_000
        rig.session.seekBy(-SportsConstants.HUB_CLIP_SEEK_MS)
        rig.session.seekBy(60_000)
        assertEquals(listOf(10_000L, 45_000L), rig.controller.seeks)
        rig.session.togglePause()
        assertTrue("pause" in rig.controller.base.calls)
        rig.session.toggleCaptions()
        assertEquals(listOf<TrackRef?>(TrackRef(3, "t", 0)), rig.controller.textSelections)
        assertTrue((rig.session.state.value as ClipSession.State.Playing).captionsOn)
    }

    @Test
    fun noResolverMeansVideosAreUnavailable() = runTest {
        val controller = Controller()
        val s = ClipSession(controller, FakeArchivePlayback(), null, backgroundScope)
        val failures = ArrayList<ClipSession.Failure>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.failures.collect { failures += it } }
        s.play(listOf(recap(7)), 0)
        runCurrent()
        assertEquals(listOf(ClipSession.Failure.UNAVAILABLE), failures)
        assertNull(s.position())
    }

    // ================================================================== (NFL hub P3, §4.2, §4.6; arch m12)

    private val nflGame = GameId(League.NFL, "401873078")
    private fun play(id: Long) = ClipRef(id, ClipKind.PLAY, nflGame)
    private fun highlight(id: Long) = ClipRef(id, ClipKind.RECAP, nflGame)

    @Test
    fun theExpiredLinkReResolveAndTryAgainAreFresh() = runTest {
        val resolver = SlowResolver()
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(highlight(50579688)), 0)
        runCurrent()
        assertEquals("the first lookup uses the catalog", listOf(false), resolver.fresh)
        assertEquals(highlight(50579688), rig.session.lastRequested)
        rig.controller.base.state.value = PlaybackState.Error(rig.controller.clipNp(100), PlaybackError(PlaybackErrorCode.CLIP_EXPIRED))
        runCurrent()
        assertEquals("one fresh re-resolve", listOf(false, true), resolver.fresh)
        rig.controller.base.state.value = PlaybackState.Error(rig.controller.clipNp(110), PlaybackError(PlaybackErrorCode.CLIP_EXPIRED))
        runCurrent()
        assertEquals("never twice for one clip", 2, resolver.calls.size)
        assertTrue(rig.session.state.value is ClipSession.State.Failed)
        rig.session.retry()
        runCurrent()
        assertEquals("Try again is fresh too", listOf(false, true, true), resolver.fresh)
    }

    @Test
    fun anExpiredFirstClipIsTheExpiredToastAndPlaysNothing() = runTest {
        val resolver = SlowResolver().apply { answers[50578630] = ClipResult.Expired }
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(play(50578630), play(50578740)), 0)
        runCurrent()
        assertEquals(listOf(ClipSession.Failure.EXPIRED), rig.failures)
        assertTrue(rig.controller.clips.isEmpty())
        assertTrue("nothing took the player: no resume", rig.effects.isEmpty())
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
    }

    @Test
    fun morePlaysAutoAdvanceWithUpNext() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(play(1), play(2)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), paused = true, ended = true)
        runCurrent()
        assertEquals("Up next applies to PLAY clips", "Title 2", (rig.session.state.value as ClipSession.State.Playing).upNext)
        advanceTimeBy(SportsConstants.HUB_UP_NEXT_MS + 1)
        runCurrent()
        assertEquals(play(2), rig.session.lastPlayed.value)
    }

    @Test
    fun anAutoAdvanceSkipsAnEntryAnsweredExpired() = runTest {
        val resolver = SlowResolver().apply { answers[2] = ClipResult.Expired }
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(play(1), play(2), play(3)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), paused = true, ended = true)
        runCurrent()
        advanceTimeBy(SportsConstants.HUB_UP_NEXT_MS + 1)
        runCurrent()
        assertEquals("2 was skipped, 3 plays", listOf(1L, 2L, 3L), resolver.calls.map { it.videoId })
        assertEquals(2, rig.controller.clips.size)
        assertEquals(play(3), rig.session.lastPlayed.value)
        assertTrue("no toast for a skipped entry", rig.failures.isEmpty())
        assertTrue(rig.effects.isEmpty())
    }

    @Test
    fun anAutoAdvanceSkipsEntriesNoLongerOfferedAndEndsWhenNoneRemain() = runTest {
        val resolver = SlowResolver()
        val rig = Rig(this, resolver)
        rig.live(x)
        val offered = HashSet(listOf(1L, 3L))
        rig.session.play(listOf(play(1), play(2), play(3), play(4)), 0) { it.videoId in offered }
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), paused = true, ended = true)
        runCurrent()
        assertEquals("Up next names the next offered clip", "Title 3", (rig.session.state.value as ClipSession.State.Playing).upNext)
        advanceTimeBy(SportsConstants.HUB_UP_NEXT_MS + 1)
        runCurrent()
        assertEquals(listOf(1L, 3L), resolver.calls.map { it.videoId })
        // 4 expires while 3 plays: the queue ends and the snapshot resumes.
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(110), paused = true, ended = true)
        runCurrent()
        assertEquals(listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Live(x), ownerTuneId = 110)), rig.effects)
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
    }

    @Test
    fun aQueueWhoseRemainingEntriesAllAnswerExpiredEndsWithTheResume() = runTest {
        val resolver = SlowResolver().apply { answers[2] = ClipResult.Expired; answers[3] = ClipResult.Expired }
        val rig = Rig(this, resolver)
        rig.live(x)
        rig.session.play(listOf(play(1), play(2), play(3)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), paused = true, ended = true)
        runCurrent()
        advanceTimeBy(SportsConstants.HUB_UP_NEXT_MS + 1)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), resolver.calls.map { it.videoId })
        assertEquals(listOf<SportsEffect>(SportsEffect.Resume(ResumePoint.Live(x), ownerTuneId = 100)), rig.effects)
        assertEquals(ClipSession.State.Idle, rig.session.state.value)
    }

    @Test
    fun theNflHighlightIsAQueueOfOne() = runTest {
        val rig = Rig(this)
        rig.live(x)
        rig.session.play(listOf(highlight(9), play(2)), 0)
        runCurrent()
        rig.controller.base.state.value = PlaybackState.Playing(rig.controller.clipNp(100), ended = true)
        runCurrent()
        assertEquals(1, rig.controller.clips.size)
        assertEquals(1, rig.effects.size)
    }
}

private typealias ChannelIdentityAlias = app.thebox.core.refresh.ChannelIdentity
