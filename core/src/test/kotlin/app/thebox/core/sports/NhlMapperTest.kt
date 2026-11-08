package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-score-bug.md §2.1, §8; docs/design/m6-power-play.md §1.2, §9.1: recorded and synthetic NHL bodies. */
class NhlMapperTest {
    private val base = "https://assets.nhle.com/logos/nhl/svg/"

    private fun body(path: String): JsonObject = Json.parseToJsonElement(SportsFixtures.text(path)).jsonObject
    private fun landing(name: String): GameSnapshot? = NhlMapper.landing(body("sports/nhl/$name"), base)

    @Test
    fun recordedDayList() {
        val day = assertNotNull(NhlMapper.day(body(SportsFixtures.SCORE_2026_11_05), base))
        assertEquals("2026-11-05", day.dayKey)
        assertEquals("2026-11-04", day.prevDay)
        assertEquals("2026-11-06", day.nextDay)
        assertEquals(11, day.games.size)
        assertEquals(9, day.games.count { it.phase == GamePhase.INTERMISSION })
        assertEquals(1, day.games.count { it.phase == GamePhase.PRE })
        assertEquals(1, day.games.count { it.phase == GamePhase.SCHEDULED })

        val g = day.games.first()
        assertEquals(GameId(League.NHL, "2026020251"), g.id)
        assertEquals("2026-11-05", g.dayKey)
        assertEquals(1_793_923_200_000L, g.startUtcMs)
        assertEquals(TeamLine("UTA", "Mammoth", 2, "${base}UTA_dark.svg"), g.away)
        assertEquals(TeamLine("MTL", "Canadiens", 2, "${base}MTL_dark.svg"), g.home)
        assertEquals(1, g.period)
        assertEquals(PeriodKind.REG, g.periodKind)
        assertNull(g.otNumber)
        assertEquals(GameClock(958, running = false, inIntermission = true), g.clock)
        assertNull(g.finalKind)

        val fut = day.games.last()
        assertEquals("NSH", fut.away.abbrev)
        assertNull(fut.away.score)
        assertNull(fut.clock)
        assertNull(fut.period)
    }

    @Test
    fun recordedFinalsIncludingOvertime() {
        val day = assertNotNull(NhlMapper.day(body(SportsFixtures.SCORE_2026_11_04), base))
        assertEquals("2026-11-04", day.dayKey)
        assertTrue(day.games.all { it.phase == GamePhase.FINAL })
        val ot = day.games.single { it.id.id == "2026020250" }
        assertEquals(PeriodKind.OT, ot.finalKind)
        assertEquals(1, ot.otNumber)
        assertEquals(listOf(PeriodKind.REG, PeriodKind.REG, PeriodKind.REG), day.games.filter { it != ot }.map { it.finalKind })
    }

    @Test
    fun recordedLandingInIntermission() {
        val g = assertNotNull(NhlMapper.landing(body(SportsFixtures.LANDING_LIVE_INT), base))
        assertEquals(GamePhase.INTERMISSION, g.phase)
        assertEquals("Utah Mammoth", g.away.name)
        assertEquals("Montréal Canadiens", g.home.name)
        assertEquals("${base}UTA_dark.svg", g.away.logo)
        assertEquals(1, g.period)
        assertEquals(GameClock(958, running = false, inIntermission = true), g.clock)
    }

    @Test
    fun runningClockAndCrit() {
        val g = assertNotNull(landing("landing-running-synthetic.json"))
        assertEquals(GamePhase.LIVE, g.phase)
        assertEquals(2, g.period)
        assertEquals(GameClock(622, running = true, inIntermission = false), g.clock)
        val c = assertNotNull(landing("landing-crit-synthetic.json"))
        assertEquals(GamePhase.LIVE, c.phase)
        assertEquals(3, c.period)
    }

    @Test
    fun overtimeNumberAndShootout() {
        val ot = assertNotNull(landing("landing-2ot-synthetic.json"))
        assertEquals(PeriodKind.OT, ot.periodKind)
        assertEquals(2, ot.otNumber)
        assertEquals(5, ot.period)
        val so = assertNotNull(landing("landing-so-synthetic.json"))
        assertEquals(GamePhase.LIVE, so.phase)
        assertEquals(PeriodKind.SO, so.periodKind)
        assertNull(so.otNumber)
    }

    @Test
    fun finalKinds() {
        val so = assertNotNull(landing("landing-final-so-synthetic.json"))
        assertEquals(GamePhase.FINAL, so.phase)
        assertEquals(PeriodKind.SO, so.finalKind)
        assertEquals(3, so.away.score)
        val ot = assertNotNull(landing("landing-final-ot-synthetic.json"))
        assertEquals(GamePhase.FINAL, ot.phase)
        assertEquals(PeriodKind.OT, ot.finalKind)
        assertEquals(1, ot.otNumber)
    }

    @Test
    fun endOfPeriodIsStillLive() {
        val g = assertNotNull(landing("landing-end-of-period-synthetic.json"))
        assertEquals(GamePhase.LIVE, g.phase)
        assertEquals(GameClock(0, running = false, inIntermission = false), g.clock)
    }

    @Test
    fun postponed() {
        val g = assertNotNull(landing("landing-ppd-synthetic.json"))
        assertEquals(GamePhase.POSTPONED, g.phase)
        assertNull(g.away.score)
    }

    @Test
    fun missingFieldsAndUnknownKeys() {
        val g = assertNotNull(landing("landing-missing-fields-synthetic.json"))
        assertEquals(GamePhase.SCHEDULED, g.phase) // unknown gameState
        assertEquals("2026-11-05", g.dayKey) // Eastern date of 2026-11-06T00:00Z
        assertNull(g.clock)
        assertNull(g.period)
        assertNull(g.periodKind)
        assertEquals("UTA", g.away.name) // no names at all: the abbreviation
        assertNull(g.away.score)
        assertEquals("MTL", g.home.name) // commonName without `default`
        assertEquals("${base}MTL_dark.svg", g.home.logo) // a darkLogo outside logoBase is replaced (A-N2)
    }

    @Test
    fun unmappableBodies() {
        assertNull(landing("landing-no-id-synthetic.json"))
        assertNull(NhlMapper.landing(JsonObject(emptyMap()), base))
        assertNull(NhlMapper.day(JsonObject(emptyMap()), base))
        // An HTML body never reaches the mapper: it is not JSON (the source maps it to BAD_BODY / HTTP_OTHER).
        val html = SportsFixtures.text("sports/nhl/landing-html-synthetic.html")
        assertTrue(runCatching { Json.parseToJsonElement(html).jsonObject }.isFailure)
    }

    @Test
    fun dayWithoutGamesAndBadGamesDropped() {
        val json = """{"currentDate":"2026-12-25","games":[{"id":1},{"nonsense":true},
            {"id":2026020300,"startTimeUTC":"2026-12-26T00:00:00Z","gameState":"FUT",
             "awayTeam":{"abbrev":"a/../b"},"homeTeam":{"abbrev":"EDM","name":{"default":"Oilers"}}}]}"""
        val day = assertNotNull(NhlMapper.day(Json.parseToJsonElement(json).jsonObject, base))
        assertEquals("2026-12-25", day.dayKey)
        assertNull(day.prevDay)
        val g = day.games.single()
        assertNull(g.away.logo) // not an abbreviation: no URL spliced from it
        assertEquals("Oilers", g.home.name)
        assertEquals(0, NhlMapper.day(Json.parseToJsonElement("""{"currentDate":"2026-12-25"}""").jsonObject, base)!!.games.size)
    }

    // ------------------------------------------------------------------ power play (docs/design/m6-power-play.md §1.2)

    private fun gameJson(situation: String?) = """{"id":2026020251,"startTimeUTC":"2026-11-06T00:00:00Z","gameState":"LIVE",
        "awayTeam":{"abbrev":"UTA"},"homeTeam":{"abbrev":"MTL"}${situation?.let { ",\"situation\":$it" } ?: ""}}"""

    private fun ppOf(situation: String?): PowerPlay? =
        assertNotNull(NhlMapper.landing(Json.parseToJsonElement(gameJson(situation)).jsonObject, base)).powerPlay

    /** A situation with [code] (raw JSON) and 79 s left. */
    private fun pp(code: String): PowerPlay? = ppOf("""{"situationCode":$code,"secondsRemaining":79}""")

    @Test
    fun recordedPowerPlaysInTheDayListAndTheLanding() {
        val day = assertNotNull(NhlMapper.day(body(SportsFixtures.SCORE_2026_11_05), base))
        val uta = day.games.single { it.away.abbrev == "UTA" }
        assertEquals(PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 79), uta.powerPlay) // 1541, 01:19
        val van = day.games.single { it.away.abbrev == "VAN" }
        assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 34), van.powerPlay) // 1451, 00:34
        assertEquals(9, day.games.count { it.powerPlay == null }, "no situation at even strength")
        assertTrue(day.games.all { it.lastGoal == null }, "the day list has no summary")

        val g = assertNotNull(NhlMapper.landing(body(SportsFixtures.LANDING_LIVE_INT), base))
        assertEquals(PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 79), g.powerPlay, "still reported in intermission")
    }

    @Test
    fun lastGoalIsTheLatestScoringEntry() {
        // landing-live-int.json: UTA 09:24 is `pp` at 2-0, but the latest entry (MTL 17:10, 2-2) is `ev`.
        val root = body(SportsFixtures.LANDING_LIVE_INT)
        val goals = root.getValue("summary").jsonObject.getValue("scoring").jsonArray
            .flatMap { it.jsonObject.getValue("goals").jsonArray }
            .map { it.jsonObject }
        val utaPp = goals.single { it.getValue("timeInPeriod").jsonPrimitive.content == "09:24" }
        assertEquals(listOf("pp", "2", "0"), listOf("strength", "awayScore", "homeScore").map { utaPp.getValue(it).jsonPrimitive.content })
        val g = assertNotNull(NhlMapper.landing(root, base))
        assertEquals(GoalFact(Side.HOME, away = 2, home = 2, pp = false), g.lastGoal)
    }

    @Test
    fun lastGoalFromThePpSynthetics() {
        val goal = assertNotNull(landing("landing-pp-goal-synthetic.json"))
        assertEquals(GoalFact(Side.HOME, 2, 2, pp = true), goal.lastGoal)
        assertNull(goal.powerPlay, "the situation is gone with the goal")
        val sh = assertNotNull(landing("landing-pp-sh-goal-synthetic.json"))
        assertEquals(GoalFact(Side.HOME, 2, 2, pp = false), sh.lastGoal, "`sh` is not a power-play goal")
        assertEquals(PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 50), sh.powerPlay, "the opponent's power play goes on")
        assertNull(assertNotNull(landing("landing-running-synthetic.json")).lastGoal, "no summary: null")
        // Odd entries are skipped; a later total wins, and a tie goes to the later entry.
        fun last(scoring: String) = NhlMapper.landing(
            Json.parseToJsonElement(gameJson(null).dropLast(1) + ""","summary":{"scoring":$scoring}}""").jsonObject, base,
        )!!.lastGoal
        val ev = """{"strength":"ev","isHome":false,"awayScore":1,"homeScore":0}"""
        assertEquals(GoalFact(Side.AWAY, 1, 0, false), last("""[{"goals":[$ev,{"isHome":true,"awayScore":1,"homeScore":1}]}]"""))
        assertEquals(GoalFact(Side.HOME, 1, 1, true), last("""[{"goals":[$ev]},"odd",{"goals":[null,{"strength":"pp","isHome":true,"awayScore":1,"homeScore":1}]}]"""))
        assertEquals(GoalFact(Side.HOME, 1, 0, false), last("""[{"goals":[$ev,{"strength":"ev","isHome":true,"awayScore":1,"homeScore":0}]}]"""))
        assertNull(last("""[{"goals":[{"strength":"pp","isHome":"maybe","awayScore":1,"homeScore":0},{"strength":"pp","isHome":true,"awayScore":-1,"homeScore":0}]}]"""))
        assertNull(last("""{"goals":[]}"""))
        assertNull(last("[]"))
    }

    @Test
    fun everyDecodingRow() {
        val one = PowerPlayKind.ONE_MAN
        val two = PowerPlayKind.TWO_MAN
        val rows = listOf(
            "\"1541\"" to PowerPlay(Side.AWAY, one, 79),
            "\"1451\"" to PowerPlay(Side.HOME, one, 79),
            "\"1351\"" to PowerPlay(Side.HOME, two, 79), // 5-on-3
            "\"1531\"" to PowerPlay(Side.AWAY, two, 79),
            "\"1431\"" to PowerPlay(Side.AWAY, one, 79), // 4-on-3 in overtime
            "\"0641\"" to PowerPlay(Side.AWAY, one, 79), // 6-on-4 with a pulled goalie: ruling 18
            "\"1550\"" to PowerPlay(Side.AWAY, one, 79), // review checks
            "\"0551\"" to PowerPlay(Side.HOME, one, 79),
            "\"1460\"" to PowerPlay(Side.HOME, one, 79),
            "\"1441\"" to null, // 4-on-4
            "\"1331\"" to null, // 3-on-3
            "\"0651\"" to null, // a delayed penalty
            "\"1560\"" to null, // an empty net at even strength
            "\"1551\"" to null,
            "\"15x1\"" to null, "\"1571\"" to null, "\"0331\"" to null, "\"0101\"" to null, "\"1010\"" to null,
            "\"1361\"" to null, // effective 6: out of range
            "\"0441\"" to PowerPlay(Side.HOME, one, 79), // away 4 skaters, net empty → effective 3 vs 4
            "\"651\"" to null, // a 3-character string is not padded
            "\"15410\"" to null,
            // JSON integers of 3–4 digits are left-padded.
            "651" to null, // → 0651
            "641" to PowerPlay(Side.AWAY, one, 79), // → 0641
            "1541" to PowerPlay(Side.AWAY, one, 79),
            "51" to null, "15410" to null, "-541" to null, "1541.0" to null, "true" to null, "null" to null, "{}" to null,
            "[]" to null,
        )
        for ((code, want) in rows) assertEquals(want, pp(code), "situationCode $code")
        assertNull(ppOf(null), "no situation")
        assertNull(ppOf("\"1541\""), "situation not an object")
        assertNull(ppOf("""{"secondsRemaining":79}"""), "no code: no strength fallback (S9)")
    }

    @Test
    fun theCrossCheckCancelsTheDecode() {
        fun with(away: String, home: String) =
            ppOf("""{"situationCode":"1541","secondsRemaining":79,"awayTeam":$away,"homeTeam":$home}""")
        val utaPp = PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 79)
        assertEquals(utaPp, with("""{"situationDescriptions":["PP"]}""", """{}"""))
        assertEquals(utaPp, with("""{}""", """{"situationDescriptions":["EN"]}"""))
        assertEquals(utaPp, with("""{}""", """{"situationDescriptions":"PP"}"""), "not an array: ignored")
        assertNull(with("""{}""", """{"situationDescriptions":["EN","PP"]}"""))
        assertNull(with("""{"situationDescriptions":["PP"]}""", """{"situationDescriptions":["PP"]}"""))
    }

    @Test
    fun secondsAreTheFeedsOwnWithinRange() {
        fun secs(fields: String) = assertNotNull(ppOf("""{"situationCode":"1451"$fields}""")).secondsRemaining
        assertEquals(79, secs(""","secondsRemaining":79,"timeRemaining":"00:10""""), "secondsRemaining wins")
        assertEquals(0, secs(""","secondsRemaining":0"""), "a reported 0:00 is kept")
        assertEquals(599, secs(""","secondsRemaining":599"""))
        assertNull(secs(""","secondsRemaining":600"""))
        assertNull(secs(""","secondsRemaining":-1"""))
        assertEquals(65, secs(""","timeRemaining":"01:05""""), "timeRemaining when there are no seconds")
        assertEquals(65, secs(""","secondsRemaining":"soon","timeRemaining":"01:05""""))
        assertNull(secs(""), "neither: no seconds, still a power play")
        assertNull(secs(""","timeRemaining":"soon""""))
        assertNull(secs(""","timeRemaining":"11:40""""))
    }

    @Test
    fun ppSynthetics() {
        assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 34), assertNotNull(landing("landing-pp-home-synthetic.json")).powerPlay)
        assertEquals(PowerPlay(Side.AWAY, PowerPlayKind.TWO_MAN, 41), assertNotNull(landing("landing-pp-two-man-synthetic.json")).powerPlay)
        // 641 (a JSON integer) → 0641: UTA's 6-on-4 keeps its power play (ruling 18); seconds from timeRemaining 01:05.
        assertEquals(PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 65), assertNotNull(landing("landing-pp-numeric-code-synthetic.json")).powerPlay)
        // 1451 with secondsRemaining 700: the power play stands, its seconds are dropped.
        assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, null), assertNotNull(landing("landing-pp-secs-out-of-range-synthetic.json")).powerPlay)
        // The cross-check: 1541 says UTA, but MTL carries "PP".
        assertNull(assertNotNull(landing("landing-pp-cross-check-synthetic.json")).powerPlay)
    }

    @Test
    fun aBrokenSituationStillMapsTheScore() {
        val g = assertNotNull(landing("landing-pp-broken-synthetic.json"))
        assertNull(g.powerPlay)
        assertNull(g.lastGoal, "odd scoring entries are skipped")
        assertEquals(2 to 2, g.away.score to g.home.score)
        assertEquals(GamePhase.LIVE, g.phase)
        assertEquals(GameClock(622, running = true, inIntermission = false), g.clock)
    }

    @Test
    fun theOlderSyntheticsCarryAnAwayPowerPlay() {
        // Their `situation` is 1541 (UTA), which now decodes. FINAL and SO keep the field; the machine gates it.
        for (name in listOf(
            "landing-running-synthetic.json", "landing-crit-synthetic.json", "landing-2ot-synthetic.json",
            "landing-so-synthetic.json", "landing-final-so-synthetic.json", "landing-final-ot-synthetic.json",
            "landing-end-of-period-synthetic.json",
        )) {
            assertEquals(PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 79), assertNotNull(landing(name)).powerPlay, name)
        }
        assertNull(assertNotNull(landing("landing-missing-fields-synthetic.json")).powerPlay)
    }

    @Test
    fun darkLogoUrl() {
        assertEquals("https://assets.nhle.com/logos/nhl/svg/CGY_dark.svg", NhlMapper.darkLogoUrl("CGY", base))
    }
}
