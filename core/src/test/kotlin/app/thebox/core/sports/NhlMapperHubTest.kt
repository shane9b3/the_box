package app.thebox.core.sports

import app.thebox.core.sports.hub.HubFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-sports-hub.md §4.1, §10.2: the NHL board's broadcasts, video facts and game counts. */
class NhlMapperHubTest {
    private val base = "https://assets.nhle.com/logos/nhl/svg/"

    private fun board(path: String): DayBoard =
        assertNotNull(NhlMapper.day(Json.parseToJsonElement(SportsFixtures.text(path)) as JsonObject, base))

    private fun body(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun finalsCarryRecapCondensedAndGoalClips() {
        val b = board(HubFixtures.SCORE_2026_11_23)
        val g = b.games.single { it.id.id == "2026020382" }
        assertEquals(GameMedia(recapId = 6408795671112L, condensedId = 6408795191112L, goalClips = 6), g.media)
        assertEquals(
            listOf(
                TvBroadcast("SN+", Market.NATIONAL, "CA"),
                TvBroadcast("Mammoth+", Market.AWAY, "US"),
                TvBroadcast("Utah16", Market.AWAY, "US"),
            ),
            g.broadcasts,
        )
        val cbj = b.games.single { it.id.id == "2026020378" }
        assertEquals(6408793654112L, cbj.media?.recapId)
        assertEquals(5, cbj.media?.goalClips)
        assertEquals(Market.HOME, cbj.broadcasts.single { it.network == "MSG" }.market)
        assertTrue(b.games.all { it.media != null }, "every final of the day has video")
    }

    @Test fun gameWeekBecomesGameCounts() {
        val b = board(HubFixtures.SCORE_2026_11_23)
        assertEquals(
            mapOf(
                "2026-11-20" to 0, "2026-11-21" to 14, "2026-11-22" to 5, "2026-11-23" to 7, "2026-11-24" to 1,
                "2026-11-25" to 14, "2026-11-26" to 0,
            ),
            b.gameCounts,
        )
    }

    @Test fun futureGameHasBroadcastsAndNoMedia() {
        val b = board(HubFixtures.SCORE_NOW_2026_11_24)
        val g = b.games.single { it.id.id == "2026020385" }
        assertNull(g.media)
        assertEquals(
            listOf(
                TvBroadcast("TNT", Market.NATIONAL, "US"),
                TvBroadcast("HBO MAX", Market.NATIONAL, "US"),
                TvBroadcast("SN", Market.NATIONAL, "CA"),
                TvBroadcast("TVAS", Market.NATIONAL, "CA"),
            ),
            g.broadcasts,
        )
    }

    @Test fun broadcastsAreTrimmedUnknownMarketsDroppedAndOddEntriesSkipped() {
        val list = NhlMapper.broadcasts(
            body(
                """{"x":[
                {"market":"N","countryCode":"ca","network":"ABTV "},
                {"market":"X","countryCode":"US","network":"NOPE"},
                {"market":"H","countryCode":"USA","network":"MSG"},
                {"market":"A","network":"  "},
                {"market":"A","countryCode":"US"},
                {"market":"n","countryCode":"CA","network":"ABTV"},
                "junk", 7, null,
                {"market":3,"network":"NUM"}
                ]}""",
            )["x"],
        )
        assertEquals(listOf(TvBroadcast("ABTV", Market.NATIONAL, "CA"), TvBroadcast("MSG", Market.HOME, null)), list)
        assertEquals(emptyList(), NhlMapper.broadcasts(body("""{"x":{"market":"N"}}""")["x"]))
        assertEquals(emptyList(), NhlMapper.broadcasts(null))
    }

    @Test fun videoIdsFromPathsAndNumbers() {
        val o = body(
            """{"a":"/video/cbj-at-njd-recap-6408793654112","b":6408787701112,"c":"/video/no-id-here","d":"https://nhl.com/video/x-6408793654112?utm=1",
               "e":-5,"f":"","g":{"x":1},"h":null}""",
        )
        assertEquals(6408793654112L, NhlMapper.videoId(o["a"]))
        assertEquals(6408787701112L, NhlMapper.videoId(o["b"]))
        assertNull(NhlMapper.videoId(o["c"]))
        assertEquals(6408793654112L, NhlMapper.videoId(o["d"]))
        assertNull(NhlMapper.videoId(o["e"]))
        assertNull(NhlMapper.videoId(o["f"]))
        assertNull(NhlMapper.videoId(o["g"]))
        assertNull(NhlMapper.videoId(o["h"]))
    }

    @Test fun oddBoardFieldsNeverFailTheBoard() {
        val b = assertNotNull(
            NhlMapper.day(
                body(
                    """{"currentDate":"2026-11-23","gameWeek":[{"date":"bad","numberOfGames":3},{"date":"2026-11-24","numberOfGames":-1},
                    {"date":"2026-11-25","numberOfGames":"4"},"x"],
                    "games":[{"id":1,"startTimeUTC":"2026-11-24T00:30:00Z","gameState":"OFF","awayTeam":{"abbrev":"UTA"},"homeTeam":{"abbrev":"WPG"},
                    "tvBroadcasts":"oops","threeMinRecap":{"a":1},"goals":[{"highlightClip":"x"},7]}]}""",
                ),
                base,
            ),
        )
        assertEquals(mapOf("2026-11-25" to 4), b.gameCounts)
        val g = b.games.single()
        assertEquals(emptyList(), g.broadcasts)
        assertNull(g.media)
        assertNull(NhlMapper.day(body("""{"currentDate":"2026-11-23"}"""), base)?.gameCounts?.takeIf { it.isNotEmpty() })
    }

    @Test fun theFollowLandingCarriesNoBoardFacts() {
        val landing = assertNotNull(NhlMapper.landing(body(SportsFixtures.text(HubFixtures.GC_382_LANDING)), base))
        assertEquals(emptyList(), landing.broadcasts)
        assertNull(landing.media)
        assertNull(landing.extras)
    }

    @Test fun recordedLiveBoards() {
        val live = HubFixtures.LIVE.filter { it.contains("score-now-385") }
        if (live.isEmpty()) return
        for (path in live) {
            val g = board(path).games.single { it.id.id == "2026020385" }
            assertTrue(g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION, path)
            assertEquals(listOf("TNT", "HBO MAX", "SN", "TVAS"), g.broadcasts.map { it.network }, path)
            assertNull(g.media?.recapId, path)
        }
        val p1 = board(live.single { it.endsWith("-p1.json") }).games.single { it.id.id == "2026020385" }
        assertEquals(GameMedia(null, null, goalClips = 1), p1.media, "a goal's clip id is posted minutes after the goal")
        assertEquals(GamePhase.INTERMISSION, board(live.single { it.endsWith("-int.json") }).games.single { it.id.id == "2026020385" }.phase)
    }
}
