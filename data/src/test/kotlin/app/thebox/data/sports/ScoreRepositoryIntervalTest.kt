package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.PollMeasure
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.ScoreBugMachine
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.TeamLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/design/m6-sports-update-interval.md §3.2–§3.5, §4.3 (review B2, S2, S6, N3): the repository resolves the interval,
 * sends it through the real wiring (`FollowMachine.of`), times each follow poll and persists the meter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreRepositoryIntervalTest {
    private class Signals : SportsSignals {
        override val foreground = MutableStateFlow(true)
        override val online = MutableStateFlow(true)
    }

    private object Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    /** A source of [league] that answers [answer] after [workMs] of virtual time and records each call. */
    private class Source(override val league: League, private val mono: MonoClock) : ScoreSource {
        val polls = ArrayList<Long>()
        val days = ArrayList<Long>()
        var workMs = 0L
        var dayWorkMs = 0L
        var inFlight = 0
        var maxInFlight = 0
        var endpoint = if (league == League.NFL) PollMeter.NFL_EVENT else PollMeter.NHL_LANDING
        lateinit var answer: () -> Fetch<GameSnapshot>

        override suspend fun today(): Fetch<DayBoard> {
            days += mono.nowMs()
            delay(dayWorkMs)
            return Fetch.Ok(SportsFixtures.board(), mono.nowMs(), null, 0)
        }
        override suspend fun day(dayKey: String): Fetch<DayBoard> = today()
        override suspend fun game(id: GameId): Fetch<GameSnapshot> {
            polls += mono.nowMs()
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(workMs)
                return answer()
            } finally {
                inFlight--
            }
        }
        override fun logoUrl(team: TeamLine): String = ""
        override fun followEndpoint(id: GameId): String = endpoint
        fun gaps() = polls.zipWithNext { a, b -> b - a }
    }

    private val nflLive = SportsFixtures.nflGame(clock = GameClock(222, running = false, inIntermission = false))
    private val nhlLive = SportsFixtures.game(clock = GameClock(622, running = true, inIntermission = false))

    private class Rig(val repo: ScoreRepositoryImpl, val source: Source, val store: FakeSettingsStore, val bugs: List<ScoreBugView?>) {
        val meter: PollMeasure? get() = store.flow(SportsFollowDomain).value.meters[source.league]
    }

    /** The real machine through the production wiring ([FollowMachine.of]): review B2. */
    private fun TestScope.rig(
        game: GameSnapshot,
        prefs: SportsPrefs = SportsPrefs(),
        doc: SportsFollowDoc = SportsFollowDoc(game = SportsFixtures.followedOf(game), pollPhaseMs = 1_500L),
        configure: Source.() -> Unit = {},
    ): Rig {
        val mono = MonoClock { testScheduler.currentTime }
        val wallBase = game.startUtcMs + 30 * 60_000L
        val wall = { wallBase + testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { doc }
        store.update(SportsDomain) { prefs }
        val source = Source(game.id.league, mono)
        source.answer = { Fetch.Ok(game, mono.nowMs(), wallBase + mono.nowMs(), 0, maxAgeS = 1, wireBytes = 2_200) }
        source.configure()
        val nhlStub = Source(League.NHL, mono).also { it.answer = { Fetch.Ok(nhlLive, mono.nowMs(), null, 0) } }
        val sources = if (game.id.league == League.NFL) mapOf(League.NFL to source, League.NHL to nhlStub) else mapOf(League.NHL to source)
        val repo = ScoreRepositoryImpl(
            sources, store, Awake, Signals(), backgroundScope, mono,
            machineFactory = { phase -> FollowMachine.of(ScoreBugMachine(mono, wall, phase)) },
        )
        val bugs = ArrayList<ScoreBugView?>()
        backgroundScope.launch { repo.bug.collect { bugs += it } }
        repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        return Rig(repo, source, store, bugs)
    }

    private fun TestScope.run(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ---------------------------------------------------------------- B2: the production wiring

    @Test fun nflAutoPollsFiveSecondsAfterEachCompletionNotTwentyOne() = runTest {
        val r = rig(nflLive) { workMs = 300 }
        run(30_000L)
        assertTrue(r.source.polls.size >= 5)
        // Each gap is the 5 s interval plus the 0.3 s the poll itself took (completion-based).
        assertEquals(List(r.source.gaps().size) { 5_300L }, r.source.gaps())
    }

    @Test fun nhlAutoStaysOnTheTwentySecondBuckets() = runTest {
        val wallBase = nhlLive.startUtcMs + 30 * 60_000L
        val r = rig(nhlLive) {
            // A CDN with 20 s buckets: Age = seconds since the bucket started (whole seconds, as HTTP sends them).
            answer = {
                val w = wallBase + testScheduler.currentTime
                Fetch.Ok(nhlLive, testScheduler.currentTime, w, ((w / 1000) % 20).toInt(), maxAgeS = 19)
            }
        }
        run(130_000L)
        // Every poll lands 1–2.5 s into a new bucket (the 1.5 s phase, Age in whole seconds), one bucket apart.
        val offsets = r.source.polls.drop(1).map { (wallBase + it) % 20_000L }
        assertTrue(offsets.toString(), offsets.all { it in 1_000L..2_500L })
        assertTrue(r.source.gaps().drop(1).toString(), r.source.gaps().drop(1).all { it in 19_000L..21_000L })
        assertEquals(PollMeter.NHL_LANDING, r.meter!!.endpoint)
        assertEquals(20, PollMeter.recommended(r.meter, League.NHL))
    }

    @Test fun aChangedPrefReachesTheMachine() = runTest {
        val r = rig(nflLive)
        run(1_000L)
        r.store.update(SportsDomain) { it.copy(nflIntervalS = 15) }
        run(60_000L)
        assertTrue(r.source.gaps().toString(), r.source.gaps().drop(1).all { it == 15_000L })
        r.store.update(SportsDomain) { it.copy(nhlIntervalS = 60) }
        run(30_000L)
        assertTrue("the other league's pref leaves the NFL alone", r.source.gaps().takeLast(2).all { it == 15_000L })
    }

    // ---------------------------------------------------------------- S2: a 429 slows every choice

    @Test fun aFixedFiveSecondChoiceAfterA429StaysAtTenOrMore() = runTest {
        var n = 0
        val r = rig(nflLive, prefs = SportsPrefs(nflIntervalS = 5)) {
            val ok = answer
            answer = { if (++n == 3) Fetch.Failed(FailKind.HTTP_429, null, testScheduler.currentTime) else ok() }
        }
        run(120_000L)
        val g = r.source.gaps()
        assertEquals(5_000L, g[0])
        assertTrue("the 429's back-off on the 20 s base: ${g[2]}", g[2] >= 16_000L)
        assertTrue("then never under 10 s: $g", g.drop(3).all { it >= 10_000L })
        assertTrue(r.meter!!.slowed)
        // A new follow clears it.
        r.repo.follow(SportsFixtures.nflGame(id = "401899002"))
        runCurrent()
        assertFalse(r.store.flow(SportsFollowDomain).value.meters[League.NFL]!!.slowed)
    }

    // ---------------------------------------------------------------- S6: the sample

    @Test fun theWorkSampleSkipsTheFirstPollAndNeverIncludesTheDayList() = runTest {
        val r = rig(nflLive, prefs = SportsPrefs(league = League.NFL)) { workMs = 400; dayWorkMs = 3_000 }
        r.repo.reportDemand(DemandOwner.SPORTS_TAB, true) // the week board decodes in the same loop turns
        run(6_000L)
        assertEquals("two polls, the first skipped (cold start)", 2, r.source.polls.size)
        assertEquals(listOf(400), r.meter!!.workMs)
        run(70_000L)
        val m = r.meter!!
        assertTrue(r.source.days.isNotEmpty())
        assertTrue("only the follow's own work: ${m.workMs}", m.workMs.size > 5 && m.workMs.all { it == 400 })
        assertEquals(PollMeter.NFL_EVENT, m.endpoint)
        assertEquals(1, m.maxAgeS)
        assertTrue(m.wireBytes.all { it == 2_200 })
    }

    @Test fun theMeterIsPersistedAtMostOnceAMinute() = runTest {
        val r = rig(nflLive)
        run(1_000L)
        r.store.updates.clear()
        run(50_000L)
        val writes = r.store.updates.count { it == SportsFollowDomain.name }
        assertEquals("the first sample at once, then nothing for a minute", 1, writes)
        run(60_000L)
        assertEquals(2, r.store.updates.count { it == SportsFollowDomain.name })
        assertTrue("the persisted window: ${r.meter!!.workMs.size}", r.meter!!.workMs.size >= 12)
    }

    @Test fun anEndpointChangeIsPersistedAtOnce() = runTest {
        val r = rig(nflLive)
        run(20_000L)
        r.source.endpoint = PollMeter.NFL_BOARD
        r.source.answer = { Fetch.Ok(nflLive, testScheduler.currentTime, null, 0, maxAgeS = 10, wireBytes = 19_700) }
        run(11_000L)
        assertEquals(PollMeter.NFL_BOARD, r.meter!!.endpoint)
        // The board's 10 s feed moves Auto to 10 s.
        run(60_000L)
        assertTrue(r.source.gaps().toString(), r.source.gaps().takeLast(3).all { it == 10_000L })
    }

    @Test fun aSlowBoxMovesAutoUp() = runTest {
        val r = rig(nflLive) { workMs = 2_000 }
        run(200_000L)
        assertEquals(10, PollMeter.recommended(r.meter, League.NFL))
        assertEquals("10 s after each 2 s poll", 12_000L, r.source.gaps().last())
    }

    @Test fun timeoutsCountAtTheirElapsedTime() = runTest {
        var n = 0
        val r = rig(nflLive) {
            val ok = answer
            answer = { if (++n % 2 == 0) Fetch.Failed(FailKind.TIMEOUT, null, testScheduler.currentTime) else ok() }
            workMs = 1_000
        }
        run(300_000L)
        val m = r.meter!!
        assertTrue(m.workMs.size >= SportsConstants.METER_MIN)
        assertTrue("timeouts are in the window, not only the good polls", m.workMs.size > m.wireBytes.size)
    }

    @Test fun oneRequestInFlightUnderAStalledSource() = runTest {
        val r = rig(nflLive) { workMs = 9_000 }
        r.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        run(120_000L)
        assertEquals(1, r.source.maxInFlight)
        assertTrue("never back to back: ${r.source.gaps()}", r.source.gaps().all { it >= 9_000L + 5_000L })
    }

    @Test fun identicalBodiesAreOneBugEmission() = runTest {
        val r = rig(nflLive)
        run(30_000L)
        assertTrue(r.source.polls.size >= 5)
        assertEquals("one view however many polls (N3): ${r.bugs}", 1, r.bugs.filterNotNull().size)
    }
}
