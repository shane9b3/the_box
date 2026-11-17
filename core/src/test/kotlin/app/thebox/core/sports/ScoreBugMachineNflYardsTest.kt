package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (NFL YDS §1.11, §1.8; S7) The machine copies each view's own entry's yards into `NflView`, both or neither, with no
 * hold across snapshots, so the yards ride their snapshot through the Delay exactly as its score does. And (§1.12) the
 * change-of-ends sequence reads, in the Pill's fields, as two `STATUS_END_PERIOD` bodies followed by the play.
 */
class ScoreBugMachineNflYardsTest {
    private class Rig(start: Long, delaySec: Int = 0) {
        var now = 0L
        val wall0 = start + 30 * 60_000L
        val m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))

        init {
            m.prefs(delaySec)
        }

        fun follow(g: GameSnapshot) {
            m.follow(SportsFixtures.followedOf(g))
            m.demand(Demand.SEEN)
        }

        fun ok(s: GameSnapshot) = m.fetched(Fetch.Ok(s, now, wall0 + now, 0))

        fun at(t: Long) {
            now = t
            m.step()
        }

        fun view(): ScoreBugView? = m.view()
    }

    private var seq = 100L

    private fun g(
        type: String = "24",
        home: Int = 0,
        phase: GamePhase = GamePhase.LIVE,
        breakKind: NflBreak? = null,
        period: Int = 2,
        away: Int? = null,
        homeY: Int? = null,
    ): GameSnapshot = SportsFixtures.nflGame(
        phase = phase,
        away = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs", 7),
        home = SportsFixtures.nflTeam("BUF", "Buffalo Bills", "Bills", home),
        period = period,
        clock = GameClock(if (phase == GamePhase.INTERMISSION) 0 else 300, running = false, inIntermission = phase == GamePhase.INTERMISSION),
        detail = SportsFixtures.nflDetail(
            breakKind = breakKind,
            lastPlay = NflLastPlay("401899001${++seq}", type, "t", Side.HOME, 0),
            awayTimeouts = 3, homeTimeouts = 3,
        ).copy(awayYards = away, homeYards = homeY),
    )

    private fun yards(v: ScoreBugView?): Pair<Int?, Int?> = v?.nfl?.let { it.awayYards to it.homeYards } ?: (null to null)

    @Test fun shownEntriesCarryTheYardsBothOrNeither() {
        val first = g()
        val r = Rig(first.startUtcMs).also { it.follow(first); it.ok(first) }
        assertEquals(null to null, yards(r.view()))
        r.at(20_000); r.ok(g(type = "21", away = 142, homeY = 51))
        assertEquals(142 to 51, yards(r.view()))
        r.at(40_000); r.ok(g(type = "21", away = 142, homeY = null)) // one side only: neither
        assertEquals(null to null, yards(r.view()))
        r.at(60_000); r.ok(g(type = "74", away = -2, homeY = 999))
        assertEquals(-2 to 999, yards(r.view()))
        r.at(80_000); r.ok(g()) // the next play: none (no hold across snapshots)
        assertEquals(null to null, yards(r.view()))
    }

    @Test fun withADelayTheYardsAppearInTheSameViewAsTheirSnapshotsScore() {
        val first = g()
        val r = Rig(first.startUtcMs, delaySec = 30).also { it.follow(first); it.ok(first) }
        r.at(10_000); r.ok(g(type = "21", home = 3, away = 142, homeY = 51)) // the stoppage after a field goal
        r.at(20_000); r.ok(g(home = 3)) // the next play: no yards
        var sawYards = false
        var t = 10_000L
        while (t <= 80_000L) {
            r.at(t)
            val v = r.view()
            if (v != null && !v.dim) {
                val y = yards(v)
                // Never ahead of the score of the same snapshot: yards shown ⇔ the timeout entry is the one shown.
                if (y.first != null) {
                    sawYards = true
                    assertEquals(3, v.home.score)
                    assertTrue(t >= 40_000, "not before the entry's show time (t=$t)")
                }
                if (t < 40_000) assertEquals(null to null, y, "t=$t")
                if (t >= 50_000) assertEquals(null to null, y, "the next play's entry has none (t=$t)")
            }
            t += 500
        }
        assertTrue(sawYards)
    }

    @Test fun aWaitingViewShowsTheOldestEntrysYardsWithItsScore() {
        // (S7) The first view under a Delay is "waiting": it shows the oldest queued entry, score and yards alike.
        val first = g(type = "21", home = 3, away = 142, homeY = 51)
        val r = Rig(first.startUtcMs, delaySec = 30).also { it.follow(first); it.ok(first) }
        val v = assertNotNull(r.view())
        assertEquals(CentreText.Delayed, v.top)
        assertEquals(3, v.home.score)
        assertEquals(142 to 51, yards(v))
        // And a waiting view over an entry without yards has none.
        val plain = g(home = 3)
        val q = Rig(plain.startUtcMs, delaySec = 30).also { it.follow(plain); it.ok(plain) }
        assertEquals(CentreText.Delayed, assertNotNull(q.view()).top)
        assertEquals(null to null, yards(q.view()))
    }

    @Test fun aStaleBreakKeepsItsYardsAndTheNextPlayHasNone() {
        val first = g()
        val r = Rig(first.startUtcMs).also { it.follow(first); it.ok(first) }
        r.at(20_000); r.ok(g(phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME, away = 75, homeY = -2))
        assertEquals(75 to -2, yards(r.view()))
        var t = 20_000L
        var stale: ScoreBugView? = null
        while (t < 20_000L + SportsConstants.HIDE_AFTER_MS) {
            t += 1_000
            r.at(t)
            val v = r.view()
            if (v?.top == CentreText.NoUpdate) { stale = v; break }
        }
        val s = assertNotNull(stale, "the break goes stale")
        assertTrue(s.dim)
        assertEquals(75 to -2, yards(s))
        r.at(t + 1_000); r.ok(g(period = 3))
        assertEquals(null to null, yards(r.view()))
    }

    // ------------------------------------------------------------------ §1.12 the Pill and the change of ends

    private fun pillSequence(names: List<String>): List<ScoreBugView?> {
        val shots = names.map { NflYardsFixtures.snapshot(it)!! }
        val r = Rig(shots.first().startUtcMs).also { it.follow(shots.first()); it.ok(shots.first()) }
        val out = ArrayList<ScoreBugView?>()
        out += r.view()
        var t = 0L
        for (s in shots.drop(1)) {
            t += 20_000
            r.at(t); r.ok(s)
            out += r.view()
        }
        return out
    }

    /** What the Pill reads: everything but the Stacked-only `nfl` detail. */
    private fun pill(v: ScoreBugView?) = v?.copy(nfl = null)

    @Test fun theChangeOfEndsReadsAsTwoEndPeriodBodiesThenThePlay() {
        val real = pillSequence(listOf(NflYardsFixtures.END_PERIOD, NflYardsFixtures.CHANGE_OF_ENDS, NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS))
        val twin = pillSequence(listOf(NflYardsFixtures.END_PERIOD, NflYardsFixtures.END_PERIOD, NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS))
        assertEquals(twin.map(::pill), real.map(::pill))
        assertTrue(real[0]?.top is CentreText.EndOf)
        assertTrue(real[1]?.top is CentreText.EndOf, "the change of ends keeps End of 1st")
        assertNull(real[1]?.bottom, "one line, as in a break")
        assertTrue(real[2]?.top is CentreText.Period, "the first snap is play in the 2nd")
        assertEquals(CentreText.Clock, real[2]?.bottom)
    }
}
