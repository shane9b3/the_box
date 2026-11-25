package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-sports-hub.md §7.1, §10.2: the CHL board's shots in `BoardExtras`; odd values never fail a board. */
class HockeyTechBoardExtrasTest {
    private fun json(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun recordedOhlBoardCarriesShots() {
        val b = assertNotNull(HockeyTechMapper.board(League.OHL, "2026-11-13", ChlFixtures.board("ohl"), null, null))
        val g = b.board.games.single { it.id.id == "29187" }
        assertEquals(BoardExtras(null, null, awayShots = 23, homeShots = 24), g.extras)
        assertEquals(23, g.away.sog, "the score bug's shots are unchanged")
        assertTrue(b.board.games.all { it.extras?.awayShots != null && it.extras?.homeShots != null })
        assertTrue(b.board.games.all { it.broadcasts.isEmpty() && it.media == null })
    }

    private fun entry(vararg kv: Pair<String, String?>): String {
        val base = linkedMapOf<String, String?>(
            "game_id" to "1", "date_played" to "2026-11-13", "schedule_time" to "19:00:00", "timezone" to "America/Toronto",
            "status" to "2", "started" to "1", "final" to "0", "period" to "2", "game_clock" to "10:00",
            "visiting_goal_count" to "1", "home_goal_count" to "2", "visiting_team" to "7", "visiting_team_code" to "KIT",
            "home_team" to "8", "home_team_code" to "LON",
        )
        for ((k, v) in kv) base[k] = v
        return base.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + (v?.let { "\"$it\"" } ?: "null") }
    }

    private fun game(vararg kv: Pair<String, String?>): GameSnapshot =
        assertNotNull(HockeyTechMapper.board(League.OHL, "2026-11-13", json("""{"SiteKit":{"Gamesbydate":[${entry(*kv)}]}}"""), null, null))
            .board.games.single()

    @Test fun oddShotsDropOnlyThemselves() {
        assertEquals(BoardExtras(null, null, awayShots = 12, homeShots = null), game("visiting_shots" to "12", "home_shots" to "x").extras)
        assertEquals(BoardExtras(null, null, awayShots = null, homeShots = 9), game("visiting_shots" to "-3", "home_shots" to "9").extras)
        assertNull(game("visiting_shots" to null).extras)
        assertNull(game("visiting_shots" to "999", "home_shots" to "500").extras, "over SOG_MAX")
    }

    @Test fun scheduledGamesHaveNoShots() {
        assertNull(game("status" to "1", "started" to "0", "visiting_goal_count" to "0", "home_goal_count" to "0", "visiting_shots" to "0", "home_shots" to "0").extras)
    }
}
