package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChlHubFixtures
import app.thebox.core.sports.hub.DetailCadence
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.GameDetail
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
 * (CHL hub P2, docs/design/m6-chl-hub.md §4.1, §4.2, D5, D7, C0 rulings R2, R6, R9) The CHL through the repository's one
 * loop, under `runTest` with the virtual clock and a fake fetcher: with `CHL_HUB_ENABLED` off every CHL game is
 * BoardOnly without a request (as before); on, the pre-game and the final are fetched with the **effective** phase as
 * the hint, live / breaks / postponed make no request, the pre-game re-reads every 30 min, a partial answer is Ready
 * (not failing, `partial`) and re-read after 120 s, the final re-reads every 5 min while the stars are missing for at
 * most 1 h, and the box score comes from the final's answer (never a request before the final).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailRepositoryChlTest {
    private class Signals : SportsSignals {
        override val foreground = MutableStateFlow(true)
        override val online = MutableStateFlow(true)
    }

    private class Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class FakeFetcher(private val mono: MonoClock) : DetailFetcher {
        /** (league:id, hint, mono) of every detail call. */
        val calls = ArrayList<Triple<String, GamePhase?, Long>>()
        var answer: (GameId, GamePhase?, Long) -> DetailAnswer = { id, hint, now -> chlAnswer(id, hint, now) }
        var boxCalls = 0

        override suspend fun detail(id: GameId, phaseHint: GamePhase?): DetailAnswer {
            calls += Triple("${id.league}:${id.id}", phaseHint, mono.nowMs())
            return answer(id, phaseHint, mono.nowMs())
        }

        override suspend fun boxScore(id: GameId): Fetch<BoxScore> {
            boxCalls++
            return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
        }
    }

    private class H(val repo: GameDetailRepositoryImpl, val fetcher: FakeFetcher) {
        val state: DetailState get() = repo.detail.value
        val ready: DetailState.Ready get() = state as DetailState.Ready
        val times: List<Long> get() = fetcher.calls.map { it.third }
        val hints: List<GamePhase?> get() = fetcher.calls.map { it.second }
    }

    private val wallBase = 1_795_813_800_000L
    private var trims = 0

    private fun TestScope.harness(chlHub: Boolean = true): H {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsDomain) { SportsPrefs() }
        store.update(SportsFollowDomain) { SportsFollowDoc(pollPhaseMs = 1_500L) }
        val fetcher = FakeFetcher(mono)
        val repo = GameDetailRepositoryImpl(
            fetcher, store, Awake(), Signals(), backgroundScope, mono, { wallBase + testScheduler.currentTime }, Random(3),
            onTrim = { trims++ }, chlHub = chlHub,
        )
        runCurrent()
        return H(repo, fetcher)
    }

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun ohl(phase: GamePhase, id: String = "29246", startUtcMs: Long = wallBase + 3_600_000L): GameSnapshot =
        SportsFixtures.game(id = id, phase = phase, league = League.OHL, startUtcMs = startUtcMs)

    private val dwell = SportsConstants.HUB_FETCH_DWELL_MS

    // ---------------------------------------------------------------- the switch (D7, R9)

    @Test fun switchOffEveryChlGameIsBoardOnlyWithoutARequest() = runTest {
        // The kill switch's off position, stated explicitly (the build default is on since the integration).
        assertFalse(GameDetailRepositoryImpl.detailed(League.OHL, chlHub = false))
        val h = harness(chlHub = false)
        for (p in GamePhase.entries) {
            for (l in listOf(League.OHL, League.WHL, League.QMJHL)) {
                h.repo.show(SportsFixtures.game(id = "29246", phase = p, league = l))
                settle(5_000)
                assertEquals(DetailState.BoardOnly(GameId(l, "29246")), h.state)
            }
        }
        assertTrue(h.fetcher.calls.isEmpty())
        assertEquals(FailKind.HTTP_OTHER, (h.repo.boxScore(GameId(League.QMJHL, "29246")) as Fetch.Failed).kind)
        assertEquals(0, h.fetcher.boxCalls)
    }

    @Test fun theBuildDefaultIsOnAndTheRepositoryFollowsIt() {
        assertTrue(SportsConstants.CHL_HUB_ENABLED)
        for (l in listOf(League.OHL, League.WHL, League.QMJHL)) assertTrue(GameDetailRepositoryImpl.detailed(l))
    }

    @Test fun switchOnTheLeaguesAreDetailedWithTheChlCadence() {
        for (l in listOf(League.OHL, League.WHL, League.QMJHL)) {
            assertTrue(GameDetailRepositoryImpl.detailed(l, chlHub = true))
            assertFalse(GameDetailRepositoryImpl.detailed(l, chlHub = false))
            assertEquals(DetailCadence.CHL, GameDetailRepositoryImpl.cadence(l))
        }
        assertTrue(GameDetailRepositoryImpl.detailed(League.NHL, chlHub = false))
        assertEquals(DetailCadence.NHL, GameDetailRepositoryImpl.cadence(League.NHL))
        assertEquals(DetailCadence.NFL, GameDetailRepositoryImpl.cadence(League.NFL))
    }

    // ---------------------------------------------------------------- pre-game

    @Test fun preGameIsFetchedAfterTheRestThenEveryThirtyMinutes() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.SCHEDULED))
        runCurrent()
        assertEquals(DetailState.Loading(GameId(League.OHL, "29246")), h.state)
        settle(dwell + 2 * SportsConstants.HUB_NFL_PRE_MS)
        assertEquals(listOf(dwell, dwell + SportsConstants.HUB_NFL_PRE_MS, dwell + 2 * SportsConstants.HUB_NFL_PRE_MS), h.times)
        assertEquals(listOf(GamePhase.SCHEDULED, GamePhase.SCHEDULED, GamePhase.SCHEDULED), h.hints)
        assertFalse(h.ready.failing)
        assertFalse(h.ready.partial)
        assertEquals(GamePhase.SCHEDULED, h.ready.detail.fetchedPhase)
    }

    @Test fun aPartialAnswerIsReadyNotFailingAndReReadAfterTwoMinutes() = runTest {
        val h = harness()
        var partial = true
        h.fetcher.answer = { id, hint, now -> chlAnswer(id, hint, now).copy(partial = partial) }
        h.repo.show(ohl(GamePhase.PRE))
        settle(dwell)
        assertTrue(h.ready.partial)
        assertFalse(h.ready.failing)
        partial = false
        settle(SportsConstants.HUB_CHL_PARTIAL_RETRY_MS)
        assertEquals(listOf(dwell, dwell + SportsConstants.HUB_CHL_PARTIAL_RETRY_MS), h.times)
        assertFalse(h.ready.partial)
        // A full answer: back to the 30 min cadence.
        settle(SportsConstants.HUB_NFL_PRE_MS - 1)
        assertEquals(2, h.fetcher.calls.size)
        settle(1)
        assertEquals(3, h.fetcher.calls.size)
    }

    @Test fun partialAnswersInARowReReadFastAtMostThreeTimesThenThePreGameCadence() = runTest {
        // (Integration) A secondary read that never recovers must not re-read the whole set every 2 min for good.
        val h = harness()
        h.fetcher.answer = { id, hint, now -> chlAnswer(id, hint, now).copy(partial = true) }
        h.repo.show(ohl(GamePhase.SCHEDULED))
        settle(dwell + 3 * SportsConstants.HUB_NFL_PRE_MS)
        val p = SportsConstants.HUB_CHL_PARTIAL_RETRY_MS
        val pre = SportsConstants.HUB_NFL_PRE_MS
        assertEquals(listOf(0L, p, 2 * p, 3 * p, 3 * p + pre, 3 * p + 2 * pre).map { dwell + it }, h.times)
        assertTrue(h.ready.partial)
        assertFalse(h.ready.failing)
        // Another game starts its own count.
        h.repo.show(ohl(GamePhase.SCHEDULED, id = "29247"))
        settle(dwell + p)
        assertEquals(listOf(0L, p).map { h.times[6] + it }, h.times.drop(6))
    }

    /** (CHL hub fix pass, C1) A partial re-read keeps the goalie lines a good earlier read showed and this one lacks. */
    @Test fun aPartialReReadKeepsTheGoalieStatsOfTheEarlierGoodRead() = runTest {
        val h = harness()
        val good = ChlHubFixtures.preOhl29246()
        val stripped = good.goalies!!.let { gp -> gp.copy(home = gp.home.map { it.copy(record = null, gaa = null, svPct = null) }, away = emptyList()) }
        var partial = false
        h.fetcher.answer = { id, hint, now ->
            if (!partial) chlAnswer(id, hint, now)
            else DetailAnswer(Fetch.Ok(good.copy(id = id, goalies = stripped), now, null, 0), false, partial = true)
        }
        h.repo.show(ohl(GamePhase.SCHEDULED))
        settle(dwell)
        assertEquals(good.goalies, h.ready.detail.goalies)
        partial = true
        settle(SportsConstants.HUB_NFL_PRE_MS)
        assertEquals(2, h.fetcher.calls.size)
        assertTrue(h.ready.partial)
        assertFalse(h.ready.failing)
        assertEquals("the stats and the away list of the good read are kept", good.goalies, h.ready.detail.goalies)
        // A new answer's own values win: a goalie with stats keeps his own line.
        val newer = good.goalies!!.let { gp -> gp.copy(home = gp.home.mapIndexed { i, g -> if (i == 0) g.copy(record = "9-4-0-0") else g.copy(record = null, gaa = null, svPct = null) }) }
        h.fetcher.answer = { id, _, now -> DetailAnswer(Fetch.Ok(good.copy(id = id, goalies = newer), now, null, 0), false, partial = true) }
        settle(SportsConstants.HUB_CHL_PARTIAL_RETRY_MS)
        assertEquals(3, h.fetcher.calls.size)
        assertEquals("9-4-0-0", h.ready.detail.goalies!!.home[0].record)
        assertEquals(good.goalies!!.home[1], h.ready.detail.goalies!!.home[1])
    }

    /** (CHL hub fix pass, C4) A refused or 429 secondary read backs off; it is not re-read after 120 s. */
    @Test fun aRefusedSecondaryReadBacksOffInsteadOfThePartialReRead() = runTest {
        for (kind in listOf(FailKind.HTTP_429, FailKind.REFUSED)) {
            val h = harness()
            h.fetcher.answer = { id, hint, now ->
                chlAnswer(id, hint, now).copy(partial = true, secondaryRefused = Fetch.Failed(kind, if (kind == FailKind.HTTP_429) 900 else null, now))
            }
            h.repo.show(ohl(GamePhase.SCHEDULED, id = "2930${kind.ordinal}"))
            settle(dwell)
            assertTrue("$kind", h.ready.partial)
            assertFalse("$kind: still Ready and not failing", h.ready.failing)
            settle(SportsConstants.HUB_CHL_PARTIAL_RETRY_MS)
            assertEquals("$kind: no 120 s re-read", 1, h.fetcher.calls.size)
            settle(SportsConstants.BACKOFF_MAX_MS - SportsConstants.HUB_CHL_PARTIAL_RETRY_MS - 1)
            assertEquals("$kind: the refusal's back-off", 1, h.fetcher.calls.size)
            assertFalse(h.ready.failing)
        }
        // A secondary read that failed otherwise (a timeout) keeps the 120 s partial re-read.
        val h = harness()
        h.fetcher.answer = { id, hint, now -> chlAnswer(id, hint, now).copy(partial = true, secondaryRefused = null) }
        h.repo.show(ohl(GamePhase.SCHEDULED, id = "29299"))
        settle(dwell + SportsConstants.HUB_CHL_PARTIAL_RETRY_MS)
        assertEquals(2, h.fetcher.calls.size)
    }

    @Test fun aFailureWithNothingCachedIsFailedWithItsKindAndBacksOff() = runTest {
        val h = harness()
        h.fetcher.answer = { _, _, now -> DetailAnswer(Fetch.Failed(FailKind.REFUSED, null, now), false) }
        h.repo.show(ohl(GamePhase.PRE))
        settle(dwell)
        assertEquals(DetailState.Failed(GameId(League.OHL, "29246"), FailKind.REFUSED), h.state)
        settle(SportsConstants.BACKOFF_MAX_MS - 1)
        assertEquals("REFUSED waits at least 300 s", 1, h.fetcher.calls.size)
    }

    // ---------------------------------------------------------------- live, breaks, postponed (D5)

    @Test fun liveBreaksAndPostponedMakeNoRequest() = runTest {
        val h = harness()
        for (p in listOf(GamePhase.LIVE, GamePhase.INTERMISSION, GamePhase.POSTPONED)) {
            h.repo.show(ohl(p, id = "9${p.ordinal}"))
            settle(SportsConstants.HUB_NFL_PRE_MS)
            assertEquals(DetailState.BoardOnly(GameId(League.OHL, "9${p.ordinal}")), h.state)
            assertEquals(FailKind.HTTP_OTHER, (h.repo.boxScore(GameId(League.OHL, "9${p.ordinal}")) as Fetch.Failed).kind)
        }
        assertTrue(h.fetcher.calls.isEmpty())
        assertEquals(0, h.fetcher.boxCalls)
    }

    @Test fun aPreGameThatGoesLiveTurnsBoardOnlyAndStopsReading() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.PRE))
        settle(dwell)
        assertEquals(1, h.fetcher.calls.size)
        h.repo.show(ohl(GamePhase.LIVE))
        settle(3 * SportsConstants.HUB_NFL_PRE_MS)
        assertEquals(DetailState.BoardOnly(GameId(League.OHL, "29246")), h.state)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun aPostponedGameIsNeverFetchedEvenAfterAPreGameRead() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.SCHEDULED))
        settle(dwell)
        h.repo.show(ohl(GamePhase.POSTPONED))
        h.repo.retry()
        settle(4 * SportsConstants.HUB_NFL_PRE_MS)
        assertEquals(DetailState.BoardOnly(GameId(League.OHL, "29246")), h.state)
        assertEquals(1, h.fetcher.calls.size)
    }

    // ---------------------------------------------------------------- the effective phase (R6)

    @Test fun theHintIsTheEffectivePhaseNotTheBoards() = runTest {
        val h = harness()
        // The board lags: still LIVE while the cached detail already saw the final.
        h.repo.show(ohl(GamePhase.FINAL, startUtcMs = wallBase - 9_000_000L))
        settle(dwell)
        assertEquals(listOf(GamePhase.FINAL), h.hints)
        h.repo.show(ohl(GamePhase.LIVE, startUtcMs = wallBase - 9_000_000L))
        h.repo.retry() // the stars are in: only a Retry reads again
        settle(1_000)
        assertTrue("effective FINAL: not BoardOnly", h.state is DetailState.Ready)
        assertEquals(listOf(GamePhase.FINAL, GamePhase.FINAL), h.hints)
    }

    @Test fun aPreGameDetailWhoseBoardTurnsFinalIsReReadAtOnceAsAFinal() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.PRE))
        settle(dwell)
        h.repo.show(ohl(GamePhase.FINAL))
        settle(SportsConstants.HUB_NFL_REFETCH_FLOOR_MS)
        assertEquals(listOf(GamePhase.PRE, GamePhase.FINAL), h.hints)
        assertEquals(listOf(dwell, dwell + SportsConstants.HUB_NFL_REFETCH_FLOOR_MS), h.times)
        assertEquals(GamePhase.FINAL, h.ready.detail.fetchedPhase)
    }

    @Test fun theNhlKeepsTheBoardPhaseAsItsHint() = runTest {
        val h = harness()
        h.fetcher.answer = { id, _, now -> DetailAnswer(Fetch.Ok(ChlHubFixtures.finalOhl29239().copy(id = id), now, null, 0), false) }
        h.repo.show(SportsFixtures.game(id = "2026020999", phase = GamePhase.LIVE, league = League.NHL))
        settle(dwell)
        h.repo.show(SportsFixtures.game(id = "2026020999", phase = GamePhase.LIVE, league = League.NHL))
        settle(60_000)
        assertTrue(h.hints.isNotEmpty())
        assertTrue(h.hints.all { it == GamePhase.LIVE })
    }

    // ---------------------------------------------------------------- the final: the stars wait (§4.2)

    @Test fun aFinalWithoutStarsIsReReadEveryFiveMinutesForAtMostAnHour() = runTest {
        val h = harness()
        h.fetcher.answer = { id, hint, now ->
            val a = chlAnswer(id, hint, now)
            a.copy(fetch = Fetch.Ok((a.fetch as Fetch.Ok).value.copy(stars = emptyList()), now, null, 0))
        }
        // First seen final, ended now (start + 2 h 30).
        h.repo.show(ohl(GamePhase.FINAL, startUtcMs = wallBase - DetailCadence.CHL.gameLengthMs))
        settle(dwell + 2 * SportsConstants.HUB_CHL_STARS_WAIT_MS)
        val expected = listOf(dwell) + (1..11).map { dwell + it * SportsConstants.HUB_DETAIL_IDLE_MS }
        assertEquals(expected, h.times)
        assertTrue("≤ 12 reads", h.fetcher.calls.size <= 12)
        assertTrue(h.hints.all { it == GamePhase.FINAL })
    }

    @Test fun aFinalWithStarsIsReadOnce() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.FINAL, startUtcMs = wallBase - DetailCadence.CHL.gameLengthMs))
        settle(dwell + SportsConstants.HUB_CHL_STARS_WAIT_MS)
        assertEquals(1, h.fetcher.calls.size)
        assertEquals(ChlHubFixtures.finalOhl29239().stars, h.ready.detail.stars)
    }

    // ---------------------------------------------------------------- the box score (§4.1)

    @Test fun theFinalsBoxComesFromTheSameAnswerWithoutAnotherRequest() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.FINAL, startUtcMs = wallBase - DetailCadence.CHL.gameLengthMs))
        settle(dwell)
        assertEquals(1, h.fetcher.calls.size)
        val box = async { h.repo.boxScore(GameId(League.OHL, "29246")) }
        runCurrent()
        assertEquals(ChlHubFixtures.boxOhl29239(), (box.await() as Fetch.Ok).value)
        assertEquals(1, h.fetcher.calls.size)
        assertEquals(0, h.fetcher.boxCalls)
    }

    @Test fun aBoxScoreBeforeTheFinalIsNeverARequest() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.PRE))
        settle(dwell)
        val box = async { h.repo.boxScore(GameId(League.OHL, "29246")) }
        runCurrent()
        assertEquals(FailKind.HTTP_OTHER, (box.await() as Fetch.Failed).kind)
        settle(1_000)
        assertEquals(1, h.fetcher.calls.size)
        assertEquals(0, h.fetcher.boxCalls)
    }

    @Test fun trimReachesTheChlSourceHook() = runTest {
        val h = harness()
        h.repo.show(ohl(GamePhase.PRE))
        settle(dwell)
        h.repo.trim()
        runCurrent()
        assertEquals(1, trims)
        assertTrue(h.state is DetailState.Ready)
    }

    companion object {
        /** The fetcher's default: the fixtures' pre-game at SCHEDULED / PRE, the final (stars, box) at FINAL. */
        internal fun chlAnswer(id: GameId, hint: GamePhase?, now: Long): DetailAnswer = when (hint) {
            GamePhase.FINAL -> DetailAnswer(
                Fetch.Ok(ChlHubFixtures.finalOhl29239().copy(id = id), now, null, 0),
                false,
                Fetch.Ok(ChlHubFixtures.boxOhl29239(), now, null, 0),
            )
            else -> DetailAnswer(Fetch.Ok(pre(id), now, null, 0), false)
        }

        private fun pre(id: GameId): GameDetail = ChlHubFixtures.preOhl29246().copy(id = id)
    }
}
