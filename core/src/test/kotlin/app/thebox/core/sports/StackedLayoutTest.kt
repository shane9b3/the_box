package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-score-bug-stacked-theme.md §2.4, §8, §10.1 `StackedLayoutTest`: every row of the §8 state table through
 * [StackedTargets.of] (the views are the real `ScoreBugMachine`'s, from snapshots, wherever the machine can produce
 * the state; hand-built only for the place-mode sample), and [StackedTargets.sameLook] for each text-swap and change pair.
 */
class StackedLayoutTest {
    // ------------------------------------------------------------------ a small machine rig (as ScoreBugMachineTest's)

    private class Rig(delaySec: Int = 0) {
        var now = 0L
        val wall0 = 1_793_923_200_000L + 30 * 60_000L
        val m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))

        init {
            m.prefs(delaySec)
        }

        fun ok(s: GameSnapshot) = m.fetched(Fetch.Ok(s, now, wall0 + now, 0))
        fun at(t: Long) { now = t; m.step() }
    }

    private val reg1 = PeriodLabel(PeriodKind.REG, 1)
    private val reg2 = PeriodLabel(PeriodKind.REG, 2)
    private val reg3 = PeriodLabel(PeriodKind.REG, 3)

    private fun team(a: String, score: Int, sog: Int?) = TeamLine(a, a, score, null, sog = sog)

    private fun snap(
        secs: Int = 622,
        period: Int = 2,
        running: Boolean = true,
        phase: GamePhase = GamePhase.LIVE,
        kind: PeriodKind = PeriodKind.REG,
        ot: Int? = null,
        inInt: Boolean = false,
        pp: PowerPlay? = null,
        awaySog: Int? = 20,
        homeSog: Int? = 15,
        away: Int = 1,
        home: Int = 2,
        finalKind: PeriodKind? = null,
    ) = SportsFixtures.game(
        phase = phase, away = team("EDM", away, awaySog), home = team("TOR", home, homeSog),
        period = period, periodKind = kind, otNumber = ot, clock = GameClock(secs, running, inInt), powerPlay = pp,
        finalKind = finalKind,
    )

    private fun int(period: Int = 1, kind: PeriodKind = PeriodKind.REG, ot: Int? = null, pp: PowerPlay? = null, awaySog: Int? = 20) =
        snap(secs = 958, period = period, running = false, phase = GamePhase.INTERMISSION, kind = kind, ot = ot, inInt = true, pp = pp, awaySog = awaySog)

    private fun so(awaySog: Int? = 30) = snap(secs = 0, period = 5, running = false, kind = PeriodKind.SO, awaySog = awaySog)

    private fun fin(kind: PeriodKind, awaySog: Int? = 30) =
        snap(secs = 0, period = 3, running = false, phase = GamePhase.FINAL, finalKind = kind, awaySog = awaySog)

    /** The machine's fresh view of [s]. */
    private fun fresh(s: GameSnapshot): ScoreBugView {
        val r = Rig()
        r.m.follow(SportsFixtures.followedOf(s))
        r.m.demand(Demand.SEEN)
        r.ok(s)
        return assertNotNull(r.m.view())
    }

    /** The machine's stale ("No update") view of [s]: no answer for 90 s. */
    private fun stale(s: GameSnapshot): ScoreBugView {
        val r = Rig()
        r.m.follow(SportsFixtures.followedOf(s))
        r.m.demand(Demand.SEEN)
        r.ok(s)
        r.m.online(false)
        r.at(90_000)
        val v = assertNotNull(r.m.view())
        assertEquals(CentreText.NoUpdate, v.bottom)
        return v
    }

    /** The machine's waiting ("Delayed") view of [s]: a 30 s delay with nothing visible yet. */
    private fun waiting(s: GameSnapshot): ScoreBugView {
        val r = Rig(delaySec = 30)
        r.m.follow(SportsFixtures.followedOf(s))
        r.m.demand(Demand.SEEN)
        r.ok(s)
        val v = assertNotNull(r.m.view())
        assertEquals(CentreText.Delayed, v.bottom)
        return v
    }

    private fun of(v: ScoreBugView) = StackedTargets.of(v)

    private fun targets(
        sogOpen: Boolean = false,
        clockOpen: Boolean = true,
        bar: BarKey? = null,
        barPeriod: PeriodLabel? = null,
        barSecs: Int? = null,
        ppSide: Side? = null,
        period: PeriodLabel? = reg2,
        clockSlot: ClockSlot = ClockSlot.Clock,
        columnDim: Boolean = false,
        barDim: Boolean = false,
    ) = StackedTargets(sogOpen, clockOpen, bar, barPeriod, barSecs, ppSide, period, clockSlot, columnDim, barDim)

    private fun ppBar(side: Side, kind: BarKind = BarKind.POWER_PLAY) = BarKey(kind, side)
    private fun neutral(kind: BarKind) = BarKey(kind, null)

    // ------------------------------------------------------------------ §8, row by row

    @Test
    fun preGameDrawsNothing() {
        for (phase in listOf(GamePhase.PRE, GamePhase.SCHEDULED)) {
            val s = snap(phase = phase, awaySog = null, homeSog = null).copy(clock = null, period = null, periodKind = null)
            val r = Rig()
            r.m.follow(SportsFixtures.followedOf(s))
            r.m.demand(Demand.SEEN)
            r.ok(s)
            assertNull(r.m.view(), "ruling 4: no view, so no targets ($phase)")
        }
    }

    @Test
    fun liveEvenStrength() {
        assertEquals(targets(), of(fresh(snap())))
    }

    @Test
    fun livePowerPlayOpensTheShotsAndTheTeamBar() {
        val home = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 97)
        assertEquals(
            targets(sogOpen = true, bar = ppBar(Side.HOME), barSecs = 97, ppSide = Side.HOME),
            of(fresh(snap(pp = home))),
        )
        // The time unknown: the label alone.
        assertEquals(
            targets(sogOpen = true, bar = ppBar(Side.AWAY), barSecs = null, ppSide = Side.AWAY),
            of(fresh(snap(pp = PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, null)))),
        )
    }

    @Test
    fun fiveOnThreeIsTheTwoManKind() {
        val t = of(fresh(snap(pp = PowerPlay(Side.AWAY, PowerPlayKind.TWO_MAN, 97))))
        assertEquals(targets(sogOpen = true, bar = ppBar(Side.AWAY, BarKind.TWO_MAN), barSecs = 97, ppSide = Side.AWAY), t)
        // 5-on-4 ⇄ 5-on-3 is a text swap (S10).
        assertTrue(StackedTargets.sameLook(ppBar(Side.AWAY), ppBar(Side.AWAY, BarKind.TWO_MAN)))
        assertTrue(StackedTargets.sameLook(ppBar(Side.AWAY, BarKind.TWO_MAN), ppBar(Side.AWAY)))
    }

    @Test
    fun sixOnFourIsAPowerPlay() {
        // Ruling 18: the mapper reports a pulled-goalie advantage as ONE_MAN for the side with more skaters.
        assertEquals(
            targets(sogOpen = true, bar = ppBar(Side.HOME), barSecs = 40, ppSide = Side.HOME, period = reg3),
            of(fresh(snap(period = 3, pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 40)))),
        )
    }

    @Test
    fun fourOnThreeInOvertime() {
        val ot = PeriodLabel(PeriodKind.OT, 1)
        assertEquals(
            targets(sogOpen = true, bar = ppBar(Side.AWAY), barSecs = 48, ppSide = Side.AWAY, period = ot),
            of(fresh(snap(period = 4, kind = PeriodKind.OT, ot = 1, pp = PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 48)))),
        )
    }

    @Test
    fun emptyNetAndFourOnFourAreEvenStrength() {
        // Ruling 14: no `powerPlay` on the snapshot.
        assertEquals(targets(), of(fresh(snap(pp = null))))
    }

    @Test
    fun endOfPeriod() {
        // Q5 default: "1ST" / "END" in the clock column, nothing else opens.
        assertEquals(targets(period = reg1, clockSlot = ClockSlot.End), of(fresh(snap(secs = 0, running = false, period = 1))))
        // With a power play still running at 0:00 the bar stays (WP-1 choice; it carries into the intermission).
        assertEquals(
            targets(sogOpen = true, bar = ppBar(Side.HOME), barSecs = 31, ppSide = Side.HOME, period = reg1, clockSlot = ClockSlot.End),
            of(fresh(snap(secs = 0, running = false, period = 1, pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 31)))),
        )
    }

    @Test
    fun ordinaryIntermission() {
        assertEquals(
            targets(sogOpen = true, clockOpen = false, bar = neutral(BarKind.INTERMISSION), barPeriod = reg1, period = reg1, clockSlot = ClockSlot.Blank),
            of(fresh(int(period = 1))),
        )
        val second = of(fresh(int(period = 2)))
        assertEquals(reg2, second.barPeriod)
    }

    @Test
    fun overtimeIntermissions() {
        // N5: "OT INTERMISSION", "2OT INTERMISSION" (playoffs).
        val ot = PeriodLabel(PeriodKind.OT, 1)
        val ot2 = PeriodLabel(PeriodKind.OT, 2)
        assertEquals(
            targets(sogOpen = true, clockOpen = false, bar = neutral(BarKind.INTERMISSION), barPeriod = ot, period = ot, clockSlot = ClockSlot.Blank),
            of(fresh(int(period = 4, kind = PeriodKind.OT, ot = 1))),
        )
        assertEquals(ot2, of(fresh(int(period = 5, kind = PeriodKind.OT, ot = 2))).barPeriod)
    }

    @Test
    fun powerPlayCarriedIntoTheIntermission() {
        // B3 (Q4b default): the PP bar with the frozen time; the clock column stays open, "1ST" over "INT".
        val pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 72)
        assertEquals(
            targets(sogOpen = true, bar = ppBar(Side.HOME), barSecs = 72, ppSide = Side.HOME, period = reg1, clockSlot = ClockSlot.Intermission),
            of(fresh(int(period = 1, pp = pp))),
        )
        // A 5-on-3 carried over.
        assertEquals(ppBar(Side.AWAY, BarKind.TWO_MAN), of(fresh(int(pp = PowerPlay(Side.AWAY, PowerPlayKind.TWO_MAN, 20)))).bar)
        // That PP's time runs out in the break: the ordinary intermission (a bar change, the clock folds).
        val after = of(fresh(int(period = 1)))
        assertEquals(neutral(BarKind.INTERMISSION), after.bar)
        assertFalse(after.clockOpen)
        assertFalse(StackedTargets.sameLook(ppBar(Side.HOME), neutral(BarKind.INTERMISSION)))
    }

    @Test
    fun goalsDoNotChangeTheTargets() {
        // The takeover and the pin are the runner's; the goal mark itself never changes a target, and PP_GOAL is never one.
        val r = Rig()
        val first = snap(away = 1, pp = PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 50))
        r.m.follow(SportsFixtures.followedOf(first))
        r.m.demand(Demand.SEEN)
        r.ok(first)
        r.at(20_000)
        // The PP goal: the score rises and the power play ends in the same snapshot.
        r.ok(snap(away = 2).copy(lastGoal = GoalFact(Side.AWAY, 2, 2, pp = true)))
        val v = assertNotNull(r.m.view())
        assertEquals(Side.AWAY, v.goalSide)
        assertTrue(v.goalPp)
        assertEquals(targets(), of(v), "the data: even strength, so the bar closes at the 3 600 release")
        assertEquals(of(v.copy(goalSide = null, goalPp = false)), of(v))
        // A shorthanded goal against the PP: the PP team's bar stays (it is still in the data).
        val sh = Rig()
        val pp = snap(home = 2, pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 80))
        sh.m.follow(SportsFixtures.followedOf(pp))
        sh.m.demand(Demand.SEEN)
        sh.ok(pp)
        sh.at(20_000)
        sh.ok(snap(away = 2, home = 2, pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 60)))
        val shv = assertNotNull(sh.m.view())
        assertEquals(Side.AWAY, shv.goalSide)
        assertEquals(ppBar(Side.HOME), of(shv).bar)
        // Every BarKind the table produces.
        val produced = listOf(
            snap(), snap(pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 9)), snap(pp = PowerPlay(Side.HOME, PowerPlayKind.TWO_MAN, 9)),
            int(), so(), fin(PeriodKind.REG),
        ).mapNotNull { of(fresh(it)).bar?.kind }.toSet() + of(stale(int())).bar!!.kind + of(waiting(int())).bar!!.kind
        assertEquals(BarKind.entries.toSet() - BarKind.PP_GOAL, produced)
    }

    @Test
    fun overtimePlay() {
        val ot2 = PeriodLabel(PeriodKind.OT, 2)
        assertEquals(targets(period = ot2), of(fresh(snap(period = 5, kind = PeriodKind.OT, ot = 2))))
    }

    @Test
    fun shootoutIsABreak() {
        // S10, Q6 default: the shots (reg + OT) stay open, the clock folds, "SHOOTOUT".
        val so = PeriodLabel(PeriodKind.SO, 1)
        assertEquals(
            targets(sogOpen = true, clockOpen = false, bar = neutral(BarKind.SHOOTOUT), period = so, clockSlot = ClockSlot.Blank),
            of(fresh(so())),
        )
        // A power play never shows in the shootout (the machine gates it).
        assertEquals(neutral(BarKind.SHOOTOUT), of(fresh(so().copy(powerPlay = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 9)))).bar)
    }

    @Test
    fun finals() {
        for ((kind, label) in listOf(PeriodKind.REG to "FINAL", PeriodKind.OT to "FINAL/OT", PeriodKind.SO to "FINAL/SO")) {
            val v = fresh(fin(kind))
            assertEquals(
                targets(sogOpen = true, clockOpen = false, bar = neutral(BarKind.FINAL), barPeriod = PeriodLabel(kind, 1), period = reg3, clockSlot = ClockSlot.Blank),
                of(v),
                label,
            )
        }
        // From SHOOTOUT or INT it is a text swap.
        assertTrue(StackedTargets.sameLook(neutral(BarKind.SHOOTOUT), neutral(BarKind.FINAL)))
        assertTrue(StackedTargets.sameLook(neutral(BarKind.INTERMISSION), neutral(BarKind.FINAL)))
    }

    @Test
    fun delayedWaitingInPlay() {
        // No PP while waiting; the period over "Delayed" at .55.
        val v = waiting(snap(pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 50)))
        assertNull(v.powerPlay)
        assertEquals(targets(clockSlot = ClockSlot.Delayed, columnDim = true), of(v))
    }

    @Test
    fun delayedWaitingInABreak() {
        // N5: "DELAYED" at .55, a text swap from the break bar.
        val v = waiting(int())
        assertEquals(
            targets(sogOpen = true, clockOpen = false, bar = neutral(BarKind.DELAYED), period = reg1, clockSlot = ClockSlot.Blank, columnDim = true, barDim = true),
            of(v),
        )
        assertTrue(StackedTargets.sameLook(neutral(BarKind.INTERMISSION), neutral(BarKind.DELAYED)))
        assertEquals(neutral(BarKind.DELAYED), of(waiting(so())).bar)
    }

    @Test
    fun staleInPlay() {
        // The PP bar closes (no PP when stale, PP §0.3); the period over "No update" at .55.
        val v = stale(snap(pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 50)))
        assertNull(v.powerPlay)
        assertEquals(targets(clockSlot = ClockSlot.NoUpdate, columnDim = true), of(v))
        // In overtime too.
        assertEquals(
            targets(period = PeriodLabel(PeriodKind.OT, 1), clockSlot = ClockSlot.NoUpdate, columnDim = true),
            of(stale(snap(period = 4, kind = PeriodKind.OT, ot = 1))),
        )
    }

    @Test
    fun staleInAnIntermission() {
        // S10: "NO UPDATE" at .55, a text swap; no reopening of the clock column.
        assertEquals(
            targets(sogOpen = true, clockOpen = false, bar = neutral(BarKind.NO_UPDATE), period = reg1, clockSlot = ClockSlot.Blank, columnDim = true, barDim = true),
            of(stale(int())),
        )
        assertTrue(StackedTargets.sameLook(neutral(BarKind.INTERMISSION), neutral(BarKind.NO_UPDATE)))
        // The words come back on the next fresh view: a swap back.
        assertTrue(StackedTargets.sameLook(neutral(BarKind.NO_UPDATE), neutral(BarKind.INTERMISSION)))
    }

    @Test
    fun staleInAPowerPlayCarriedIntoTheIntermission() {
        val carried = int(period = 1, pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 72))
        val lastFresh = of(fresh(carried))
        assertEquals(ClockSlot.Intermission, lastFresh.clockSlot)
        val v = stale(carried)
        assertNull(v.powerPlay)
        // §8: as last; the PP stripe narrows; SOG open; the clock column open, "1ST" / "No update" at .55; bar closed.
        val want = targets(sogOpen = true, period = reg1, clockSlot = ClockSlot.NoUpdate, columnDim = true)
        assertEquals(want, StackedTargets.of(v, lastFresh))
        // Repeated stale views keep it (the runner passes the last *fresh* targets).
        assertEquals(want, StackedTargets.of(v, lastFresh))
        // Waiting likewise.
        assertEquals(want.copy(clockSlot = ClockSlot.Delayed), StackedTargets.of(waiting(carried), lastFresh))
        // Without that history the view alone is the ordinary stale break.
        assertEquals(neutral(BarKind.NO_UPDATE), of(v).bar)
        assertFalse(of(v).clockOpen)
        // History from another period, or from an ordinary break, does not carry.
        assertEquals(neutral(BarKind.NO_UPDATE), StackedTargets.of(v, lastFresh.copy(period = reg2)).bar)
        assertEquals(neutral(BarKind.NO_UPDATE), StackedTargets.of(v, of(fresh(int(period = 1)))).bar)
        // A fresh view ignores the history.
        assertEquals(of(fresh(int(period = 1))), StackedTargets.of(fresh(int(period = 1)), lastFresh))
        // Every row that is not an intermission is the same with or without history.
        for (s in listOf(snap(), so(), snap(secs = 0, running = false))) {
            assertEquals(of(stale(s)), StackedTargets.of(stale(s), lastFresh.copy(period = stale(s).period)), "$s")
        }
    }

    @Test
    fun staleAtTheEndOfAPeriod() {
        // N5: "1ST" / "No update", .55.
        assertEquals(
            targets(period = reg1, clockSlot = ClockSlot.NoUpdate, columnDim = true),
            of(stale(snap(secs = 0, running = false, period = 1))),
        )
        assertEquals(
            targets(period = reg1, clockSlot = ClockSlot.Delayed, columnDim = true),
            of(waiting(snap(secs = 0, running = false, period = 1))),
        )
    }

    @Test
    fun staleInTheShootout() {
        assertEquals(
            targets(sogOpen = true, clockOpen = false, bar = neutral(BarKind.NO_UPDATE), period = PeriodLabel(PeriodKind.SO, 1), clockSlot = ClockSlot.Blank, columnDim = true, barDim = true),
            of(stale(so())),
        )
    }

    @Test
    fun staleOrWaitingAtFinalCannotHappen() {
        // `staleAt()` is null outside LIVE / INTERMISSION: a final view stays fresh offline.
        val r = Rig()
        val f = fin(PeriodKind.REG)
        r.m.follow(SportsFixtures.followedOf(f))
        r.m.demand(Demand.SEEN)
        r.ok(f)
        r.m.online(false)
        r.at(200_000)
        val v = assertNotNull(r.m.view())
        assertFalse(v.dim)
        assertEquals(neutral(BarKind.FINAL), of(v).bar)
        // Were one built by hand, it would read as a dimmed break, never crash.
        assertEquals(neutral(BarKind.NO_UPDATE), of(v.copy(bottom = CentreText.NoUpdate, dim = true)).bar)
    }

    @Test
    fun missingShotsKeepTheColumnClosedInEveryState() {
        val pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 50)
        val states = listOf(
            snap(pp = pp, awaySog = null), snap(pp = pp, homeSog = null), int(awaySog = null), int(pp = pp, awaySog = null),
            so(awaySog = null), fin(PeriodKind.REG, awaySog = null),
        )
        for (s in states) {
            val t = of(fresh(s))
            assertFalse(t.sogOpen, "$s")
            // Everything else is as with shots.
            assertEquals(of(fresh(s.copy(away = s.away.copy(sog = 20), home = s.home.copy(sog = 15)))).copy(sogOpen = false), t)
        }
        assertFalse(of(stale(int(awaySog = null))).sogOpen)
    }

    @Test
    fun theShotsChangeNoTarget() {
        // The SOG digits are the painter's; a SOG-only change keeps every target (no track kicks).
        assertEquals(of(fresh(int(awaySog = 20))), of(fresh(int(awaySog = 21))))
    }

    @Test
    fun placeModeSample() {
        // "AWY 0 / HME 0", clock "0:00", period slot empty (ScoreBug.kt's sampleView).
        val sample = ScoreBugView(
            TeamLine("AWY", "AWY", 0, null), TeamLine("HME", "HME", 0, null), CentreText.Clock, null, false, 0, null, clockSecs = 0,
        )
        assertEquals(targets(period = null), of(sample))
    }

    @Test
    fun aClocklessPlayViewHasABlankSlot() {
        val v = fresh(snap()).copy(clockSecs = null)
        assertEquals(ClockSlot.Blank, of(v).clockSlot)
    }

    // ------------------------------------------------------------------ sameLook

    @Test
    fun sameLookForEverySwapAndChangePair() {
        val teamKinds = listOf(BarKind.POWER_PLAY, BarKind.TWO_MAN, BarKind.PP_GOAL)
        val neutralKinds = listOf(BarKind.INTERMISSION, BarKind.SHOOTOUT, BarKind.FINAL, BarKind.NO_UPDATE, BarKind.DELAYED)
        assertEquals(BarKind.entries.toSet(), (teamKinds + neutralKinds).toSet())
        for (k in teamKinds) assertTrue(StackedTargets.isTeam(k))
        for (k in neutralKinds) assertFalse(StackedTargets.isTeam(k))
        // Text swaps: same side, team kinds (5-on-4 ⇄ 5-on-3, a PP ⇄ its "POWER PLAY GOAL"); any two neutral kinds.
        for (side in Side.entries) for (a in teamKinds) for (b in teamKinds) {
            assertTrue(StackedTargets.sameLook(BarKey(a, side), BarKey(b, side)), "$a/$b $side")
        }
        for (a in neutralKinds) for (b in neutralKinds) assertTrue(StackedTargets.sameLook(neutral(a), neutral(b)), "$a/$b")
        // Changes: the other side's PP; a PP ⇄ any break.
        for (a in teamKinds) for (b in teamKinds) {
            assertFalse(StackedTargets.sameLook(BarKey(a, Side.AWAY), BarKey(b, Side.HOME)), "$a away / $b home")
        }
        for (side in Side.entries) for (t in teamKinds) for (n in neutralKinds) {
            assertFalse(StackedTargets.sameLook(BarKey(t, side), neutral(n)), "$t $side / $n")
            assertFalse(StackedTargets.sameLook(neutral(n), BarKey(t, side)), "$n / $t $side")
        }
    }
}
