package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.thebox.core.sports.SportsConstants as K

/**
 * docs/design/m6-nfl-stacked.md §3.4, §9.1 `StackedNflLayoutTest` (WP-2): every §3.4 row of `StackedNflTargets.of` and
 * the RC4 precedence (stale / waiting > break / final > flag > timeout > play), the RC9 kick-off (also end to end from the
 * live fixture through the mapper and the machine), the knownView shapes, the pips' counts and slots.
 */
class StackedNflLayoutTest {
    private val kc = Side.AWAY
    private val buf = Side.HOME
    private val reg4 = PeriodLabel(PeriodKind.REG, 4)

    private fun view(
        down: DownText? = DownText.Down(1, 10, goal = false),
        tab: NflTab? = NflTab(kc, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35)),
        away: Int? = 3, home: Int? = 2, slots: Int? = 3,
        timeout: NflTimeoutMark? = null, flagId: String? = null,
    ) = SportsFixtures.nflStackedView(down = down, tab = tab, awayTimeouts = away, homeTimeouts = home, timeoutSlots = slots, timeout = timeout, flagId = flagId)

    private fun of(v: ScoreBugView) = StackedNflTargets.of(v)

    /** [v] as the NFL machine draws a stale (`NoUpdate`) or waiting (`Delayed`) view, in play or in a break. */
    private fun dimmed(v: ScoreBugView, top: CentreText, inPlay: Boolean) =
        v.copy(top = top, bottom = if (inPlay) CentreText.Clock else null, dim = true, clockSecs = if (inPlay) v.clockSecs else null)

    /** [v] in a break: one line ([top]), no clock. */
    private fun brk(v: ScoreBugView, top: CentreText) = v.copy(top = top, bottom = null, clockSecs = null)

    @Test
    fun playWithTheSideKnown() {
        val t = of(view())
        assertEquals(
            StackedNflTargets(
                clockOpen = true,
                bar = NflBarKey(NflBarKind.PLAY, kc, down = DownText.Down(1, 10, false), tail = TabTail.Field(FieldZone.OWN, 35)),
                possSide = kc, period = reg4, clockSlot = ClockSlot.Clock, columnDim = false, barDim = false,
                awayTimeouts = 3, homeTimeouts = 2, timeoutSlots = 3, burnSide = null,
            ),
            t,
        )
        // "3RD | KC 35" (distance unknown), "4TH & GOAL | BUF 2", midfield, no field (label alone).
        for (d in listOf(DownText.Down(3, null, false), DownText.Down(4, null, true))) {
            assertEquals(d, of(view(down = d)).bar?.down)
        }
        val mid = of(view(tab = NflTab(buf, TabLead.BALL, TabTail.Field(FieldZone.MIDFIELD, 50))))
        assertEquals(NflBarKey(NflBarKind.PLAY, buf, DownText.Down(1, 10, false), TabTail.Field(FieldZone.MIDFIELD, 50)), mid.bar)
        assertEquals(buf, mid.possSide)
        assertNull(of(view(tab = NflTab(kc, TabLead.BALL, null))).bar?.tail)
        // The red zone: the gated tail (R26).
        assertEquals(TabTail.RedZone, of(view(tab = NflTab(kc, TabLead.BALL, TabTail.RedZone))).bar?.tail)
        // A clock-only update keeps the key (no REKEY).
        val v = view()
        assertEquals(of(v).bar, of(v.copy(clockSecs = 100)).bar)
        assertEquals(ClockSlot.Blank, of(v.copy(clockSecs = null)).clockSlot)
    }

    @Test
    fun playWithTheSideUnknownIsTheNeutralBar() {
        // S-D10 (Rec 8): a known down with no possession shows a neutral bar with the words; no ball.
        val t = of(view(tab = null))
        assertEquals(NflBarKey(NflBarKind.PLAY, null, DownText.Down(1, 10, false), null), t.bar)
        assertNull(t.possSide)
        assertTrue(t.clockOpen)
    }

    @Test
    fun patAndKickoff() {
        val pat = of(view(down = DownText.Pat, tab = NflTab(buf, TabLead.BALL, TabTail.ExtraPoint)))
        assertEquals(NflBarKey(NflBarKind.PAT, buf), pat.bar, "\"EXTRA POINT\", no spot (S-D7)")
        assertEquals(buf, pat.possSide)
        // Owner ruling 2026-11-16: the two-point try keeps the PAT kind (colours, side) and carries its tail ("2-PT TRY").
        val two = of(view(down = DownText.Pat, tab = NflTab(buf, TabLead.BALL, TabTail.TwoPointTry)))
        assertEquals(NflBarKey(NflBarKind.PAT, buf, tail = TabTail.TwoPointTry), two.bar)
        assertEquals(buf, two.possSide)
        val ko = of(view(down = DownText.Kickoff, tab = NflTab(buf, TabLead.KICKOFF, TabTail.Field(FieldZone.OWN, 35))))
        assertEquals(NflBarKey(NflBarKind.KICKOFF, buf, DownText.Kickoff, TabTail.Field(FieldZone.OWN, 35)), ko.bar)
        assertEquals(buf, ko.possSide)
        // RC9: a kick-off never reads RED ZONE, even if a tail says so.
        val red = of(view(down = DownText.Kickoff, tab = NflTab(buf, TabLead.KICKOFF, TabTail.RedZone)))
        assertEquals(NflBarKey(NflBarKind.KICKOFF, buf, DownText.Kickoff, null), red.bar)
    }

    @Test
    fun noSituationClosesTheBar() {
        // R21: LIVE with no situation: the bar closes; the clock column stays open.
        val t = of(view(down = null, tab = null))
        assertNull(t.bar); assertNull(t.possSide); assertTrue(t.clockOpen); assertEquals(ClockSlot.Clock, t.clockSlot)
        // The pips still show (held counts).
        assertEquals(3, t.awayTimeouts); assertEquals(2, t.homeTimeouts)
    }

    @Test
    fun theFlagAndTheTimeoutMarks() {
        val flag = of(view(flagId = "401873061503"))
        assertEquals(NflBarKey(NflBarKind.FLAG, null), flag.bar)
        assertEquals(kc, flag.possSide, "ball as play"); assertNull(flag.burnSide); assertTrue(flag.clockOpen)
        for (confirmed in listOf(true, false)) {
            val to = of(view(timeout = NflTimeoutMark(buf, 1, confirmed), home = 1))
            assertEquals(NflBarKey(NflBarKind.TIMEOUT, buf, left = 1), to.bar, "confirmed=$confirmed")
            assertEquals(buf, to.burnSide); assertEquals(kc, to.possSide); assertEquals(1, to.homeTimeouts)
        }
        assertEquals(0, of(view(timeout = NflTimeoutMark(kc, 0, true), away = 0)).bar?.left, "\"0 LEFT\"")
        // RC4: the flag above the timeout, the timeout above the play.
        assertEquals(NflBarKind.FLAG, of(view(flagId = "9", timeout = NflTimeoutMark(buf, 1, true))).bar?.kind)
        assertEquals(NflBarKind.TIMEOUT, of(view(timeout = NflTimeoutMark(buf, 1, true), down = DownText.Kickoff)).bar?.kind)
        // A mark with no situation still shows its bar.
        assertEquals(NflBarKind.TIMEOUT, of(view(down = null, tab = null, timeout = NflTimeoutMark(kc, 2, false))).bar?.kind)
    }

    @Test
    fun staleAndWaitingInPlayCloseTheBar() {
        for ((top, slot) in listOf(CentreText.NoUpdate to ClockSlot.NoUpdate, CentreText.Delayed to ClockSlot.Delayed)) {
            // RC5: even with a mark or a down, stale / waiting in play closes the bar and dims the column.
            for (v in listOf(view(), view(flagId = "1"), view(timeout = NflTimeoutMark(buf, 1, true)))) {
                val t = of(dimmed(v, top, inPlay = true))
                assertNull(t.bar, "$top"); assertNull(t.possSide); assertTrue(t.clockOpen)
                assertEquals(slot, t.clockSlot); assertTrue(t.columnDim); assertFalse(t.barDim); assertNull(t.burnSide)
                assertEquals(reg4, t.period, "the column keeps 4TH (period)")
                assertEquals(3, t.awayTimeouts, "pips are row content, never dimmed or hidden")
            }
        }
    }

    @Test
    fun staleAndWaitingInABreakNameTheNeutralBar() {
        for ((top, kind) in listOf(CentreText.NoUpdate to NflBarKind.NO_UPDATE, CentreText.Delayed to NflBarKind.DELAYED)) {
            val t = of(dimmed(view(down = null, tab = null), top, inPlay = false))
            assertEquals(NflBarKey(kind, null), t.bar)
            assertFalse(t.clockOpen); assertEquals(ClockSlot.Blank, t.clockSlot); assertTrue(t.barDim); assertTrue(t.columnDim)
            assertNull(t.possSide)
        }
    }

    @Test
    fun breaksAndFinal() {
        val v = view(down = null, tab = null)
        val end = of(brk(v, CentreText.EndOf(PeriodLabel(PeriodKind.REG, 1))))
        assertEquals(NflBarKey(NflBarKind.END_PERIOD, null, period = PeriodLabel(PeriodKind.REG, 1)), end.bar)
        assertFalse(end.clockOpen); assertNull(end.possSide); assertEquals(ClockSlot.Blank, end.clockSlot); assertFalse(end.barDim)
        assertEquals(NflBarKey(NflBarKind.END_PERIOD, null, period = PeriodLabel(PeriodKind.OT, 1)), of(brk(v, CentreText.EndOf(PeriodLabel(PeriodKind.OT, 1)))).bar)
        assertEquals(NflBarKey(NflBarKind.HALFTIME, null), of(brk(v, CentreText.Halftime)).bar)
        assertEquals(NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.REG), of(brk(v, CentreText.Final(PeriodKind.REG))).bar)
        assertEquals(NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.OT), of(brk(v, CentreText.Final(PeriodKind.OT))).bar)
        // RC4: a break is above the marks and above a PAT carried through it (no ball in a break).
        for (marked in listOf(view(flagId = "1"), view(timeout = NflTimeoutMark(buf, 1, true)), view(down = DownText.Pat, tab = NflTab(kc, TabLead.BALL, TabTail.ExtraPoint)))) {
            val t = of(brk(marked, CentreText.Halftime))
            assertEquals(NflBarKind.HALFTIME, t.bar?.kind); assertNull(t.possSide); assertNull(t.burnSide); assertFalse(t.clockOpen)
        }
        // The pips through a break (held counts).
        assertEquals(2, of(brk(v, CentreText.Halftime)).homeTimeouts)
    }

    @Test
    fun theKnownViewShapes() {
        // knownView's NFL views carry no NflView: stale in play closes the bar; Final is the FINAL bar; no pips.
        val g = SportsFixtures.nflGame()
        val base = ScoreBugView(g.away, g.home, CentreText.NoUpdate, CentreText.Clock, dim = true, goalSeq = 0, goalSide = null, clockSecs = 100, league = League.NFL)
        val play = of(base)
        assertNull(play.bar); assertEquals(ClockSlot.NoUpdate, play.clockSlot); assertTrue(play.clockOpen)
        assertNull(play.awayTimeouts); assertEquals(0, play.timeoutSlots)
        val fin = of(base.copy(top = CentreText.Final(PeriodKind.REG), bottom = null, dim = false, clockSecs = null))
        assertEquals(NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.REG), fin.bar)
        assertEquals(NflBarKind.NO_UPDATE, of(base.copy(bottom = null, clockSecs = null)).bar?.kind)
    }

    @Test
    fun thePipsCountsAndSlots() {
        assertEquals(3, of(view()).timeoutSlots)
        // No slots → hidden.
        val none = of(view(slots = null))
        assertNull(none.awayTimeouts); assertNull(none.homeTimeouts); assertEquals(0, none.timeoutSlots)
        // An OT group of 2 slots (RC7); counts clamped to the slots and to 0..3.
        val ot = of(view(away = 2, home = 1, slots = 2))
        assertEquals(2, ot.timeoutSlots); assertEquals(2, ot.awayTimeouts); assertEquals(1, ot.homeTimeouts)
        assertEquals(2, of(view(away = 3, slots = 2)).awayTimeouts)
        assertEquals(3, of(view(slots = 7)).timeoutSlots)
        assertNull(of(view(away = null)).awayTimeouts)
    }

    @Test
    fun placeModeSample() {
        // NFL §4.6: KC 24 – BUF 17, "4TH" / "3:42", "1ST & 10 | KC 35", ball KC, pips 3 / 2 of 3.
        val t = of(SportsFixtures.nflStackedView(tab = NflTab(kc, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35))))
        assertEquals(NflBarKind.PLAY, t.bar?.kind); assertEquals(kc, t.possSide); assertTrue(t.clockOpen)
        assertEquals(3, t.awayTimeouts); assertEquals(2, t.homeTimeouts); assertEquals(3, t.timeoutSlots)
    }

    // ------------------------------------------------------------------ end to end from the live fixtures

    private fun machineView(s: GameSnapshot): ScoreBugView? {
        var now = 0L
        val wall0 = s.startUtcMs + 30 * 60_000L
        val m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))
        m.follow(SportsFixtures.followedOf(s))
        m.demand(Demand.SEEN)
        m.fetched(Fetch.Ok(s, now, wall0 + now, 0))
        now += 20_000
        m.step()
        m.fetched(Fetch.Ok(s, now, wall0 + now, 0))
        return m.view()
    }

    @Test
    fun theOfficialTimeoutKickoffNeverReadsRedZone() {
        // RC9: SEA @ LV, the kick-off after a score with the raw `isRedZone: true`.
        val s = assertNotNull(NflStackedFixtures.snapshot(NflStackedFixtures.OFFICIAL_TIMEOUT_KICKOFF_REDZONE))
        val v = assertNotNull(machineView(s))
        val t = of(v)
        val bar = assertNotNull(t.bar, "the kick-off shows a bar")
        assertEquals(NflBarKind.KICKOFF, bar.kind)
        assertTrue(bar.tail != TabTail.RedZone, "never RED ZONE: $bar")
        assertEquals(buf, bar.side, "the kicker, LV (home)")
    }

    @Test
    fun theKickoffReturnWithNoPossessionReadsAPlay() {
        val s = assertNotNull(NflStackedFixtures.snapshot(NflStackedFixtures.KICKOFF_RETURN_NO_POSSESSION))
        val t = of(assertNotNull(machineView(s)))
        assertEquals(NflBarKind.PLAY, t.bar?.kind)
        assertEquals(DownText.Down(1, 10, goal = false), t.bar?.down)
    }

    // ------------------------------------------------------------------ keys

    @Test
    fun theTeamKindsAndTheBarColoursAgree() {
        // Every key `of` produces maps to a colour (NflStackedColours.bar): team kinds with a side, neutral otherwise.
        val p = NflStackedColours.pair("KC", "BUF")
        val keys = listOf(
            of(view()).bar, of(view(tab = null)).bar, of(view(flagId = "1")).bar,
            of(view(timeout = NflTimeoutMark(buf, 1, true))).bar,
            of(brk(view(), CentreText.Halftime)).bar,
        )
        val bgs = keys.map { NflStackedColours.bar(assertNotNull(it), p).bg }
        assertEquals(listOf(p.away.barBg, K.STACKED_BAR_NEUTRAL, K.STACKED_NFL_FLAG_BG, p.home.barBg, K.STACKED_BAR_NEUTRAL), bgs)
    }
}
