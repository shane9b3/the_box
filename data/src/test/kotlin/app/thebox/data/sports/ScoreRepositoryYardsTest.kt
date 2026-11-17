package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FollowExtras
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PollMeasure
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.ScoreBugMachine
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.TeamLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

/**
 * (NFL YDS §1.9, §1.10) The repository asks for the yards only while the Stacked NFL bug can be seen (NFL, Style Stacked,
 * demand SEEN with the full-screen player showing the follow), and the yards step's `sideMs` never enters the work sample.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreRepositoryYardsTest {
    private class Signals : SportsSignals {
        override val foreground = MutableStateFlow(true)
        override val online = MutableStateFlow(true)
    }

    private object Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    /** A source that records the extras of every follow poll and answers after [workMs] with [sideMs] reported. */
    private class Source(override val league: League, private val mono: MonoClock, private val game: GameSnapshot) : ScoreSource {
        val extras = ArrayList<FollowExtras>()
        var workMs = 0L
        var sideMs = 0L

        override suspend fun today(): Fetch<DayBoard> = Fetch.Ok(SportsFixtures.board(), mono.nowMs(), null, 0)
        override suspend fun day(dayKey: String): Fetch<DayBoard> = today()
        override suspend fun game(id: GameId): Fetch<GameSnapshot> = error("the follow uses the extras overload")
        override suspend fun game(id: GameId, startUtcMs: Long?, extras: FollowExtras): Fetch<GameSnapshot> {
            this.extras += extras
            delay(workMs)
            return Fetch.Ok(game, mono.nowMs(), null, 0, maxAgeS = 1, wireBytes = 2_200, sideMs = sideMs)
        }
        override fun logoUrl(team: TeamLine): String = ""
        override fun followEndpoint(id: GameId): String? = if (league == League.NFL) PollMeter.NFL_EVENT else PollMeter.NHL_LANDING
    }

    private val nflLive = SportsFixtures.nflGame(clock = GameClock(222, running = false, inIntermission = false))
    private val nhlLive = SportsFixtures.game(clock = GameClock(622, running = true, inIntermission = false))

    private class Rig(val repo: ScoreRepositoryImpl, val source: Source, val store: FakeSettingsStore) {
        val meter: PollMeasure? get() = store.flow(SportsFollowDomain).value.meters[source.league]
        fun yards(): List<Boolean> = source.extras.map { it.nflYards }
    }

    private fun TestScope.rig(
        game: GameSnapshot,
        style: NhlBugStyle,
        owner: DemandOwner? = DemandOwner.FULL_WITH_FOLLOW,
        configure: Source.() -> Unit = {},
    ): Rig {
        val mono = MonoClock { testScheduler.currentTime }
        val wall = { game.startUtcMs + 30 * 60_000L + testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { SportsFollowDoc(game = SportsFixtures.followedOf(game), pollPhaseMs = 1_500L) }
        store.update(SportsDomain) { SportsPrefs(nhlStyle = style) }
        val source = Source(game.id.league, mono, game).apply(configure)
        val repo = ScoreRepositoryImpl(
            mapOf(game.id.league to source), store, Awake, Signals(), backgroundScope, mono,
            machineFactory = { phase -> FollowMachine.of(ScoreBugMachine(mono, wall, phase)) },
        )
        if (owner != null) repo.reportDemand(owner, true)
        runCurrent()
        return Rig(repo, source, store)
    }

    private fun TestScope.run(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun nflStackedFullScreenAsksForTheYards() = runTest {
        val r = rig(nflLive, NhlBugStyle.STACKED)
        run(30_000L)
        assertTrue(r.yards().size >= 3)
        assertTrue(r.yards().toString(), r.yards().all { it })
    }

    @Test fun thePillNeverAsks() = runTest {
        val r = rig(nflLive, NhlBugStyle.PILL)
        run(30_000L)
        assertTrue(r.yards().isNotEmpty())
        assertTrue(r.yards().none { it })
    }

    @Test fun theSportsTabOrPickerAloneNeverAsks() = runTest {
        for (owner in listOf(DemandOwner.SPORTS_TAB, DemandOwner.SCORE_PICKER)) {
            val r = rig(nflLive, NhlBugStyle.STACKED, owner = owner)
            run(30_000L)
            assertTrue(owner.name, r.yards().isNotEmpty())
            assertTrue(owner.name, r.yards().none { it })
        }
    }

    @Test fun anUnseenFollowNeverAsks() = runTest {
        val r = rig(nflLive, NhlBugStyle.STACKED, owner = null) // demand UNSEEN
        run(120_000L)
        assertTrue(r.yards().none { it })
    }

    @Test fun theNhlNeverAsks() = runTest {
        val r = rig(nhlLive, NhlBugStyle.STACKED)
        run(60_000L)
        assertTrue(r.yards().isNotEmpty())
        assertTrue(r.yards().none { it })
    }

    @Test fun switchingTheStyleOrLeavingThePlayerTakesEffectOnTheNextPoll() = runTest {
        val r = rig(nflLive, NhlBugStyle.PILL)
        run(12_000L)
        assertFalse(r.yards().any { it })
        r.store.update(SportsDomain) { it.copy(nhlStyle = NhlBugStyle.STACKED) }
        run(12_000L)
        assertTrue("Stacked: the next poll asks", r.yards().last())
        r.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, false)
        r.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        val n = r.yards().size
        run(30_000L)
        assertTrue(r.yards().size > n)
        assertTrue("the player left: no yards", r.yards().drop(n).none { it })
    }

    @Test fun theWorkSampleExcludesTheYardsStep() = runTest {
        val r = rig(nflLive, NhlBugStyle.STACKED) { workMs = 1_400; sideMs = 1_000 }
        run(40_000L)
        val m = r.meter!!
        assertTrue(m.workMs.toString(), m.workMs.isNotEmpty())
        assertEquals("1 400 ms of which 1 000 are the yards", List(m.workMs.size) { 400 }, m.workMs)
        assertTrue("the board's bytes only: ${m.wireBytes}", m.wireBytes.all { it == 2_200 })
        assertFalse(m.slowed)
    }
}
