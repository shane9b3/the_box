package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * (CHL §5.5, §5.6) The worked examples E1–E33 and the hide rules H1–H9 through [ChlPowerPlay.derive]. Notation as in the
 * design: `A2@9:00` = an away, 2-minute, PP-flagged row called at 9:00 elapsed; now = (period, **remaining**); periods
 * 1200 s, OT 300 s. E7, E26 and E33 also run through the mapper in `HockeyTechMapperTest`.
 */
class ChlPowerPlayTest {

    // ------------------------------------------------------------------ builders

    private var nextId = 1000L

    /** A penalty row; the player defaults to a fresh id (no accidental chains). */
    private fun pen(
        home: Boolean,
        time: String,
        period: Int = 1,
        minutes: Int? = 2,
        pp: Boolean = true,
        start: String? = time,
        on: String? = null,
        player: Long? = -1,
        penaltyShot: Boolean = false,
        id: Long = nextId++,
    ) = ChlPenalty(
        id = id, home = home, period = period, timeS = s(time), startS = start?.let(::s), onS = on?.let(::s),
        minutes = minutes, ppFlag = pp, penaltyShot = penaltyShot, playerId = if (player == -1L) 50_000 + id else player,
    )

    private fun a2(time: String, period: Int = 1, player: Long? = -1, start: String? = time, on: String? = null) =
        pen(home = false, time = time, period = period, player = player, start = start, on = on)

    private fun goal(home: Boolean, time: String, period: Int? = 1, pp: Boolean = false, sh: Boolean = false, ps: Boolean = false) =
        ChlGoal(nextId++, home, period, s(time), pp, sh, ps)

    private fun now(
        period: Int,
        remaining: String,
        phase: GamePhase = GamePhase.LIVE,
        feedPeriod: Int = period,
        shootout: Boolean = false,
        decided: Boolean = false,
    ) = ChlNow(period, s(remaining), feedPeriod, phase, shootout, decided)

    private val reg: (Int) -> Int? = { q -> if (q <= 3) 1200 else 300 }

    private fun derive(
        pens: List<ChlPenalty>?,
        now: ChlNow,
        goals: List<ChlGoal>? = emptyList(),
        goalTotal: Int = goals?.size ?: 0,
        attempts: Int? = pens?.size ?: 0,
        length: (Int) -> Int? = reg,
    ): ChlPp = ChlPowerPlay.derive(pens, goals, goalTotal, attempts, now, length)

    private fun home(secs: Int, kind: PowerPlayKind = PowerPlayKind.ONE_MAN): ChlPp = ChlPp.Shown(PowerPlay(Side.HOME, kind, secs))
    private fun away(secs: Int, kind: PowerPlayKind = PowerPlayKind.ONE_MAN): ChlPp = ChlPp.Shown(PowerPlay(Side.AWAY, kind, secs))

    private fun s(t: String): Int = t.split(':').let { it[0].toInt() * 60 + it[1].toInt() }

    // ------------------------------------------------------------------ E1–E33

    @Test fun e01_oneMinor() = assertEquals(home(60), derive(listOf(a2("9:00")), now(1, "10:00")))

    @Test fun e02_aPpGoalReleasesTheMinor() =
        assertEquals(ChlPp.None, derive(listOf(a2("9:00")), now(1, "10:00"), listOf(goal(true, "9:30", pp = true))))

    @Test fun e03_twoMinorsDifferentPlayers() =
        assertEquals(home(70, PowerPlayKind.TWO_MAN), derive(listOf(a2("8:30"), a2("9:00")), now(1, "10:40")))

    @Test fun e04_coincidentUnflaggedMinors() = assertEquals(
        ChlPp.None,
        derive(listOf(pen(false, "5:00", pp = false), pen(true, "5:00", pp = false)), now(1, "14:30")),
    )

    @Test fun e05_aMajorIsNotReleased() = assertEquals(
        home(180),
        derive(listOf(pen(false, "2:00", minutes = 5)), now(1, "16:00"), listOf(goal(true, "3:00", pp = true))),
    )

    @Test fun e06_breakFreezesTheSeconds() =
        assertEquals(home(90), derive(listOf(a2("19:30")), now(1, "0:00", GamePhase.INTERMISSION)))

    /** E7: the feed's "20:00 2nd" break form, after the mapper's S12 adjustment (period 1, remaining 0, feed period 2). */
    @Test fun e07_theTwentyMinuteBreakForm() =
        assertEquals(home(90), derive(listOf(a2("19:30")), now(1, "0:00", GamePhase.INTERMISSION, feedPeriod = 2)))

    @Test fun e08_carryOver() = assertEquals(home(70), derive(listOf(a2("19:30")), now(2, "19:40")))

    private fun doubleMinor(secondStart: String = "5:00") = listOf(
        a2("5:00", player = 77, start = "5:00").copy(id = 1),
        a2("5:00", player = 77, start = secondStart).copy(id = 2),
    )

    @Test fun e09_doubleMinorCountsDownOnce() = assertEquals(home(180), derive(doubleMinor(), now(1, "14:00")))

    @Test fun e10_ppGoalInADoubleMinor() =
        assertEquals(home(90), derive(doubleMinor(), now(1, "13:00"), listOf(goal(true, "6:30", pp = true))))

    @Test fun e11_editedDoubleMinor() =
        assertEquals(home(90), derive(doubleMinor(secondStart = "6:30"), now(1, "13:00"), listOf(goal(true, "6:30", pp = true))))

    private fun stacked() = listOf(a2("1:00"), a2("1:30"), a2("2:00"))

    @Test fun e12_stackedThree() = assertEquals(home(60, PowerPlayKind.TWO_MAN), derive(stacked(), now(1, "17:30")))

    @Test fun e13_stackedThreeLater() = assertEquals(home(20, PowerPlayKind.TWO_MAN), derive(stacked(), now(1, "16:50")))

    @Test fun e14_overtimeThreeOnThree() = assertEquals(home(90), derive(listOf(a2("1:00", period = 4)), now(4, "3:30")))

    @Test fun e15_carriedIntoOvertime() = assertEquals(home(30), derive(listOf(a2("19:00", period = 3)), now(4, "4:30")))

    @Test fun e16_timeOnCapsTheEnd() = assertEquals(ChlPp.None, derive(listOf(a2("9:00", on = "9:20")), now(1, "10:30")))

    @Test fun e17_flaggedBenchMinor() = assertEquals(home(90), derive(listOf(a2("4:00", player = null)), now(1, "15:30")))

    @Test fun e18_misconductPlusMinor() = assertEquals(
        home(60),
        derive(listOf(pen(false, "4:00", minutes = 10, pp = false, player = 9), pen(false, "4:00", player = 9)), now(1, "15:00")),
    )

    @Test fun e19_penaltyShot() =
        assertEquals(ChlPp.None, derive(listOf(pen(false, "4:00", penaltyShot = true)), now(1, "15:00")))

    @Test fun e20_awayAdvantageToTheEarliestHomeSeat() = assertEquals(
        away(80),
        derive(listOf(pen(true, "1:00"), pen(true, "1:30"), pen(false, "1:10")), now(1, "18:20")),
    )

    @Test fun e21_ppGoalWithNoPenaltyIsUnknown() =
        assertEquals(ChlPp.Unknown, derive(emptyList(), now(1, "11:00"), listOf(goal(true, "8:00", pp = true)), attempts = 0))

    @Test fun e22_goalsBehindTheSummary() = assertEquals(
        ChlPp.Unknown,
        derive(listOf(a2("9:00")), now(1, "10:30"), listOf(goal(true, "2:00"), goal(false, "4:00")), goalTotal = 3),
    )

    @Test fun e23_aPenaltyFromTheFuture() = assertEquals(ChlPp.Unknown, derive(listOf(a2("15:00")), now(1, "6:00")))

    @Test fun e24_shootoutOrFinal() {
        assertEquals(ChlPp.None, derive(listOf(a2("2:00", period = 4)), now(4, "0:00", shootout = true)))
        assertEquals(ChlPp.None, derive(listOf(a2("19:30", period = 3)), now(3, "0:00", GamePhase.FINAL)))
        assertEquals(ChlPp.None, derive(null, now(1, "10:00", GamePhase.SCHEDULED), attempts = null))
    }

    @Test fun e25_overtimeLengthUnknown() = assertEquals(
        ChlPp.Unknown,
        derive(listOf(a2("1:00", period = 4)), now(4, "4:00"), length = { q -> if (q <= 3) 1200 else null }),
    )

    // E26 (penalties failed this poll, H8) is the mapper's: HockeyTechMapperTest.h8_aFailedNodeIsUnknown.

    @Test fun e27_noPenaltiesNodeAndNoAttempts() = assertEquals(ChlPp.None, derive(null, now(1, "10:00"), attempts = 0))

    @Test fun e28_noPenaltiesNodeButAttempts() = assertEquals(ChlPp.Unknown, derive(null, now(1, "10:00"), attempts = 2))

    /** E29 (B2a): one player's tripping + unsportsmanlike at 16:48 chain (one seat), never TWO_MAN. */
    @Test fun e29_onePlayersTwoMinorsChain() {
        val rows = listOf(a2("16:48", player = 24638).copy(id = 956755), a2("16:48", player = 24638).copy(id = 956756))
        assertEquals(home(198), derive(rows, now(1, "2:30")))
        assertEquals(home(78), derive(rows, now(1, "0:30")))
        assertEquals(home(38), derive(rows, now(2, "19:50"))) // still a PP 18:48–20:48
        assertEquals(ChlPp.None, derive(rows, now(2, "19:12")))
    }

    /** E30 (B2b): OHL 29179 P2, the edited pair with reversed ids, then the 15:11 double minor (as recorded). */
    @Test fun e30_editedPairWithReversedIds() {
        val p1507a = pen(false, "15:07", period = 2, start = "15:16", player = 9971, id = 454471)
        val p1507b = pen(false, "15:07", period = 2, start = "15:07", player = 9971, id = 454472)
        val p1511a = pen(false, "15:11", period = 2, start = "16:50", player = 9572, id = 454473)
        val p1511b = pen(false, "15:11", period = 2, start = "15:11", player = 9572, id = 454474)
        val g1516 = goal(true, "15:16", period = 2, pp = true)
        val g1650 = goal(true, "16:50", period = 2, pp = true)
        assertEquals(home(237), derive(listOf(p1507a, p1507b), now(2, "4:50")))
        assertEquals(
            home(76, PowerPlayKind.TWO_MAN),
            derive(listOf(p1507a, p1507b, p1511a, p1511b), now(2, "4:00"), listOf(g1516)),
        )
        // No phantom minor to 19:16: after both PP goals only the 15:11 chain's second link serves (16:50–18:50).
        assertEquals(home(50), derive(listOf(p1507a, p1507b, p1511a, p1511b), now(2, "2:00"), listOf(g1516, g1650)))
        assertEquals(ChlPp.None, derive(listOf(p1507a, p1507b, p1511a, p1511b), now(2, "1:00"), listOf(g1516, g1650)))
    }

    /** E31 (S1): WHL 1023281 P2: a flagged home fighting major against an unflagged away minor at 5:16. */
    @Test fun e31_majorAgainstAnUnflaggedMinor() {
        val rows = listOf(
            pen(true, "5:16", period = 2, minutes = 5, pp = true),
            pen(false, "5:16", period = 2, minutes = 2, pp = false),
        )
        assertEquals(ChlPp.Unknown, derive(rows, now(2, "14:30"))) // H9: really 4-on-4
        assertEquals(away(136), derive(rows, now(2, "12:00")))
    }

    /** E32 (S2): OHL 29185 P1: a double minor at 5:36, both TimeStart 07:36, the first TimeOn 07:36 (inconsistent). */
    @Test fun e32_timeOnNotAfterTimeStart() {
        val rows = listOf(
            pen(true, "5:36", start = "7:36", on = "9:36", player = 8774, id = 454520),
            pen(true, "5:36", start = "7:36", on = "7:36", player = 8774, id = 454521),
        )
        assertEquals(away(186), derive(rows, now(1, "13:30")))
        assertEquals(ChlPp.None, derive(rows, now(1, "9:30"))) // no phantom 9:36–11:36 bar
    }

    /** E33 (S3): the mapper marks a decided game at 0:00 (P3, 4–1): no power play. */
    @Test fun e33_decidedGame() =
        assertEquals(ChlPp.None, derive(listOf(a2("19:08", period = 3)), now(3, "0:00", decided = true)))

    // ------------------------------------------------------------------ H1–H9

    @Test fun h1_periodLengths() {
        assertEquals(ChlPp.Unknown, derive(listOf(a2("1:00")), now(2, "10:00"), length = { q -> if (q == 1) null else 1200 }))
        assertEquals(ChlPp.Unknown, derive(listOf(a2("1:00")), now(1, "21:00"))) // remaining outside 0..L
        assertEquals(ChlPp.Unknown, derive(listOf(a2("1:00")), ChlNow(0, 600, 0, GamePhase.LIVE, false, false)))
    }

    @Test fun h2_unplaceableRows() {
        assertEquals(ChlPp.Unknown, derive(listOf(a2("9:00").copy(timeS = null)), now(1, "10:00")))
        assertEquals(ChlPp.Unknown, derive(listOf(a2("9:00").copy(minutes = null)), now(1, "10:00")))
        assertEquals(ChlPp.Unknown, derive(listOf(a2("1:00", period = 2)), now(1, "5:00")))
        assertEquals(ChlPp.Unknown, derive(listOf(a2("1:00").copy(period = 0)), now(1, "5:00")))
        // An unflagged row with no time is not needed: no hide.
        assertEquals(home(60), derive(listOf(a2("9:00"), pen(true, "3:00", pp = false).copy(timeS = null)), now(1, "10:00")))
    }

    /** S12: a penalty logged in period P during the "20:00 P" break (snapshot period P − 1, feed period P) is placed. */
    @Test fun h2_aPenaltyLoggedDuringTheBreakIsPlaced() =
        assertEquals(home(120), derive(listOf(a2("0:00", period = 2)), now(1, "0:00", GamePhase.INTERMISSION, feedPeriod = 2)))

    @Test fun h2_aGoalWithoutATime() {
        val g = ChlGoal(1, true, 1, null, pp = false, sh = false, penaltyShot = false)
        assertEquals(ChlPp.Unknown, derive(listOf(a2("9:00")), now(1, "10:00"), listOf(g)))
        assertEquals(ChlPp.None, derive(listOf(a2("2:00")), now(1, "10:00"), listOf(g))) // nothing serving
        assertEquals(ChlPp.Unknown, derive(listOf(a2("9:00")), now(1, "10:00"), listOf(g.copy(timeS = 30, period = null))))
    }

    @Test fun h3_withinTheTolerance() {
        assertEquals(ChlPp.None, derive(listOf(a2("10:05")), now(1, "10:00"))) // 5 s ahead: kept, not yet serving
        assertEquals(ChlPp.Unknown, derive(listOf(a2("10:06")), now(1, "10:00")))
    }

    /** H3 applies to the first link of a chain: a later link starts where the chain puts it (E30, E32). */
    @Test fun h3_aLaterChainLinkMayStartInTheFuture() {
        val rows = listOf(a2("5:00", player = 5, start = "5:00").copy(id = 1), a2("5:00", player = 5, start = "7:00").copy(id = 2))
        assertEquals(home(150), derive(rows, now(1, "13:30")))
    }

    @Test fun h4_secondsOutOfRange() {
        // One player: a major and four minors at one stoppage chain into one 13-minute seat.
        val rows = listOf(5, 2, 2, 2, 2).mapIndexed { i, m -> pen(false, "2:00", minutes = m, player = 3, id = 10L + i) }
        assertEquals(ChlPp.Unknown, derive(rows, now(1, "17:50"))) // 770 s > PP_SECS_MAX
        assertEquals(home(590), derive(rows.dropLast(1), now(1, "16:50")))
    }

    @Test fun h5_recentGoalFlags() {
        // A short-handed flag from the side on the power play.
        assertEquals(ChlPp.Unknown, derive(listOf(a2("9:00")), now(1, "10:30"), listOf(goal(true, "9:10", sh = true))))
        // A short-handed flag from the short-handed side agrees.
        assertEquals(home(60), derive(listOf(a2("9:00")), now(1, "10:00"), listOf(goal(false, "9:30", sh = true))))
        // Outside the 120 s window the contradiction is forgotten.
        assertEquals(ChlPp.None, derive(emptyList(), now(1, "8:59"), listOf(goal(true, "9:00", pp = true)), attempts = 0))
        assertEquals(ChlPp.Unknown, derive(emptyList(), now(1, "9:00"), listOf(goal(true, "9:00", pp = true)), attempts = 0))
    }

    @Test fun h6_goalsNodeMissingWhileServing() {
        assertEquals(ChlPp.Unknown, derive(listOf(a2("9:00")), now(1, "10:00"), goals = null, goalTotal = 0))
        assertEquals(ChlPp.None, derive(listOf(a2("2:00")), now(1, "10:00"), goals = null, goalTotal = 0))
        // The shootout +1 is the caller's to exclude; a count that matches passes.
        assertEquals(home(60), derive(listOf(a2("9:00")), now(1, "10:00"), listOf(goal(false, "1:00")), goalTotal = 1))
    }

    @Test fun h7_noPenaltiesNode() {
        assertEquals(ChlPp.Unknown, derive(null, now(1, "10:00"), attempts = 1))
        assertEquals(ChlPp.Unknown, derive(null, now(1, "10:00"), attempts = null))
        assertEquals(ChlPp.None, derive(null, now(1, "10:00"), attempts = 0))
    }

    /** H9's pairing keeps "roughing pair + extra minor" visible (QMJHL 32755 P1 07:29, as recorded). */
    @Test fun h9_pairedRoughingKeepsTheDoubleMinor() {
        val rows = listOf(
            pen(true, "7:29", pp = false, player = 19680, id = 956750),
            pen(false, "7:29", pp = false, player = 21651, id = 956751),
            pen(false, "7:29", player = 23756, id = 956752),
            pen(false, "7:29", player = 23756, id = 956753),
        )
        assertEquals(home(180), derive(rows, now(1, "11:31")))
    }

    @Test fun h9_unpairedMinorAgainstACountedMinor() {
        val rows = listOf(pen(true, "6:00"), pen(false, "6:00", pp = false), pen(false, "6:00", pp = false), pen(true, "6:00", pp = false))
        // Away: two unflagged, home: one flagged + one unflagged → one unflagged away minor is left: hidden for 2:00.
        assertEquals(ChlPp.Unknown, derive(rows, now(1, "13:00")))
        assertEquals(ChlPp.None, derive(rows, now(1, "11:59"))) // the flagged minor ended at 8:00 too
    }

    @Test fun h9_unflaggedMinorOnTheSameSideDoesNotHide() {
        val rows = listOf(pen(false, "6:00"), pen(false, "6:00", pp = false))
        assertEquals(home(60), derive(rows, now(1, "13:00")))
    }

    // ------------------------------------------------------------------ phases and the end-of-game rule

    @Test fun onlyInPlay() {
        val rows = listOf(a2("9:00"))
        for (phase in listOf(GamePhase.SCHEDULED, GamePhase.PRE, GamePhase.FINAL, GamePhase.POSTPONED)) {
            assertEquals(ChlPp.None, derive(rows, now(1, "10:00", phase)), "$phase")
        }
        assertEquals(home(60), derive(rows, now(1, "10:00", GamePhase.INTERMISSION)))
    }

    @Test fun aRegularSeasonOvertimeTwoPenaltiesIsFiveOnThree() {
        val rows = listOf(a2("1:00", period = 4), a2("1:20", period = 4))
        assertEquals(home(80, PowerPlayKind.TWO_MAN), derive(rows, now(4, "3:20")))
        val oneEach = listOf(a2("1:00", period = 4), pen(true, "1:00", period = 4))
        assertEquals(ChlPp.None, derive(oneEach, now(4, "3:20")))
    }

    @Test fun aPlayoffOvertimeUsesItsLength() =
        assertEquals(home(60), derive(listOf(a2("19:00", period = 4)), now(4, "0:00", GamePhase.INTERMISSION), length = { 1200 }))
}
