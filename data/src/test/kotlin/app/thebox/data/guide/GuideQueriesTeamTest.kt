package app.thebox.data.guide

import app.thebox.core.parental.ParentalAccess
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-event-channels.md §8.2, §11.1 `GuideQueriesTeamTest`: stage T's candidate query. The group word or the
 * tvg-id suffix, any prefix and case; the lineup's hidden, missing and disabled-playlist rules; the parental rule for the
 * caller's access; the limit; the hit's columns. `TeamChannelRule.teamOf` (WP-1) narrows the hits; it is not run here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideQueriesTeamTest {
    private lateinit var db: LibraryDatabase
    private lateinit var gg: GuideGraph

    @Before fun setUp() { db = TestDb.create(); gg = GuideGraph(TestGraph(db)) }
    @After fun tearDown() { db.close() }

    private suspend fun names(word: String, access: ParentalAccess = ParentalAccess.OPEN, limit: Int = 400) =
        gg.queries.teamChannelCandidates(word, access, limit).map { it.name }

    @Test
    fun matchesTheTeamsGroupOrTheTvgIdSuffix() = runBlocking {
        val p = db.newPlaylist()
        val teams = db.newGroup(p, "g:t", "Sports | NHL Teams")
        val caps = db.newGroup(p, "g:c", "CA | NHL TEAMS")
        val two = db.newGroup(p, "g:2", "NHL Teams (2)")
        val nhl = db.newGroup(p, "g:n", "Sports | NHL")
        val nba = db.newGroup(p, "g:b", "Sports | NBA Teams")
        val nfl = db.newGroup(p, "g:f", "Sports | NFL Teams")
        db.newChannel(p, "a", "Edmonton Oilers", groupId = teams, sortIndex = 0)
        db.newChannel(p, "b", "CA| EDMONTON OILERS HD", groupId = caps, sortIndex = 1)
        db.newChannel(p, "c", "Montreal Canadiens", groupId = two, sortIndex = 2)
        db.newChannel(p, "d", "Utah Hockey Club", groupId = nhl, tvgId = "UtahHockeyClub.NHL", sortIndex = 3)
        db.newChannel(p, "e", "NHL 01: Kings @ Ducks", groupId = nhl, sortIndex = 4)
        db.newChannel(p, "f", "Detroit Pistons", groupId = nba, sortIndex = 5)
        db.newChannel(p, "g", "Washington Commanders", groupId = nfl, tvgId = "WashingtonCommanders.nfl", sortIndex = 6)
        db.newChannel(p, "h", "Sportsnet Oilers", tvgId = "sportsnet.oilers.ca", sortIndex = 7)
        assertEquals(listOf("Edmonton Oilers", "CA| EDMONTON OILERS HD", "Montreal Canadiens", "Utah Hockey Club"), names("nhl"))
        assertEquals(listOf("Washington Commanders"), names("NFL"))
        assertEquals(emptyList<String>(), names(" "))
        assertEquals(listOf("Edmonton Oilers", "CA| EDMONTON OILERS HD"), names("nhl", limit = 2))
        assertEquals(emptyList<String>(), names("nhl", limit = 0))
        val hit = gg.queries.teamChannelCandidates("nhl", ParentalAccess.OPEN, 400).single { it.name == "Utah Hockey Club" }
        assertEquals("Sports | NHL", hit.group)
        assertEquals("UtahHockeyClub.NHL", hit.tvgId)
        assertEquals(p, hit.playlistId)
        assertEquals("Utah Hockey Club", hit.displayName)
    }

    @Test
    fun appliesTheLineupAndParentalRules() = runBlocking {
        val p = db.newPlaylist()
        val off = db.newPlaylist("off")
        db.exec("UPDATE playlist SET enabled = 0 WHERE id = ?", off)
        val teams = db.newGroup(p, "g:t", "Sports | NHL Teams")
        val adult = db.newGroup(p, "g:a", "NHL Teams adult", adult = true)
        val offTeams = db.newGroup(off, "g:t", "Sports | NHL Teams")
        db.newChannel(p, "shown", "Edmonton Oilers", groupId = teams, sortIndex = 0)
        db.newChannel(p, "renamed", "Calgary Flames", groupId = teams, sortIndex = 1)
        db.newChannel(p, "userHidden", "Vancouver Canucks", groupId = teams, sortIndex = 2)
        db.newChannel(p, "ruleHidden", "Seattle Kraken", groupId = teams, sortIndex = 3)
        db.newChannel(p, "missing", "Vegas Golden Knights", groupId = teams, sortIndex = 4, missingSince = 5L)
        db.newChannel(p, "locked", "Toronto Maple Leafs", groupId = teams, sortIndex = 5)
        db.newChannel(p, "adult", "Ottawa Senators", groupId = adult, sortIndex = 6)
        db.newChannel(off, "x", "Boston Bruins", groupId = offTeams)
        db.channelPrefsDao().upsertHidden(p, "userHidden", true, 1)
        db.channelPrefsDao().upsertLocked(p, "locked", true, 1)
        db.channelPrefsDao().upsertCustomName(p, "renamed", "Flames!", 1)
        db.exec("UPDATE channel SET rule_hidden = 1 WHERE stable_key = 'ruleHidden'")
        assertEquals(listOf("Edmonton Oilers", "Calgary Flames", "Toronto Maple Leafs", "Ottawa Senators"), names("nhl", ParentalAccess.OPEN))
        assertEquals(listOf("Edmonton Oilers", "Calgary Flames"), names("nhl", ParentalAccess.LOCKED_AUTO))
        // `name` is the provider name (what teamOf reads), `displayName` what the user sees.
        val renamed = gg.queries.teamChannelCandidates("nhl", ParentalAccess.OPEN, 400).single { it.name == "Calgary Flames" }
        assertEquals("Flames!", renamed.displayName)
        // A hidden group hides its channels too.
        db.groupDao().upsertGroupHidden(p, 0, "g:t", true, 1)
        assertEquals(listOf("Ottawa Senators"), names("nhl", ParentalAccess.OPEN))
    }
}
