package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-stacked.md §3.3, §9.1 (WP-1): the timeout counts, the per-half low-water mark, the timeout and flag
 * marks, their precedence against a score, stale / waiting and breaks, and the score delay. Polls 20 s apart (every
 * comparison fresh, C-N1); the 2026-11-15 fixtures go through [EspnMapper] via [NflStackedFixtures.snapshot].
 */
class ScoreBugMachineNflStackedTest {
    private class Rig(start: Long) {
        var now = 0L
        val wall0 = start + 30 * 60_000L
        val m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))

        fun follow(g: GameSnapshot, demand: Demand = Demand.SEEN) {
            m.follow(SportsFixtures.followedOf(g))
            m.demand(demand)
        }

        fun ok(s: GameSnapshot) = m.fetched(Fetch.Ok(s, now, wall0 + now, 0))

        fun at(t: Long) {
            now = t
            m.step()
        }

        fun next(s: GameSnapshot, gapMs: Long = 20_000): NflView? {
            at(now + gapMs)
            ok(s)
            return m.view()?.nfl
        }

        fun nfl(): NflView? = m.view()?.nfl
    }

    private fun rig(first: GameSnapshot, demand: Demand = Demand.SEEN): Rig =
        Rig(first.startUtcMs).also { it.follow(first, demand); it.ok(first) }

    private fun fx(name: String): GameSnapshot = assertNotNull(NflStackedFixtures.snapshot(name), name)

    private var seq = 100L

    /** A play with a fresh, rising id (KC @ BUF event 401899001) unless [id] is given. */
    private fun lp(type: String = "24", penalty: Boolean = false, side: Side? = Side.AWAY, id: Long? = null, score: Int? = 0) =
        NflLastPlay((id ?: ++seq).let { "401899001$it" }, type, "t$type", side, score, penalty)

    private fun g(
        a: Int? = 3,
        h: Int? = 3,
        lastPlay: NflLastPlay? = lp(),
        period: Int = 1,
        secs: Int = 600,
        away: Int = 0,
        home: Int = 0,
        phase: GamePhase = GamePhase.LIVE,
        breakKind: NflBreak? = null,
        play: NflPlay = NflPlay.Down(1, 10, goalToGo = false, field = FieldPos(Side.AWAY, 35)),
        finalKind: PeriodKind? = null,
        poss: Side? = Side.AWAY,
    ): GameSnapshot = SportsFixtures.nflGame(
        phase = phase,
        away = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs", away),
        home = SportsFixtures.nflTeam("BUF", "Buffalo Bills", "Bills", home),
        period = period,
        periodKind = if (period >= 5) PeriodKind.OT else PeriodKind.REG,
        otNumber = if (period >= 5) period - 4 else null,
        clock = GameClock(secs, running = false, inIntermission = phase == GamePhase.INTERMISSION),
        finalKind = finalKind,
        detail = SportsFixtures.nflDetail(play, poss, false, breakKind, lastPlay, a, h),
    )

    // ------------------------------------------------------------------ the live sequence (§3.1 fixtures)

    @Test
    fun penaltyThenTeamTimeoutThenHeldThenTheNextSnap() {
        val r = rig(fx(NflStackedFixtures.CLOCK_LAG_B)) // LAR @ ARI, 10:41, counts 3 / 2: the follow's history
        assertNull(r.nfl()?.flagId)
        assertEquals(3, r.nfl()?.awayTimeouts)
        assertEquals(2, r.nfl()?.homeTimeouts)
        assertEquals(3, r.nfl()?.timeoutSlots)

        val pen = assertNotNull(r.next(fx(NflStackedFixtures.PENALTY_TYPE8)))
        assertEquals("401873061503", pen.flagId, "type 8 with a new id: the flag")
        assertNull(pen.timeout)
        val shownAt = r.now
        r.at(shownAt + SportsConstants.NFL_FLAG_MARK_MS - 1)
        assertEquals("401873061503", r.nfl()?.flagId, "held 8 s")
        r.at(shownAt + SportsConstants.NFL_FLAG_MARK_MS)
        assertNull(r.nfl()?.flagId, "gone at 8 s")

        val to = assertNotNull(r.next(fx(NflStackedFixtures.TEAM_TIMEOUT_TYPE21)))
        assertEquals(NflTimeoutMark(Side.AWAY, 2, confirmed = true), to.timeout, "LAR from the count, 3 → 2")
        assertNull(to.flagId)
        assertEquals(2, to.awayTimeouts)
        assertEquals(3, to.timeoutSlots)

        val held = assertNotNull(r.next(fx(NflStackedFixtures.TEAM_TIMEOUT_HELD), gapMs = 30_000))
        assertEquals(NflTimeoutMark(Side.AWAY, 2, confirmed = true), held.timeout, "held while the 21 is the lastPlay")
        r.at(r.now + 25_000)
        assertNotNull(r.nfl()?.timeout, "no clock and no 8-s limit on a confirmed mark")

        val after = assertNotNull(r.next(fx(NflStackedFixtures.AFTER_TIMEOUT), gapMs = 5_000))
        assertNull(after.timeout, "ended by the new lastPlay …536")
        assertNull(after.flagId)
        assertEquals(2, after.awayTimeouts)
    }

    @Test
    fun theCallerComesFromTheCountNeverFromLastPlayTeam() {
        val r = rig(g(a = 3, h = 3))
        val v = assertNotNull(r.next(g(a = 3, h = 2, lastPlay = lp("21", side = Side.AWAY))))
        assertEquals(NflTimeoutMark(Side.HOME, 2, confirmed = true), v.timeout, "BUF's count dropped; lastPlay.team says KC")
    }

    @Test
    fun theFirstViewAndARestartMidHoldNeverMark() {
        // The follow's first view is the penalty: no flag.
        rig(fx(NflStackedFixtures.PENALTY_TYPE8)).let { assertNull(it.nfl()?.flagId) }
        // A restart (a new machine) while the 21 is held: the first view, then the held poll, never mark.
        val r = rig(fx(NflStackedFixtures.TEAM_TIMEOUT_TYPE21))
        assertNull(r.nfl()?.timeout)
        assertNull(r.next(fx(NflStackedFixtures.TEAM_TIMEOUT_HELD))?.timeout)
        assertEquals(2, r.nfl()?.awayTimeouts, "the pips still show the counts")
        assertNull(r.next(fx(NflStackedFixtures.AFTER_TIMEOUT))?.timeout)
        // A restart mid-flag: the re-sent penalty after a fresh first view does not flag.
        val f = rig(fx(NflStackedFixtures.PENALTY_TYPE8))
        assertNull(f.next(fx(NflStackedFixtures.PENALTY_TYPE8))?.flagId)
    }

    @Test
    fun theClockIsNotAPlaySignal() {
        // The clock-lag pair: an official timeout then a pass at the same 10:41 sets and ends nothing.
        val r = rig(fx(NflStackedFixtures.CLOCK_LAG_A))
        r.next(fx(NflStackedFixtures.CLOCK_LAG_B)).let { assertNull(it?.timeout); assertNull(it?.flagId) }
        // A held 21 whose clock catches up mid-timeout keeps the mark.
        val t = rig(g(a = 3, h = 3, secs = 400))
        val to = lp("21")
        assertNotNull(t.next(g(a = 2, h = 3, lastPlay = to, secs = 400))?.timeout)
        assertNotNull(t.next(g(a = 2, h = 3, lastPlay = to, secs = 388))?.timeout, "the clock jumped: still the same 21")
        assertNotNull(t.next(g(a = 2, h = 3, lastPlay = to, secs = 388, period = 1))?.timeout)
    }

    @Test
    fun theKickoffFixturesKeepThePillFieldsAndNeverReadRedZone() {
        val r = rig(fx(NflStackedFixtures.KICKOFF_RETURN_NO_POSSESSION)) // first view
        // The kick-off return poll: no `possession`, the Kickoff lastPlay's team (SEA) is the receiver.
        assertEquals(NflTab(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OWN, 27)), r.nfl()?.tab)
        val k = rig(fx(NflStackedFixtures.OFFICIAL_TIMEOUT_KICKOFF_REDZONE))
        val v = assertNotNull(k.nfl())
        assertEquals(DownText.Kickoff, v.down)
        assertEquals(NflTab(Side.HOME, TabLead.KICKOFF, TabTail.Field(FieldZone.OWN, 35)), v.tab, "LV KICKOFF | LV 35, no red")
        assertNull(v.timeout, "type 74 is never a team timeout")
        assertEquals(3, v.awayTimeouts)
        assertEquals(3, v.homeTimeouts)
    }

    // ------------------------------------------------------------------ the low-water mark (RC2) and the fallback

    @Test
    fun aFlapTwoThreeTwoMarksOnce() {
        val r = rig(g(a = 3))
        val first = assertNotNull(r.next(g(a = 2)))
        assertEquals(NflTimeoutMark(Side.AWAY, 2, confirmed = false), first.timeout, "−1 with no 21: the fallback")
        r.at(r.now + SportsConstants.NFL_TIMEOUT_FALLBACK_MS - 1)
        assertNotNull(r.nfl()?.timeout)
        r.at(r.now + 1)
        assertNull(r.nfl()?.timeout, "the fallback ends at 8 s")
        assertNull(r.next(g(a = 3))?.timeout, "an increase never marks")
        assertEquals(3, r.nfl()?.awayTimeouts)
        assertNull(r.next(g(a = 2))?.timeout, "not below the low-water mark 2")
        assertNull(r.next(g(a = 2, lastPlay = lp("21")))?.timeout, "a new 21 with no drop below 2 only waits")
        assertNull(r.next(g(a = 3))?.timeout)
        assertNull(r.next(g(a = 2))?.timeout)
    }

    @Test
    fun theHalftimeResetAndTheNewHalfNeverMark() {
        val r = rig(g(a = 1, h = 2, period = 2))
        // Halftime with the counts reset in period 2, then Q3 at 3 / 3.
        assertNull(r.next(g(a = 3, h = 3, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME))?.timeout)
        val q3 = assertNotNull(r.next(g(a = 3, h = 3, period = 3)))
        assertNull(q3.timeout)
        assertEquals(3, q3.awayTimeouts)
        assertEquals(3, q3.timeoutSlots)
        // The new group's low-water is 3: a −1 now is a fallback mark.
        assertEquals(NflTimeoutMark(Side.HOME, 2, false), r.next(g(a = 3, h = 2, period = 3))?.timeout)
        // Q2 at 1 → Q3 at 3 directly, and the reset seen first at 2: no mark either way.
        val d = rig(g(a = 1, h = 1, period = 2))
        assertNull(d.next(g(a = 3, h = 3, period = 3))?.timeout)
        val e = rig(g(a = 1, h = 1, period = 2))
        assertNull(e.next(g(a = 2, h = 3, period = 3))?.timeout, "a new group starts at its first counts")
        assertNull(e.next(g(a = 3, h = 3, period = 3))?.timeout)
        // Review fix C-S2 (this test pinned the opposite): the 3 / 3 that follows re-seeds the low-water marks, so the
        // half's first real timeout marks (before the fix, KC's 3 → 2 here was "not below the low-water mark 2").
        assertEquals(NflTimeoutMark(Side.AWAY, 2, false), e.next(g(a = 2, h = 3, period = 3))?.timeout)
    }

    /** Review fix C-S2, the reviewer's scenario: Q2 ends 1 / 1, the first Q3 body still says 1 / 1, then 3 / 3, then a 21. */
    @Test
    fun staleCountsAtTheStartOfAHalfDoNotSilenceItsTimeouts() {
        val r = rig(g(a = 1, h = 1, period = 2))
        assertNull(r.next(g(a = 1, h = 1, period = 3))?.timeout, "the old half's situation")
        assertNull(r.next(g(a = 3, h = 3, period = 3))?.timeout, "the reset: an increase, no mark")
        val to = lp("21", side = Side.HOME)
        assertEquals(NflTimeoutMark(Side.HOME, 2, true), r.next(g(a = 3, h = 2, period = 3, lastPlay = to))?.timeout)
        // The fallback too.
        val f = rig(g(a = 1, h = 1, period = 2))
        f.next(g(a = 1, h = 1, period = 3))
        f.next(g(a = 3, h = 3, period = 3))
        assertEquals(NflTimeoutMark(Side.AWAY, 2, false), f.next(g(a = 2, h = 3, period = 3))?.timeout)
        // A pending 21 seen on the stale counts is forgotten by the re-seed (its drop was never below the new marks).
        val p = rig(g(a = 1, h = 1, period = 2))
        p.next(g(a = 1, h = 1, period = 3))
        val pend = lp("21")
        assertNull(p.next(g(a = 1, h = 1, period = 3, lastPlay = pend))?.timeout)
        assertNull(p.next(g(a = 3, h = 3, period = 3, lastPlay = pend))?.timeout, "the reset is not the 21's drop")
        // The window closes on the half's first new play after its first body: stale counts that outlast it are the
        // documented limit (the marks then wait for a count below the stale low).
        val late = rig(g(a = 1, h = 1, period = 2))
        late.next(g(a = 1, h = 1, period = 3))
        late.next(g(a = 1, h = 1, period = 3)) // a new play, still the old counts: the window closes
        late.next(g(a = 3, h = 3, period = 3))
        assertNull(late.next(g(a = 2, h = 3, period = 3))?.timeout)
        // Overtime groups never re-seed (their first counts are the group's own).
        val ot = rig(g(a = 1, h = 1, period = 4))
        ot.next(g(a = 2, h = 2, period = 5))
        ot.next(g(a = 3, h = 3, period = 5))
        assertNull(ot.next(g(a = 2, h = 3, period = 5))?.timeout, "OT: not below the group's low 2")
    }

    /** C-S2: the Q4 2 → 3 → 2 flap after the half's plays and marks never re-seeds (the rule above keeps it). */
    @Test
    fun aFlapToThreeThreeLaterInTheHalfNeverReSeeds() {
        val r = rig(g(a = 3, h = 3, period = 4))
        assertEquals(NflTimeoutMark(Side.AWAY, 2, true), r.next(g(a = 2, h = 3, period = 4, lastPlay = lp("21")))?.timeout)
        assertNull(r.next(g(a = 3, h = 3, period = 4))?.timeout, "a flap to 3 / 3")
        assertNull(r.next(g(a = 2, h = 3, period = 4, lastPlay = lp("5")))?.timeout, "not below the low-water mark 2")
        // With no mark in the group but its plays seen, the same.
        val n = rig(g(a = 3, h = 3, period = 4, lastPlay = lp("24")))
        n.next(g(a = 3, h = 3, period = 4, lastPlay = lp("24")))
        assertNull(n.next(g(a = 2, h = 3, period = 4, lastPlay = lp("74")))?.timeout, "a drop on an official timeout: no mark")
        n.next(g(a = 3, h = 3, period = 4))
        assertNull(n.next(g(a = 2, h = 3, period = 4, lastPlay = lp("5")))?.timeout)
    }

    /** Review fix C-N3: playoff overtime, one half group per pair of OT periods (5–6, 7–8): 3OT starts fresh. */
    @Test
    fun playoffOvertimePairsAreTheirOwnHalves() {
        val r = rig(g(a = 3, h = 3, period = 5))
        assertEquals(NflTimeoutMark(Side.AWAY, 2, true), r.next(g(a = 2, h = 3, period = 5, lastPlay = lp("21")))?.timeout)
        assertEquals(NflTimeoutMark(Side.AWAY, 1, true), r.next(g(a = 1, h = 3, period = 6, lastPlay = lp("21")))?.timeout, "2OT is the same half")
        val third = assertNotNull(r.next(g(a = 3, h = 3, period = 7)))
        assertNull(third.timeout)
        assertEquals(3, third.timeoutSlots)
        assertEquals(NflTimeoutMark(Side.AWAY, 2, true), r.next(g(a = 2, h = 3, period = 7, lastPlay = lp("21")))?.timeout, "3OT: a new half")
        assertEquals(3, r.nfl()?.timeoutSlots)
        assertEquals(NflTimeoutMark(Side.HOME, 2, true), r.next(g(a = 2, h = 2, period = 8, lastPlay = lp("21", side = Side.HOME)))?.timeout)
    }

    /**
     * Review fix C-N1: a permanent downward id correction. After three shown entries below the high-water it drops to
     * them, so a later play above the correction marks; a body flipping back to an id already shown never re-marks.
     */
    @Test
    fun aPermanentDownwardIdCorrectionLowersTheHighWater() {
        val r = rig(g(lastPlay = lp(id = 899)))
        val pen900 = lp("8", penalty = true, id = 900)
        assertEquals(pen900.id, r.next(g(lastPlay = pen900))?.flagId)
        r.at(r.now + SportsConstants.NFL_FLAG_MARK_MS)
        val corrected = lp("24", id = 880)
        r.next(g(lastPlay = corrected)); r.next(g(lastPlay = corrected)); r.next(g(lastPlay = corrected))
        val pen885 = lp("8", penalty = true, id = 885)
        assertEquals(pen885.id, r.next(g(lastPlay = pen885))?.flagId, "a new play above the correction")
        r.at(r.now + SportsConstants.NFL_FLAG_MARK_MS)
        assertNull(r.next(g(lastPlay = pen900))?.flagId, "900 was shown before: never new again")
        assertNull(r.next(g(lastPlay = lp("8", penalty = true, id = 870)))?.flagId, "below the lowered mark")
        // Two entries below are not enough: a CDN flip back and forth keeps the high-water.
        val b = rig(g(lastPlay = lp(id = 899)))
        b.next(g(lastPlay = lp(id = 900)))
        b.next(g(lastPlay = corrected)); b.next(g(lastPlay = corrected))
        assertNull(b.next(g(lastPlay = pen885))?.flagId, "885 is below 900 and is the third: the mark drops only after it")
    }

    // ------------------------------------------------------------------ the held bar (C-S1) and the neutral field (C-N4)

    /** Review fix C-S1: §3.4 "LIVE, dead ball after FG / safety | the held bar": the bar holds through the dead ball. */
    @Test
    fun theBarIsHeldOnTheDeadBallAfterAFieldGoal() {
        val fourth = NflPlay.Down(4, 3, goalToGo = false, field = FieldPos(Side.HOME, 20))
        val r = rig(g(play = fourth, secs = 400))
        val play = assertNotNull(StackedNflTargets.of(assertNotNull(r.m.view())).bar)
        assertEquals(NflBarKind.PLAY, play.kind)
        val dead = g(play = NflPlay.Dead(Side.AWAY, 3), away = 3, secs = 395, lastPlay = lp("59", score = 3))
        r.next(dead)
        val v = assertNotNull(r.m.view())
        assertEquals(Side.AWAY, v.goalSide, "the field goal flags")
        assertNull(v.nfl?.down, "the Pill's down column is unchanged (none on a dead ball)")
        assertEquals(DownText.Down(4, 3, false), v.nfl?.heldDown)
        assertEquals(play, StackedNflTargets.of(v).bar, "the held bar: no change")
        // After the goal mark, and on the re-sent dead body: still held.
        r.at(r.now + SportsConstants.GOAL_MARK_MS + 1)
        assertEquals(play, StackedNflTargets.of(assertNotNull(r.m.view())).bar)
        r.next(dead)
        assertEquals(play, StackedNflTargets.of(assertNotNull(r.m.view())).bar)
        // The kick-off body re-keys to the kicker's bar.
        r.next(g(play = NflPlay.Kickoff(Side.AWAY, FieldPos(Side.AWAY, 35)), away = 3, secs = 395))
        assertEquals(NflBarKind.KICKOFF, StackedNflTargets.of(assertNotNull(r.m.view())).bar?.kind)
        // A dead ball with no held tab (the follow starts on it) is still closed.
        val s = rig(dead)
        assertNull(s.nfl()?.heldDown)
        assertNull(StackedNflTargets.of(assertNotNull(s.m.view())).bar)
    }

    /** Review fix C-N4: the neutral bar (no possession) keeps the field position: "1ST & 10 | KC 35" by territory. */
    @Test
    fun theNeutralBarKeepsTheField() {
        val r = rig(g(poss = null, lastPlay = lp("24", side = null), play = NflPlay.Down(1, 10, false, FieldPos(Side.HOME, 35))))
        val v = assertNotNull(r.m.view())
        assertNull(v.nfl?.tab, "no possession: no ball, no tab")
        val bar = assertNotNull(StackedNflTargets.of(v).bar)
        assertEquals(NflBarKey(NflBarKind.PLAY, null, down = DownText.Down(1, 10, false), tail = TabTail.Field(FieldZone.OPP, 35)), bar)
        val mid = rig(g(poss = null, lastPlay = lp("24", side = null), play = NflPlay.Down(1, 10, false, FieldPos(null, 50))))
        assertEquals(TabTail.Field(FieldZone.MIDFIELD, 50), StackedNflTargets.of(assertNotNull(mid.m.view())).bar?.tail)
    }

    @Test
    fun aDropOfTwoOrOnBothSidesIsNoFallback() {
        val r = rig(g(a = 3, h = 3))
        assertNull(r.next(g(a = 1, h = 3))?.timeout, "−2 with no 21")
        assertNull(r.next(g(a = 1, h = 3))?.timeout)
        assertNull(r.next(g(a = 0, h = 2))?.timeout, "two-sided with no 21")
        assertEquals(NflTimeoutMark(Side.HOME, 1, true), r.next(g(a = 0, h = 1, lastPlay = lp("21")))?.timeout, "−1 below the new mark 2")
        val b = rig(g(a = 3, h = 3))
        assertNull(b.next(g(a = 2, h = 2, lastPlay = lp("21")))?.timeout, "two-sided with a 21: no caller")
        // (WP-1 reading) A 21 with a −2 still names its side: the count is below the mark.
        val c = rig(g(a = 3, h = 3))
        assertEquals(NflTimeoutMark(Side.AWAY, 1, true), c.next(g(a = 1, h = 3, lastPlay = lp("21")))?.timeout)
    }

    @Test
    fun officialTimeoutTwoMinuteWarningAndPeriodEndsNeverMark() {
        for (type in listOf("74", "75", "2", "65", "66")) {
            val r = rig(g(a = 3, h = 3))
            assertNull(r.next(g(a = 2, h = 3, lastPlay = lp(type)))?.timeout, "type $type with a −1")
            assertNull(r.next(g(a = 2, h = 3, lastPlay = lp("5")))?.timeout, "the drop is used up by the low-water mark")
            val n = rig(g(a = 3, h = 3))
            assertNull(n.next(g(a = 3, h = 3, lastPlay = lp(type)))?.timeout, "type $type alone")
        }
    }

    @Test
    fun aLostChallengeIsTheFallbackAndADropBeforeItsTwentyOneIsUpgraded() {
        val r = rig(g(a = 3, h = 3, lastPlay = lp("24")))
        assertEquals(NflTimeoutMark(Side.HOME, 2, false), r.next(g(a = 3, h = 2, lastPlay = lp("5")))?.timeout, "lost challenge")
        // The 21 arrives a poll after its drop, within the fallback's 8 s: it becomes the confirmed, held mark.
        val to = lp("21")
        assertEquals(NflTimeoutMark(Side.HOME, 2, true), r.next(g(a = 3, h = 2, lastPlay = to), gapMs = 5_000)?.timeout)
        r.at(r.now + 50_000)
        assertNotNull(r.nfl()?.timeout, "held well past the fallback's 8 s")
        assertNull(r.next(g(a = 3, h = 2, lastPlay = lp("24")), gapMs = 1_000)?.timeout)
    }

    @Test
    fun aTwentyOneBeforeItsDropWaitsForIt() {
        val r = rig(g(a = 3, h = 3))
        val to = lp("21")
        assertNull(r.next(g(a = 3, h = 3, lastPlay = to))?.timeout, "no count has dropped yet")
        assertEquals(NflTimeoutMark(Side.AWAY, 2, true), r.next(g(a = 2, h = 3, lastPlay = to))?.timeout)
        // A pending 21 overtaken by a new play is forgotten: the later drop is the fallback.
        val s = rig(g(a = 3, h = 3))
        assertNull(s.next(g(a = 3, h = 3, lastPlay = lp("21")))?.timeout)
        assertEquals(NflTimeoutMark(Side.AWAY, 2, false), s.next(g(a = 2, h = 3, lastPlay = lp("24")))?.timeout)
    }

    @Test
    fun theConfirmedMarkEndsOnScoreBreakStaleAndTheMaximum() {
        val to = lp("21")
        // A flagged score.
        val a = rig(g(a = 3, lastPlay = lp(id = 1)))
        assertEquals(true, a.next(g(a = 2, lastPlay = to))?.timeout?.confirmed)
        a.next(g(a = 2, lastPlay = to, home = 3)).let { assertNull(it?.timeout); assertEquals(Side.HOME, a.m.view()?.goalSide) }
        // A phase other than LIVE: the end of the quarter.
        val b = rig(g(a = 3, lastPlay = lp(id = 1)))
        assertNotNull(b.next(g(a = 2, lastPlay = to))?.timeout)
        assertNull(b.next(g(a = 2, lastPlay = to, phase = GamePhase.INTERMISSION, breakKind = NflBreak.END_PERIOD))?.timeout)
        assertNull(b.next(g(a = 2, lastPlay = to, period = 2))?.timeout, "ended, not paused")
        // Stale: ended for good, even when the same 21 comes back.
        val c = rig(g(a = 3, lastPlay = lp(id = 1)))
        assertNotNull(c.next(g(a = 2, lastPlay = to))?.timeout)
        c.at(c.now + 61_000)
        assertEquals(CentreText.NoUpdate, c.m.view()?.top)
        assertNull(c.nfl()?.timeout)
        assertEquals(2, c.nfl()?.awayTimeouts, "the pips are held through stale")
        assertEquals(3, c.nfl()?.timeoutSlots)
        assertEquals(PeriodLabel(PeriodKind.REG, 1), c.m.view()?.period, "period kept while stale")
        assertNull(c.next(g(a = 2, lastPlay = to), gapMs = 1_000)?.timeout)
        // The 180-s maximum.
        val d = rig(g(a = 3, lastPlay = lp(id = 1)))
        assertEquals(true, d.next(g(a = 2, lastPlay = to))?.timeout?.confirmed)
        val start = d.now
        for (t in 1..8) d.next(g(a = 2, lastPlay = to))
        d.at(start + SportsConstants.NFL_TIMEOUT_MARK_MAX_MS - 1)
        d.ok(g(a = 2, lastPlay = to))
        assertEquals(NflTimeoutMark(Side.AWAY, 2, true), d.nfl()?.timeout)
        d.at(start + SportsConstants.NFL_TIMEOUT_MARK_MAX_MS)
        assertNull(d.nfl()?.timeout)
    }

    @Test
    fun pipsAreHeldThroughABreakWithNoSituation() {
        val r = rig(g(a = 2, h = 1, period = 2))
        val half = assertNotNull(r.next(g(a = null, h = null, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME, lastPlay = null, play = NflPlay.None)))
        assertEquals(2, half.awayTimeouts)
        assertEquals(1, half.homeTimeouts)
        assertEquals(3, half.timeoutSlots)
        // No counts at all: the pips are hidden.
        val n = rig(g(a = null, h = null))
        assertNull(n.nfl()?.awayTimeouts)
        assertNull(n.nfl()?.timeoutSlots)
        // Final has no situation: the half's counts stay.
        val f = rig(g(a = 1, h = 0, period = 4))
        f.next(g(a = null, h = null, period = 4, phase = GamePhase.FINAL, finalKind = PeriodKind.REG, lastPlay = null, play = NflPlay.None)).let {
            assertEquals(1, it?.awayTimeouts)
            assertEquals(0, it?.homeTimeouts)
            assertNull(it?.timeout)
        }
        // A new half group with no counts yet hides the pips (the old half's counts would be wrong).
        assertNull(r.next(g(a = null, h = null, period = 3, lastPlay = null, play = NflPlay.None))?.awayTimeouts)
    }

    @Test
    fun overtimeSlotsAreTheHighestCountInTheGroup() {
        val r = rig(g(a = 0, h = 1, period = 4))
        val ot = assertNotNull(r.next(g(a = 2, h = 2, period = 5)))
        assertEquals(2, ot.timeoutSlots, "an OT group starting at 2/2 draws 2 pips")
        assertNull(ot.timeout)
        val mark = assertNotNull(r.next(g(a = 2, h = 1, period = 5, lastPlay = lp("21", side = Side.HOME))))
        assertEquals(NflTimeoutMark(Side.HOME, 1, true), mark.timeout)
        assertEquals(2, mark.timeoutSlots)
        assertEquals(2, r.next(g(a = 2, h = 1, period = 6))?.timeoutSlots, "2OT is the same group")
        // Never more than 3, and a late higher count raises the slots.
        val s = rig(g(a = 2, h = 2, period = 5))
        assertEquals(2, s.nfl()?.timeoutSlots)
        assertEquals(3, s.next(g(a = 3, h = 2, period = 5))?.timeoutSlots)
    }

    // ------------------------------------------------------------------ the flag (RC3)

    @Test
    fun aReSentOrRevertedPlayNeverReFlags() {
        val r = rig(g(lastPlay = lp(id = 400)))
        val pen = lp("8", penalty = true, id = 503)
        assertEquals(pen.id, r.next(g(lastPlay = pen))?.flagId)
        assertEquals(pen.id, r.next(g(lastPlay = pen), gapMs = 5_000)?.flagId, "the re-sent body keeps the running hold")
        r.at(r.now + SportsConstants.NFL_FLAG_MARK_MS)
        assertNull(r.nfl()?.flagId)
        assertNull(r.next(g(lastPlay = pen))?.flagId, "re-sent after the hold")
        assertNull(r.next(g(lastPlay = lp("8", penalty = true, id = 490)))?.flagId, "an older (reverted) id")
        assertNull(r.next(g(lastPlay = pen))?.flagId, "back to 503")
        assertNull(r.next(g(lastPlay = NflLastPlay("abc", "8", "Penalty", Side.AWAY, 0, true)))?.flagId, "non-numeric id")
        assertEquals("401899001504", r.next(g(lastPlay = lp("24", penalty = true, id = 504)))?.flagId, "a newer accepted foul")
    }

    @Test
    fun aRefusedPollThenTheSamePlayDoesNotReFlag() {
        val r = rig(g(lastPlay = lp(id = 400)))
        val pen = lp("8", penalty = true, id = 503)
        assertNotNull(r.next(g(lastPlay = pen))?.flagId)
        r.at(r.now + 2_000)
        r.m.fetched(Fetch.Failed(FailKind.REFUSED, null, r.now))
        r.at(r.now + SportsConstants.NFL_FLAG_MARK_MS)
        assertNull(r.nfl()?.flagId)
        assertNull(r.next(g(lastPlay = pen))?.flagId)
    }

    @Test
    fun aPenaltyOnAScoringPlayIsTheScoreOnly() {
        val r = rig(g(away = 0, lastPlay = lp(id = 400)))
        r.next(g(away = 6, lastPlay = lp("67", penalty = true, id = 410, score = 6)))
        val v = assertNotNull(r.m.view())
        assertEquals(Side.AWAY, v.goalSide)
        assertNull(v.nfl?.flagId, "the score wins")
        // (WP-1 reading) Any score change in the entry, flagged or not (here a correction back to 0), shows no flag.
        r.next(g(away = 0, lastPlay = lp("8", penalty = true, id = 420)))
        assertNull(r.nfl()?.flagId, "a score change in the entry")
        // A penalty on the next play, with no score change, flags.
        assertEquals("401899001421", r.next(g(away = 0, lastPlay = lp("8", penalty = true, id = 421)))?.flagId)
    }

    @Test
    fun aDeclinedFoulShowsNoFlag() {
        val r = rig(g(lastPlay = lp(id = 400)))
        // The mapper writes penalty = false for "Penalty on …, declined." (EspnMapperStackedTest); the machine sees false.
        assertNull(r.next(g(lastPlay = lp("3", penalty = false, id = 401)))?.flagId)
    }

    @Test
    fun aFlagAndATimeoutNeverCoexist() {
        val r = rig(g(a = 3, lastPlay = lp(id = 400)))
        assertNotNull(r.next(g(a = 3, lastPlay = lp("8", penalty = true, id = 401)), gapMs = 3_000)?.flagId)
        r.next(g(a = 2, lastPlay = lp("21", id = 402)), gapMs = 3_000).let {
            assertNull(it?.flagId, "the newer 21 ends the flag")
            assertEquals(NflTimeoutMark(Side.AWAY, 2, true), it?.timeout)
        }
        r.next(g(a = 2, lastPlay = lp("8", penalty = true, id = 403)), gapMs = 3_000).let {
            assertNull(it?.timeout, "the newer penalty ends the timeout")
            assertEquals("401899001403", it?.flagId)
        }
        // A fallback drop while a flag runs: the newer event (the drop) ends the flag.
        r.next(g(a = 2, h = 2, lastPlay = lp("8", penalty = true, id = 403)), gapMs = 3_000).let {
            assertNull(it?.flagId)
            assertEquals(NflTimeoutMark(Side.HOME, 2, false), it?.timeout)
        }
        // A new penalty and a drop in one entry: the flag (newer play, higher precedence).
        r.next(g(a = 1, h = 2, lastPlay = lp("8", penalty = true, id = 404)), gapMs = 3_000).let {
            assertEquals("401899001404", it?.flagId)
            assertNull(it?.timeout)
        }
    }

    @Test
    fun theFlagIsMaskedWhileStaleOrWaiting() {
        val r = rig(g(lastPlay = lp(id = 400)))
        assertNotNull(r.next(g(lastPlay = lp("8", penalty = true, id = 401)))?.flagId)
        // Not fresh when the bug goes stale (the hold has long ended by then; the mask also covers a read before a step).
        r.at(r.now + 61_000)
        assertNull(r.nfl()?.flagId)
    }

    @Test
    fun noMarksUnderUnseenDemandAndTheLowWaterStillMoves() {
        val r = rig(g(a = 3, lastPlay = lp(id = 400)), demand = Demand.UNSEEN)
        assertNull(r.next(g(a = 2, lastPlay = lp("8", penalty = true, id = 401)), gapMs = 120_000)?.flagId)
        r.m.demand(Demand.SEEN)
        assertNull(r.next(g(a = 2, lastPlay = lp("8", penalty = true, id = 401)))?.flagId, "not a new play")
        assertNull(r.nfl()?.timeout, "the drop was absorbed while unseen")
        assertEquals(2, r.nfl()?.awayTimeouts)
    }

    // ------------------------------------------------------------------ the score delay (R1)

    @Test
    fun theMarksFollowTheDelayedTimeline() {
        val first = g(a = 3, lastPlay = lp(id = 400))
        val r = Rig(first.startUtcMs)
        r.follow(first)
        r.m.prefs(30)
        r.ok(first)
        r.at(30_000)
        assertNotNull(r.m.view(), "shown after the delay")
        r.at(40_000)
        r.ok(g(a = 3, lastPlay = lp("8", penalty = true, id = 401)))
        r.at(69_999)
        assertNull(r.nfl()?.flagId, "not before its entry is shown")
        r.at(70_000)
        assertEquals("401899001401", r.nfl()?.flagId, "starts when shown")
        r.at(70_000 + SportsConstants.NFL_FLAG_MARK_MS - 1)
        assertNotNull(r.nfl()?.flagId)
        r.at(70_000 + SportsConstants.NFL_FLAG_MARK_MS)
        assertNull(r.nfl()?.flagId, "8 s on the machine clock from when shown")
        // The timeout too.
        r.at(80_000)
        r.ok(g(a = 2, lastPlay = lp("21", id = 402)))
        r.at(109_999)
        assertNull(r.nfl()?.timeout)
        r.at(110_000)
        assertEquals(NflTimeoutMark(Side.AWAY, 2, true), r.nfl()?.timeout)
    }

    @Test
    fun theDelayGrowingGoesBackSilentlyAndReplays() {
        val r = rig(g(a = 3, lastPlay = lp(id = 400)))
        r.next(g(a = 3, lastPlay = lp(id = 401)))
        assertEquals("401899001402", r.next(g(a = 3, lastPlay = lp("8", penalty = true, id = 402)), gapMs = 15_000)?.flagId)
        val shownAt = r.now
        // Delay 0 → 30 s: back to the entry anchored 30 s earlier, silently (no mark), counts re-seeded from it.
        r.m.prefs(30)
        val back = assertNotNull(r.nfl())
        assertNull(back.flagId)
        assertEquals(3, back.awayTimeouts)
        // The penalty's entry is shown again 30 s after it was received: it marks again, as a goal would.
        r.at(shownAt + 30_000)
        assertEquals("401899001402", r.nfl()?.flagId)
    }

    // ------------------------------------------------------------------ properties

    @Test
    fun propertyRandomWalksMarkOnlyOnRealDropsAndFlagOnlyNewPenalties() {
        val rnd = Random(20261115)
        val never = setOf("74", "75", "2", "65", "66")
        repeat(200) { run ->
            val base = 1_000L * (run + 1)
            val r = rig(g(a = 3, h = 3, lastPlay = lp(id = base)))
            var a = 3; var h = 3; var lowA = 3; var lowH = 3; var maxId = base
            var prevMark: NflTimeoutMark? = null
            var prevFlag: String? = null
            var fallbacks = 0
            repeat(40) { i ->
                when (rnd.nextInt(6)) {
                    0 -> a = (a + rnd.nextInt(-1, 2)).coerceIn(0, 3)
                    1 -> h = (h + rnd.nextInt(-1, 2)).coerceIn(0, 3)
                    2 -> { a = (a - 1).coerceAtLeast(0); h = (h - 1).coerceAtLeast(0) }
                    else -> Unit
                }
                val type = listOf("24", "5", "74", "75", "2", "8", "21", "3").random(rnd)
                val id = if (rnd.nextInt(5) == 0) maxId - rnd.nextLong(0, 3) else maxId + 1 + rnd.nextLong(0, 3)
                val pen = type == "8" || rnd.nextInt(10) == 0
                val v = assertNotNull(r.next(g(a = a, h = h, lastPlay = lp(type, penalty = pen, id = id)), gapMs = 3_000))
                val at = "run $run step $i"
                if (v.flagId != null && v.flagId != prevFlag) {
                    assertEquals("401899001$id", v.flagId, "$at: a flag starts on the shown play")
                    assertTrue(id > maxId && pen, "$at: a flag only on a new penalty")
                }
                if (v.flagId != null) assertNull(v.timeout, "$at: a flag and a timeout never coexist")
                val t = v.timeout
                if (t != null && t != prevMark) {
                    val dropA = lowA - a
                    val dropH = lowH - h
                    if (t.confirmed) {
                        assertEquals("21", type, "$at: a confirmed mark only on a type 21")
                    } else {
                        assertTrue(type !in never, "$at: never on $type")
                        val (mine, other) = if (t.side == Side.AWAY) dropA to dropH else dropH to dropA
                        assertTrue(mine == 1 && other <= 0, "$at: the fallback needs exactly one side exactly 1 below ($t)")
                        fallbacks++
                    }
                    assertEquals(if (t.side == Side.AWAY) a else h, t.left, "$at: left = the caller's count")
                }
                assertEquals(a, v.awayTimeouts)
                assertEquals(h, v.homeTimeouts)
                assertEquals(3, v.timeoutSlots)
                prevMark = t
                prevFlag = v.flagId
                lowA = minOf(lowA, a); lowH = minOf(lowH, h); maxId = maxOf(maxId, id)
            }
            assertTrue(fallbacks <= 6, "$run: at most three drops per side in a half")
        }
    }
}
