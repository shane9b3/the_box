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
        assertEquals(958, v.clockSecs)
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
        // Intermission: the snapshot value too, never counted down locally.
        r.at(100_000)
        r.ok(live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958, period = 3))
        r.at(139_000)
        assertEquals(958, r.m.view()!!.clockSecs)
        // Negative feed values read 0; one-line states carry no clock.
        r.at(140_000)
        r.ok(live(secs = -3, running = false, period = 3))
        assertEquals(0, r.m.view()!!.clockSecs)
        assertEquals(CentreText.EndOf(PeriodLabel(PeriodKind.REG, 3)), r.m.view()!!.bottom)
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
            CentreText.Clock to CentreText.Intermission(PeriodLabel(PeriodKind.REG, 1)),
            pair(viewOf(live(inInt = true, running = false, phase = GamePhase.INTERMISSION, secs = 958, period = 1))),
        )
        assertEquals(CentreText.Clock to CentreText.EndOf(PeriodLabel(PeriodKind.REG, 3)), pair(viewOf(live(secs = 0, running = false, period = 3))))
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

    @Test
    fun answersForAnotherGameAreIgnored() {
        val r = Rig()
        r.follow()
        r.ok(live().copy(id = GameId(League.NHL, "2026020001")))
        assertNull(r.m.view())
        assertTrue(r.m.pollDue())
    }
}
