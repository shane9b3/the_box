package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-score-bug.md §2.1, §8: recorded and synthetic NHL bodies. */
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

    @Test
    fun darkLogoUrl() {
        assertEquals("https://assets.nhle.com/logos/nhl/svg/CGY_dark.svg", NhlMapper.darkLogoUrl("CGY", base))
    }
}
