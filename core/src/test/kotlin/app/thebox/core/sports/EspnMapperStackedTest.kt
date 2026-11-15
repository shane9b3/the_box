package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-stacked.md §3.2, §9.1 (WP-1): the timeout counts, the `penalty` flag and the kick-off's red zone
 * (RC9), from the 2026-11-08 and 2026-11-15 live fixtures and hand-made bodies.
 */
class EspnMapperStackedTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val logoBase = "https://a.espncdn.com/"
    private val cinAtl = GameId(League.NFL, "401873038")

    private fun gameDto(text: String) = json.decodeFromString(EspnGameDto.serializer(), text)
    private fun nfl(s: GameSnapshot?): LeagueDetail.Nfl = assertIs<LeagueDetail.Nfl>(assertNotNull(s).detail)
    private fun stacked(name: String): LeagueDetail.Nfl = nfl(NflStackedFixtures.snapshot(name))
    private fun nov8(name: String): LeagueDetail.Nfl =
        nfl(EspnMapper.game(gameDto(SportsFixtures.text("sports/nfl/$name-2026-11-08.json")), cinAtl, null, logoBase))

    /** A one-event KC @ BUF body, in play, with [situation] (a JSON object or null). */
    private fun body(situation: String?): String = """
        {"events":[{"id":"401899001","date":"2026-11-08T18:00Z","competitions":[{
          "competitors":[
            {"id":"12","homeAway":"away","score":"7","team":{"id":"12","abbreviation":"KC","displayName":"Kansas City Chiefs"}},
            {"id":"2","homeAway":"home","score":"3","team":{"id":"2","abbreviation":"BUF","displayName":"Buffalo Bills"}}
          ],
          "status":{"clock":600.0,"displayClock":"10:00","period":2,"type":{"id":"2","name":"STATUS_IN_PROGRESS","state":"in"}}
          ${situation?.let { ",\"situation\":$it" } ?: ""}
        }]}]}
    """.trimIndent()

    private fun map(situation: String?): LeagueDetail.Nfl =
        nfl(EspnMapper.game(gameDto(body(situation)), GameId(League.NFL, "401899001"), null, logoBase))

    private fun counts(away: String, home: String) = map(
        """{"down":1,"distance":10,"yardLine":35,"possession":"12","awayTimeouts":$away,"homeTimeouts":$home}""",
    )

    private fun play(typeId: String, text: String) = map(
        """{"down":2,"distance":7,"yardLine":40,"possession":"12",
            "lastPlay":{"id":"401899001100","type":{"id":"$typeId","text":"x"},"text":${Json.encodeToString(JsonElement.serializer(), JsonPrimitive(text))},"team":{"id":"12"}}}""",
    ).lastPlay

    // ------------------------------------------------------------------ counts

    @Test
    fun countsAreThreeAndThreeInEveryNov8LiveFixture() {
        for (name in listOf(
            "scoreboard-live-coin-toss", "scoreboard-live-end-period", "scoreboard-live-fg-dead-ball",
            "scoreboard-live-first-down", "scoreboard-live-kickoff", "scoreboard-live-kickoff-after-fg",
            "scoreboard-live-no-possession-after-kickoff", "scoreboard-live-plus7-away-kickoff",
            "scoreboard-live-td-score-first", "scoreboard-live-try",
        )) {
            val d = nov8(name)
            assertEquals(3, d.awayTimeouts, name)
            assertEquals(3, d.homeTimeouts, name)
        }
    }

    @Test
    fun countsFromTheNov15Fixtures() {
        stacked(NflStackedFixtures.PENALTY_TYPE8).let { assertEquals(3, it.awayTimeouts); assertEquals(2, it.homeTimeouts) }
        stacked(NflStackedFixtures.TEAM_TIMEOUT_TYPE21).let { assertEquals(2, it.awayTimeouts); assertEquals(2, it.homeTimeouts) }
        stacked(NflStackedFixtures.TEAM_TIMEOUT_HELD).let { assertEquals(2, it.awayTimeouts); assertEquals(2, it.homeTimeouts) }
        stacked(NflStackedFixtures.AFTER_TIMEOUT).let { assertEquals(2, it.awayTimeouts); assertEquals(2, it.homeTimeouts) }
        for (name in NflStackedFixtures.ALL) {
            val d = stacked(name)
            assertNotNull(d.awayTimeouts, name)
            assertNotNull(d.homeTimeouts, name)
        }
    }

    @Test
    fun countsAreLenientAndKeptInRange() {
        assertNull(map(null).awayTimeouts, "no situation")
        assertNull(map(null).homeTimeouts)
        map("""{"down":1,"distance":10,"yardLine":35}""").let { assertNull(it.awayTimeouts); assertNull(it.homeTimeouts) }
        counts("-1", "4").let { assertNull(it.awayTimeouts); assertNull(it.homeTimeouts) }
        counts("\"x\"", "{}").let { assertNull(it.awayTimeouts); assertNull(it.homeTimeouts) }
        counts("\"2\"", "2.0").let { assertEquals(2, it.awayTimeouts); assertEquals(2, it.homeTimeouts) }
        counts("0", "3").let { assertEquals(0, it.awayTimeouts); assertEquals(3, it.homeTimeouts) }
        counts("1.5", "null").let { assertNull(it.awayTimeouts); assertNull(it.homeTimeouts) }
        // One bad count does not drop the other, nor the play.
        counts("\"x\"", "1").let { assertNull(it.awayTimeouts); assertEquals(1, it.homeTimeouts); assertIs<NflPlay.Down>(it.play) }
    }

    // ------------------------------------------------------------------ penalty (RC3)

    @Test
    fun penaltyFromTypeEightOrUpperCaseText() {
        stacked(NflStackedFixtures.PENALTY_TYPE8).lastPlay.let {
            assertEquals("401873061503", it?.id)
            assertEquals(true, it?.penalty, "type 8")
        }
        assertEquals(true, nov8("scoreboard-live-td-score-first").lastPlay?.penalty, "type 8 on the Nov 8 TD body")
        assertEquals(true, play("24", "J.Allen pass to K.Shakir for 22 yards. PENALTY on BUF-D.Dawkins, Unnecessary Roughness, 15 yards")?.penalty)
        assertEquals(true, play("53", "kicks 65 yards. PENALTY on KC-X, Holding, 10 yards, enforced at KC 20.")?.penalty)
        assertEquals(true, play("8", "")?.penalty, "type 8 with no text")
        // A declined foul (mixed case) and offsetting fouls' unproven text, as the rule is written.
        assertEquals(false, play("3", "P.Mahomes pass incomplete. Penalty on KC-J.Thuney, Offensive Holding, declined.")?.penalty)
        assertEquals(false, play("5", "Rush for 3 yards. penalty on BUF, declined")?.penalty)
        assertEquals(true, play("5", "PENALTY on KC-A, Holding, offsetting. PENALTY on BUF-B, Holding, offsetting.")?.penalty)
        assertEquals(false, play("5", "PENALTYon KC")?.penalty)
    }

    @Test
    fun noPenaltyOnOrdinaryPlays() {
        assertEquals(false, nov8("scoreboard-live-first-down").lastPlay?.penalty)
        for (name in NflStackedFixtures.ALL - NflStackedFixtures.PENALTY_TYPE8) {
            assertEquals(false, stacked(name).lastPlay?.penalty, name)
        }
        assertEquals(false, play("21", "Timeout #1 by LA at 06:56.")?.penalty)
    }

    // ------------------------------------------------------------------ RC9 and the possession-null poll

    @Test
    fun theKickoffAfterAScoreIsNeverInTheRedZone() {
        val d = stacked(NflStackedFixtures.OFFICIAL_TIMEOUT_KICKOFF_REDZONE)
        val k = assertIs<NflPlay.Kickoff>(d.play)
        assertEquals(Side.HOME, k.kicker, "LV kicks")
        assertEquals(FieldPos(Side.HOME, 35), k.field)
        assertEquals(Side.HOME, d.possession)
        assertFalse(d.redZone, "isRedZone true in the body, but a kick-off is never RED ZONE")
        assertEquals("74", d.lastPlay?.typeId)
        assertEquals(3, d.awayTimeouts)
        assertEquals(3, d.homeTimeouts)
    }

    @Test
    fun theKickoffReturnPollIsADownWithNoPossession() {
        val d = stacked(NflStackedFixtures.KICKOFF_RETURN_NO_POSSESSION)
        val down = assertIs<NflPlay.Down>(d.play)
        assertEquals(1, down.down)
        assertEquals(10, down.distance)
        assertEquals(FieldPos(Side.AWAY, 27), down.field)
        assertNull(d.possession)
        assertEquals("53", d.lastPlay?.typeId)
        assertEquals(Side.AWAY, d.lastPlay?.side, "the kick-off's team is the receiver, SEA (NFL §3.4 rule 1)")
    }

    @Test
    fun timeoutAndClockLagFixturesMapAsTheFeedFactsSay() {
        stacked(NflStackedFixtures.TEAM_TIMEOUT_TYPE21).lastPlay!!.let {
            assertEquals("21", it.typeId)
            assertEquals("401873061520", it.id)
            assertFalse(it.penalty)
        }
        assertEquals("401873061536", stacked(NflStackedFixtures.AFTER_TIMEOUT).lastPlay?.id)
        assertTrue(stacked(NflStackedFixtures.AFTER_TIMEOUT).redZone)
        assertEquals("74", stacked(NflStackedFixtures.CLOCK_LAG_A).lastPlay?.typeId)
        assertEquals("24", stacked(NflStackedFixtures.CLOCK_LAG_B).lastPlay?.typeId)
    }
}
