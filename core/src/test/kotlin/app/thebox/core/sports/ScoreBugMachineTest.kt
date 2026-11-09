package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-score-bug.md §2.3–§2.7, §8. A fake mono clock ([Rig.now]) and a wall clock tied to it; the server's
 * `Date` is the true wall time, the device's may be skewed. "Wake" tests feed no input: they only move the clock to
 * `nextWakeAtMono()` and call `step()` (A-B1).
 */
class ScoreBugMachineTest {
    private class Rig(startOffsetMs: Long = 30 * 60_000L, deviceSkewMs: Long = 0, phaseMs: Long = 1_500L, delaySec: Int = 0) {
        var now = 0L
        val start = 1_793_923_200_000L // SportsFixtures.game()'s start
        /** True (server) time at mono 0. */
        val wall0 = start + startOffsetMs
        val m = ScoreBugMachine({ now }, { wall0 + now + deviceSkewMs }, phaseMs, Random(7))

        init {
            m.prefs(delaySec)
        }

        fun server(t: Long = now) = wall0 + t

        fun follow(g: GameSnapshot = SportsFixtures.game(), demand: Demand = Demand.SEEN) {
            m.follow(SportsFixtures.followedOf(g))
            m.demand(demand)
        }

        fun ok(s: GameSnapshot, ageS: Int = 0, date: Long? = server()) = m.fetched(Fetch.Ok(s, now, date, ageS))

        fun at(t: Long) {
            now = t
            m.step()
        }

        /** Moves only through wake-ups (no input) up to [until]; returns each wake instant with the view after it. */
        fun wakeUntil(until: Long): List<Pair<Long, ScoreBugView?>> {
            val seen = ArrayList<Pair<Long, ScoreBugView?>>()
            while (true) {
                val w = m.nextWakeAtMono() ?: break
                check(w > now) { "a poll is due at $w (now $now); a wake test must not need input" }
                if (w > until) break
                now = w
                m.step()
                seen += w to m.view()
                if (m.followed() == null) break
            }
            return seen
        }
    }

    private fun live(
        secs: Int = 622,
        away: Int = 0,
        home: Int = 2,
        period: Int = 2,
        running: Boolean = true,
        phase: GamePhase = GamePhase.LIVE,
        kind: PeriodKind = PeriodKind.REG,
        ot: Int? = null,
        inInt: Boolean = false,
    ) = SportsFixtures.game(
        phase = phase,
        away = SportsFixtures.team("CGY", "Flames", away),
        home = SportsFixtures.team("EDM", "Oilers", home),
        period = period, periodKind = kind, otNumber = ot,
        clock = GameClock(secs, running, inInt),
    )

    private fun final(away: Int = 0, home: Int = 2, kind: PeriodKind = PeriodKind.REG) =
        live(away = away, home = home, period = 3, running = false, secs = 0, phase = GamePhase.FINAL).copy(finalKind = kind)

    private fun pre(phase: GamePhase = GamePhase.PRE) = SportsFixtures.game(
        phase = phase, away = SportsFixtures.team("CGY", "Flames"), home = SportsFixtures.team("EDM", "Oilers"),
        period = null, periodKind = null, clock = null,
    )

    // ------------------------------------------------------------------ cadence (§2.3)

    @Test
    fun cadenceTableWithBucketAlignment() {
        val r = Rig()
        r.follow()
        assertTrue(r.m.pollDue(), "one poll on Follow")
        r.at(1_000)
        r.ok(live(), ageS = 5) // bucket ends at 1 000 + 15 000; + phase 1 500
        assertFalse(r.m.pollDue())
        assertEquals(17_500, r.m.nextWakeAtMono())
        r.at(17_500)
        assertTrue(r.m.pollDue())
        // Intermission skips one bucket.
        r.ok(live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958), ageS = 2)
        assertEquals(17_500 + 18_000 + 1_500 + 20_000, r.m.nextWakeAtMono())
        // No Date: received + 20 s.
        val n = Rig()
        n.follow()
        n.at(3_000)
        n.ok(live(), ageS = 5, date = null)
        assertEquals(23_000, n.m.nextWakeAtMono())
        // Live with a stopped clock polls like live.
        val s = Rig()
        s.follow()
        s.ok(live(running = false), ageS = 0)
        assertEquals(21_500, s.m.nextWakeAtMono())
    }

    @Test
    fun unseenIsTheMaxOfCadenceAnd120sAndFinalNeverPolls() {
        val r = Rig()
        r.follow(demand = Demand.UNSEEN)
        assertTrue(r.m.pollDue())
        r.at(1_000)
        r.ok(live(running = false), ageS = 5) // stopped: no cap instant in the wake list
        assertEquals(17_500 + 6 * 20_000, r.m.nextWakeAtMono()) // first aligned instant ≥ received + 120 s
        r.at(137_500)
        assertTrue(r.m.pollDue())
        r.ok(final())
        assertFalse(r.m.pollDue())
        r.m.demand(Demand.SEEN)
        assertFalse(r.m.pollDue(), "a final game does not poll on a rise to SEEN either")
        val f = r.m.nextWakeAtMono()
        assertEquals(137_500 + SportsConstants.EXPIRE_AFTER_FINAL_MS, f, "only the expiry is left")
    }

    @Test
    fun preGamePollsOnceSleepsUntilStartMinus2MinThenEvery40sAndDrawsNothing() {
        val r = Rig(startOffsetMs = -3_600_000L) // an hour before puck drop
        r.follow(pre(GamePhase.SCHEDULED))
        assertTrue(r.m.pollDue())
        r.ok(pre(GamePhase.SCHEDULED), ageS = 0)
        assertNull(r.m.view())
        val preWake = 3_600_000L - SportsConstants.PRE_WAKE_MS
        val first = assertNotNull(r.m.nextWakeAtMono())
        assertTrue(first >= preWake && first < preWake + 20_000, "first poll $first at start − 2 min")
        // Nothing before it: wakes only (no input) see no poll and no view.
        r.m.online(false)
        r.wakeUntil(preWake - 1).forEach { (_, v) -> assertNull(v) }
        r.m.online(true)
        r.at(first)
        assertTrue(r.m.pollDue())
        var t = first
        repeat(3) {
            r.ok(pre(), ageS = 1)
            assertNull(r.m.view())
            val next = assertNotNull(r.m.nextWakeAtMono())
            assertEquals(19_000 + 20_000 + 1_500, next - t) // bucket end (Age 1) + one skipped bucket + phase
            t = next
            r.at(t)
            assertTrue(r.m.pollDue())
        }
        // The first LIVE snapshot makes the bug appear.
        r.ok(live(secs = 1200, period = 1, away = 0, home = 0), ageS = 1)
        assertNotNull(r.m.view())
    }

    @Test
    fun preGameViewIsNullUntilLiveOnTheDelayedTimeline() {
        val r = Rig(delaySec = 30, startOffsetMs = -60_000L)
        r.follow(pre())
        r.ok(pre())
        for (t in listOf(1_000L, 29_000L, 30_000L, 45_000L)) {
            r.at(t)
            assertNull(r.m.view(), "pre at $t")
        }
        r.ok(live(secs = 1200, period = 1, away = 0, home = 0)) // received 45 000, shown from 75 000
        r.at(74_999)
        assertNull(r.m.view())
        r.at(75_000)
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 1)), assertNotNull(r.m.view()).bottom)
    }

    @Test
    fun riseToSeenPollsAtOnceAndSuppressesNoUpdate() {
        val r = Rig()
        r.follow(demand = Demand.UNSEEN)
        r.ok(live())
        r.m.online(false)
        r.at(250_000) // UNSEEN stale threshold: max(60 s, 2 × 120 s) = 240 s
        assertEquals(CentreText.NoUpdate, r.m.view()!!.bottom)
        r.m.online(true)
        r.m.demand(Demand.SEEN)
        assertTrue(r.m.pollDue())
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), r.m.view()!!.bottom, "No update suppressed until the poll")
        assertFalse(r.m.view()!!.dim)
        r.m.fetched(Fetch.Failed(FailKind.TIMEOUT, null, r.now))
        assertEquals(CentreText.NoUpdate, r.m.view()!!.bottom, "the poll failed: stale again")

        // A fresh answer (≤ 20 s old) does not poll on the rise.
        val f = Rig()
        f.follow(demand = Demand.UNSEEN)
        f.ok(live(), ageS = 0)
        f.at(10_000)
        f.m.demand(Demand.SEEN)
        assertFalse(f.m.pollDue())
        assertEquals(21_500, f.m.nextWakeAtMono())
    }

    @Test
    fun riseToSeenWhileOfflineKeepsNoUpdateAndHide() {
        // C-S2 / C-N6: the rise happens while offline (the Wi-Fi dropped, or Awake(true) before the network is back).
        val r = Rig()
        r.follow(demand = Demand.UNSEEN)
        r.ok(live())
        r.m.online(false)
        r.at(250_000)
        assertEquals(CentreText.NoUpdate, r.m.view()!!.bottom)
        r.m.demand(Demand.UNSEEN)
        r.m.demand(Demand.SEEN)
        assertFalse(r.m.pollDue())
        assertEquals(CentreText.NoUpdate, r.m.view()!!.bottom, "no poll can answer: still stale")
        assertTrue(r.m.view()!!.dim)
        r.at(3_600_000)
        assertNull(r.m.view(), "and it hides after 5 min stale, offline")
        assertNotNull(r.m.followed())
        // Suppression set while online is cleared when the network goes.
        val o = Rig()
        o.follow(demand = Demand.UNSEEN)
        o.ok(live())
        o.at(250_000)
        o.m.demand(Demand.SEEN) // online: poll at once, "No update" suppressed
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), o.m.view()!!.bottom)
        o.m.online(false)
        assertEquals(CentreText.NoUpdate, o.m.view()!!.bottom)
    }

    @Test
    fun riseToSeenNeverPollsAheadOfABackoffOrRetryAfter() {
        // C-S3 / C-N6: a 429 with Retry-After 300, then the user zaps guide ↔ full screen.
        val r = Rig()
        r.follow()
        r.ok(live())
        r.at(21_500)
        assertTrue(r.m.pollDue())
        r.m.fetched(Fetch.Failed(FailKind.HTTP_429, 300, r.now))
        for (k in 1..20) {
            r.at(21_500 + k * 2_000L)
            r.m.demand(Demand.UNSEEN)
            r.m.demand(Demand.SEEN)
            assertFalse(r.m.pollDue(), "edge $k polled inside the Retry-After")
        }
        r.at(321_499)
        assertFalse(r.m.pollDue())
        r.at(321_500)
        assertTrue(r.m.pollDue())
    }

    @Test
    fun demandNoneIsIdle() {
        val r = Rig()
        r.follow(demand = Demand.NONE)
        assertFalse(r.m.pollDue())
        assertNull(r.m.nextWakeAtMono())
        r.m.demand(Demand.SEEN)
        r.ok(live())
        r.m.demand(Demand.NONE)
        for (t in listOf(0L, 21_500L, 600_000L)) {
            r.at(t)
            assertFalse(r.m.pollDue())
            assertNull(r.m.nextWakeAtMono())
        }
        // Offline: no poll is due, but the wake list still has the time-driven changes.
        val o = Rig()
        o.follow()
        o.ok(live())
        o.m.online(false)
        assertFalse(o.m.pollDue())
        assertEquals(60_000, o.m.nextWakeAtMono()) // stale (no clock wake-ups: owner ruling 6)
    }

    // ------------------------------------------------------------------ wake tests with no input (A-B1)

    @Test
    fun finalClearsExactlyFiveMinutesAfterTheFinal() {
        val r = Rig()
        r.follow()
        r.at(1_000)
        r.ok(live())
        r.at(20_000)
        r.ok(final(away = 1)) // OT-less final with a late goal
        assertEquals(CentreText.Final(PeriodKind.REG), r.m.view()!!.top)
        assertNull(r.m.view()!!.bottom, "one line, even with the late goal")
        assertEquals(Side.AWAY, r.m.view()!!.goalSide)
        assertEquals(r.server(20_000), r.m.followed()!!.finalSeenUtcMs)
        val wakes = r.wakeUntil(Long.MAX_VALUE / 4)
        assertEquals(20_000 + SportsConstants.EXPIRE_AFTER_FINAL_MS, wakes.last().first)
        assertNull(r.m.followed())
        assertNull(r.m.view())
        assertTrue(r.m.drainEvents().isEmpty(), "expiry after the final is silent")
        assertNull(r.m.nextWakeAtMono())
    }

    @Test
    fun thirtySecondDelayScoreAndFinalLandOnTimeAfterPollingStopped() {
        val r = Rig(delaySec = 30)
        r.follow()
        r.ok(live(secs = 622)) // anchor 0 → shown 30 000
        r.at(20_000)
        r.ok(live(secs = 606, away = 1), ageS = 4) // anchor 16 000 → shown 46 000
        r.at(40_000)
        r.ok(final(away = 1)) // anchor 40 000 → shown 70 000
        assertFalse(r.m.pollDue())
        assertEquals(0, r.m.view()!!.away.score)
        val wakes = r.wakeUntil(Long.MAX_VALUE / 4).toMap()
        assertEquals(1, wakes.getValue(46_000)!!.away.score)
        assertEquals(1, wakes.getValue(46_000)!!.goalSeq)
        assertEquals(Side.AWAY, wakes.getValue(46_000)!!.goalSide)
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), wakes.getValue(46_000)!!.bottom) // never replaced
        assertNull(wakes.getValue(46_000 + SportsConstants.GOAL_MARK_MS)!!.goalSide) // the 4.5 s mark ends on a wake
        assertEquals(CentreText.Final(PeriodKind.REG), wakes.getValue(70_000)!!.top)
        assertTrue(370_000L in wakes)
        assertNull(wakes.getValue(370_000))
        assertNull(r.m.followed())
    }

    @Test
    fun staleFiresAtItsThresholdAndHideAtFiveMinutes() {
        val r = Rig()
        r.follow()
        r.ok(live())
        r.m.online(false)
        val wakes = r.wakeUntil(400_000)
        assertEquals(listOf(60_000L, 360_000L), wakes.map { it.first })
        assertEquals(CentreText.NoUpdate, wakes[0].second!!.bottom)
        assertTrue(wakes[0].second!!.dim)
        assertEquals(622, wakes[0].second!!.clockSecs, "stale shows the last snapshot's clock")
        assertNull(wakes[1].second, "hidden")
        assertNotNull(r.m.followed(), "the follow is kept")
        // The bug returns on the next Ok.
        r.m.online(true)
        r.ok(live(secs = 300))
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), r.m.view()!!.bottom)
    }

    @Test
    fun staleShowsTheSnapshotClockInIntermissionToo() {
        val r = Rig()
        r.follow()
        r.ok(live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958, period = 1))
        r.m.online(false)
        r.at(80_000) // intermission: max(60 s, 2 × 40 s)
        val v = assertNotNull(r.m.view())
        assertEquals(CentreText.NoUpdate, v.bottom)
        assertEquals(CentreText.Intermission(PeriodLabel(PeriodKind.REG, 1)), v.top)
        assertNull(v.clockSecs, "ruling 33: no clock in a break")
    }

    @Test
    fun hardExpiryFiresEightHoursAfterTheStart() {
        val r = Rig(startOffsetMs = SportsConstants.HARD_EXPIRY_MS - 120_000L)
        r.follow()
        r.ok(live(secs = 30, period = 3))
        r.m.online(false)
        val wakes = r.wakeUntil(Long.MAX_VALUE / 4)
        assertEquals(120_000L, wakes.last().first)
        assertNull(r.m.followed())
    }

    // ------------------------------------------------------------------ the clock (owner ruling 6: no local countdown)

    @Test
    fun shownClockIsExactlyTheSnapshotValueAndStepsOnlyOnANewSnapshot() {
        val r = Rig()
        r.follow()
        r.ok(live(secs = 622))
        for (t in 0L until 20_000L step 1_000L) {
            r.at(t)
            assertEquals(622, r.m.view()!!.clockSecs, "no countdown between snapshots (at $t)")
        }
        r.at(20_000)
        r.ok(live(secs = 610)) // the feed lagged 8 s behind the old extrapolation: shown as reported
        assertEquals(610, r.m.view()!!.clockSecs)
        r.at(40_000)
        r.ok(live(secs = 610, running = false)) // a whistle: unchanged
        assertEquals(610, r.m.view()!!.clockSecs)
        r.at(60_000)
        r.ok(live(secs = 640, running = false)) // a clock correction upward: the feed wins, no hold
        assertEquals(640, r.m.view()!!.clockSecs)
        r.at(80_000)
        r.ok(live(secs = 1200, period = 3))
        assertEquals(1200, r.m.view()!!.clockSecs)
        // Intermission (ruling 33): one line, no clock at all.
        r.at(100_000)
        r.ok(live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958, period = 3))
        r.at(139_000)
        assertNull(r.m.view()!!.clockSecs)
        // A negative feed value at the end of a period is "End 3rd", one line with no clock.
        r.at(140_000)
        r.ok(live(secs = -3, running = false, period = 3))
        assertNull(r.m.view()!!.clockSecs)
        assertEquals(CentreText.EndOf(PeriodLabel(PeriodKind.REG, 3)), r.m.view()!!.top)
        assertNull(r.m.view()!!.bottom)
        r.at(160_000)
        r.ok(final())
        assertNull(r.m.view()!!.clockSecs)
    }

    @Test
    fun noWakeIsScheduledForClockTicks() {
        // Running, stopped and intermission: offline, the only wakes are stale and hide; online, the poll comes first.
        for (snap in listOf(live(secs = 622), live(secs = 622, running = false), live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958))) {
            val r = Rig()
            r.follow()
            r.ok(snap)
            r.m.online(false)
            val wakes = r.wakeUntil(400_000).map { it.first }
            val stale = if (snap.phase == GamePhase.INTERMISSION) 80_000L else 60_000L
            assertEquals(listOf(stale, stale + SportsConstants.HIDE_AFTER_MS), wakes, "wakes for ${snap.phase} running=${snap.clock?.running}")
            val o = Rig()
            o.follow()
            o.ok(snap)
            val next = assertNotNull(o.m.nextWakeAtMono())
            assertTrue(next >= 20_000, "first wake $next is the poll, not a clock tick")
            assertTrue(o.m.pollDue().not())
        }
    }

    @Test
    fun withADelayTheClockStepsWhenEachDelayedSnapshotBecomesVisible() {
        val r = Rig(delaySec = 30)
        r.follow()
        r.ok(live(secs = 622)) // shown from 30 000
        r.at(20_000)
        r.ok(live(secs = 602)) // shown from 50 000
        r.m.online(false)
        r.at(30_000)
        assertEquals(622, r.m.view()!!.clockSecs)
        r.at(49_999)
        assertEquals(622, r.m.view()!!.clockSecs, "the delayed timeline, not the newest answer")
        assertEquals(50_000, r.m.nextWakeAtMono())
        r.at(50_000)
        assertEquals(602, r.m.view()!!.clockSecs)
    }

    // ------------------------------------------------------------------ states (§4.5)

    @Test
    fun centreColumnStates() {
        fun viewOf(s: GameSnapshot): ScoreBugView {
            val r = Rig()
            r.follow()
            r.ok(s)
            return assertNotNull(r.m.view())
        }
        fun pair(v: ScoreBugView) = v.top to v.bottom
        assertEquals(CentreText.Clock to CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), pair(viewOf(live())))
        assertEquals(CentreText.Clock to CentreText.Period(PeriodLabel(PeriodKind.OT, 2)), pair(viewOf(live(period = 5, kind = PeriodKind.OT, ot = 2))))
        assertEquals(CentreText.Shootout to null, pair(viewOf(live(period = 5, kind = PeriodKind.SO, secs = 0, running = false))))
        assertEquals(
            CentreText.Intermission(PeriodLabel(PeriodKind.REG, 1)) to null,
            pair(viewOf(live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958, period = 1))),
        )
        assertEquals(CentreText.EndOf(PeriodLabel(PeriodKind.REG, 3)) to null, pair(viewOf(live(secs = 0, running = false, period = 3))))
        assertEquals(CentreText.Final(PeriodKind.OT) to null, pair(viewOf(final(kind = PeriodKind.OT))))
        assertEquals(CentreText.Final(PeriodKind.SO) to null, pair(viewOf(final(kind = PeriodKind.SO))))
        val v = viewOf(live())
        assertEquals(SportsFixtures.liveView(live()), v)
    }

    @Test
    fun delayedWaitingShowsTheOldestDimmedAndFrozen() {
        val r = Rig(delaySec = 30)
        r.follow()
        r.ok(live(secs = 622))
        r.m.online(false)
        val v = assertNotNull(r.m.view())
        assertEquals(CentreText.Delayed, v.bottom)
        assertTrue(v.dim)
        assertEquals(622, v.clockSecs)
        assertEquals(30_000, r.m.nextWakeAtMono())
        r.at(30_000)
        assertFalse(r.m.view()!!.dim)
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), r.m.view()!!.bottom)
        assertEquals(622, r.m.view()!!.clockSecs)
        // Changing the delay takes effect at once.
        val c = Rig(delaySec = 30)
        c.follow()
        c.ok(live(secs = 622))
        c.at(5_000)
        assertTrue(c.m.view()!!.dim)
        c.m.prefs(0)
        assertFalse(c.m.view()!!.dim)
        assertEquals(622, c.m.view()!!.clockSecs)
    }

    // ------------------------------------------------------------------ queue and follow

    @Test
    fun goalSeqOncePerRiseOnTheDelayedTimelineNotOnADropNotWhileUnseen() {
        val r = Rig()
        r.follow()
        r.ok(live(away = 0, home = 2))
        r.at(20_000)
        r.ok(live(secs = 602, away = 1, home = 2))
        var v = r.m.view()!!
        assertEquals(1, v.goalSeq)
        assertEquals(Side.AWAY, v.goalSide)
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), v.bottom)
        r.at(20_000 + SportsConstants.GOAL_MARK_MS - 1)
        assertEquals(Side.AWAY, r.m.view()!!.goalSide)
        r.at(20_000 + SportsConstants.GOAL_MARK_MS)
        v = r.m.view()!!
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), v.bottom)
        assertNull(v.goalSide)
        r.at(40_000)
        r.ok(live(secs = 582, away = 0, home = 2)) // taken back: silent
        assertEquals(1, r.m.view()!!.goalSeq)
        assertEquals(0, r.m.view()!!.away.score)
        r.at(60_000)
        r.ok(live(secs = 562, away = 0, home = 3))
        assertEquals(2, r.m.view()!!.goalSeq)
        assertEquals(Side.HOME, r.m.view()!!.goalSide)

        val u = Rig()
        u.follow(demand = Demand.UNSEEN)
        u.ok(live(away = 0))
        u.at(120_000)
        u.ok(live(away = 1, secs = 502))
        assertEquals(0, u.m.view()!!.goalSeq)
        assertEquals(1, u.m.view()!!.away.score)
    }

    @Test
    fun noGoalAgainstAStaleComparisonNorWhenBothSidesRose() {
        // C-N1: an hour in the background (NONE), then back in FULL: the first answer updates silently.
        val r = Rig()
        r.follow()
        r.ok(live(away = 0, home = 2))
        r.m.demand(Demand.NONE)
        r.at(3_600_000)
        r.m.demand(Demand.SEEN)
        r.ok(live(away = 1, home = 2, period = 3))
        assertEquals(0, r.m.view()!!.goalSeq)
        assertNull(r.m.view()!!.goalSide)
        assertEquals(1, r.m.view()!!.away.score)
        // Both scores rose since the last snapshot: no tag, no box.
        val b = Rig()
        b.follow()
        b.ok(live(away = 0, home = 2))
        b.at(40_000)
        b.ok(live(away = 1, home = 3))
        assertEquals(0, b.m.view()!!.goalSeq)
    }

    @Test
    fun newestQualifyingSnapshotIsNeverPruned() {
        val r = Rig(delaySec = 120)
        r.follow()
        r.ok(live(away = 1))
        r.m.online(false)
        r.at(125_000)
        assertEquals(1, r.m.view()!!.away.score)
        r.at(200_000) // > QUEUE_KEEP_MS after its anchor, nothing newer arrived
        val v = assertNotNull(r.m.view())
        assertEquals(1, v.away.score)
        assertTrue(v.bottom != CentreText.Delayed)
    }

    @Test
    fun twoNotFoundsAtLeast20sApartRemoveTheFollow() {
        val r = Rig()
        r.follow()
        r.m.fetched(Fetch.NotFound)
        val next = assertNotNull(r.m.nextWakeAtMono())
        assertTrue(next >= 20_000)
        r.at(10_000)
        r.m.fetched(Fetch.NotFound) // too soon: not confirmed
        assertNotNull(r.m.followed())
        r.at(20_000)
        val g = r.m.followed()!!
        r.m.fetched(Fetch.NotFound)
        assertNull(r.m.followed())
        assertEquals(listOf<ScoreEvent>(ScoreEvent.Removed(RemoveReason.NOT_LISTED, g)), r.m.drainEvents())
        assertTrue(r.m.drainEvents().isEmpty())

        // An Ok in between resets the count.
        val o = Rig()
        o.follow()
        o.m.fetched(Fetch.NotFound)
        o.at(20_000)
        o.ok(live())
        o.at(40_000)
        o.m.fetched(Fetch.NotFound)
        assertNotNull(o.m.followed())
    }

    @Test
    fun postponedRemovesAtOnce() {
        val r = Rig()
        r.follow(pre())
        val g = r.m.followed()!!
        r.ok(pre().copy(phase = GamePhase.POSTPONED))
        assertNull(r.m.followed())
        assertEquals(listOf<ScoreEvent>(ScoreEvent.Removed(RemoveReason.POSTPONED, g)), r.m.drainEvents())
        assertNull(r.m.nextWakeAtMono())
    }

    @Test
    fun backoffSequenceAndRetryAfter() {
        val r = Rig()
        r.follow()
        val expected = listOf(20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L)
        for (base in expected) {
            assertTrue(r.m.pollDue())
            r.m.fetched(Fetch.Failed(FailKind.HTTP_5XX, null, r.now))
            val gap = r.m.nextWakeAtMono()!! - r.now
            assertTrue(gap >= base * 0.8 - 1 && gap <= base * 1.2 + 1, "gap $gap for $base")
            r.at(r.now + gap)
        }
        // Retry-After is a floor on the back-off (C-S4): at the 7th failure the 300 s step outlasts a 7 s Retry-After.
        r.m.fetched(Fetch.Failed(FailKind.HTTP_429, 7, r.now))
        val capped = r.m.nextWakeAtMono()!! - r.now
        assertTrue(capped in 240_000..360_000, "Retry-After 7 under a 300 s step: $capped")
        r.at(r.now + capped)
        r.ok(live(running = false), ageS = 0)
        assertEquals(r.now + 21_500, r.m.nextWakeAtMono(), "an Ok resets the back-off")
        r.at(r.now + 21_500)
        r.m.fetched(Fetch.Failed(FailKind.IO, null, r.now))
        val gap = r.m.nextWakeAtMono()!! - r.now
        assertTrue(gap in 16_000..24_000, "first step again: $gap")
    }

    @Test
    fun retryAfterZeroNeverPollsAtOneHertzAndALongOneWins() {
        // C-S4: `Retry-After: 0` from a CDN would otherwise make the next poll due at once.
        val r = Rig()
        r.follow()
        r.at(5_000)
        r.m.fetched(Fetch.Failed(FailKind.HTTP_5XX, 0, r.now))
        val gap = r.m.nextWakeAtMono()!! - r.now
        assertTrue(gap in 16_000..24_000, "Retry-After 0 keeps the 20 s step: $gap")
        assertFalse(r.m.pollDue())
        val l = Rig()
        l.follow()
        l.m.fetched(Fetch.Failed(FailKind.HTTP_429, 300, l.now))
        assertEquals(300_000, l.m.nextWakeAtMono())
    }

    @Test
    fun skewFromServerDateCorrectsExpiry() {
        // The device clock is 10 min slow: without the skew the 8 h expiry would fire 10 min late.
        val r = Rig(startOffsetMs = SportsConstants.HARD_EXPIRY_MS - 600_000L, deviceSkewMs = -600_000L)
        r.follow()
        r.ok(live(secs = 30, period = 3)) // Date = true time
        r.m.online(false)
        val wakes = r.wakeUntil(Long.MAX_VALUE / 4)
        assertEquals(600_000L, wakes.last().first)
        assertNull(r.m.followed())

        // And finalSeenUtcMs is stored in server time.
        val f = Rig(deviceSkewMs = 600_000L)
        f.follow()
        f.ok(live())
        f.at(20_000)
        f.ok(final())
        assertEquals(f.server(20_000), f.m.followed()!!.finalSeenUtcMs)
    }

    @Test
    fun restoredFollowExpiresFromItsPersistedFinal() {
        val r = Rig()
        val g = SportsFixtures.followedOf(final()).copy(finalSeenUtcMs = r.server(0) - 100_000)
        r.m.follow(g)
        r.m.demand(Demand.SEEN)
        r.m.online(false)
        val wakes = r.wakeUntil(Long.MAX_VALUE / 4)
        assertEquals(200_000L, wakes.last().first)
        assertNull(r.m.followed())
    }

    @Test
    fun startIsRewrittenFromASnapshotAndFollowNullClears() {
        val r = Rig()
        r.follow()
        r.ok(live().copy(startUtcMs = r.start + 60_000))
        assertEquals(r.start + 60_000, r.m.followed()!!.startUtcMs)
        r.m.follow(null)
        assertNull(r.m.followed())
        assertNull(r.m.view())
        assertNull(r.m.nextWakeAtMono())
        assertFalse(r.m.pollDue())
    }

    // ------------------------------------------------------------------ goal mark (goal animation §5.1, §5.2)

    @Test
    fun markLastsExactly4500OnTheDelayedTimelineAndEndsOnAWake() {
        val r = Rig(delaySec = 30)
        r.follow()
        r.ok(live(away = 0)) // anchor 0 → shown 30 000
        r.at(20_000)
        r.ok(live(secs = 602, away = 1)) // anchor 20 000 → shown 50 000
        r.m.online(false)
        r.at(49_999)
        assertNull(r.m.view()!!.goalSide, "not before the delayed snapshot is visible")
        assertEquals(0, r.m.view()!!.goalSeq)
        val wakes = r.wakeUntil(60_000).toMap()
        assertEquals(Side.AWAY, wakes.getValue(50_000)!!.goalSide, "marked at anchor + 30 s")
        assertEquals(1, wakes.getValue(50_000)!!.goalSeq)
        assertTrue(50_000L + SportsConstants.GOAL_MARK_MS in wakes, "a wake fires at the mark's end")
        assertNull(wakes.getValue(50_000L + SportsConstants.GOAL_MARK_MS)!!.goalSide)
        assertEquals(1, wakes.getValue(50_000L + SportsConstants.GOAL_MARK_MS)!!.goalSeq)
        // The same, stepped by hand to either side of the end.
        val s = Rig(delaySec = 30)
        s.follow()
        s.ok(live(away = 0))
        s.at(20_000)
        s.ok(live(secs = 602, away = 1))
        s.m.online(false)
        s.at(30_000) // the first snapshot shows
        s.at(50_000 + SportsConstants.GOAL_MARK_MS - 1)
        assertEquals(Side.AWAY, s.m.view()!!.goalSide, "late step: the mark still runs from the visible instant")
        assertEquals(Side.AWAY, s.m.view()!!.goalSide, "view() is read-only")
        s.at(50_000 + SportsConstants.GOAL_MARK_MS)
        assertNull(s.m.view()!!.goalSide)
        assertEquals(1, s.m.view()!!.goalSeq)
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), s.m.view()!!.bottom, "the bottom line is never replaced")
    }

    @Test
    fun secondGoalInsideTheMarkRestartsItWithTheNewSide() {
        val r = Rig()
        r.follow()
        r.ok(live(away = 0, home = 2))
        r.at(20_000)
        r.ok(live(secs = 602, away = 1, home = 2))
        r.at(22_000)
        r.ok(live(secs = 600, away = 1, home = 3))
        val v = r.m.view()!!
        assertEquals(2, v.goalSeq)
        assertEquals(Side.HOME, v.goalSide)
        r.at(22_000 + SportsConstants.GOAL_MARK_MS - 1)
        assertEquals(Side.HOME, r.m.view()!!.goalSide, "the mark runs from the second goal")
        r.at(22_000 + SportsConstants.GOAL_MARK_MS)
        assertNull(r.m.view()!!.goalSide)
    }

    @Test
    fun overtimeWinnerOnTheFinalSnapshotIsMarked() {
        val r = Rig()
        r.follow()
        r.ok(live(away = 2, home = 2, period = 4, kind = PeriodKind.OT, ot = 1, secs = 140))
        r.at(20_000)
        r.ok(final(away = 3, home = 2, kind = PeriodKind.OT))
        val v = r.m.view()!!
        assertEquals(CentreText.Final(PeriodKind.OT), v.top)
        assertEquals(1, v.goalSeq)
        assertEquals(Side.AWAY, v.goalSide)
    }

    @Test
    fun shootoutWinnersPlusOneIsMarked() {
        // Owner ruling 11: the winner's +1 at the end of a shootout counts as a goal for the animation.
        val r = Rig()
        r.follow()
        r.ok(live(away = 2, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false))
        assertEquals(CentreText.Shootout, r.m.view()!!.top)
        r.at(20_000)
        r.ok(final(away = 2, home = 3, kind = PeriodKind.SO))
        val v = r.m.view()!!
        assertEquals(CentreText.Final(PeriodKind.SO), v.top)
        assertEquals(1, v.goalSeq)
        assertEquals(Side.HOME, v.goalSide)
        // Also when the +1 lands while the snapshot still says SO (before the FINAL).
        val s = Rig()
        s.follow()
        s.ok(live(away = 2, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false))
        s.at(20_000)
        s.ok(live(away = 3, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false))
        assertEquals(Side.AWAY, s.m.view()!!.goalSide)
        assertEquals(1, s.m.view()!!.goalSeq)
    }

    @Test
    fun noTakeoverCases() {
        // The machine's side of §5.2's table: each of these leaves goalSeq alone and sets no mark.
        fun after(first: GameSnapshot, second: GameSnapshot): ScoreBugView {
            val r = Rig()
            r.follow()
            r.ok(first)
            r.at(20_000)
            r.ok(second)
            return r.m.view()!!
        }
        val cases = listOf(
            "score corrected down" to after(live(away = 1, home = 2), live(secs = 602, away = 0, home = 2)),
            "both sides rose" to after(live(away = 0, home = 2), live(secs = 602, away = 1, home = 3)),
            "snapshot only turns FINAL" to after(live(away = 0, home = 2, period = 3, secs = 0, running = false), final(away = 0, home = 2)),
            "nothing changed" to after(live(), live(secs = 602)),
            "shootout ends tied in the score (no +1 yet)" to after(
                live(away = 2, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false),
                live(away = 2, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false),
            ),
        )
        for ((name, v) in cases) {
            assertEquals(0, v.goalSeq, name)
            assertNull(v.goalSide, name)
        }
        // The first snapshot of a follow is never a goal, whatever its score.
        val f = Rig()
        f.follow()
        f.ok(live(away = 4, home = 5))
        assertEquals(0, f.m.view()!!.goalSeq)
        assertNull(f.m.view()!!.goalSide)
    }

    @Test
    fun plusTwoIsOneSeqAndAReassignedGoalMarksTheSideThatRose() {
        val r = Rig()
        r.follow()
        r.ok(live(away = 0, home = 2))
        r.at(40_000) // one missed poll
        r.ok(live(secs = 582, away = 2, home = 2))
        assertEquals(1, r.m.view()!!.goalSeq, "+2 in one update is one goal")
        assertEquals(Side.AWAY, r.m.view()!!.goalSide)
        assertEquals(2, r.m.view()!!.away.score, "the view carries the new score")
        // A goal moved from away to home: −1 / +1.
        r.at(60_000)
        r.ok(live(secs = 562, away = 1, home = 3))
        assertEquals(2, r.m.view()!!.goalSeq)
        assertEquals(Side.HOME, r.m.view()!!.goalSide)
    }

    @Test
    fun goalSeqDoesNotResetAcrossAReFollow() {
        val r = Rig()
        r.follow()
        r.ok(live(away = 0))
        r.at(20_000)
        r.ok(live(secs = 602, away = 1))
        assertEquals(1, r.m.view()!!.goalSeq)
        // Another game: its first snapshot keeps the seq and is not a goal.
        val other = live(away = 0, home = 0).copy(id = GameId(League.NHL, "2026020001"))
        r.m.follow(SportsFixtures.followedOf(other))
        r.ok(other)
        assertEquals(1, r.m.view()!!.goalSeq)
        assertNull(r.m.view()!!.goalSide, "a re-follow clears the mark")
        assertEquals(other.id, r.m.view()!!.gameId)
        r.at(40_000)
        r.ok(other.copy(home = SportsFixtures.team("EDM", "Oilers", 1)))
        assertEquals(2, r.m.view()!!.goalSeq)
        assertEquals(Side.HOME, r.m.view()!!.goalSide)
        // Unfollow, then follow the first game again: still monotonic.
        r.m.follow(null)
        r.follow()
        r.at(60_000)
        r.ok(live(away = 1))
        assertEquals(2, r.m.view()!!.goalSeq)
        r.at(80_000)
        r.ok(live(secs = 582, away = 2))
        assertEquals(3, r.m.view()!!.goalSeq)
        // A removal (two NotFounds) does not reset it either.
        r.at(100_000)
        r.m.fetched(Fetch.NotFound)
        r.at(130_000)
        r.m.fetched(Fetch.NotFound)
        assertNull(r.m.followed())
        r.follow()
        r.at(140_000)
        r.ok(live(away = 2))
        assertEquals(3, r.m.view()!!.goalSeq)
        assertEquals(3, r.m.knownView()!!.goalSeq)
    }

    @Test
    fun gameIdIsSetOnEveryView() {
        val r = Rig()
        val g = live()
        r.follow(g)
        r.ok(g)
        assertEquals(g.id, r.m.view()!!.gameId)
        r.at(20_000)
        r.ok(final(away = 1))
        assertEquals(g.id, r.m.view()!!.gameId)
        // Delayed (waiting) and stale views, and the stale-hide's known view.
        val d = Rig(delaySec = 30)
        d.follow(g)
        d.ok(g)
        d.m.online(false)
        assertEquals(CentreText.Delayed, d.m.view()!!.bottom)
        assertEquals(g.id, d.m.view()!!.gameId)
        d.at(30_000 + 60_000)
        assertEquals(CentreText.NoUpdate, d.m.view()!!.bottom)
        assertEquals(g.id, d.m.view()!!.gameId)
        d.at(30_000 + 60_000 + SportsConstants.HIDE_AFTER_MS)
        assertNull(d.m.view())
        assertEquals(g.id, d.m.knownView()!!.gameId)
    }

    @Test
    fun answersForAnotherGameAreIgnored() {
        val r = Rig()
        r.follow()
        r.ok(live().copy(id = GameId(League.NHL, "2026020001")))
        assertNull(r.m.view())
        assertTrue(r.m.pollDue())
    }

    // ------------------------------------------------------------------ power play (docs/design/m6-power-play.md §1.4)

    private fun pp(side: Side, kind: PowerPlayKind = PowerPlayKind.ONE_MAN, secs: Int? = 79) = PowerPlay(side, kind, secs)

    /** The first snapshot [a], then [b] 20 s later (a fresh comparison, SEEN): the view after [b]. */
    private fun after(a: GameSnapshot, b: GameSnapshot): ScoreBugView {
        val r = Rig()
        r.follow()
        r.ok(a)
        r.at(20_000)
        r.ok(b)
        return r.m.view()!!
    }

    @Test
    fun powerPlayIsShownWhileLiveOrInIntermissionOnly() {
        fun viewOf(s: GameSnapshot): ScoreBugView {
            val r = Rig()
            r.follow()
            r.ok(s)
            return assertNotNull(r.m.view())
        }
        val home = pp(Side.HOME, secs = 34)
        assertEquals(home, viewOf(live().copy(powerPlay = home)).powerPlay)
        assertEquals(SportsFixtures.liveView(live().copy(powerPlay = home)), viewOf(live().copy(powerPlay = home)))
        // Ruling 19: it stays through intermission, frozen at the snapshot's seconds.
        val int = live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958, period = 1)
        assertEquals(home, viewOf(int.copy(powerPlay = home)).powerPlay)
        assertEquals(pp(Side.AWAY, PowerPlayKind.TWO_MAN), viewOf(live().copy(powerPlay = pp(Side.AWAY, PowerPlayKind.TWO_MAN))).powerPlay)
        assertEquals(pp(Side.AWAY), viewOf(live(period = 4, kind = PeriodKind.OT, ot = 1).copy(powerPlay = pp(Side.AWAY))).powerPlay)
        // Never in the shootout or a final.
        assertNull(viewOf(live(period = 5, kind = PeriodKind.SO, secs = 0, running = false).copy(powerPlay = home)).powerPlay)
        assertNull(viewOf(final().copy(powerPlay = home)).powerPlay)
        assertNull(viewOf(final(kind = PeriodKind.OT).copy(powerPlay = home)).powerPlay)
        assertNull(viewOf(live()).powerPlay)
    }

    @Test
    fun powerPlayHidesWhileStaleOrWaitingAndKnownViewNeverHasOne() {
        val home = pp(Side.HOME)
        val r = Rig()
        r.follow()
        r.ok(live().copy(powerPlay = home))
        assertEquals(home, r.m.view()!!.powerPlay)
        r.m.online(false)
        r.at(60_000)
        assertEquals(CentreText.NoUpdate, r.m.view()!!.bottom)
        assertNull(r.m.view()!!.powerPlay, "a stale time is wrong")
        r.at(60_000 + SportsConstants.HIDE_AFTER_MS)
        assertNull(r.m.view())
        val known = assertNotNull(r.m.knownView())
        assertNull(known.powerPlay)
        r.m.online(true)
        r.ok(live(secs = 300).copy(powerPlay = pp(Side.HOME, secs = 20)))
        assertEquals(pp(Side.HOME, secs = 20), r.m.view()!!.powerPlay, "fresh again")

        // Waiting on the delay: none, then the snapshot's own once it is visible (the delay is automatic).
        val d = Rig(delaySec = 30)
        d.follow()
        d.ok(live().copy(powerPlay = home))
        d.m.online(false)
        assertEquals(CentreText.Delayed, d.m.view()!!.bottom)
        assertNull(d.m.view()!!.powerPlay)
        d.at(30_000)
        assertEquals(home, d.m.view()!!.powerPlay)
    }

    @Test
    fun powerPlayRidesTheDelayedTimeline() {
        val r = Rig(delaySec = 30)
        r.follow()
        r.ok(live()) // anchor 0 → shown 30 000
        r.at(20_000)
        r.ok(live(secs = 602).copy(powerPlay = pp(Side.AWAY, secs = 100))) // anchor 20 000 → shown 50 000
        r.m.online(false)
        r.at(49_999)
        assertNull(r.m.view()!!.powerPlay, "not before the delayed snapshot is visible")
        r.at(50_000)
        assertEquals(pp(Side.AWAY, secs = 100), r.m.view()!!.powerPlay)
    }

    @Test
    fun goalPpFromTheScoringSummary() {
        val base = live(away = 0, home = 2)
        val goal = live(secs = 602, away = 1, home = 2)
        // `pp` matched to the score: true, whatever the previous snapshot said.
        assertTrue(after(base, goal.copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = true))).goalPp)
        // A shorthanded goal against the home power play: false.
        assertFalse(after(base.copy(powerPlay = pp(Side.HOME)), goal.copy(powerPlay = pp(Side.HOME), lastGoal = GoalFact(Side.AWAY, 1, 2, pp = false))).goalPp)
        // `ev` while the scorer had a power play (it ended inside the gap): the summary wins, false.
        val ev = after(base.copy(powerPlay = pp(Side.AWAY)), goal.copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = false)))
        assertEquals(Side.AWAY, ev.goalSide)
        assertFalse(ev.goalPp)
        // The view carries goalPp only with the goal mark.
        val v = after(base, goal.copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = true)))
        assertEquals(Side.AWAY, v.goalSide)
        assertEquals(1, v.goalSeq)
        // No goal: never.
        assertFalse(after(base.copy(powerPlay = pp(Side.AWAY)), base.copy(lastGoal = GoalFact(Side.HOME, 0, 2, pp = true))).goalPp)
    }

    @Test
    fun goalPpFallsBackToThePreviousSnapshotWhenTheSummaryLags() {
        val base = live(away = 0, home = 2)
        val goal = live(secs = 602, away = 1, home = 2)
        val lagging = GoalFact(Side.HOME, 0, 2, pp = false) // the previous goal: not the new score
        assertTrue(after(base.copy(powerPlay = pp(Side.AWAY)), goal.copy(lastGoal = lagging)).goalPp, "p had the scorer on the PP")
        assertTrue(after(base.copy(powerPlay = pp(Side.AWAY)), goal).goalPp, "no summary at all (the day list)")
        assertFalse(after(base.copy(powerPlay = pp(Side.HOME)), goal).goalPp, "p had the other side on the PP")
        assertFalse(after(base, goal.copy(lastGoal = lagging)).goalPp)
        // A summary entry for the other side at the same score is not this goal: fallback.
        assertTrue(after(base.copy(powerPlay = pp(Side.AWAY)), goal.copy(lastGoal = GoalFact(Side.HOME, 1, 2, pp = false))).goalPp)
        assertTrue(after(base.copy(powerPlay = pp(Side.AWAY, PowerPlayKind.TWO_MAN)), goal).goalPp, "5-on-3 too")
    }

    @Test
    fun goalPpFallbacksDocumentedMisses() {
        val base = live(away = 0, home = 2)
        val goal = live(secs = 582, away = 1, home = 2)
        // 1. A minor expires inside the poll gap, then an even-strength goal by the same team: a false PP GOAL.
        assertTrue(after(base.copy(powerPlay = pp(Side.AWAY, secs = 10)), goal).goalPp)
        // 2. A power play starts and is scored on inside one gap: no PP GOAL.
        assertFalse(after(base, goal).goalPp)
        // 3. `situation` clears a bucket before the score rises: no PP GOAL.
        val r = Rig()
        r.follow()
        r.ok(base.copy(powerPlay = pp(Side.AWAY, secs = 30)))
        r.at(20_000)
        r.ok(live(secs = 602, away = 0, home = 2)) // cleared, score not yet
        r.at(40_000)
        r.ok(goal)
        assertEquals(Side.AWAY, r.m.view()!!.goalSide)
        assertFalse(r.m.view()!!.goalPp)
    }

    @Test
    fun goalPpNeverInTheShootout() {
        val so = live(away = 2, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false)
        val win = final(away = 2, home = 3, kind = PeriodKind.SO)
        assertFalse(after(so.copy(powerPlay = pp(Side.HOME)), win.copy(lastGoal = GoalFact(Side.HOME, 2, 3, pp = true))).goalPp)
        val soPlusOne = after(so.copy(powerPlay = pp(Side.AWAY)), so.copy(away = so.away.copy(score = 3)))
        assertEquals(Side.AWAY, soPlusOne.goalSide)
        assertFalse(soPlusOne.goalPp)
        // An overtime power-play winner on the FINAL snapshot: the previous snapshot is LIVE OT.
        val ot = live(away = 2, home = 2, period = 4, kind = PeriodKind.OT, ot = 1, secs = 140)
        val otWin = final(away = 3, home = 2, kind = PeriodKind.OT)
        assertTrue(after(ot.copy(powerPlay = pp(Side.AWAY)), otWin).goalPp)
        assertTrue(after(ot, otWin.copy(lastGoal = GoalFact(Side.AWAY, 3, 2, pp = true))).goalPp)
    }

    @Test
    fun goalPpAfterAPowerPlayCarriedOverTheIntermission() {
        // Fix pass S4: a minor late in the 2nd carries over the break; the intermission polls every other bucket, so a goal
        // early in the 3rd is flagged against an INTERMISSION snapshot. The summary says `pp`: PP GOAL.
        val int = live(away = 0, home = 2, period = 2, phase = GamePhase.INTERMISSION, inInt = true, running = false, secs = 958)
            .copy(powerPlay = pp(Side.AWAY, secs = 90))
        val goal = live(away = 1, home = 2, period = 3, secs = 1185).copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = true))
        val v = after(int, goal)
        assertEquals(Side.AWAY, v.goalSide)
        assertTrue(v.goalPp)
        // The fallback (no summary yet) uses the intermission's power play too.
        assertTrue(after(int, goal.copy(lastGoal = null)).goalPp)
        // A summary saying `ev` wins.
        assertFalse(after(int, goal.copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = false))).goalPp)
        // Never the shootout: an intermission before the shootout, then the FINAL/SO winner's +1.
        val preSo = live(away = 2, home = 2, period = 4, kind = PeriodKind.OT, ot = 1, phase = GamePhase.INTERMISSION, inInt = true, running = false, secs = 0)
            .copy(powerPlay = pp(Side.AWAY, secs = 40))
        val soWin = final(away = 3, home = 2, kind = PeriodKind.SO).copy(lastGoal = GoalFact(Side.AWAY, 3, 2, pp = true))
        val w = after(preSo, soWin)
        assertEquals(Side.AWAY, w.goalSide)
        assertFalse(w.goalPp)
    }

    @Test
    fun goalPpIsClearedWithTheMarkAndByReset() {
        val r = Rig()
        r.follow()
        r.ok(live(away = 0, home = 2).copy(powerPlay = pp(Side.AWAY)))
        r.at(20_000)
        r.ok(live(secs = 602, away = 1, home = 2).copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = true)))
        assertTrue(r.m.view()!!.goalPp)
        r.at(20_000 + SportsConstants.GOAL_MARK_MS - 1)
        assertTrue(r.m.view()!!.goalPp)
        r.at(20_000 + SportsConstants.GOAL_MARK_MS)
        assertNull(r.m.view()!!.goalSide)
        assertFalse(r.m.view()!!.goalPp, "cleared with the mark")
        // A second, even-strength goal inside a PP goal's mark replaces it.
        val s = Rig()
        s.follow()
        s.ok(live(away = 0, home = 2).copy(powerPlay = pp(Side.AWAY)))
        s.at(20_000)
        s.ok(live(secs = 602, away = 1, home = 2).copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = true)))
        s.at(22_000)
        s.ok(live(secs = 600, away = 1, home = 3).copy(lastGoal = GoalFact(Side.HOME, 1, 3, pp = false)))
        assertEquals(Side.HOME, s.m.view()!!.goalSide)
        assertFalse(s.m.view()!!.goalPp)
        // reset() (a re-follow) inside the mark: the new follow's views carry no goalPp (N12).
        val t = Rig()
        t.follow()
        t.ok(live(away = 0, home = 2).copy(powerPlay = pp(Side.AWAY)))
        t.at(20_000)
        t.ok(live(secs = 602, away = 1, home = 2).copy(lastGoal = GoalFact(Side.AWAY, 1, 2, pp = true)))
        assertTrue(t.m.view()!!.goalPp)
        val other = live().copy(id = GameId(League.NHL, "2026020002"))
        t.follow(other)
        t.ok(other.copy(powerPlay = pp(Side.AWAY)))
        assertNull(t.m.view()!!.goalSide)
        assertFalse(t.m.view()!!.goalPp)
        assertFalse(t.m.view()!!.ppEnded)
    }

    @Test
    fun ppEndedNotWhenTheGameEndsTheAdvantage() {
        val f = Rig()
        f.follow()
        f.ok(live(away = 0, home = 2).copy(powerPlay = pp(Side.HOME)))
        f.at(20_000)
        f.ok(final(away = 0, home = 2))
        assertFalse(f.m.view()!!.ppEnded, "PP then FINAL")

        val so = Rig()
        so.follow()
        so.ok(live(away = 2, home = 2, period = 4, secs = 30).copy(powerPlay = pp(Side.HOME)))
        so.at(20_000)
        so.ok(live(away = 2, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false))
        assertFalse(so.m.view()!!.ppEnded, "PP then SO")

        val ev = Rig()
        ev.follow()
        ev.ok(live().copy(powerPlay = pp(Side.HOME)))
        ev.at(20_000)
        ev.ok(live(secs = 602))
        assertTrue(ev.m.view()!!.ppEnded, "PP then even strength LIVE")
    }

    @Test
    fun ppEndedOnlyOnAFreshEndWithNoGoal() {
        val r = Rig()
        r.follow()
        r.ok(live().copy(powerPlay = pp(Side.HOME)))
        assertFalse(r.m.view()!!.ppEnded, "a start is not an end")
        r.at(20_000)
        r.ok(live(secs = 602))
        assertTrue(r.m.view()!!.ppEnded, "the power play is over")
        assertNull(r.m.view()!!.powerPlay)
        r.at(40_000)
        r.ok(live(secs = 582))
        assertFalse(r.m.view()!!.ppEnded, "said once")
        // A side change is not an end; neither is a kind change.
        r.at(60_000)
        r.ok(live(secs = 562).copy(powerPlay = pp(Side.HOME)))
        r.at(80_000)
        r.ok(live(secs = 542).copy(powerPlay = pp(Side.HOME, PowerPlayKind.TWO_MAN)))
        assertFalse(r.m.view()!!.ppEnded)
        r.at(100_000)
        r.ok(live(secs = 522).copy(powerPlay = pp(Side.AWAY)))
        assertFalse(r.m.view()!!.ppEnded)

        // A goal that ends the power play: no "over", not even after the mark clears.
        val g = Rig()
        g.follow()
        g.ok(live(away = 0, home = 2).copy(powerPlay = pp(Side.HOME)))
        g.at(20_000)
        g.ok(live(secs = 602, away = 0, home = 3).copy(lastGoal = GoalFact(Side.HOME, 0, 3, pp = true)))
        assertEquals(Side.HOME, g.m.view()!!.goalSide)
        assertTrue(g.m.view()!!.goalPp)
        assertFalse(g.m.view()!!.ppEnded)
        g.at(20_000 + SportsConstants.GOAL_MARK_MS)
        assertNull(g.m.view()!!.goalSide)
        assertFalse(g.m.view()!!.ppEnded)

        // Fix pass N5: an unflagged goal (UNSEEN demand) that ends the power play is not an "over" either.
        val u = Rig()
        u.follow(demand = Demand.UNSEEN)
        u.ok(live(away = 0, home = 2).copy(powerPlay = pp(Side.HOME)))
        u.at(20_000)
        u.ok(live(secs = 602, away = 0, home = 3))
        assertNull(u.m.view()!!.goalSide, "not flagged while unseen")
        assertFalse(u.m.view()!!.ppEnded)

        // Going stale is never an end; a fresh view without one after the stale stretch is.
        val s = Rig()
        s.follow()
        s.ok(live().copy(powerPlay = pp(Side.HOME)))
        s.m.online(false)
        s.at(60_000)
        assertNull(s.m.view()!!.powerPlay)
        assertFalse(s.m.view()!!.ppEnded, "never said on going stale")
        s.m.online(true)
        s.ok(live(secs = 300))
        assertTrue(s.m.view()!!.ppEnded)
        // Stale again while that end is shown: not said.
        s.m.online(false)
        s.at(60_000 + 60_000)
        assertEquals(CentreText.NoUpdate, s.m.view()!!.bottom)
        assertFalse(s.m.view()!!.ppEnded)

        // A power play that ends with the game going to the shootout: not an end in play, so not said.
        assertFalse(
            after(
                live(away = 2, home = 2, period = 4, kind = PeriodKind.OT, ot = 1, secs = 0, running = false).copy(powerPlay = pp(Side.AWAY)),
                live(away = 2, home = 2, period = 5, kind = PeriodKind.SO, secs = 0, running = false).copy(powerPlay = pp(Side.AWAY)),
            ).ppEnded,
        )
        // On the delayed timeline: said when the end becomes visible.
        val d = Rig(delaySec = 30)
        d.follow()
        d.ok(live().copy(powerPlay = pp(Side.HOME)))
        d.at(20_000)
        d.ok(live(secs = 602))
        d.m.online(false)
        d.at(30_000)
        assertFalse(d.m.view()!!.ppEnded, "the power play is still on screen")
        d.at(50_000)
        assertTrue(d.m.view()!!.ppEnded, "on the delayed timeline")
    }

    @Test
    fun pollsAreIdenticalWithAndWithoutAPowerPlay() {
        fun trace(withPp: Boolean): List<Any?> {
            val r = Rig()
            val out = ArrayList<Any?>()
            r.follow()
            out += r.m.pollDue()
            val snaps = listOf(
                live(secs = 622), live(secs = 602), live(secs = 582, away = 1),
                live(secs = 958, phase = GamePhase.INTERMISSION, inInt = true, running = false), live(secs = 562),
            )
            var t = 1_000L
            for ((i, s) in snaps.withIndex()) {
                r.at(t)
                r.ok(if (withPp) s.copy(powerPlay = pp(if (i % 2 == 0) Side.HOME else Side.AWAY, secs = 60 - i)) else s, ageS = 3)
                out += r.m.pollDue()
                out += r.m.nextWakeAtMono()
                t = r.m.nextWakeAtMono()!!
                out += r.m.view()?.let { it.copy(powerPlay = null, goalPp = false, ppEnded = false) }
            }
            r.m.online(false)
            out.add(r.wakeUntil(t + 400_000).map { (w, v) -> w to v?.copy(powerPlay = null, goalPp = false, ppEnded = false) })
            return out
        }
        assertEquals(trace(false), trace(true))
    }
}
