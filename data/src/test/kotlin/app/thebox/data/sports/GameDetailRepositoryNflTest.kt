package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.GameClip
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.TeamBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * (NFL hub P2, docs/design/m6-nfl-hub.md §5.2, §5.5, §8.2 `GameDetailRepositoryImplTest`) The NFL through the same
 * single loop as the NHL, under `runTest` with the virtual clock: the rest, the per-league cadence (pre 30 min, live 60 s
 * unaligned, final 5 min until a highlight is offered and at most 2 h), the live → final switch, back-off winning, the
 * `league:id` keys, the bounded cache, the box score through the loop (slot, one shared parse, failures, closed gate),
 * the shown-game change cancelling, offline. The NHL suite is `GameDetailRepositoryImplTest` (unchanged but for its
 * BoardOnly case, which now covers the CHL only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailRepositoryNflTest {
    private class Signals(fg: Boolean = true, on: Boolean = true) : SportsSignals {
        override val foreground = MutableStateFlow(fg)
        override val online = MutableStateFlow(on)
    }

    private class Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class FakeFetcher(private val mono: MonoClock) : DetailFetcher {
        /** (league:id, mono) of every detail call. */
        val calls = ArrayList<Pair<String, Long>>()
        val cancelled = ArrayList<String>()
        var inFlightNow = 0
        var maxInFlight = 0
        var gate: CompletableDeferred<Unit>? = null
        var answer: (GameId, Long) -> DetailAnswer = { id, now -> DetailAnswer(ok(detail(id), now), false, okBox(now)) }
        var boxCalls = 0

        override suspend fun detail(id: GameId, phaseHint: GamePhase?): DetailAnswer {
            calls += "${id.league}:${id.id}" to mono.nowMs()
            inFlightNow++
            maxInFlight = maxOf(maxInFlight, inFlightNow)
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += id.id
                throw e
            } finally {
                inFlightNow--
            }
            return answer(id, mono.nowMs())
        }

        override suspend fun boxScore(id: GameId): Fetch<BoxScore> {
            boxCalls++
            return okBox(mono.nowMs())
        }
    }

    private class H(val repo: GameDetailRepositoryImpl, val fetcher: FakeFetcher, val store: FakeSettingsStore, val signals: Signals) {
        val state: DetailState get() = repo.detail.value
        val ready: DetailState.Ready get() = state as DetailState.Ready
    }

    private var wall = 1_795_489_210_000L
    private var trims = 0

    private fun TestScope.harness(signals: Signals = Signals()): H {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsDomain) { SportsPrefs() }
        store.update(SportsFollowDomain) { SportsFollowDoc(pollPhaseMs = 1_500L) }
        val fetcher = FakeFetcher(mono)
        val repo = GameDetailRepositoryImpl(fetcher, store, Awake(), signals, backgroundScope, mono, { wall }, Random(3), onTrim = { trims++ })
        runCurrent()
        return H(repo, fetcher, store, signals)
    }

    private fun nfl(
        id: String = "401873078",
        phase: GamePhase = GamePhase.LIVE,
        media: GameMedia? = null,
        startUtcMs: Long = wall - 3_600_000L,
    ): GameSnapshot = SportsFixtures.game(id = id, phase = phase, league = League.NFL, startUtcMs = startUtcMs).copy(media = media)

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun times(h: H) = h.fetcher.calls.map { it.second }

    // ---------------------------------------------------------------- routing and the rest

    @Test fun nflIsFetchedAfterTheRest() = runTest {
        val h = harness()
        h.repo.show(nfl())
        runCurrent()
        assertEquals(DetailState.Loading(GameId(League.NFL, "401873078")), h.state)
        settle(SportsConstants.HUB_FETCH_DWELL_MS - 1)
        assertTrue(h.fetcher.calls.isEmpty())
        settle(1)
        assertEquals(listOf("NFL:401873078" to SportsConstants.HUB_FETCH_DWELL_MS), h.fetcher.calls)
        assertEquals(League.NFL, h.ready.detail.id.league)
    }

    @Test fun chlStaysBoardOnly() = runTest {
        val h = harness()
        h.repo.show(SportsFixtures.game(id = "29187", league = League.OHL))
        settle(5_000)
        assertEquals(DetailState.BoardOnly(GameId(League.OHL, "29187")), h.state)
        assertTrue(h.fetcher.calls.isEmpty())
        assertEquals(FailKind.HTTP_OTHER, (h.repo.boxScore(GameId(League.OHL, "29187")) as Fetch.Failed).kind)
    }

    // ---------------------------------------------------------------- cadence (§5.2)

    @Test fun liveReReadsEverySixtySecondsNotBucketAligned() = runTest {
        val h = harness()
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600 + 185_000)
        assertEquals(listOf(600L, 60_600L, 120_600L, 180_600L), times(h))
    }

    @Test fun bothBreaksKeepTheSixtySecondLoop() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.INTERMISSION), now), false, okBox(now)) }
        h.repo.show(nfl(phase = GamePhase.INTERMISSION))
        settle(600 + 125_000)
        assertEquals(listOf(600L, 60_600L, 120_600L), times(h))
    }

    @Test fun preReReadsEveryThirtyMinutes() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.PRE), now), false, okBox(now)) }
        h.repo.show(nfl(phase = GamePhase.PRE, startUtcMs = wall + 7_200_000L))
        settle(600 + SportsConstants.HUB_NFL_PRE_MS - 1)
        assertEquals(1, h.fetcher.calls.size)
        settle(1)
        assertEquals(listOf(600L, 600L + SportsConstants.HUB_NFL_PRE_MS), times(h))
        assertFalse(h.ready.stale)
    }

    @Test fun finalReReadsEveryFiveMinutesUntilAHighlightIsOffered() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false, okBox(now)) }
        // Seen live, then final: the 2 h wait runs from the moment this process saw the end.
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600)
        h.repo.show(nfl(phase = GamePhase.FINAL))
        runCurrent()
        val n0 = h.fetcher.calls.size
        settle(SportsConstants.HUB_DETAIL_IDLE_MS)
        assertEquals(n0 + 1, h.fetcher.calls.size)
        // The highlight appears (offered): no more reads.
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL, clips = listOf(highlight(wall + 86_400_000L))), now), false, okBox(now)) }
        settle(SportsConstants.HUB_DETAIL_IDLE_MS)
        val n1 = h.fetcher.calls.size
        assertEquals(n0 + 2, n1)
        settle(SportsConstants.HUB_DETAIL_IDLE_MS * 4)
        assertEquals("an offered highlight ends the wait", n1, h.fetcher.calls.size)
    }

    @Test fun anExpiredHighlightDoesNotEndTheWait() = runTest {
        val h = harness()
        // The detail lists a highlight past its margin, and the board's highlight has expired too: still "not posted".
        h.fetcher.answer = { id, now ->
            DetailAnswer(ok(detail(id, GamePhase.FINAL, clips = listOf(highlight(wall + SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS))), now), false, okBox(now))
        }
        val media = GameMedia(recapId = 7L, condensedId = null, goalClips = 0, recapExpiresMs = wall - 1)
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600)
        h.repo.show(nfl(phase = GamePhase.FINAL, media = media))
        runCurrent()
        val n0 = h.fetcher.calls.size
        settle(SportsConstants.HUB_DETAIL_IDLE_MS * 2)
        assertEquals(n0 + 2, h.fetcher.calls.size)
    }

    @Test fun anOfferedBoardHighlightEndsTheWait() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false, okBox(now)) }
        val media = GameMedia(recapId = 7L, condensedId = null, goalClips = 0, recapExpiresMs = wall + 86_400_000L)
        h.repo.show(nfl(phase = GamePhase.FINAL, media = media, startUtcMs = wall - 3_600_000L))
        settle(600 + SportsConstants.HUB_DETAIL_IDLE_MS * 3)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun theFinalWaitEndsTwoHoursAfterTheEnd() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false, okBox(now)) }
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600)
        h.repo.show(nfl(phase = GamePhase.FINAL))
        runCurrent()
        val n0 = h.fetcher.calls.size
        wall += SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS
        settle(SportsConstants.HUB_DETAIL_IDLE_MS * 3)
        assertEquals("the wait is over", n0, h.fetcher.calls.size)
    }

    /** A game first seen final is "ended" at start + 3 h 15 min (the NFL's length), not the NHL's 2 h 30 min. */
    @Test fun aGameFirstSeenFinalUsesTheNflGameLength() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false, okBox(now)) }
        // Started 4 h 30 min ago: NHL-length would say it ended 2 h ago (no wait); NFL-length says 1 h 15 min (waiting).
        h.repo.show(nfl(phase = GamePhase.FINAL, startUtcMs = wall - 16_200_000L))
        settle(600 + SportsConstants.HUB_DETAIL_IDLE_MS)
        assertEquals(2, h.fetcher.calls.size)
    }

    /**
     * LIVE → FINAL on the board: the effective phase is FINAL at once, and P1's phase-change rule reads the final detail
     * at once, no earlier than 15 s after the last answer (integration §18 tightened P2 note 15's loose bound), then
     * every 5 min while no highlight.
     */
    @Test fun liveToFinalSwitchesTheCadence() = runTest {
        val h = harness()
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600 + 30_000)
        assertEquals(1, h.fetcher.calls.size)
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false, okBox(now)) }
        h.repo.show(nfl(phase = GamePhase.FINAL))
        runCurrent()
        assertEquals("read at once (30 s after the live answer, past the 15 s floor)", listOf(600L, 30_600L), times(h))
        settle(SportsConstants.HUB_DETAIL_IDLE_MS - 30_000)
        assertEquals("the final read came", 2, h.fetcher.calls.size)
        assertEquals(GamePhase.FINAL, h.ready.detail.fetchedPhase)
        val at = h.fetcher.calls.last().second
        settle(SportsConstants.HUB_DETAIL_IDLE_MS)
        assertEquals(at + SportsConstants.HUB_DETAIL_IDLE_MS, h.fetcher.calls.last().second)
    }

    /** The floor itself: a board FINAL 5 s after the live answer is read at 15 s, not at once and not 5 min later. */
    @Test fun aPhaseChangeSoonAfterAnAnswerWaitsForTheFloor() = runTest {
        val h = harness()
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600 + 5_000)
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false, okBox(now)) }
        h.repo.show(nfl(phase = GamePhase.FINAL))
        settle(SportsConstants.HUB_NFL_REFETCH_FLOOR_MS)
        assertEquals(listOf(600L, 600L + SportsConstants.HUB_NFL_REFETCH_FLOOR_MS), times(h))
    }

    /**
     * (Integration §18, P1 note 1's residual risk) ESPN's summary header lags the board's FINAL: the live answer keeps
     * coming back. The 15 s floor is used `HUB_NFL_PHASE_LAG_TRIES` times, then the final's 5 min cadence; it never polls
     * every 15 s indefinitely. When the header catches up, the normal final rules apply.
     */
    @Test fun aSummaryLaggingTheBoardsFinalIsCappedThenTheFinalCadence() = runTest {
        val h = harness()
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600 + 30_000)
        h.repo.show(nfl(phase = GamePhase.FINAL)) // the fetcher still answers LIVE
        settle(10 * 60_000L)
        val floor = SportsConstants.HUB_NFL_REFETCH_FLOOR_MS
        val idle = SportsConstants.HUB_DETAIL_IDLE_MS
        val t = times(h)
        assertEquals(listOf(600L, 30_600L, 30_600L + floor, 30_600L + 2 * floor, 30_600L + 2 * floor + idle), t.take(5))
        assertTrue("then every 5 min: ${t}", t.zipWithNext().drop(3).all { (a, b) -> b - a == idle })
        // The header catches up: final, no highlight, ended just now → the final wait (5 min), still no 15 s loop.
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false, okBox(now)) }
        settle(idle)
        assertEquals(GamePhase.FINAL, h.ready.detail.fetchedPhase)
        val caught = h.fetcher.calls.size
        val last = h.fetcher.calls.last().second
        settle(last + idle - testScheduler.currentTime - 1)
        assertEquals(caught, h.fetcher.calls.size)
        settle(1)
        assertEquals(caught + 1, h.fetcher.calls.size)
    }

    @Test fun backOffWinsOverThePhaseChangeAndTheBox() = runTest {
        val h = harness()
        h.fetcher.answer = { _, now -> DetailAnswer(Fetch.Failed(FailKind.HTTP_429, 10, now), false) }
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600)
        assertEquals(DetailState.Failed(GameId(League.NFL, "401873078"), FailKind.HTTP_429), h.state)
        h.repo.show(nfl(phase = GamePhase.FINAL)) // a phase change: still the 429 floor
        runCurrent()
        val box = h.repo.boxScore(GameId(League.NFL, "401873078"))
        assertEquals("the box does not force a request while backing off", FailKind.HTTP_429, (box as Fetch.Failed).kind)
        settle(SportsConstants.BACKOFF_MAX_MS - 1_000)
        assertEquals(1, h.fetcher.calls.size)
        settle(2_000)
        assertEquals(2, h.fetcher.calls.size)
    }

    // ---------------------------------------------------------------- keys and the cache (arch m8)

    @Test fun theSameNumericIdInNhlAndNflIsKeptApart() = runTest {
        val h = harness()
        h.repo.show(SportsFixtures.game(id = "123", league = League.NHL, phase = GamePhase.PRE))
        settle(700)
        assertEquals(League.NHL, h.ready.detail.id.league)
        h.repo.show(nfl(id = "123", phase = GamePhase.PRE))
        runCurrent()
        assertEquals("no NHL cache hit for the NFL id", DetailState.Loading(GameId(League.NFL, "123")), h.state)
        settle(700)
        assertEquals(listOf("NHL:123", "NFL:123"), h.fetcher.calls.map { it.first })
        assertEquals(League.NFL, h.ready.detail.id.league)
        h.repo.show(SportsFixtures.game(id = "123", league = League.NHL, phase = GamePhase.PRE))
        runCurrent()
        assertEquals("the NHL entry is its own", League.NHL, h.ready.detail.id.league)
        // Trim keeps the shown game's own entry only.
        h.repo.trim()
        runCurrent()
        assertEquals(1, trims)
        h.repo.show(nfl(id = "123", phase = GamePhase.PRE))
        runCurrent()
        assertEquals(DetailState.Loading(GameId(League.NFL, "123")), h.state)
    }

    @Test fun theCacheIsBounded() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id, GamePhase.PRE), now), false, okBox(now)) }
        for (i in 0..SportsConstants.HUB_DETAIL_LRU) {
            h.repo.show(nfl(id = "4018730${100 + i}", phase = GamePhase.PRE))
            settle(700)
        }
        h.repo.show(nfl(id = "4018730${100 + SportsConstants.HUB_DETAIL_LRU}", phase = GamePhase.PRE))
        runCurrent()
        assertTrue("the newest is cached", h.state is DetailState.Ready)
        h.repo.show(nfl(id = "4018730100", phase = GamePhase.PRE))
        runCurrent()
        assertEquals("the eldest was evicted", DetailState.Loading(GameId(League.NFL, "4018730100")), h.state)
        assertTrue("${h.repo.seenUnfinishedCount}", h.repo.seenUnfinishedCount <= SportsConstants.HUB_DETAIL_LRU)
    }

    // ---------------------------------------------------------------- moves, offline

    @Test fun aMoveCancelsTheInFlightSummary() = runTest {
        val h = harness()
        h.fetcher.gate = CompletableDeferred()
        h.repo.show(nfl(id = "1"))
        settle(600)
        h.repo.show(nfl(id = "2"))
        runCurrent()
        assertEquals(listOf("1"), h.fetcher.cancelled)
        h.fetcher.gate = null
        settle(600)
        assertEquals("2", h.ready.detail.id.id)
    }

    @Test fun offlineMakesNoRequest() = runTest {
        val signals = Signals(on = false)
        val h = harness(signals)
        h.repo.show(nfl())
        settle(60_000)
        assertEquals(DetailState.Failed(GameId(League.NFL, "401873078"), FailKind.OFFLINE), h.state)
        assertTrue(h.fetcher.calls.isEmpty())
        assertEquals(FailKind.OFFLINE, (h.repo.boxScore(GameId(League.NFL, "401873078")) as Fetch.Failed).kind)
        signals.online.value = true
        settle(10)
        assertEquals(1, h.fetcher.calls.size)
        signals.online.value = false
        runCurrent()
        assertTrue("a cached detail stays, failing", h.ready.failing)
    }

    // ---------------------------------------------------------------- the box score through the loop (§5.5, arch M4)

    @Test fun theBoxIsTheSlotOfTheLastParse() = runTest {
        val h = harness()
        h.repo.show(nfl(phase = GamePhase.FINAL))
        settle(600)
        assertEquals(1, h.fetcher.calls.size)
        val box = h.repo.boxScore(GameId(League.NFL, "401873078"))
        assertTrue(box is Fetch.Ok)
        assertEquals("a slot hit: no request", 1, h.fetcher.calls.size)
        assertEquals("never the separate box read", 0, h.fetcher.boxCalls)
    }

    @Test fun aStaleLiveSlotForcesOneParseSharedWithThePoll() = runTest {
        val h = harness()
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600)
        h.repo.pause(true) // a clip plays; the slot ages past the 60 s interval
        settle(90_000)
        h.repo.pause(false)
        runCurrent()
        assertEquals("the overdue poll fired", 2, h.fetcher.calls.size)
        // Now with the poll held in flight, the sheet asks: it waits for that parse, no second one.
        settle(30_000)
        h.fetcher.gate = CompletableDeferred()
        settle(31_000)
        assertEquals(3, h.fetcher.calls.size)
        val a = async { h.repo.boxScore(GameId(League.NFL, "401873078")) }
        val b = async { h.repo.boxScore(GameId(League.NFL, "401873078")) }
        runCurrent()
        assertEquals(3, h.fetcher.calls.size)
        h.fetcher.gate!!.complete(Unit)
        runCurrent()
        assertTrue(a.await() is Fetch.Ok)
        assertTrue(b.await() is Fetch.Ok)
        assertEquals("one parse at a time, the box included", 1, h.fetcher.maxInFlight)
        assertEquals(3, h.fetcher.calls.size)
        assertEquals(0, h.fetcher.boxCalls)
    }

    @Test fun aMissForcesTheShownGamesNextParse() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id), now), false, box = null) } // a parse without a box
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600)
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id), now), false, okBox(now)) }
        val box = async { h.repo.boxScore(GameId(League.NFL, "401873078")) }
        runCurrent()
        assertTrue(box.await() is Fetch.Ok)
        assertEquals("forced at once (not at the 60 s poll)", listOf(600L, 600L), times(h))
    }

    @Test fun aFailedParseCompletesTheWaiterWithTheFailure() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> DetailAnswer(ok(detail(id), now), false, box = null) }
        h.repo.show(nfl(phase = GamePhase.LIVE))
        settle(600)
        h.fetcher.answer = { _, now -> DetailAnswer(Fetch.Failed(FailKind.HTTP_5XX, null, now), false) }
        h.fetcher.gate = CompletableDeferred()
        val box = async { h.repo.boxScore(GameId(League.NFL, "401873078")) }
        runCurrent()
        assertEquals("the forced parse is in flight", 2, h.fetcher.calls.size)
        h.fetcher.gate!!.complete(Unit)
        runCurrent()
        assertEquals(FailKind.HTTP_5XX, (box.await() as Fetch.Failed).kind)
        assertTrue("the detail is kept, failing", h.ready.failing)
    }

    @Test fun aClosedGateOrAnotherGameIsAnImmediateFailure() = runTest {
        val h = harness()
        h.repo.show(nfl(id = "1", phase = GamePhase.FINAL))
        settle(600)
        assertEquals("not the shown game", FailKind.HTTP_OTHER, (h.repo.boxScore(GameId(League.NFL, "2")) as Fetch.Failed).kind)
        h.repo.pause(true)
        runCurrent()
        assertEquals("a clip plays", FailKind.HTTP_OTHER, (h.repo.boxScore(GameId(League.NFL, "1")) as Fetch.Failed).kind)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun aMoveReleasesTheBoxWaiter() = runTest {
        val h = harness()
        h.fetcher.gate = CompletableDeferred()
        h.repo.show(nfl(id = "1"))
        settle(600)
        val box = async { h.repo.boxScore(GameId(League.NFL, "1")) }
        runCurrent()
        h.repo.show(nfl(id = "2"))
        runCurrent()
        assertEquals(FailKind.HTTP_OTHER, (box.await() as Fetch.Failed).kind)
        assertEquals(listOf("1"), h.fetcher.cancelled)
    }

    companion object {
        fun ok(d: GameDetail, mono: Long): Fetch.Ok<GameDetail> = Fetch.Ok(d, mono, null, 0)
        fun okBox(mono: Long): Fetch.Ok<BoxScore> = Fetch.Ok(BoxScore(TeamBox(emptyList(), emptyList()), TeamBox(emptyList(), emptyList())), mono, null, 0)

        fun highlight(expiresMs: Long) = GameClip(50579688L, ClipKind.RECAP, "Game highlights", 74_000L, null, null, expiresMs, null)

        fun detail(id: GameId, phase: GamePhase = GamePhase.LIVE, clips: List<GameClip> = emptyList()) = GameDetail(
            id = id, fetchedPhase = phase, serverDateMs = null, awayScore = null, homeScore = null, venue = "Northwest Stadium",
            gameStats = emptyList(), season = null, periods = emptyList(), goals = emptyList(), stars = emptyList(),
            leaders = emptyList(), goalies = null, series = null, recapId = null, condensedId = null, broadcasts = emptyList(),
            clips = clips,
        )
    }
}
