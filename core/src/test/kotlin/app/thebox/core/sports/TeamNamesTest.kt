package app.thebox.core.sports

import app.thebox.core.events.EventMatchup
import app.thebox.core.sports.TeamNames.Strength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-event-channels.md §4.2, §11.1 (`TeamNamesTest`). */
class TeamNamesTest {
    private fun tok(s: String) = TeamNames.tokens(s)
    private fun nhl(a: String) = TeamNames.of(League.NHL, a)!!
    private fun nfl(a: String) = TeamNames.of(League.NFL, a)!!

    @Test
    fun thirtyTwoTeamsEachKeyedByTheBoardsAbbreviations() {
        assertEquals(NhlTeamPlaces.TABLE.keys, TeamNames.all(League.NHL).map { it.abbrev }.toSet())
        assertEquals(NhlTeamColours.TABLE.keys, TeamNames.all(League.NHL).map { it.abbrev }.toSet())
        assertEquals(NflTeamColours.TABLE.keys, TeamNames.all(League.NFL).map { it.abbrev }.toSet())
        assertEquals(32, TeamNames.all(League.NHL).size)
        assertEquals(32, TeamNames.all(League.NFL).size)
        for (l in listOf(League.OHL, League.WHL, League.QMJHL)) assertTrue(TeamNames.all(l).isEmpty())
        assertNull(TeamNames.of(League.OHL, "EDM"))
        assertNull(TeamNames.of(League.NHL, "XYZ"))
        assertEquals("WSH", TeamNames.of(League.NHL, " wsh ")!!.abbrev)
        assertEquals(listOf("Washington Commanders", "Washington Football Team", "Washington Redskins"), nfl("WSH").fullNames)
    }

    @Test
    fun nhlPlacesAgreeWithTheHubsPlaceTable() {
        for (t in TeamNames.all(League.NHL)) {
            val place = NhlTeamPlaces.of(t.abbrev)!!
            assertEquals(Strength.PLACE, TeamNames.sideMatch(tok(place), t), t.abbrev)
            assertEquals(t.league, League.NHL)
        }
    }

    @Test
    fun everyTeamMatchesItsOwnNamesAndNoOtherTeamsFullName() {
        for (league in listOf(League.NHL, League.NFL)) {
            val all = TeamNames.all(league)
            for (t in all) {
                for (f in t.fullNames) {
                    assertEquals(Strength.FULL, TeamNames.sideMatch(tok(f), t), f)
                    for (o in all) if (o !== t) assertNull(TeamNames.sideMatch(tok(f), o), "$f vs ${o.abbrev}")
                }
                for (n in t.nicknames) {
                    assertEquals(Strength.NICKNAME, TeamNames.sideMatch(tok(n), t), n)
                    for (o in all) if (o !== t) assertFalse(TeamNames.sideMatch(tok(n), o) == Strength.NICKNAME, "$n vs ${o.abbrev}")
                }
                assertTrue(t.tvgStems.isNotEmpty())
            }
        }
    }

    @Test
    fun tokensFold() {
        assertEquals(listOf("st", "louis"), tok("St. Louis"))
        assertEquals(listOf("st", "louis"), tok("Saint Louis"))
        assertEquals(listOf("st", "louis"), tok("ST LOUIS"))
        assertEquals(listOf("st", "louis", "blues"), tok("St.Louis Blues"))
        assertEquals(listOf("la", "kings"), tok("L.A. Kings"))
        assertEquals(listOf("montreal", "canadiens"), tok("Montréal Canadiens"))
        assertEquals(listOf("womens", "hockey"), tok("Women`s Hockey"))
        assertEquals(listOf("womens"), tok("Women’s"))
        assertEquals(listOf("texas", "a", "and", "m"), tok("Texas A&M"))
        assertEquals(listOf("nhl", "tv"), tok("NHL.TV"))
        assertEquals(listOf("san", "jose"), tok("SAN JOSÉ"))
        assertEquals(listOf("saints"), tok("Saints"))
        assertEquals(emptyList(), tok(""))
        assertEquals(emptyList(), tok("•|@()"))
    }

    @Test
    fun sideMatchIsEquality() {
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Edmonton Oilers"), nhl("EDM")))
        assertEquals(Strength.NICKNAME, TeamNames.sideMatch(tok("Oilers"), nhl("EDM")))
        assertEquals(Strength.PLACE, TeamNames.sideMatch(tok("Edmonton"), nhl("EDM")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Montreal Canadiens"), nhl("MTL")))
        assertEquals(Strength.PLACE, TeamNames.sideMatch(tok("Montreal"), nhl("MTL")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("LA Kings"), nhl("LAK")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Los Angeles Kings"), nhl("LAK")))
        assertEquals(Strength.PLACE, TeamNames.sideMatch(tok("L.A."), nhl("LAK")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Saint Louis Blues"), nhl("STL")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Utah Mammoth"), nhl("UTA")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Utah Hockey Club"), nhl("UTA")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Arizona Coyotes"), nhl("UTA")))
        assertEquals(Strength.PLACE, TeamNames.sideMatch(tok("Utah"), nhl("UTA")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Vegas Golden Knights"), nhl("VGK")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Las Vegas Golden Knights"), nhl("VGK")))
        assertEquals(Strength.NICKNAME, TeamNames.sideMatch(tok("Golden Knights"), nhl("VGK")))
        assertEquals(Strength.PLACE, TeamNames.sideMatch(tok("Las Vegas"), nfl("LV")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("Oakland Raiders"), nfl("LV")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("San Francisco 49ers"), nfl("SF")))
        assertEquals(Strength.NICKNAME, TeamNames.sideMatch(tok("49ers"), nfl("SF")))
        assertEquals(Strength.FULL, TeamNames.sideMatch(tok("NY Giants"), nfl("NYG")))
    }

    @Test
    fun containmentIsNeverAMatch() {
        assertNull(TeamNames.sideMatch(tok("Southern Utah"), nhl("UTA")))
        assertNull(TeamNames.sideMatch(tok("Utah State"), nhl("UTA")))
        assertNull(TeamNames.sideMatch(tok("Boston College"), nhl("BOS")))
        assertNull(TeamNames.sideMatch(tok("Colorado College"), nhl("COL")))
        assertNull(TeamNames.sideMatch(tok("Washington State"), nhl("WSH")))
        assertNull(TeamNames.sideMatch(tok("Washington State"), nfl("WSH")))
        assertNull(TeamNames.sideMatch(tok("Sacramento Kings"), nhl("LAK")))
        assertNull(TeamNames.sideMatch(tok("Basketball NBA Dallas Mavericks"), nhl("DAL")))
        assertNull(TeamNames.sideMatch(tok("Dallas Mavericks"), nfl("DAL")))
        assertNull(TeamNames.sideMatch(tok("Boston Celtics"), nhl("BOS")))
        assertNull(TeamNames.sideMatch(tok("Edmonton Oilers TV"), nhl("EDM")))
        assertNull(TeamNames.sideMatch(emptyList(), nhl("EDM")))
        // Raw tokens carry the week; EventMatchup's cleaning removes it.
        assertNull(TeamNames.sideMatch(tok("2026 Week 11 Cincinnati Bengals"), nfl("CIN")))
        val side = assertNotNull(EventMatchup.of("2026 Week 11 Cincinnati Bengals vs. Washington Commanders")).first
        assertEquals(Strength.FULL, TeamNames.sideMatch(side, nfl("CIN")))
    }

    @Test
    fun sharedPlaces() {
        assertTrue(TeamNames.placeShared(League.NHL, "New York"))
        assertTrue(TeamNames.placeShared(League.NHL, "NY"))
        assertTrue(TeamNames.placeShared(League.NFL, "New York"))
        assertTrue(TeamNames.placeShared(League.NFL, "Los Angeles"))
        assertTrue(TeamNames.placeShared(League.NFL, "L.A."))
        assertFalse(TeamNames.placeShared(League.NHL, "Los Angeles"))
        assertFalse(TeamNames.placeShared(League.NHL, "Edmonton"))
        assertFalse(TeamNames.placeShared(League.NHL, ""))
        assertFalse(TeamNames.placeShared(League.OHL, "New York"))
    }

    @Test
    fun crossLeagueNicknames() {
        for (n in listOf("Jets", "Panthers", "Kings", "Rangers", "Senators", "Hurricanes")) {
            assertTrue(TeamNames.crossLeagueNickname(League.NHL, tok(n)), n)
        }
        for (n in listOf("Jets", "Panthers", "Giants", "Cardinals")) assertTrue(TeamNames.crossLeagueNickname(League.NFL, tok(n)), n)
        for (n in listOf("Oilers", "Golden Knights", "Maple Leafs", "Canadiens")) {
            assertFalse(TeamNames.crossLeagueNickname(League.NHL, tok(n)), n)
        }
        assertFalse(TeamNames.crossLeagueNickname(League.NFL, tok("Commanders")))
    }

    @Test
    fun tvgStems() {
        assertTrue("StLouisBlues" in nhl("STL").tvgStems)
        assertTrue("MontrealCanadiens" in nhl("MTL").tvgStems)
        assertTrue("UtahHockeyClub" in nhl("UTA").tvgStems)
        assertTrue("UtahMammoth" in nhl("UTA").tvgStems)
        assertTrue("WashingtonCommanders" in nfl("WSH").tvgStems)
        assertTrue("SanFrancisco49ers" in nfl("SF").tvgStems)
        assertTrue("LasVegasRaiders" in nfl("LV").tvgStems)
    }
}
