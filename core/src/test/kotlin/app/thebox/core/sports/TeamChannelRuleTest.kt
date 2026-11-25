package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-event-channels.md §8.2, §11.1 (`TeamChannelRuleTest`). */
class TeamChannelRuleTest {
    private val nhlTeams = "Sports | NHL Teams"
    private val nflTeams = "Sports | NFL Teams"

    /** The owner's 64 team channels follow this layout: "<full name>", "<Stem>.<league>", "Sports | <LEAGUE> Teams". */
    private fun ownerChannels(league: League): List<Triple<String, String, String>> = TeamNames.all(league).map { t ->
        val name = when (t.abbrev) {
            "MTL" -> "Montreal Canadiens"
            else -> t.fullNames.first()
        }
        val stem = if (t.abbrev == "UTA") "UtahHockeyClub" else t.tvgStems.first()
        Triple(t.abbrev, name, "$stem.${league.name.lowercase()}")
    }

    @Test
    fun allSixtyFourOwnerTeamChannelsByGroupAndByTvgId() {
        for ((league, group) in listOf(League.NHL to nhlTeams, League.NFL to nflTeams)) {
            val channels = ownerChannels(league)
            assertEquals(32, channels.size)
            for ((abbrev, name, tvg) in channels) {
                assertEquals(abbrev, TeamChannelRule.teamOf(league, name, group, tvg)?.abbrev, name)
                assertEquals(abbrev, TeamChannelRule.teamOf(league, name, group, null)?.abbrev, "$name by group")
                assertEquals(abbrev, TeamChannelRule.teamOf(league, name, "Sports | ${league.name}", tvg)?.abbrev, "$name by tvg id")
                assertNull(TeamChannelRule.teamOf(league, name, "Sports | ${league.name}", null), "$name without context")
            }
        }
    }

    @Test
    fun namedExamples() {
        assertEquals("STL", TeamChannelRule.teamOf(League.NHL, "St. Louis Blues", nhlTeams, "StLouisBlues.nhl")?.abbrev)
        assertEquals("UTA", TeamChannelRule.teamOf(League.NHL, "Utah Mammoth", "Sports | NHL", "UtahHockeyClub.nhl")?.abbrev)
        assertEquals("UTA", TeamChannelRule.teamOf(League.NHL, "Utah Hockey Club", nhlTeams, null)?.abbrev)
        assertEquals("MTL", TeamChannelRule.teamOf(League.NHL, "Montreal Canadiens", "Sports | NHL", "MontrealCanadiens.nhl")?.abbrev)
        assertEquals("WSH", TeamChannelRule.teamOf(League.NFL, "Washington Commanders", "Sports | NFL", "WashingtonCommanders.nfl")?.abbrev)
        assertEquals("EDM", TeamChannelRule.teamOf(League.NHL, "CA| EDMONTON OILERS HD", "CA | NHL TEAMS", null)?.abbrev)
        assertEquals("EDM", TeamChannelRule.teamOf(League.NHL, "NHL: Oilers", "NHL Teams (2)", null)?.abbrev)
        assertEquals("EDM", TeamChannelRule.teamOf(League.NHL, "Edmonton Oilers", "Sports | NHL", "edmontonoilers.NHL@SD")?.abbrev)
        assertEquals("LAK", TeamChannelRule.teamOf(League.NHL, "LA Kings", nhlTeams, null)?.abbrev)
    }

    @Test
    fun negatives() {
        assertNull(TeamChannelRule.teamOf(League.NHL, "Sportsnet Oilers", "CA | Sports", null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Sportsnet Oilers", nhlTeams, null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Radio: Detroit Pistons", nhlTeams, null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Radio: Edmonton Oilers", nhlTeams, null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Detroit Pistons", "Sports | NBA Teams", null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Sacramento Kings", "Sports | NBA Teams", null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Kings", "Sports | NBA Teams", null))
        assertNull(TeamChannelRule.teamOf(League.NFL, "St. Louis Cardinals", "Sports | MLB Teams", null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Edmonton Oilers", "CA | Sports", null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Boston Bruins", "Sports | NHL Teams Radio", null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Oilers TV", nhlTeams, null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "Edmonton", nhlTeams, null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "New York", nhlTeams, null))
        assertNull(TeamChannelRule.teamOf(League.NFL, "New York", nflTeams, null))
        // The tvg id names another team.
        assertNull(TeamChannelRule.teamOf(League.NHL, "Edmonton Oilers", "Sports | NHL", "CalgaryFlames.nhl"))
        // The tvg id's league is the other one.
        assertNull(TeamChannelRule.teamOf(League.NHL, "Edmonton Oilers", "Sports | NHL", "EdmontonOilers.nfl"))
        // An NFL club in an NHL group, and the reverse.
        assertNull(TeamChannelRule.teamOf(League.NHL, "Washington Commanders", nhlTeams, null))
        assertNull(TeamChannelRule.teamOf(League.NFL, "Edmonton Oilers", nflTeams, null))
        assertNull(TeamChannelRule.teamOf(League.OHL, "London Knights", "Sports | OHL Teams", null))
        assertNull(TeamChannelRule.teamOf(League.NHL, "", nhlTeams, null))
    }

    @Test
    fun groupIsTeams() {
        for (g in listOf("Sports | NHL Teams", "CA | NHL TEAMS", "NHL Teams (2)", "Sports | NHL Team", "nhl teams")) {
            assertTrue(TeamChannelRule.groupIsTeams(League.NHL, g), g)
        }
        assertTrue(TeamChannelRule.groupIsTeams(League.NFL, "Sports | NFL Teams"))
        for (g in listOf(
            "Sports | NHL Teams Radio", "NHL Teams Replays", "NHL Teams Highlights", "Classic NHL Teams", "Sports | NBA Teams",
            "Sports | NHL", "Sports | NFL Teams", "Teams NHL", null, "",
        )) assertFalse(TeamChannelRule.groupIsTeams(League.NHL, g), "$g")
        assertFalse(TeamChannelRule.groupIsTeams(League.OHL, "Sports | OHL Teams"))
    }
}
