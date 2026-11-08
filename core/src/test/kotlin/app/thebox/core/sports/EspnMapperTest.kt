package app.thebox.core.sports

import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl.md §1.2, §1.3, §10.1. Every fixture is decoded through the commit-0 DTO (B3), with the same
 * `Json { ignoreUnknownKeys = true }` the source streams with.
 */
class EspnMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val logoBase = "https://a.espncdn.com/"
    private val cinAtl = GameId(League.NFL, "401873038")
    /** 2026-11-08 14:40Z: ten minutes after the CIN @ ATL start, 3 h 20 min before the 18:00Z games. */
    private val serverNow = Instant.parse("2026-11-08T14:40:00Z").toEpochMilli()

    private fun boardDto(text: String) = json.decodeFromString(EspnBoardDto.serializer(), text)
    private fun gameDto(text: String) = json.decodeFromString(EspnGameDto.serializer(), text)
    private fun fixture(name: String) = SportsFixtures.text("sports/nfl/$name-2026-11-08.json")

    private fun live(name: String, date: Long? = serverNow): GameSnapshot =
        assertNotNull(EspnMapper.game(gameDto(fixture(name)), cinAtl, date, logoBase), name)

    private fun nfl(s: GameSnapshot): LeagueDetail.Nfl = assertIs<LeagueDetail.Nfl>(s.detail)

    /** A one-event body around [event] (an `events[0]` JSON object), for the hand-made shapes. */
    private fun oneEvent(event: String) = """{"events":[$event]}"""

    private fun event(
        state: String = "in",
        name: String = "STATUS_IN_PROGRESS",
        period: String = "2",
        clock: String = "\"clock\": 600.0, \"displayClock\": \"10:00\"",
        awayScore: String = "\"7\"",
        homeScore: String = "\"3\"",
        situation: String? = null,
    ): String = """
        {"id":"401899001","date":"2026-11-08T18:00Z","competitions":[{
          "competitors":[
            {"id":"12","homeAway":"away","score":$awayScore,"team":{"id":"12","abbreviation":"KC","displayName":"Kansas City Chiefs","shortDisplayName":"Chiefs","logo":"https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/kc.png"}},
            {"id":"2","homeAway":"home","score":$homeScore,"team":{"id":"2","abbreviation":"BUF","displayName":"Buffalo Bills","shortDisplayName":"Bills"}}
          ],
          "status":{$clock,"period":$period,"type":{"id":"2","name":"$name","state":"$state"}}
          ${situation?.let { ",\"situation\":$it" } ?: ""}
        }]}
    """.trimIndent()

    private fun mapOne(e: String, date: Long? = serverNow): GameSnapshot? =
        EspnMapper.game(gameDto(oneEvent(e)), GameId(League.NFL, "401899001"), date, logoBase)

    // ------------------------------------------------------------------ the week and the keys (S6)

    @Test
    fun weekKeyGrammarAndTheOneParser() {
        assertEquals("w2026-2-9", EspnMapper.weekKey(2026, 2, 9))
        assertEquals(Triple(2026, 2, 9), EspnMapper.parseWeekKey("w2026-2-9"))
        assertEquals(Triple(2026, 3, 1), EspnMapper.parseWeekKey(EspnMapper.weekKey(2026, 3, 1)))
        for (bad in listOf("2026-11-08", "w2026-2", "w2026-0-9", "w2026-2-0", "W2026-2-9", "w2026-2-9x", "", "w26-2-9", "w2026--2-9")) {
            assertNull(EspnMapper.parseWeekKey(bad), bad)
        }
        // A week key can never be read as a date.
        assertTrue(runCatching { java.time.LocalDate.parse("w2026-2-9") }.isFailure)
    }

    @Test
    fun theWeekBoardHasItsKeyNeighboursAndEveryEvent() {
        val b = assertNotNull(EspnMapper.board(boardDto(SportsFixtures.text("sports/nfl/scoreboard-week9-pre-2026-11-08.json")), serverNow, logoBase))
        assertEquals("w2026-2-9", b.dayKey)
        assertEquals("w2026-2-8", b.prevDay)
        assertEquals("w2026-2-10", b.nextDay)
        assertEquals(15, b.games.size)
        val thu = b.games.single { it.id.id == "401873037" }
        assertEquals(GamePhase.FINAL, thu.phase)
        assertEquals(PeriodKind.REG, thu.finalKind)
        assertEquals("JAX", thu.away.abbrev)
        assertEquals(33, thu.away.score)
        assertEquals(10, thu.home.score)
        assertEquals("2026-11-05", thu.dayKey, "the Thursday night game's ET date")
        val mon = b.games.single { it.id.id == "401873051" }
        assertEquals("2026-11-09", mon.dayKey, "Monday 01:15Z is Sunday night ET")
        assertEquals(GamePhase.SCHEDULED, mon.phase)
        assertNull(mon.away.score, "no score before `in`")
        // The CIN @ ATL start is 10 min before the server time: PRE (within the hour); the 18:00Z games are 3 h out.
        assertEquals(GamePhase.PRE, b.games.single { it.id == cinAtl }.phase)
        assertEquals(GamePhase.SCHEDULED, b.games.single { it.id.id == "401873045" }.phase)
        // No server Date: never PRE from a `pre` body.
        val noDate = assertNotNull(EspnMapper.board(boardDto(SportsFixtures.text("sports/nfl/scoreboard-week9-pre-2026-11-08.json")), null, logoBase))
        assertEquals(GamePhase.SCHEDULED, noDate.games.single { it.id == cinAtl }.phase)
    }

    private fun weekBody(type: Int, week: Int): String {
        val text = SportsFixtures.text("sports/nfl/scoreboard-week9-pre-2026-11-08.json")
        val root = Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val m = root.toMutableMap()
        m["season"] = Json.parseToJsonElement("""{"type":$type,"year":2026}""")
        m["week"] = Json.parseToJsonElement("""{"number":$week}""")
        m["events"] = Json.parseToJsonElement("[]")
        return kotlinx.serialization.json.JsonObject(m).toString()
    }

    @Test
    fun calendarNeighboursAcrossSeasonTypesAndNullAtTheEdges() {
        fun b(type: Int, week: Int) = assertNotNull(EspnMapper.board(boardDto(weekBody(type, week)), serverNow, logoBase))
        b(2, 18).let { assertEquals("w2026-2-17", it.prevDay); assertEquals("w2026-3-1", it.nextDay, "Week 18 → Wild Card") }
        b(2, 1).let { assertEquals("w2026-1-4", it.prevDay); assertEquals("w2026-2-2", it.nextDay) }
        b(3, 4).let { assertEquals("w2026-3-3", it.prevDay); assertNull(it.nextDay, "no week after the Super Bowl") }
        b(1, 1).let { assertNull(it.prevDay, "Hall of Fame Weekend is the first entry"); assertEquals("w2026-1-2", it.nextDay) }
        b(4, 1).let {
            assertEquals("w2026-4-1", it.dayKey)
            assertNull(it.prevDay, "the off-season has no weeks")
            assertNull(it.nextDay)
            assertTrue(it.games.isEmpty())
        }
        // No calendar at all: no neighbours.
        val bare = assertNotNull(EspnMapper.board(boardDto("""{"season":{"type":2,"year":2026},"week":{"number":9},"events":[]}"""), null, logoBase))
        assertEquals("w2026-2-9", bare.dayKey)
        assertNull(bare.prevDay)
        assertNull(bare.nextDay)
    }

    @Test
    fun aDatedBoardIsKeyedByItsEtDateWithNoNeighbours() {
        val b = assertNotNull(EspnMapper.board(boardDto(SportsFixtures.text("sports/nfl/scoreboard-day-pre-2026-11-08.json")), serverNow, logoBase))
        assertEquals("2026-11-08", b.dayKey)
        assertNull(b.prevDay)
        assertNull(b.nextDay)
        assertEquals(3, b.games.size)
        assertEquals("2026-11-08", b.games.single { it.id.id == "401873050" }.dayKey, "01:20Z is 8:20 pm ET")
        // An empty dated board falls back to the server date's ET day.
        val empty = assertNotNull(EspnMapper.board(boardDto("""{"events":[]}"""), serverNow, logoBase))
        assertEquals("2026-11-08", empty.dayKey)
        assertTrue(empty.games.isEmpty())
    }

    @Test
    fun aMissingEventsListFailsTheBoardAndABadEventIsDropped() {
        assertNull(EspnMapper.board(boardDto("""{"leagues":[]}"""), serverNow, logoBase))
        val noState = event().replace("\"state\":\"in\"", "\"stateX\":\"in\"")
        val badState = event(state = "later")
        val noHome = event().replace("\"homeAway\":\"home\"", "\"homeAway\":\"neutral\"")
        val good = event()
        val b = assertNotNull(EspnMapper.board(boardDto("""{"events":[$noState,$badState,$noHome,$good]}"""), serverNow, logoBase))
        assertEquals(1, b.games.size)
        assertNull(mapOne(noState), "game() of a dropped event is null (BAD_BODY)")
        assertNull(EspnMapper.game(gameDto(oneEvent(good)), GameId(League.NFL, "1"), serverNow, logoBase), "another id")
        // The spine re-typed (`events` not a list) fails the decode.
        assertTrue(runCatching { boardDto("""{"events":{"a":1}}""") }.isFailure)
    }

    // ------------------------------------------------------------------ status (§1.3 table, S8)

    @Test
    fun theCoinTossIsPreAndTheFirstDownIsLive() {
        val toss = live("scoreboard-live-coin-toss")
        assertEquals(GamePhase.PRE, toss.phase, "S8: `in` at the coin toss is PRE (ruling 4)")
        assertEquals(0, toss.away.score, "an `in` PRE keeps its scores (the machine's LIVE-cadence marker)")
        assertEquals(0, toss.home.score)
        val first = live("scoreboard-live-first-down")
        assertEquals(GamePhase.LIVE, first.phase)
        assertEquals(1, first.period)
        assertEquals(PeriodKind.REG, first.periodKind)
        assertEquals(GameClock(894, running = false, inIntermission = false), first.clock)
        assertEquals("401873038", first.id.id)
        assertEquals(League.NFL, first.id.league)
        assertEquals(Instant.parse("2026-11-08T14:30:00Z").toEpochMilli(), first.startUtcMs)
        assertEquals("2026-11-08", first.dayKey)
        // A logged play after the coin toss, a score, or a later period: LIVE.
        val sit = """{"down":0,"distance":0,"yardLine":0,"lastPlay":{"id":"9","type":{"id":"53","text":"Kickoff"}}}"""
        assertEquals(GamePhase.LIVE, mapOne(event(period = "1", awayScore = "\"0\"", homeScore = "\"0\"", situation = sit))?.phase)
        val noPlay = """{"down":0,"distance":0,"yardLine":0}"""
        val atStart = "\"clock\": 900.0, \"displayClock\": \"15:00\""
        assertEquals(GamePhase.PRE, mapOne(event(period = "1", clock = atStart, awayScore = "\"0\"", homeScore = "\"0\"", situation = noPlay))?.phase)
        assertEquals(GamePhase.PRE, mapOne(event(period = "1", clock = atStart, awayScore = "\"0\"", homeScore = "\"0\""))?.phase, "no situation")
        assertEquals(GamePhase.LIVE, mapOne(event(period = "1", awayScore = "\"0\"", homeScore = "\"3\"", situation = noPlay))?.phase)
        assertEquals(GamePhase.LIVE, mapOne(event(period = "2", awayScore = "\"0\"", homeScore = "\"0\"", situation = noPlay))?.phase)
    }

    @Test
    fun aMidQuarterBodyWithNoLastPlayIsLive() {
        // C3: a 0–0 first quarter whose lastPlay went missing for a poll, with the clock run down: LIVE, not PRE.
        val zero = "\"0\""
        val noPlay = """{"down":2,"distance":7,"yardLine":40}"""
        assertEquals(GamePhase.LIVE, mapOne(event(period = "1", awayScore = zero, homeScore = zero, situation = noPlay))?.phase, "10:00")
        assertEquals(GamePhase.LIVE, mapOne(event(period = "1", awayScore = zero, homeScore = zero))?.phase, "10:00, no situation")
        val justUnder = "\"clock\": 899.0, \"displayClock\": \"14:59\""
        assertEquals(GamePhase.LIVE, mapOne(event(period = "1", clock = justUnder, awayScore = zero, homeScore = zero))?.phase)
        val noClock = "\"displayClock\": \"\""
        assertEquals(GamePhase.LIVE, mapOne(event(period = "1", clock = noClock, awayScore = zero, homeScore = zero))?.phase, "unknown clock")
        val displayOnly = "\"displayClock\": \"15:00\""
        assertEquals(GamePhase.PRE, mapOne(event(period = "1", clock = displayOnly, awayScore = zero, homeScore = zero))?.phase)
        // The coin toss keeps the S8 rule whatever the clock says.
        val toss = """{"lastPlay":{"id":"1","type":{"id":"70","text":"Coin Toss"}}}"""
        assertEquals(GamePhase.PRE, mapOne(event(period = "1", awayScore = zero, homeScore = zero, situation = toss))?.phase)
    }

    @Test
    fun theStatusTable() {
        val end = live("scoreboard-live-end-period")
        assertEquals(GamePhase.INTERMISSION, end.phase)
        assertEquals(NflBreak.END_PERIOD, nfl(end).breakKind)
        assertTrue(end.clock!!.inIntermission)
        assertIs<NflPlay.Down>(nfl(end).play, "the situation is kept through the break")
        val half = assertNotNull(mapOne(event(name = "STATUS_HALFTIME")))
        assertEquals(GamePhase.INTERMISSION, half.phase)
        assertEquals(NflBreak.HALFTIME, nfl(half).breakKind)
        assertEquals(GamePhase.LIVE, mapOne(event(name = "STATUS_WEATHER_DELAY"))?.phase, "an unknown `in` name is LIVE")
        assertNull(nfl(mapOne(event())!!).breakKind)
        val ot = assertNotNull(mapOne(event(state = "post", name = "STATUS_FINAL", period = "5")))
        assertEquals(GamePhase.FINAL, ot.phase)
        assertEquals(PeriodKind.OT, ot.finalKind)
        assertEquals(PeriodKind.OT, ot.periodKind)
        assertEquals(1, ot.otNumber)
        assertEquals(2, mapOne(event(period = "6"))?.otNumber, "2OT")
        assertEquals(PeriodKind.REG, mapOne(event(state = "post", name = "STATUS_FINAL", period = "4"))?.finalKind)
        assertEquals(GamePhase.POSTPONED, mapOne(event(state = "pre", name = "STATUS_POSTPONED"))?.phase)
        assertEquals(GamePhase.POSTPONED, mapOne(event(state = "post", name = "STATUS_CANCELED"))?.phase)
        assertEquals(GamePhase.SCHEDULED, mapOne(event(state = "pre", name = "STATUS_SCHEDULED"), date = null)?.phase)
        assertNull(mapOne(event(state = "unknown")))
    }

    @Test
    fun clockFromSecondsElseFromTheDisplayElseNone() {
        assertEquals(600, mapOne(event(clock = "\"clock\": 600.9, \"displayClock\": \"10:00\""))?.clock?.secondsRemaining, "floored")
        assertEquals(222, mapOne(event(clock = "\"displayClock\": \"3:42\""))?.clock?.secondsRemaining)
        assertEquals(222, mapOne(event(clock = "\"clock\": \"222.0\""))?.clock?.secondsRemaining, "a string clock")
        assertNull(mapOne(event(clock = "\"clockX\": 1"))?.clock)
        assertFalse(mapOne(event())!!.clock!!.running, "ESPN has no running flag")
    }

    // ------------------------------------------------------------------ teams (S5)

    @Test
    fun teamsNamesScoresAndLogos() {
        val s = live("scoreboard-live-td-score-first")
        assertEquals("CIN", s.away.abbrev)
        assertEquals("ATL", s.home.abbrev)
        assertEquals("Atlanta Falcons", s.home.name)
        assertEquals("Falcons", s.home.shortName)
        assertEquals(0, s.away.score)
        assertEquals(6, s.home.score)
        assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/atl.png", s.home.logo)
        val e = assertNotNull(mapOne(event()))
        assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/buf.png", e.home.logo, "derived when the feed has none")
        assertEquals(7, mapOne(event(awayScore = "7"))?.away?.score, "a number score")
        assertNull(mapOne(event(awayScore = "\"seven\""))?.away?.score, "a non-integer score is null for that side only")
        assertEquals(3, mapOne(event(awayScore = "\"seven\""))?.home?.score)
        assertNull(mapOne(event(state = "pre", name = "STATUS_SCHEDULED", awayScore = "\"0\""))?.away?.score, "null before `in`")
        // A logo outside the base is replaced.
        val other = EspnMapper.game(gameDto(oneEvent(event())), GameId(League.NFL, "401899001"), serverNow, "http://10.0.2.2:8080/")
        assertEquals("http://10.0.2.2:8080/i/teamlogos/nfl/500/scoreboard/kc.png", other?.away?.logo)
    }

    // ------------------------------------------------------------------ situation (§1.2 fixtures)

    @Test
    fun eachLiveFixtureMaps() {
        // 14:36:52 the normal case: ATL (home) 1st & 10 at ATL 32.
        nfl(live("scoreboard-live-first-down")).let {
            assertEquals(NflPlay.Down(1, 10, goalToGo = false, field = FieldPos(Side.HOME, 32)), it.play)
            assertEquals(Side.HOME, it.possession)
            assertFalse(it.redZone)
            assertEquals(NflLastPlay("40187303873", "24", "Pass Reception", Side.HOME, 0), it.lastPlay)
        }
        // 14:41:58 the score before the play: still the old 1st & Goal at CIN 1.
        nfl(live("scoreboard-live-td-score-first")).let {
            assertEquals(NflPlay.Down(1, 1, goalToGo = true, field = FieldPos(Side.AWAY, 1)), it.play)
            assertTrue(it.redZone)
            assertEquals("8", it.lastPlay?.typeId)
        }
        // 14:42:13 the try: a dead ball after the TD.
        nfl(live("scoreboard-live-try")).let {
            assertEquals(NflPlay.Dead(Side.HOME, 6), it.play)
            assertNull(it.possession)
            assertFalse(it.redZone, "isRedZone is trusted only with a real down")
            assertEquals("67", it.lastPlay?.typeId)
        }
        // 14:42:44 the kick-off: ATL (home) kicks from ATL 35 = yl 35.
        nfl(live("scoreboard-live-kickoff")).let {
            assertEquals(NflPlay.Kickoff(Side.HOME, FieldPos(Side.HOME, 35)), it.play)
            assertFalse(it.redZone)
        }
        // 14:50:07 the +7 body: CIN (away) kicks from CIN 35 = home-relative 65.
        nfl(live("scoreboard-live-plus7-away-kickoff")).let {
            assertEquals(NflPlay.Kickoff(Side.AWAY, FieldPos(Side.AWAY, 35)), it.play)
        }
        // 14:53:10 no possession after the kick-off; the territory still comes from possessionText (B1).
        nfl(live("scoreboard-live-no-possession-after-kickoff")).let {
            assertEquals(NflPlay.Down(1, 10, goalToGo = false, field = FieldPos(Side.HOME, 40)), it.play)
            assertNull(it.possession)
            assertEquals(NflLastPlay("401873038482", "53", "Kickoff", Side.HOME, 0), it.lastPlay)
        }
        // 15:04:07 the FG: the same dead-ball shape as the try, with the FG as the scoring play.
        nfl(live("scoreboard-live-fg-dead-ball")).let { assertEquals(NflPlay.Dead(Side.HOME, 3), it.play) }
        nfl(live("scoreboard-live-kickoff-after-fg")).let { assertEquals(NflPlay.Kickoff(Side.HOME, FieldPos(Side.HOME, 35)), it.play) }
        // The coin toss: down 0, distance 0, no possession.
        nfl(live("scoreboard-live-coin-toss")).let {
            assertEquals(NflPlay.None, it.play)
            assertEquals("70", it.lastPlay?.typeId)
        }
    }

    private fun sit(body: String): LeagueDetail.Nfl = nfl(assertNotNull(mapOne(event(situation = body))))

    @Test
    fun goalToGoFromTheTextAndFromTheNumbers() {
        // The text alone (no possession, no numbers that agree).
        assertEquals(true, (sit("""{"down":4,"distance":13,"yardLine":87,"shortDownDistanceText":"4th & Goal"}""").play as NflPlay.Down).goalToGo)
        // The numbers: KC (away) ball at BUF 8, 8 to go; the text says nothing.
        val n = sit("""{"down":1,"distance":8,"yardLine":8,"possessionText":"BUF 8","possession":"12","shortDownDistanceText":"1st & 8"}""")
        assertEquals(NflPlay.Down(1, 8, goalToGo = true, field = FieldPos(Side.HOME, 8)), n.play)
        // Own half: never goal to go.
        val own = sit("""{"down":1,"distance":8,"yardLine":92,"possessionText":"KC 8","possession":"12"}""")
        assertEquals(false, (own.play as NflPlay.Down).goalToGo)
        assertEquals(FieldPos(Side.AWAY, 8), (own.play as NflPlay.Down).field)
    }

    @Test
    fun theTerritoryFromTextOrYardLineAndMidfield() {
        // With and without `possession`: the territory is the text's team (B1).
        assertEquals(FieldPos(Side.AWAY, 30), (sit("""{"down":2,"distance":5,"possessionText":"KC 30","possession":"2"}""").play as NflPlay.Down).field)
        assertEquals(FieldPos(Side.AWAY, 30), (sit("""{"down":2,"distance":5,"possessionText":"KC 30"}""").play as NflPlay.Down).field)
        // 50 → midfield, from the text and from the yard line.
        assertEquals(FieldPos(null, 50), (sit("""{"down":2,"distance":5,"possessionText":"KC 50"}""").play as NflPlay.Down).field)
        assertEquals(FieldPos(null, 50), (sit("""{"down":2,"distance":5,"yardLine":50}""").play as NflPlay.Down).field)
        // The yard line is home-relative: home kicker at 35, away kicker at 65 → AWAY 35.
        assertEquals(NflPlay.Kickoff(Side.HOME, FieldPos(Side.HOME, 35)), sit("""{"down":-1,"distance":0,"yardLine":35,"possession":"2"}""").play)
        assertEquals(NflPlay.Kickoff(Side.AWAY, FieldPos(Side.AWAY, 35)), sit("""{"down":-1,"distance":0,"yardLine":65,"possession":"12"}""").play)
        // An unknown prefix falls back to the yard line; nothing usable gives no field.
        assertEquals(FieldPos(Side.HOME, 20), (sit("""{"down":1,"distance":10,"yardLine":20,"possessionText":"XYZ 20"}""").play as NflPlay.Down).field)
        assertNull((sit("""{"down":1,"distance":10}""").play as NflPlay.Down).field)
    }

    @Test
    fun missingOrBadSituation() {
        val none = nfl(assertNotNull(mapOne(event())))
        assertEquals(LeagueDetail.Nfl(NflPlay.None, null, redZone = false, breakKind = null, lastPlay = null), none)
        // A bad down: no Down; a dead ball with a distance and no possession.
        assertEquals(NflPlay.Dead(null, null), sit("""{"down":7,"distance":10}""").play)
        assertEquals(NflPlay.None, sit("""{"down":"x","distance":"y"}""").play)
        // An out-of-range distance on a real down is null.
        assertEquals(NflPlay.Down(3, null, goalToGo = false, field = null), sit("""{"down":3,"distance":0}""").play)
        assertEquals(NflPlay.Down(3, null, goalToGo = false, field = null), sit("""{"down":3,"distance":120}""").play)
        // isRedZone without a down is false.
        assertFalse(sit("""{"down":-1,"distance":0,"possession":"2","isRedZone":true}""").redZone)
        assertTrue(sit("""{"down":1,"distance":10,"possession":"2","possessionText":"KC 12","isRedZone":true}""").redZone)
    }

    @Test
    fun aRetypedBodyStillYieldsScoreAndClock() {
        // possession a number, lastPlay an array, clock a string, period a string.
        val s = assertNotNull(
            mapOne(
                event(
                    period = "\"3\"",
                    clock = "\"clock\": \"222\", \"displayClock\": 9",
                    situation = """{"down":"2","distance":7.0,"possession":12,"possessionText":["KC 30"],"yardLine":"70","isRedZone":"maybe","lastPlay":[1,2]}""",
                ),
            ),
        )
        assertEquals(GamePhase.LIVE, s.phase)
        assertEquals(3, s.period)
        assertEquals(222, s.clock?.secondsRemaining)
        assertEquals(7, s.away.score)
        val d = nfl(s)
        assertEquals(Side.AWAY, d.possession, "a numeric possession still matches the team id")
        assertEquals(NflPlay.Down(2, 7, goalToGo = false, field = FieldPos(Side.AWAY, 30)), d.play)
        assertNull(d.lastPlay)
        assertFalse(d.redZone)
        // Re-typed `team` leaves drop only that event.
        val badTeam = event().replace("\"team\":{\"id\":\"2\"", "\"teamX\":{\"id\":\"2\"")
        assertNull(mapOne(badTeam))
        // The same through the board, beside a good event.
        val b = assertNotNull(EspnMapper.board(boardDto("""{"events":[$badTeam,${event()}]}"""), serverNow, logoBase))
        assertEquals(1, b.games.size)
    }

    @Test
    fun theLiveLogMatchesTheMapperOnEveryState() {
        // The proposer's 15 s log: every state seen maps to the expected phase (S8 at the coin toss).
        val lines = SportsFixtures.text("sports/nfl/live-sample-2026-11-08.log").lines().filter { it.isNotBlank() }
        assertTrue(lines.size > 100)
        var sawToss = false
        for (l in lines) {
            val o = Json.parseToJsonElement(l) as kotlinx.serialization.json.JsonObject
            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val state = (o["state"] as kotlinx.serialization.json.JsonPrimitive).content
            val period = (o["period"] as kotlinx.serialization.json.JsonPrimitive).content
            val score = o["score"] as kotlinx.serialization.json.JsonObject
            fun sc(k: String) = ((score[k] as kotlinx.serialization.json.JsonArray)[1] as kotlinx.serialization.json.JsonPrimitive).content
            // The log records the play type's text; the mapper reads its id (70 = Coin Toss).
            val lpText = (o["lpType"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val lpType = lpText?.let { if (it == "Coin Toss") "70" else "5" }
            val sitBody = lpType?.let { """{"lastPlay":{"id":"1","type":{"id":"$it","text":"$lpText"}}}""" }
            val clockS = (o["clockS"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val clockField = "\"clock\": $clockS"
            val s = assertNotNull(mapOne(event(state = state, name = name, period = period, clock = clockField, awayScore = "\"${sc("away")}\"", homeScore = "\"${sc("home")}\"", situation = sitBody)), l)
            val want = when {
                state == "pre" -> GamePhase.SCHEDULED // the hand-made event starts 18:00Z, over an hour after serverNow
                state == "post" -> GamePhase.FINAL
                name == "STATUS_END_PERIOD" || name == "STATUS_HALFTIME" -> GamePhase.INTERMISSION
                period == "1" && sc("away") == "0" && sc("home") == "0" && (lpType == "70" || (lpType == null && clockS >= 900.0)) -> GamePhase.PRE.also { sawToss = true }
                else -> GamePhase.LIVE
            }
            assertEquals(want, s.phase, l)
        }
        assertTrue(sawToss, "the log has the coin-toss rows")
    }
}
