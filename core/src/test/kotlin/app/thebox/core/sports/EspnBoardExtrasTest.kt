package app.thebox.core.sports

import app.thebox.core.sports.hub.PeriodScore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-sports-hub.md §7.1, §7.2, §10.2: records, quarters, leaders and broadcasts from recorded ESPN bodies
 * (the week-11 board of 2026-11-24, all final; the per-event bodies of 2026-11-08 / -16: live, final, pre), decoded through
 * the DTO as the source does. Odd or absent fields never fail a board.
 */
class EspnBoardExtrasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val logoBase = "https://a.espncdn.com/"

    private fun board(text: String): DayBoard =
        assertNotNull(EspnMapper.board(json.decodeFromString(EspnBoardDto.serializer(), text), null, logoBase))

    /** A recorded per-event body (`scoreboard/{id}`) as a one-event board. */
    private fun eventBoard(name: String): GameSnapshot =
        board("""{"events":[${SportsFixtures.text("sports/nfl/$name")}]}""").games.single()

    private fun reg(n: Int, a: Int, h: Int) = PeriodScore(PeriodLabel(PeriodKind.REG, n), a, h)

    @Test fun recordedFinalWeekBoard() {
        val b = board(SportsFixtures.text("sports/nfl/scoreboard-week11-final-2026-11-24.json"))
        assertEquals(3, b.games.size)
        val pitPhi = b.games.single { it.id.id == "401873076" }
        val x = assertNotNull(pitPhi.extras)
        assertEquals("6-4", x.awayRecord)
        assertEquals("3-7", x.homeRecord)
        assertEquals(listOf(reg(1, 0, 13), reg(2, 3, 0), reg(3, 6, 3), reg(4, 7, 3)), x.periods)
        assertEquals(
            listOf(
                BoardLeader("passing", Side.AWAY, "A. Rodgers", "30/44, 227 YDS, 1 TD"),
                BoardLeader("rushing", Side.HOME, "S. Barkley", "17 CAR, 75 YDS, 1 TD"),
                BoardLeader("receiving", Side.AWAY, "M. Hurleman", "6 REC, 61 YDS, 1 TD"),
            ),
            x.leaders,
        )
        assertEquals(listOf(TvBroadcast("CBS", Market.NATIONAL, "US")), pitPhi.broadcasts)
        assertEquals(listOf(TvBroadcast("Prime Video", Market.NATIONAL, "US")), b.games.single { it.id.id == "401873066" }.broadcasts)
        assertEquals("4-5-1", b.games.single { it.id.id == "401873078" }.extras?.homeRecord, "a tie in the record")
        assertTrue(b.games.all { it.extras?.awayShots == null && it.extras?.homeShots == null })
    }

    @Test fun recordedLiveEventHasThePeriodsPlayedSoFar() {
        val g = eventBoard("sbEvent.json")
        assertEquals(GamePhase.LIVE, g.phase)
        val x = assertNotNull(g.extras)
        assertEquals("5-3", x.awayRecord)
        assertEquals(listOf(reg(1, 14, 0), reg(2, 10, 14), reg(3, 10, 0)), x.periods)
        assertEquals(BoardLeader("passing", Side.AWAY, "J. Love", "21/27, 323 YDS, 2 TD"), x.leaders[0])
        assertEquals(Side.HOME, x.leaders[1].side)
        assertEquals(listOf(TvBroadcast("Fox", Market.NATIONAL, "US")), g.broadcasts)
    }

    @Test fun recordedFinalEvent() {
        val x = assertNotNull(eventBoard("ev_401873045.json").extras)
        assertEquals("2-7" to "6-2", x.awayRecord to x.homeRecord)
        assertEquals(listOf(reg(1, 6, 0), reg(2, 6, 14), reg(3, 0, 14), reg(4, 0, 7)), x.periods)
        assertEquals(listOf("P. Mahomes", "K. Walker III", "T. Kelce"), x.leaders.map { it.name })
        assertTrue(x.leaders.all { it.side == Side.HOME })
    }

    @Test fun preGameHasRecordsButNoSeasonLeadersOrPeriods() {
        val g = eventBoard("ev_401873050.json")
        val x = assertNotNull(g.extras)
        assertEquals("3-5" to "6-2", x.awayRecord to x.homeRecord)
        assertEquals(emptyList(), x.periods)
        assertEquals(emptyList(), x.leaders, "pre-game leaders are season leaders")
        assertEquals(listOf(TvBroadcast("NBC", Market.NATIONAL, "US")), g.broadcasts)
    }

    @Test fun theFollowSnapshotCarriesNoBoardFacts() {
        val e = SportsFixtures.text("sports/nfl/sbEvent.json")
        val g = assertNotNull(
            EspnMapper.game(json.decodeFromString(EspnGameDto.serializer(), """{"events":[$e]}"""), GameId(League.NFL, "401873049"), null, logoBase),
        )
        assertNull(g.extras)
        assertEquals(emptyList(), g.broadcasts)
    }

    @Test fun oddFieldsNeverFailTheBoard() {
        fun competitor(side: String, id: String, abbr: String, extra: String) =
            """{"id":"$id","homeAway":"$side","score":"7","team":{"id":"$id","abbreviation":"$abbr"}$extra}"""
        val event = """{"id":"401899001","date":"2026-11-08T18:00Z","competitions":[{
            "competitors":[
              ${competitor("away", "12", "KC", ""","records":"x","linescores":[{"value":"7","period":1},{"value":3.5,"period":2},{"period":3},"junk",{"value":-1,"period":4},{"value":0,"period":5}]""")},
              ${competitor("home", "2", "BUF", ""","records":[{"type":"home","summary":"x-y"}],"linescores":[{"value":0,"period":1},{"value":3,"period":2},{"value":7,"period":5}]""")}
            ],
            "status":{"period":5,"clock":300.0,"type":{"id":"2","name":"STATUS_IN_PROGRESS","state":"in"}},
            "leaders":[{"name":"passingYards","leaders":[{"displayValue":"1/1, 9 YDS","athlete":{"displayName":"Pat Mahomes"},"team":{"id":"12"}},
                        {"displayValue":"x","athlete":{"shortName":"No Team"}},{"athlete":{"shortName":"No Line"},"team":{"id":"2"}}]},
                       {"name":"sacks","leaders":[{"displayValue":"1","athlete":{"shortName":"S"},"team":{"id":"2"}}]},
                       {"name":"rushingYards","leaders":"x"}, 5],
            "broadcasts":[{"market":"national","names":["CBS"," CBS ",7,""]},{"market":"home","names":["WGRZ"]},{"market":"regional","names":["X"]},{"market":"away"}]
          }]}"""
        val g = board("""{"events":[$event]}""").games.single()
        val x = assertNotNull(g.extras)
        assertNull(x.awayRecord)
        assertNull(x.homeRecord)
        assertEquals(listOf(reg(1, 7, 0), PeriodScore(PeriodLabel(PeriodKind.OT, 1), 0, 7)), x.periods)
        assertEquals(listOf(BoardLeader("passing", Side.AWAY, "Pat Mahomes", "1/1, 9 YDS")), x.leaders)
        assertEquals(listOf(TvBroadcast("CBS", Market.NATIONAL, "US"), TvBroadcast("WGRZ", Market.HOME, "US")), g.broadcasts)

        val bare = board("""{"events":[{"id":"1","date":"2026-11-08T18:00Z","competitions":[{"competitors":[
            {"homeAway":"away","team":{"abbreviation":"KC"}},{"homeAway":"home","team":{"abbreviation":"BUF"}}],
            "status":{"type":{"state":"pre"}},"leaders":{"a":1},"broadcasts":"CBS"}]}]}""").games.single()
        assertNull(bare.extras)
        assertEquals(emptyList(), bare.broadcasts)
    }
}
