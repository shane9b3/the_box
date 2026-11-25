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
import app.thebox.core.sports.hub.DetailState
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
 * docs/design/m6-sports-hub.md §4.3, §4.4, §10.2: the detail repository's actor under `runTest`, a `MonoClock` on the
 * test scheduler, a scripted fetcher. Covers the rest, cancel on a move, every phase's cadence, the cache, every "no
 * request" condition, failures, staleness and retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailRepositoryImplTest {
    private class Signals(fg: Boolean = true, on: Boolean = true) : SportsSignals {
        override val foreground = MutableStateFlow(fg)
        override val online = MutableStateFlow(on)
    }

    private class Awake(on: Boolean = true) : DeviceAwake {
        val flow = MutableStateFlow(on)
        override val awake: StateFlow<Boolean> = flow
    }

    private class FakeFetcher(private val mono: MonoClock) : DetailFetcher {
        val calls = ArrayList<Pair<String, Long>>()
        val cancelled = ArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null
        var answer: (GameId, Long) -> NhlDetailSource.DetailAnswer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id), now), false) }
        var box: Fetch<BoxScore> = Fetch.Ok(BoxScore(TeamBox(emptyList(), emptyList()), TeamBox(emptyList(), emptyList())), 0L, null, 0)
        var boxCalls = 0
        var boxGate: CompletableDeferred<Unit>? = null

        override suspend fun detail(id: GameId, phaseHint: GamePhase?): NhlDetailSource.DetailAnswer {
            calls += id.id to mono.nowMs()
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += id.id
                throw e
            }
            return answer(id, mono.nowMs())
        }

        /** Thrown once (then cleared) by the next [boxScore] after its gate. */
        var boxThrow: Throwable? = null
        override suspend fun boxScore(id: GameId): Fetch<BoxScore> {
            boxCalls++
            boxGate?.await()
            boxThrow?.let { boxThrow = null; throw it }
            return box
        }
    }

    private class H(
        val repo: GameDetailRepositoryImpl,
        val fetcher: FakeFetcher,
        val store: FakeSettingsStore,
        val signals: Signals,
        val awake: Awake,
    ) {
        val state: DetailState get() = repo.detail.value
        val ready: DetailState.Ready get() = state as DetailState.Ready
    }

    private var wall = 1_795_489_210_000L

    private fun TestScope.harness(prefs: SportsPrefs = SportsPrefs(), signals: Signals = Signals(), awake: Awake = Awake()): H {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsDomain) { prefs }
        store.update(SportsFollowDomain) { SportsFollowDoc(pollPhaseMs = 1_500L) }
        val fetcher = FakeFetcher(mono)
        val repo = GameDetailRepositoryImpl(fetcher, store, awake, signals, backgroundScope, mono, { wall }, Random(3))
        runCurrent()
        return H(repo, fetcher, store, signals, awake)
    }

    private fun game(id: String = "2026020385", phase: GamePhase = GamePhase.LIVE, league: League = League.NHL, media: GameMedia? = null, startUtcMs: Long = wall - 3_600_000L): GameSnapshot =
        SportsFixtures.game(id = id, phase = phase, league = league, startUtcMs = startUtcMs).copy(media = media)

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ---------------------------------------------------------------- the rest and the moves

    @Test fun nflAndChlAreBoardOnlyWithoutARequest() = runTest {
        val h = harness()
        h.repo.show(game(id = "401873049", league = League.NFL))
        settle(5_000)
        assertEquals(DetailState.BoardOnly(GameId(League.NFL, "401873049")), h.state)
        h.repo.show(game(id = "29187", league = League.OHL))
        settle(5_000)
        assertEquals(DetailState.BoardOnly(GameId(League.OHL, "29187")), h.state)
        assertTrue(h.fetcher.calls.isEmpty())
    }

    @Test fun theFetchStartsAfterTheRest() = runTest {
        val h = harness()
        h.repo.show(game())
        runCurrent()
        assertEquals(DetailState.Loading(GameId(League.NHL, "2026020385")), h.state)
        settle(SportsConstants.HUB_FETCH_DWELL_MS - 1)
        assertTrue(h.fetcher.calls.isEmpty())
        settle(1)
        assertEquals(listOf("2026020385" to SportsConstants.HUB_FETCH_DWELL_MS), h.fetcher.calls)
        assertEquals("2026020385", h.ready.detail.id.id)
        assertFalse(h.ready.failing)
        assertFalse(h.ready.stale)
    }

    @Test fun quickMovesMakeOneRequestForTheGameThatRests() = runTest {
        val h = harness()
        for (id in listOf("1", "2", "3", "4")) {
            h.repo.show(game(id = id))
            settle(200)
        }
        settle(SportsConstants.HUB_FETCH_DWELL_MS)
        assertEquals(listOf("4"), h.fetcher.calls.map { it.first })
    }

    /** (Integration) The page's showNow(): it has rested already, so two shows back to back fetch at once (one rest, not two). */
    @Test fun twoShowsBackToBackFetchAtOnce() = runTest {
        val h = harness()
        h.repo.show(game())
        h.repo.show(game())
        runCurrent()
        assertEquals(listOf("2026020385" to 0L), h.fetcher.calls)
    }

    @Test fun showingTheRestingGameAgainSkipsTheRest() = runTest {
        val h = harness()
        h.repo.show(game())
        settle(100)
        h.repo.show(game()) // OK enters the panel
        runCurrent()
        assertEquals(listOf("2026020385" to 100L), h.fetcher.calls)
    }

    @Test fun aMoveCancelsTheInFlightCallAndDropsItsAnswer() = runTest {
        val h = harness()
        h.fetcher.gate = CompletableDeferred()
        h.repo.show(game(id = "1"))
        settle(SportsConstants.HUB_FETCH_DWELL_MS)
        assertEquals(listOf("1"), h.fetcher.calls.map { it.first })
        h.repo.show(game(id = "2"))
        runCurrent()
        assertEquals(listOf("1"), h.fetcher.cancelled)
        h.fetcher.gate = null
        settle(SportsConstants.HUB_FETCH_DWELL_MS)
        assertEquals("2", h.ready.detail.id.id)
    }

    @Test fun nullClosesThePage() = runTest {
        val h = harness()
        h.repo.show(game())
        settle(1_000)
        h.repo.show(null)
        runCurrent()
        assertEquals(DetailState.None, h.state)
        settle(600_000)
        assertEquals(1, h.fetcher.calls.size)
    }

    // ---------------------------------------------------------------- cadence and cache

    @Test fun liveReReadsEveryThirtySeconds() = runTest {
        val h = harness()
        h.repo.show(game(phase = GamePhase.LIVE))
        settle(600 + 95_000)
        val t = h.fetcher.calls.map { it.second }
        assertEquals(listOf(600L, 30_600L, 60_600L, 90_600L), t)
    }

    @Test fun preReReadsEveryFiveMinutesAndACacheHitIsShownWithoutARequest() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id, GamePhase.PRE), now), false) }
        h.repo.show(game(id = "pre", phase = GamePhase.PRE))
        settle(600 + 60_000)
        h.repo.show(game(id = "other", phase = GamePhase.PRE))
        settle(1_000)
        h.repo.show(game(id = "pre", phase = GamePhase.PRE))
        runCurrent()
        assertEquals("pre", h.ready.detail.id.id)
        settle(1_000)
        assertEquals(listOf("pre", "other"), h.fetcher.calls.map { it.first })
        settle(SportsConstants.HUB_DETAIL_IDLE_MS)
        assertEquals(listOf("pre", "other", "pre"), h.fetcher.calls.map { it.first })
        assertEquals(600L + SportsConstants.HUB_DETAIL_IDLE_MS, h.fetcher.calls.last().second)
    }

    @Test fun aBoardThatWentLiveReTimesAPreGameDetail() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id, GamePhase.PRE), now), false) }
        h.repo.show(game(phase = GamePhase.PRE))
        settle(600 + 40_000)
        h.repo.show(game(phase = GamePhase.LIVE))
        runCurrent()
        assertEquals("30 s after the PRE answer: overdue, so at once", 2, h.fetcher.calls.size)
    }

    @Test fun finalOnceWhenTheRecapIsThere() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id, GamePhase.FINAL, recapId = 9L), now), false) }
        h.repo.show(game(phase = GamePhase.FINAL))
        settle(600 + 3_600_000)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun finalWithoutRecapReReadsUntilThreeHoursAfterTheEnd() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false) }
        // Seen live first, then final: the end is when this process saw it turn.
        h.repo.show(game(phase = GamePhase.LIVE))
        settle(600)
        h.repo.show(game(phase = GamePhase.FINAL))
        runCurrent()
        val before = h.fetcher.calls.size
        settle(SportsConstants.HUB_DETAIL_IDLE_MS)
        assertEquals(before + 1, h.fetcher.calls.size)
        wall += SportsConstants.HUB_RECAP_WAIT_MS
        settle(SportsConstants.HUB_DETAIL_IDLE_MS * 3)
        assertEquals("the wait is over", before + 1, h.fetcher.calls.size)
    }

    @Test fun aBoardRecapEndsTheWait() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id, GamePhase.FINAL), now), false) }
        h.repo.show(game(phase = GamePhase.FINAL, media = GameMedia(7L, null, 0), startUtcMs = wall - 3_600_000L))
        settle(600 + SportsConstants.HUB_DETAIL_IDLE_MS * 3)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun postponedOnce() = runTest {
        val h = harness()
        h.repo.show(game(phase = GamePhase.POSTPONED))
        settle(600 + 3_600_000)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun trimKeepsOnlyTheShownGame() = runTest {
        val h = harness()
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id, GamePhase.PRE), now), false) }
        h.repo.show(game(id = "a", phase = GamePhase.PRE))
        settle(700)
        h.repo.show(game(id = "b", phase = GamePhase.PRE))
        settle(700)
        h.repo.trim()
        runCurrent()
        assertEquals("b", h.ready.detail.id.id)
        h.repo.show(game(id = "a", phase = GamePhase.PRE))
        runCurrent()
        assertEquals(DetailState.Loading(GameId(League.NHL, "a")), h.state)
    }

    // ---------------------------------------------------------------- no request (§4.3)

    @Test fun nothingWhileSportsIsOffAsleepInTheBackgroundOrPaused() = runTest {
        val signals = Signals()
        val awake = Awake()
        val h = harness(signals = signals, awake = awake)
        fun check(off: () -> Unit, on: () -> Unit) {
            off()
            h.repo.show(game(id = "g${h.fetcher.calls.size}"))
            settle(3_600_000)
            val n = h.fetcher.calls.size
            settle(3_600_000)
            assertEquals(n, h.fetcher.calls.size)
            on()
            settle(1_000)
            assertEquals(n + 1, h.fetcher.calls.size)
            off()
            settle(3_600_000)
            assertEquals(n + 1, h.fetcher.calls.size)
            on()
            settle(100)
        }
        check({ h.store.update(SportsDomain) { it.copy(enabled = false) } }, { h.store.update(SportsDomain) { it.copy(enabled = true) } })
        check({ awake.flow.value = false }, { awake.flow.value = true })
        check({ signals.foreground.value = false }, { signals.foreground.value = true })
        check({ h.repo.pause(true) }, { h.repo.pause(false) })
    }

    @Test fun offlineWithNothingCachedIsFailedOffline() = runTest {
        val signals = Signals(on = false)
        val h = harness(signals = signals)
        h.repo.show(game())
        settle(60_000)
        assertEquals(DetailState.Failed(GameId(League.NHL, "2026020385"), FailKind.OFFLINE), h.state)
        assertTrue(h.fetcher.calls.isEmpty())
        signals.online.value = true
        settle(10)
        assertEquals(1, h.fetcher.calls.size)
        assertTrue(h.state is DetailState.Ready)
        signals.online.value = false
        runCurrent()
        assertTrue("a cached detail stays, marked failing", h.ready.failing)
        assertEquals(FailKind.OFFLINE, (h.repo.boxScore(GameId(League.NHL, "1")) as Fetch.Failed).kind)
        assertEquals(0, h.fetcher.boxCalls)
    }

    // ---------------------------------------------------------------- failures, staleness, retry

    @Test fun failuresShowTheKindThenTheCachedDetailFailing() = runTest {
        val h = harness()
        h.fetcher.answer = { _, now -> NhlDetailSource.DetailAnswer(Fetch.Failed(FailKind.TIMEOUT, null, now), false) }
        h.repo.show(game())
        settle(600)
        assertEquals(DetailState.Failed(GameId(League.NHL, "2026020385"), FailKind.TIMEOUT), h.state)
        // Back-off 20 s ± 20 %.
        settle(16_000 - 1)
        assertEquals(1, h.fetcher.calls.size)
        settle(9_000)
        assertEquals(2, h.fetcher.calls.size)
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id), now), landingFailed = true) }
        h.repo.retry()
        runCurrent()
        assertEquals(3, h.fetcher.calls.size)
        assertTrue("a failed landing is failing", h.ready.failing)
        h.fetcher.answer = { _, now -> NhlDetailSource.DetailAnswer(Fetch.Failed(FailKind.HTTP_5XX, null, now), false) }
        settle(30_000)
        assertTrue(h.ready.failing)
        assertEquals("2026020385", h.ready.detail.id.id)
    }

    @Test fun refusedIsNotRetriedEarly() = runTest {
        val h = harness()
        h.fetcher.answer = { _, now -> NhlDetailSource.DetailAnswer(Fetch.Failed(FailKind.HTTP_429, 10, now), false) }
        h.repo.show(game())
        settle(600)
        h.repo.retry()
        settle(SportsConstants.BACKOFF_MAX_MS - 1_000)
        assertEquals(1, h.fetcher.calls.size)
        settle(2_000)
        assertEquals(2, h.fetcher.calls.size)
    }

    @Test fun aDetailGoesStaleAtTwiceTheInterval() = runTest {
        val h = harness()
        h.repo.show(game())
        settle(600)
        h.repo.pause(true) // a clip plays: no requests
        settle(60_000)
        assertFalse(h.ready.stale)
        settle(2)
        assertTrue(h.ready.stale)
        h.repo.pause(false)
        runCurrent()
        assertFalse("the refetch lands", h.ready.stale)
    }

    @Test fun notFoundIsAFailure() = runTest {
        val h = harness()
        h.fetcher.answer = { _, now -> NhlDetailSource.DetailAnswer(Fetch.NotFound, false) }
        h.repo.show(game())
        settle(600)
        assertEquals(DetailState.Failed(GameId(League.NHL, "2026020385"), FailKind.HTTP_OTHER), h.state)
    }

    @Test fun boxScoreGoesThroughTheGate() = runTest {
        val h = harness()
        assertTrue(h.repo.boxScore(GameId(League.NHL, "1")) is Fetch.Ok)
        assertEquals(1, h.fetcher.boxCalls)
        h.repo.pause(true)
        runCurrent()
        assertEquals(FailKind.HTTP_OTHER, (h.repo.boxScore(GameId(League.NHL, "1")) as Fetch.Failed).kind)
        assertEquals(1, h.fetcher.boxCalls)
    }

    /**
     * (Sports Hub code review 2026-11-25, L4) One landing failure during a live game keeps the cached goals, stars and
     * leaders (marked failing) instead of emptying them until the next poll.
     */
    @Test fun aFailedLandingKeepsTheCachedLandingFacts() = runTest {
        val h = harness()
        val star = app.thebox.core.sports.hub.Star(1, "Connor McDavid", "McDavid", "EDM", null)
        val leader = app.thebox.core.sports.hub.LeaderPair("goals", app.thebox.core.sports.hub.Leader("A", 9, null), null)
        h.fetcher.answer = { id, now -> NhlDetailSource.DetailAnswer(ok(detail(id).copy(stars = listOf(star), leaders = listOf(leader)), now), false) }
        h.repo.show(game())
        settle(600)
        assertEquals(listOf(star), h.ready.detail.stars)
        assertFalse(h.ready.failing)
        h.fetcher.answer = { id, now ->
            NhlDetailSource.DetailAnswer(ok(detail(id).copy(venue = null, awayScore = null, homeScore = null), now), landingFailed = true)
        }
        settle(30_000)
        assertEquals(2, h.fetcher.calls.size)
        assertEquals("the stars stay", listOf(star), h.ready.detail.stars)
        assertEquals(listOf(leader), h.ready.detail.leaders)
        assertEquals("Rogers Place", h.ready.detail.venue)
        assertEquals("the old landing scores are not passed off as the new answer's", null, h.ready.detail.awayScore)
        assertTrue("still failing", h.ready.failing)
    }

    /** (L6) The sheet opened twice quickly: one box-score request, both callers get its answer. */
    @Test fun twoBoxScoreCallsForOneGameShareOneRequest() = runTest {
        val h = harness()
        h.fetcher.boxGate = CompletableDeferred()
        val id = GameId(League.NHL, "1")
        val a = async { h.repo.boxScore(id) }
        val b = async { h.repo.boxScore(id) }
        runCurrent()
        h.fetcher.boxGate!!.complete(Unit)
        assertTrue(a.await() is Fetch.Ok)
        assertTrue(b.await() is Fetch.Ok)
        assertEquals(1, h.fetcher.boxCalls)
        // Done: the next call is a new request.
        h.repo.boxScore(id)
        assertEquals(2, h.fetcher.boxCalls)
    }

    /** (L6) The first caller cancelled (the sheet closed): a waiting caller makes the request itself. */
    @Test fun aWaiterWhoseOwnerWasCancelledFetchesItself() = runTest {
        val h = harness()
        h.fetcher.boxGate = CompletableDeferred()
        val id = GameId(League.NHL, "1")
        val a = async { h.repo.boxScore(id) }
        runCurrent()
        val b = async { h.repo.boxScore(id) }
        runCurrent()
        a.cancel()
        runCurrent()
        h.fetcher.boxGate!!.complete(Unit)
        assertTrue(b.await() is Fetch.Ok)
        assertEquals(2, h.fetcher.boxCalls)
    }

    /** (Verification review L-b) The owner's call throws something fetchBox does not catch: a waiter is released, never stuck. */
    @Test fun aWaiterIsReleasedWhenTheOwnersCallThrows() = runTest {
        val h = harness()
        h.fetcher.boxGate = CompletableDeferred()
        h.fetcher.boxThrow = java.io.IOException("socket closed")
        val id = GameId(League.NHL, "1")
        val a = async { runCatching { h.repo.boxScore(id) } }
        runCurrent()
        val b = async { h.repo.boxScore(id) }
        runCurrent()
        h.fetcher.boxGate!!.complete(Unit)
        assertTrue("the owner sees its own failure", a.await().exceptionOrNull() is java.io.IOException)
        val answer = kotlinx.coroutines.withTimeoutOrNull(5_000) { b.await() }
        assertTrue("the waiter fetched for itself: $answer", answer is Fetch.Ok)
        assertEquals(2, h.fetcher.boxCalls)
    }

    /** (L7) The ids seen unfinished are bounded without a trim. */
    @Test fun seenUnfinishedIsBounded() = runTest {
        val h = harness()
        for (i in 0 until SportsConstants.HUB_DETAIL_LRU * 3) {
            h.repo.show(game(id = "20260200$i"))
            runCurrent()
        }
        assertTrue("${h.repo.seenUnfinishedCount}", h.repo.seenUnfinishedCount <= SportsConstants.HUB_DETAIL_LRU)
    }

    companion object {
        fun ok(d: GameDetail, mono: Long): Fetch.Ok<GameDetail> = Fetch.Ok(d, mono, null, 0)

        fun detail(id: GameId, phase: GamePhase = GamePhase.LIVE, recapId: Long? = null) = GameDetail(
            id = id, fetchedPhase = phase, serverDateMs = null, awayScore = 1, homeScore = 2, venue = "Rogers Place",
            gameStats = emptyList(), season = null, periods = emptyList(), goals = emptyList(), stars = emptyList(),
            leaders = emptyList(), goalies = null, series = null, recapId = recapId, condensedId = null, broadcasts = emptyList(),
        )
    }
}
