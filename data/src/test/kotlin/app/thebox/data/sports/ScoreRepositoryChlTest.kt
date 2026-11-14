package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.Demand
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.TeamLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * (CHL §1.6 rows 35–37 and 39, S7) The repository's CHL wiring: no silent source fallback, the CHL interval setting, the
 * REFUSED day back-off at `BACKOFF_MAX_MS` (and the usual back-off for every other kind), and a 429 slowing the CHL meter.
 * The day policy is the real one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreRepositoryChlTest {

    private class Signals : SportsSignals {
        override val foreground = MutableStateFlow(true)
        override val online = MutableStateFlow(true)
    }

    private class Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class Source(override val league: League, private val mono: MonoClock) : ScoreSource {
        val calls = ArrayList<Long>()
        val games = ArrayList<String>()
        var day: () -> Fetch<DayBoard> = { Fetch.Ok(SportsFixtures.board(dayKey = "2026-11-13").copy(league = league), mono.nowMs(), null, 0) }
        var game: () -> Fetch<GameSnapshot> = { Fetch.Failed(FailKind.HTTP_429, null, mono.nowMs()) }
        override suspend fun today(): Fetch<DayBoard> = day().also { calls += mono.nowMs() }
        override suspend fun day(dayKey: String): Fetch<DayBoard> = day().also { calls += mono.nowMs() }
        override suspend fun game(id: GameId): Fetch<GameSnapshot> = game().also { games += "${id.league}:${id.id}" }
        override fun logoUrl(team: TeamLine): String = ""
        override fun followEndpoint(id: GameId): String? = PollMeter.CHL_LIVE
    }

    private class Machine(private val mono: MonoClock) : FollowMachine {
        var game: FollowedGame? = null
        var demand = Demand.NONE
        var online = false
        var nextPoll: Long? = null
        val fetched = ArrayList<Fetch<GameSnapshot>>()
        val intervals = ArrayList<Long?>()
        private fun active() = game != null && demand != Demand.NONE && online
        override fun follow(game: FollowedGame?) { this.game = game; nextPoll = game?.let { mono.nowMs() } }
        override fun prefs(delaySec: Int) = Unit
        override fun demand(d: Demand) { demand = d }
        override fun online(on: Boolean) { online = on }
        override fun fetched(f: Fetch<GameSnapshot>) { fetched += f; nextPoll = mono.nowMs() + 20_000L }
        override fun step() = Unit
        override fun pollDue(): Boolean = active() && (nextPoll ?: Long.MAX_VALUE) <= mono.nowMs()
        override fun nextWakeAtMono(): Long? = if (active()) nextPoll else null
        override fun view(): ScoreBugView? = null
        override fun knownView(): ScoreBugView? = null
        override fun followed(): FollowedGame? = game
        override fun drainEvents(): List<ScoreEvent> = emptyList()
        override fun interval(liveMs: Long?) { intervals += liveMs }
    }

    private class Rig(val repo: ScoreRepositoryImpl, val store: FakeSettingsStore, val machine: () -> Machine)

    private val whlGame = FollowedGame(League.WHL, "1023288", 1_794_621_600_000L, "CGY", "MH")
    private val ohlGame = FollowedGame(League.OHL, "29191", 1_794_617_100_000L, "PBO", "OSH")

    private fun TestScope.rig(sources: List<ScoreSource>, doc: SportsFollowDoc, prefs: SportsPrefs): Rig {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { doc }
        store.update(SportsDomain) { prefs }
        var machine: Machine? = null
        val repo = ScoreRepositoryImpl(
            sources.associateBy { it.league }, store, Awake(), Signals(), backgroundScope, mono,
            machineFactory = { Machine(mono).also { machine = it } }, random = Random(3),
        )
        runCurrent()
        return Rig(repo, store, { machine!! })
    }

    // ---------------------------------------------------------------- row 35

    @Test fun aFollowedLeagueWithoutASourceFailsInsteadOfAskingAnotherLeague() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val nhl = Source(League.NHL, mono)
        val r = rig(listOf(nhl), SportsFollowDoc(game = whlGame, pollPhaseMs = 1_500L), SportsPrefs())
        r.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        assertTrue("the NHL source was never asked for a WHL game", nhl.games.isEmpty())
        val f = r.machine().fetched.single() as Fetch.Failed
        assertEquals(FailKind.HTTP_OTHER, f.kind)
    }

    @Test fun aShownLeagueWithoutASourceShowsAFailureNotAnotherLeaguesBoard() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val nhl = Source(League.NHL, mono)
        val r = rig(listOf(nhl), SportsFollowDoc(pollPhaseMs = 1_500L), SportsPrefs(league = League.QMJHL))
        r.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertTrue(nhl.calls.isEmpty())
        assertEquals(DayState.Failed(FailKind.HTTP_OTHER), r.repo.day.value)
    }

    // ---------------------------------------------------------------- row 36

    @Test fun theChlIntervalSettingReachesTheMachine() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val ohl = Source(League.OHL, mono).apply { game = { Fetch.Failed(FailKind.IO, null, mono.nowMs()) } }
        val r = rig(listOf(ohl), SportsFollowDoc(game = ohlGame, pollPhaseMs = 1_500L), SportsPrefs(chlIntervalS = 5))
        assertEquals(5_000L, r.machine().intervals.last())
        r.store.update(SportsDomain) { it.copy(chlIntervalS = 20) }
        runCurrent()
        assertEquals(20_000L, r.machine().intervals.last())
        // The NHL setting does not touch a CHL follow.
        val n = r.machine().intervals.size
        r.store.update(SportsDomain) { it.copy(nhlIntervalS = 30) }
        runCurrent()
        assertEquals(n, r.machine().intervals.size)
    }

    // ---------------------------------------------------------------- row 37 (S7)

    /** (Lead ruling) REFUSED retries at `max(300 s, Retry-After)`: none and 30 s give 300 s, 900 s gives 900 s. */
    @Test fun aRefusedDayWaitsForTheLongerOfBackoffMaxAndRetryAfter() {
        for ((retryAfter, expectedMs) in listOf(null to 300_000L, 30 to 300_000L, 900 to 900_000L)) runTest {
            val mono = MonoClock { testScheduler.currentTime }
            val ohl = Source(League.OHL, mono).apply { day = { Fetch.Failed(FailKind.REFUSED, retryAfter, mono.nowMs()) } }
            val r = rig(listOf(ohl), SportsFollowDoc(pollPhaseMs = 1_500L), SportsPrefs(league = League.OHL))
            r.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
            runCurrent()
            advanceTimeBy(expectedMs - 1)
            runCurrent()
            assertEquals("Retry-After $retryAfter", 1, ohl.calls.size)
            advanceTimeBy(2)
            runCurrent()
            assertEquals("Retry-After $retryAfter", 2, ohl.calls.size)
            assertEquals(expectedMs, ohl.calls[1] - ohl.calls[0])
        }
    }

    @Test fun aRefusedDayIsAskedAgainOnlyAfterBackoffMax() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val ohl = Source(League.OHL, mono).apply { day = { Fetch.Failed(FailKind.REFUSED, 1, mono.nowMs()) } }
        val r = rig(listOf(ohl), SportsFollowDoc(pollPhaseMs = 1_500L), SportsPrefs(league = League.OHL))
        r.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertEquals(1, ohl.calls.size)
        assertEquals(DayState.Failed(FailKind.REFUSED), r.repo.day.value)
        // Retry (OK on the tab) and a day switch never bring it closer.
        r.repo.retry()
        r.repo.showDay("2026-11-12")
        runCurrent()
        assertEquals(DayState.Failed(FailKind.REFUSED), r.repo.day.value)
        advanceTimeBy(SportsConstants.BACKOFF_MAX_MS - 1_000)
        runCurrent()
        assertEquals(1, ohl.calls.size)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, ohl.calls.size)
        assertEquals(SportsConstants.BACKOFF_MAX_MS, ohl.calls[1] - ohl.calls[0])
        // An answer clears it: the board, then the usual 30 s live cadence.
        ohl.day = { Fetch.Ok(SportsFixtures.board(dayKey = "2026-11-12").copy(league = League.OHL), mono.nowMs(), null, 0) }
        advanceTimeBy(SportsConstants.BACKOFF_MAX_MS + 1)
        runCurrent()
        assertTrue(r.repo.day.value is DayState.Board)
    }

    @Test fun otherDayFailuresKeepTheUsualBackoff() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val ohl = Source(League.OHL, mono).apply { day = { Fetch.Failed(FailKind.HTTP_5XX, null, mono.nowMs()) } }
        rig(listOf(ohl), SportsFollowDoc(pollPhaseMs = 1_500L), SportsPrefs(league = League.OHL)).repo
            .reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        advanceTimeBy(SportsConstants.BACKOFF_BASE_MS + 5_000)
        runCurrent()
        assertEquals("the 20 s base (±20 %)", 2, ohl.calls.size)
        assertTrue(ohl.calls[1] - ohl.calls[0] in 16_000L..24_000L)
    }

    @Test fun aRefusalInOneLeagueDoesNotHoldAnother() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val ohl = Source(League.OHL, mono).apply { day = { Fetch.Failed(FailKind.REFUSED, null, mono.nowMs()) } }
        val whl = Source(League.WHL, mono)
        val r = rig(listOf(ohl, whl), SportsFollowDoc(pollPhaseMs = 1_500L), SportsPrefs(league = League.OHL))
        r.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        r.store.update(SportsDomain) { it.copy(league = League.WHL, chlLeague = League.WHL) }
        runCurrent()
        assertEquals(1, whl.calls.size)
        assertTrue(r.repo.day.value is DayState.Board)
        // Back to the refused league: still held until its 300 s.
        r.store.update(SportsDomain) { it.copy(league = League.OHL, chlLeague = League.OHL) }
        runCurrent()
        assertEquals(1, ohl.calls.size)
        assertEquals(DayState.Failed(FailKind.REFUSED), r.repo.day.value)
    }

    // ---------------------------------------------------------------- row 39

    @Test fun a429SlowsTheChlMeter() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val ohl = Source(League.OHL, mono)
        val r = rig(listOf(ohl), SportsFollowDoc(game = ohlGame, pollPhaseMs = 1_500L), SportsPrefs())
        r.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        val m = r.store.flow(SportsFollowDomain).value.meters[League.OHL]
        assertTrue("slowed", m?.slowed == true)
        assertEquals(PollMeter.CHL_LIVE, m?.endpoint)
    }
}
