package app.thebox.core.sports.hub

import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (CHL hub commit 0, docs/design/m6-chl-hub.md §4.2, §5) The frozen contracts: the CHL cadence's numbers, the kill
 * switch off, the appended members' defaults, and that the fixture fakes load and agree with the worked examples' key
 * values. `ChlDetailMapperTest` (P1) owns the mapper's behaviour.
 */
class ChlHubContractsTest {
    @Test fun theKillSwitchesAreOffInCommit0() {
        assertFalse(SportsConstants.CHL_HUB_ENABLED)
        assertFalse(SportsConstants.CHL_HUB_LIVE_ENABLED)
        assertEquals(setOf(League.QMJHL), SportsConstants.CHL_HITS_LEAGUES)
    }

    @Test fun theChlCadence() {
        val c = DetailCadence.CHL
        assertEquals(1_800_000L, c.preMs)
        assertEquals(30_000L, c.liveMs)
        assertFalse(c.liveAligned)
        assertEquals(3_600_000L, c.finalWaitMs)
        assertNull(c.playsWaitMs)
        assertEquals(9_000_000L, c.gameLengthMs)
        assertEquals(15_000L, c.phaseChangeFloorMs)
        val none = FinalFacts(recapPosted = false, playClips = false, endedAgoMs = 10 * 60_000L)
        assertEquals(SportsConstants.HUB_DETAIL_IDLE_MS, DetailPollPolicy.intervalMs(c, GamePhase.FINAL, none))
        assertNull(DetailPollPolicy.intervalMs(c, GamePhase.FINAL, none.copy(recapPosted = true))) // the stars are in
        assertNull(DetailPollPolicy.intervalMs(c, GamePhase.FINAL, none.copy(endedAgoMs = 3_600_000L))) // ≥ 1 h
        assertNull(DetailPollPolicy.intervalMs(c, GamePhase.POSTPONED, none))
        val ok = Fetch.Ok(Unit, 1_000L, null, 0)
        val gate = DetailPollPolicy.DetailGate(sportsOn = true, awake = true, foreground = true, online = true, paused = false)
        // A board that went FINAL re-reads a pre-game detail at once, no earlier than the 15 s floor.
        assertEquals(16_000L, DetailPollPolicy.nextAt(c, gate, ok, GamePhase.FINAL, GamePhase.SCHEDULED, none, 0, null, null, false, 0L, 0.0, 2_000L))
    }

    @Test fun theAppendedMembersDefaultToTodaysBehaviour() {
        val d = ChlHubFixtures.preOhl29246().copy(attendance = null, leadersSeason = false)
        assertNull(d.attendance)
        assertFalse(Goalie("A. B", null, null, null, null).starting)
        assertTrue(BoxScore(TeamBox(emptyList(), emptyList()), TeamBox(emptyList(), emptyList())).periods.isEmpty())
        assertTrue(BoxScore(TeamBox(emptyList(), emptyList()), TeamBox(emptyList(), emptyList())).periodShots.isEmpty())
        assertFalse(DetailState.Ready(d, 0L, failing = false, stale = false).partial)
        assertFalse(ChlHubFixtures.finalOhl29239().goals.first().gameWinning)
    }

    @Test fun theFakesCarryTheWorkedExamples() {
        val pre = ChlHubFixtures.preOhl29246()
        assertEquals(Ranked(87.0 / 24, 12), pre.season!!.homeGa) // "3.63 (12th)"
        assertEquals(9, pre.season!!.homePp!!.rank) // LDN and SOO tie at 19/87: 9th, not the feed's 10th
        assertEquals(listOf("B. Cooke", "A. Jaswal"), pre.goalies!!.home.map { it.name })
        assertEquals(listOf(true, false), pre.goalies!!.away.map { it.starting }) // Haffner, Yermolenko
        assertTrue(pre.leadersSeason)
        assertEquals(SeriesFacts(2, 1, 3), ChlHubFixtures.preWhl1023348().series) // CGY leads 2–1
        val fin = ChlHubFixtures.finalOhl29239()
        assertEquals(8, fin.goals.size)
        assertEquals("Dietsch", fin.goals.single { it.gameWinning }.scorerLast)
        assertEquals(listOf("Manza", "Bowes", "Dietsch"), fin.stars.map { it.lastName })
        assertEquals(2801, fin.attendance)
        val box = ChlHubFixtures.boxOhl29239()
        assertEquals(listOf("M. Nikonovich"), box.home.goalies.map { it.name }) // one line, Bach (0 s) left out
        assertEquals("58:21", box.home.goalies.single().toi)
        assertEquals(listOf(15, 17, 6), box.periodShots.map { it.away })
        val ot = ChlHubFixtures.boxOtQmjhl32952()
        assertEquals(4, ot.periods.size)
        assertEquals(3, ot.periodShots.last().away) // Chi's 3 OT shots
    }
}
