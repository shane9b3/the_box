package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-sports-update-interval.md §3.4, §3.5 (review B1, S2, S5): `ScoreBugMachine.interval`. A fake mono clock;
 * the server `Date` is the true wall time. NHL at 20 s must be byte-identical to `interval(null)` (today's timing).
 */
class ScoreBugMachineIntervalTest {
    private class Rig(start: Long, phaseMs: Long = 1_500L, liveMs: Long? = null) {
        var now = 0L
        val wall0 = start + 30 * 60_000L
        val m = ScoreBugMachine({ now }, { wall0 + now }, phaseMs, Random(7))

        init {
            m.interval(liveMs)
        }

        fun follow(g: GameSnapshot, demand: Demand = Demand.SEEN) {
            m.follow(SportsFixtures.followedOf(g))
            m.demand(demand)
        }

        fun ok(s: GameSnapshot, ageS: Int = 0, received: Long = now) = m.fetched(Fetch.Ok(s, received, wall0 + received, ageS))
        fun fail(kind: FailKind = FailKind.IO, retryAfterS: Int? = null) = m.fetched(Fetch.Failed(kind, retryAfterS, now))

        fun at(t: Long) {
            now = t
            m.step()
        }

        /** The next poll instant: the next wake-up at which a poll is due (other wake-ups are stepped through). */
        fun nextPoll(limit: Long = now + 3_600_000L): Long? {
            if (m.pollDue()) return now
            while (true) {
                val w = m.nextWakeAtMono() ?: return null
                if (w > limit) return null
                at(w)
                if (m.pollDue()) return w
            }
        }
    }

    private fun nhl(
        secs: Int = 622,
        away: Int = 0,
        home: Int = 2,
        period: Int = 2,
        phase: GamePhase = GamePhase.LIVE,
    ) = SportsFixtures.game(
        phase = phase,
        away = SportsFixtures.team("CGY", "Flames", away),
        home = SportsFixtures.team("EDM", "Oilers", home),
        period = period,
        clock = GameClock(secs, phase == GamePhase.LIVE, phase == GamePhase.INTERMISSION),
    )

    private fun nfl(
        away: Int = 24,
        home: Int = 17,
        secs: Int = 222,
        period: Int = 4,
        phase: GamePhase = GamePhase.LIVE,
        lastPlayId: String? = null,
    ) = SportsFixtures.nflGame(
        phase = phase,
        away = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs", away),
        home = SportsFixtures.nflTeam("BUF", "Buffalo Bills", "Bills", home),
        period = period,
        clock = GameClock(secs, running = false, inIntermission = phase == GamePhase.INTERMISSION),
        detail = SportsFixtures.nflDetail(lastPlay = lastPlayId?.let { NflLastPlay(it, "5", "Rush", Side.AWAY, 0) }),
    )

    private val nhlStart = SportsFixtures.game().startUtcMs
    private val nflStart = SportsFixtures.nflGame().startUtcMs

    // ------------------------------------------------------------------ NHL 20 s == today

    /** One scripted session, played on two machines: the whole observable trace must match. */
    private fun trace(liveMs: Long?, script: List<Pair<Long, (Rig) -> Unit>>, start: Long = nhlStart): List<String> {
        val r = Rig(start, liveMs = liveMs)
        val out = ArrayList<String>()
        for ((t, act) in script) {
            r.at(t)
            act(r)
            out += "$t due=${r.m.pollDue()} wake=${r.m.nextWakeAtMono()} view=${r.m.view()} known=${r.m.knownView()}"
            // Walk the wake-ups to the next scripted instant, recording each.
            val next = script.map { it.first }.firstOrNull { it > t } ?: (t + 400_000L)
            while (true) {
                val w = r.m.nextWakeAtMono() ?: break
                if (w <= r.now || w >= next) break
                r.at(w)
                out += "  wake $w due=${r.m.pollDue()} view=${r.m.view()}"
            }
        }
        return out
    }

    @Test
    fun nhlAtTwentySecondsIsByteIdenticalToTodaysTiming() {
        val script: List<Pair<Long, (Rig) -> Unit>> = listOf(
            0L to { r -> r.follow(nhl()) },
            100L to { r -> r.ok(nhl(), ageS = 7) },
            21_600L to { r -> r.ok(nhl(secs = 600), ageS = 1) },
            41_600L to { r -> r.ok(nhl(secs = 580, home = 3), ageS = 1) }, // a goal
            61_600L to { r -> r.fail(FailKind.TIMEOUT) },
            82_000L to { r -> r.fail(FailKind.HTTP_429, retryAfterS = 30) },
            130_000L to { r -> r.ok(nhl(secs = 0, home = 3, phase = GamePhase.INTERMISSION), ageS = 2) },
            170_000L to { r -> r.m.demand(Demand.UNSEEN) },
            200_000L to { r -> r.m.demand(Demand.SEEN) },
            210_000L to { r -> r.ok(nhl(secs = 1200, home = 3, period = 3), ageS = 4) },
            231_000L to { r -> r.m.fetched(Fetch.NotFound) },
            260_000L to { r -> r.ok(nhl(secs = 1150, away = 1, home = 3, period = 3), ageS = 0) },
            300_000L to { r -> r.m.online(false) },
            330_000L to { r -> r.m.online(true) },
            340_000L to { r -> r.ok(nhl(secs = 1100, away = 1, home = 3, period = 3), ageS = 3) },
        )
        assertEquals(trace(null, script), trace(20_000L, script))
    }

    @Test
    fun nflWithNoIntervalIsTodaysTiming() {
        // interval(null) on the NFL keeps the bucket-aligned received + 20 s + phase (ESPN sends no Age).
        val r = Rig(nflStart)
        r.follow(nfl())
        r.ok(nfl())
        assertEquals(21_500L, r.nextPoll())
    }

    // ------------------------------------------------------------------ NHL 40 / 60 aligned

    @Test
    fun nhlFortyAndSixtyStayBucketAlignedAndPhaseExact() {
        for ((liveMs, expected) in listOf(20_000L to 21_500L, 40_000L to 41_500L, 60_000L to 61_500L)) {
            val r = Rig(nhlStart, liveMs = liveMs)
            r.follow(nhl())
            r.ok(nhl(), ageS = 0)
            assertEquals(expected, r.nextPoll(), "live at $liveMs")
            // With Age 7 the bucket ends 13 s after the answer: still bucket end + phase + skip × 20 s.
            r.at(expected)
            r.ok(nhl(secs = 600), ageS = 7)
            assertEquals(expected + 13_000L + 1_500L + (liveMs - 20_000L), r.nextPoll(), "aligned at $liveMs")
        }
    }

    @Test
    fun breaksPollAtTwiceLiveWithATwentySecondFloor() {
        // NHL: 20 → 40 s (unchanged), 40 → 80 s, 60 → 120 s; all bucket-aligned.
        for ((liveMs, gap) in listOf(20_000L to 40_000L, 40_000L to 80_000L, 60_000L to 120_000L)) {
            val r = Rig(nhlStart, liveMs = liveMs)
            r.follow(nhl())
            r.ok(nhl(phase = GamePhase.INTERMISSION, secs = 0))
            assertEquals(gap + 1_500L, r.nextPoll(), "NHL break at $liveMs")
        }
        // NFL: 5 and 10 s → 20 s (the floor), 15 → 30 s; from completion.
        for ((liveMs, gap) in listOf(5_000L to 20_000L, 10_000L to 20_000L, 15_000L to 30_000L)) {
            val r = Rig(nflStart, liveMs = liveMs)
            r.follow(nfl())
            r.ok(nfl(phase = GamePhase.INTERMISSION, secs = 0))
            assertEquals(gap, r.nextPoll(), "NFL break at $liveMs")
        }
    }

    // ------------------------------------------------------------------ NFL from completion

    @Test
    fun nflPollsFromCompletionIncludingAPollSlowerThanTheInterval() {
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl())
        r.ok(nfl())
        assertEquals(5_000L, r.nextPoll())
        // A poll whose headers came at 5.1 s but whose decode finished at 8 s: next = 8 + 5 s, never at once.
        r.at(8_000L)
        r.ok(nfl(secs = 200), received = 5_100L)
        assertEquals(13_000L, r.nextPoll())
        r.at(13_000L)
        r.ok(nfl(secs = 190))
        assertEquals(18_000L, r.nextPoll())
    }

    @Test
    fun theCoinTossPreGamePollsAtTheLiveIntervalAndPreGameIsUnchanged() {
        val toss = nfl(away = 0, home = 0, secs = 900, period = 1).copy(phase = GamePhase.PRE)
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(toss)
        r.ok(toss)
        assertEquals(5_000L, r.nextPoll(), "an ESPN `in` body at the coin toss polls at the live interval")

        // A plain pre-game (no scores) keeps its fixed sleep, with or without an interval.
        fun preNext(liveMs: Long?): Long? {
            val pre = SportsFixtures.nflGame(
                phase = GamePhase.PRE,
                away = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs"),
                home = SportsFixtures.nflTeam("BUF", "Buffalo Bills", "Bills"),
                startUtcMs = nflStart + 3_600_000L,
            )
            val p = Rig(nflStart, liveMs = liveMs)
            p.follow(pre)
            p.ok(pre)
            return p.nextPoll()
        }
        assertEquals(preNext(null), preNext(5_000L))
    }

    @Test
    fun unseenKeepsItsTwoMinuteFloor() {
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl(), Demand.UNSEEN)
        r.ok(nfl())
        assertEquals(SportsConstants.POLL_UNSEEN_MS, r.nextPoll())
    }

    // ------------------------------------------------------------------ back-off

    @Test
    fun nflBacksOffFromFiveSecondsAndA429KeepsTheTwentySecondBase() {
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl())
        r.ok(nfl())
        r.at(5_000L)
        r.fail(FailKind.TIMEOUT)
        val first = r.nextPoll()!! - 5_000L
        assertTrue(first in 4_000L..6_000L, "5 s ± 20 %: $first")
        r.fail(FailKind.IO)
        val failedAt = r.now
        val second = r.nextPoll()!! - failedAt
        assertTrue(second in 8_000L..12_000L, "10 s ± 20 %: $second")

        val q = Rig(nflStart, liveMs = 5_000L)
        q.follow(nfl())
        q.ok(nfl())
        q.at(5_000L)
        q.fail(FailKind.HTTP_429)
        val gap = q.nextPoll()!! - 5_000L
        assertTrue(gap in 16_000L..24_000L, "a 429 backs off on the 20 s base: $gap")
        val w = Rig(nflStart, liveMs = 5_000L)
        w.follow(nfl())
        w.ok(nfl())
        w.at(5_000L)
        w.fail(FailKind.HTTP_429, retryAfterS = 60)
        assertEquals(65_000L, w.nextPoll(), "Retry-After stays a floor")
    }

    @Test
    fun theRiseToSeenThresholdIsTheInterval() {
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl(), Demand.UNSEEN)
        r.ok(nfl())
        r.at(6_000L)
        r.m.demand(Demand.SEEN)
        assertTrue(r.m.pollDue(), "the last answer is older than one 5 s interval: poll at once")
        val n = Rig(nhlStart, liveMs = 20_000L)
        n.follow(nhl(), Demand.UNSEEN)
        n.ok(nhl())
        n.at(6_000L)
        n.m.demand(Demand.SEEN)
        assertTrue(!n.m.pollDue(), "NHL 20 s: a 6 s old answer is fresh")
    }

    @Test
    fun staleIsSixtySecondsOrTwiceTheCadence() {
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl())
        r.ok(nfl())
        r.at(1_000L)
        assertNotNull(r.m.view())
        r.at(59_999L)
        assertEquals(false, r.m.view()!!.dim)
        r.at(60_000L)
        assertEquals(CentreText.NoUpdate, r.m.view()!!.top, "max(60 s, 2 × 5 s) = 60 s")
        // A 60 s NHL interval has a 120 s break: stale after 2 × 120 = 240 s in the break.
        val n = Rig(nhlStart, liveMs = 60_000L)
        n.follow(nhl())
        n.ok(nhl(phase = GamePhase.INTERMISSION, secs = 0))
        n.at(239_999L)
        assertEquals(false, n.m.view()!!.dim)
        n.at(240_000L)
        assertEquals(true, n.m.view()!!.dim)
    }

    // ------------------------------------------------------------------ B1: goals after long breaks

    @Test
    fun aGoalOnTheFirstPollAfterASixtySecondIntervalBreakIsFlagged() {
        val r = Rig(nhlStart, liveMs = 60_000L)
        r.follow(nhl())
        r.ok(nhl(phase = GamePhase.INTERMISSION, secs = 0, home = 2))
        r.at(121_500L)
        r.ok(nhl(period = 3, secs = 1150, home = 3))
        r.at(121_501L)
        assertEquals(Side.HOME, r.m.view()!!.goalSide, "120 s after the last snapshot: still a goal (B1)")
        // Today's 100 s at 20 s: a 120 s gap is silent.
        val t = Rig(nhlStart, liveMs = 20_000L)
        t.follow(nhl())
        t.ok(nhl(phase = GamePhase.INTERMISSION, secs = 0, home = 2))
        t.at(121_500L)
        t.ok(nhl(period = 3, secs = 1150, home = 3))
        t.at(121_501L)
        assertNull(t.m.view()!!.goalSide, "NHL 20 s keeps the 100 s window")
        // A genuinely old comparison is still silent at 60 s (> 2 × 120 + 20 s).
        val o = Rig(nhlStart, liveMs = 60_000L)
        o.follow(nhl())
        o.ok(nhl(phase = GamePhase.INTERMISSION, secs = 0, home = 2))
        o.at(300_000L)
        o.ok(nhl(period = 3, secs = 1150, home = 3))
        o.at(300_001L)
        assertNull(o.m.view()?.goalSide)
    }

    // ------------------------------------------------------------------ S5: never back in time

    @Test
    fun anOlderNflBodyIsANoOpOk() {
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl())
        r.ok(nfl(away = 24, secs = 200, lastPlayId = "4013906"))
        r.at(5_000L)
        r.ok(nfl(away = 30, secs = 190, lastPlayId = "4013936")) // TD
        r.at(5_001L)
        val td = r.m.view()!!
        assertEquals(30, td.away.score)
        assertEquals(Side.AWAY, td.goalSide)
        // The board fallback's first answer lags: the pre-TD body with the older lastPlay.
        r.at(10_000L)
        r.ok(nfl(away = 24, secs = 200, lastPlayId = "4013906"))
        r.at(10_001L)
        assertEquals(30, r.m.view()!!.away.score, "the score never drops")
        assertEquals(td.goalSeq, r.m.view()!!.goalSeq)
        r.at(15_000L)
        r.ok(nfl(away = 30, secs = 190, lastPlayId = "4013936"))
        r.at(15_001L)
        assertEquals(td.goalSeq, r.m.view()!!.goalSeq, "no second takeover")
        // The older body still counted as an Ok: the schedule ran from it.
        assertEquals(20_000L, r.nextPoll())
        // With no ids: an earlier clock at an equal or lower score sum is older too.
        val n = Rig(nflStart, liveMs = 5_000L)
        n.follow(nfl())
        n.ok(nfl(secs = 100))
        n.at(5_000L)
        n.ok(nfl(secs = 150))
        n.at(5_001L)
        assertEquals(100, n.m.view()!!.clockSecs)
    }

    @Test
    fun anOlderBodyIsHeldAtMostThirtySeconds() {
        // Fix pass 5: ESPN deletes an overturned TD and lastPlay goes back to a lower id for good.
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl())
        r.ok(nfl(away = 24, lastPlayId = "4013906"))
        r.at(5_000L)
        r.ok(nfl(away = 30, secs = 190, lastPlayId = "4013936")) // the TD, later overturned
        r.at(5_001L)
        assertEquals(30, r.m.view()!!.away.score)
        var t = 10_000L
        while (t < 10_000L + SportsConstants.NFL_OLDER_HOLD_MAX_MS) {
            r.at(t)
            r.ok(nfl(away = 24, secs = 185, lastPlayId = "4013906"))
            r.at(t + 1)
            assertEquals(30, r.m.view()!!.away.score, "held at $t")
            t += 5_000L
        }
        r.at(t)
        r.ok(nfl(away = 24, secs = 180, lastPlayId = "4013906"))
        r.at(t + 1)
        assertEquals(24, r.m.view()!!.away.score, "the correction shows after 30 s")
    }

    @Test
    fun anOlderBodyWithTheSameScoreStaysANoOpAndAFinalIsNeverHeld() {
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl())
        r.ok(nfl(secs = 200, lastPlayId = "4013936"))
        r.at(1L)
        r.at(5_000L)
        r.ok(nfl(secs = 210, lastPlayId = "4013906"))
        r.at(40_000L)
        r.ok(nfl(secs = 215, lastPlayId = "4013906"))
        r.at(45_001L)
        assertEquals(200, r.m.view()!!.clockSecs, "same score and phase: still held")
        // A Final body is never held, however its lastPlay compares.
        r.at(50_000L)
        r.ok(nfl(away = 27, phase = GamePhase.FINAL, lastPlayId = "4013900"))
        r.at(50_001L)
        assertEquals(27, r.m.view()!!.away.score)
    }

    @Test
    fun identicalBodiesAreEqualViews() {
        // N3: the StateFlow dedupes equal views, so an unchanged body never recomposes the bug.
        val r = Rig(nflStart, liveMs = 5_000L)
        r.follow(nfl())
        r.ok(nfl())
        r.at(1L)
        val a = r.m.view()
        r.at(5_000L)
        r.ok(nfl())
        r.at(5_001L)
        assertEquals(a, r.m.view())
    }
}
