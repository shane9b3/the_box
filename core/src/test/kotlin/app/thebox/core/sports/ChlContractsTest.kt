package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * (CHL commit 0, docs/design/m6-chl-leagues.md §1.1–§1.5, Appendix A) The league seam, the prefs, the constants and the
 * league-keyed colour lookups. §1.4 (backup and downgrade) is in `:data`'s ChlSportsCompatTest.
 */
class ChlContractsTest {
    // ------------------------------------------------------------------ §1.1 the league seam

    @Test
    fun theLeaguesAreAppendedAndTheDerivedHelpersAgree() {
        assertEquals(listOf("NHL", "NFL", "OHL", "WHL", "QMJHL"), League.entries.map { it.name }, "append-only, persisted by name")
        assertEquals(listOf(League.OHL, League.WHL, League.QMJHL), CHL_LEAGUES)
        for (l in League.entries) {
            assertEquals(l != League.NFL, l.isHockey, "$l isHockey")
            assertEquals(if (l == League.NFL) Sport.FOOTBALL else Sport.HOCKEY, l.sport)
            assertEquals(l in CHL_LEAGUES, l.isChl, "$l isChl")
            assertEquals(if (l == League.NHL) PollTiming.BUCKET else PollTiming.COMPLETION, l.timing, "$l timing")
            assertTrue(l in l.group.members, "$l in its group")
        }
        assertEquals(LeagueGroup.NHL, League.NHL.group)
        assertEquals(LeagueGroup.NFL, League.NFL.group)
        assertEquals(listOf(League.NHL), LeagueGroup.NHL.members)
        assertEquals(listOf(League.NFL), LeagueGroup.NFL.members)
        assertEquals(CHL_LEAGUES, LeagueGroup.CHL.members)
        assertEquals(League.entries.toSet(), LeagueGroup.entries.flatMap { it.members }.toSet(), "every league in one group")
    }

    @Test
    fun theAppendedFieldsDefaultToTheirOldMeaning() {
        val s = SportsFixtures.game()
        assertFalse(s.powerPlayUnknown, "an NHL snapshot's power play is never unknown")
        assertEquals(null, DayBoard("2026-11-13", null, null, emptyList()).league, "a board infers its league by default")
        assertEquals(FailKind.entries.last(), FailKind.REFUSED, "REFUSED is appended")
        assertEquals(
            listOf("TIMEOUT", "IO", "HTTP_429", "HTTP_5XX", "HTTP_OTHER", "BAD_BODY", "OFFLINE", "REFUSED"),
            FailKind.entries.map { it.name },
        )
    }

    // ------------------------------------------------------------------ §1.3 prefs

    @Test
    fun theIntervalIsOneSettingPerGroupAndChlShownFallsBackToOhl() {
        val p = SportsPrefs(nhlIntervalS = 40, nflIntervalS = 10, chlIntervalS = 15)
        assertEquals(40, p.intervalS(League.NHL))
        assertEquals(10, p.intervalS(League.NFL))
        for (l in CHL_LEAGUES) assertEquals(15, p.intervalS(l), "$l shares the CHL setting")
        assertEquals(League.OHL, SportsPrefs().chlShown)
        for (l in CHL_LEAGUES) assertEquals(l, SportsPrefs(chlLeague = l).chlShown)
        assertEquals(League.OHL, SportsPrefs(chlLeague = League.NHL).chlShown, "a non-CHL value reads as OHL")
        assertEquals(League.OHL, SportsPrefs(chlLeague = League.NFL).chlShown)
    }

    // §1.4 (backup, restore and downgrade) is tested in `:data` against the store's own JSON: ChlSportsCompatTest (L8).

    // ------------------------------------------------------------------ constants (Appendix A) and the meter

    @Test
    fun theChlConstantsAndIntervalChoices() {
        assertEquals(listOf(5, 10, 15, 20, 30), SportsConstants.INTERVAL_CHOICES_CHL_S)
        assertEquals(5, SportsConstants.INTERVAL_DEFAULT_CHL_S)
        for (l in CHL_LEAGUES) {
            assertEquals(SportsConstants.INTERVAL_CHOICES_CHL_S, SportsConstants.intervalChoices(l))
            assertEquals(SportsConstants.INTERVAL_DEFAULT_CHL_S, SportsConstants.intervalDefault(l))
        }
        assertEquals(listOf(20, 40, 60), SportsConstants.intervalChoices(League.NHL), "the NHL's are unchanged")
        assertEquals(listOf(5, 10, 15, 20, 30), SportsConstants.intervalChoices(League.NFL), "the NFL's are unchanged")
        assertEquals(20, SportsConstants.intervalDefault(League.NHL))
        assertEquals(5, SportsConstants.intervalDefault(League.NFL))
        assertEquals(2_000, SportsConstants.EST_WIRE_BYTES_CHL)
        assertEquals(131_072L, SportsConstants.CHL_NODE_MAX_BYTES)
        assertEquals(5, SportsConstants.CHL_DAY_ROLLOVER_H)
        assertEquals(1_200, SportsConstants.CHL_REG_PERIOD_S)
        assertEquals(300, SportsConstants.CHL_OT_REG_S)
        assertEquals(1_200, SportsConstants.CHL_OT_PLAYOFF_S)
        assertEquals(5, SportsConstants.CHL_PP_FUTURE_TOLERANCE_S)
        assertEquals(120, SportsConstants.CHL_PP_CONSISTENCY_WINDOW_S)
        assertEquals(30_000L, SportsConstants.PP_UNKNOWN_HOLD_MS)
        assertEquals(10f, SportsConstants.LOGO_CAPSULE_PAD)
        assertEquals("chl-live", PollMeter.CHL_LIVE)
        assertEquals(15, PollMeter.normalize(15, League.OHL))
        assertEquals(0, PollMeter.normalize(45, League.WHL), "a value above the CHL list reads as Auto")
    }

    // ------------------------------------------------------------------ §1.5 league-keyed colours

    @Test
    fun theColourLookupsAreLeagueKeyedAndTheNhlsAreUnchanged() {
        for (code in listOf("OTT", "CGY", "EDM", "SEA", "VAN", "CHI", "TOR", null)) {
            assertEquals(NhlTeamColours.of(code), TeamColourTable.of(League.NHL, code))
            assertEquals(NhlStackedColours.pair(code, "TOR"), StackedColourTable.pair(League.NHL, code, "TOR"))
        }
        assertEquals(NflTeamColours.of("SEA"), TeamColourTable.of(League.NFL, "SEA"))
        for (l in CHL_LEAGUES) for (code in listOf("OTT", "CGY", "VIC", "NFL", "LDN", null)) {
            assertEquals(ChlTeamColours.of(l, code), TeamColourTable.of(l, code), "$l $code goes to the CHL table")
        }
        // (L2) The QMJHL's "NFL" goes to the CHL table, never the NFL's.
        assertEquals(ChlTeamColours.of(League.QMJHL, "NFL"), TeamColourTable.of(League.QMJHL, "NFL"))
    }
}
