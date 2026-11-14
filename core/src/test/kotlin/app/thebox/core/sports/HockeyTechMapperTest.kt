package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** (CHL §3, §7.2) `HockeyTechMapper` on the recorded bodies and on synthetic ones (leniency, statuses, the clock table). */
class HockeyTechMapperTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun ms(date: String, time: String, zone: String): Long =
        LocalDateTime.parse("${date}T$time").atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ §3.5 teams, §2.3 seasons

    @Test
    fun recordedTeams() {
        val counts = ChlFixtures.CLIENTS.associateWith { c ->
            HockeyTechMapper.teams(ChlFixtures.teams(c), ChlFixtures.logoPrefix(c))!!.size
        }
        assertEquals(mapOf("ohl" to 20, "whl" to 23, "lhjmq" to 18), counts)

        val ohl = HockeyTechMapper.teams(ChlFixtures.teams("ohl"), ChlFixtures.logoPrefix("ohl"))!!
        assertEquals(ChlTeam("5", "OTT", "Ottawa 67's", "67's", "https://assets.leaguestat.com/ohl/logos/5_88.png"), ohl["5"])
        assertEquals("Sault Ste. Marie Greyhounds", ohl.getValue("16").fullName) // city + nickname, not "Soo Greyhounds"
        // JPEG swap (P6): BRAM and NIAG.
        assertEquals("https://assets.leaguestat.com/ohl/logos/18.png", ohl.getValue("18").logo)
        assertEquals("https://assets.leaguestat.com/ohl/logos/20.png", ohl.getValue("20").logo)
        assertEquals(setOf("ER", "NB", "OS"), ohl.values.map { it.code }.filter { it.length == 2 }.toSet())
        assertEquals(setOf("BRAM", "FLNT", "NIAG"), ohl.values.map { it.code }.filter { it.length == 4 }.toSet())

        val whl = HockeyTechMapper.teams(ChlFixtures.teams("whl"), ChlFixtures.logoPrefix("whl"))!!
        assertEquals("https://assets.leaguestat.com/whl/logos/277.png", whl.getValue("277").logo) // PEN 277_295.jpg
        assertEquals("https://assets.leaguestat.com/whl/logos/222.png", whl.getValue("222").logo) // WEN 222_295.jpg

        // QMJHL: mixed case upper-cased (owner decision 4), comma names, and the team code "NFL" (L2).
        val q = HockeyTechMapper.teams(ChlFixtures.teams("lhjmq"), ChlFixtures.logoPrefix("lhjmq"))!!
        assertEquals(ChlTeam("16", "BAC", "Baie-Comeau Drakkar", "Drakkar", "https://assets.leaguestat.com/lhjmq/logos/16.png"), q["16"])
        assertEquals(ChlTeam("2", "NFL", "Newfoundland Regiment", "Regiment", "https://assets.leaguestat.com/lhjmq/logos/2_214.png"), q["2"])
        assertEquals("https://assets.leaguestat.com/lhjmq/logos/3.png", q.getValue("3").logo) // CAP 3.jpg
        assertEquals(listOf("CAP", "CHA", "CHI", "DRU", "GAT", "HAL", "MON", "QUE", "RIM", "ROU", "SHA", "SHE", "VDO", "VIC"),
            q.values.map { it.code }.filter { it != "BAC" && it != "BLB" && it != "NFL" && it != "SNB" }.sorted())
        assertEquals("Sherbrooke Phœnix", q.getValue("60").fullName)
        assertEquals(61, counts.values.sum())
    }

    @Test
    fun logoValidation() {
        val p = "https://assets.leaguestat.com/ohl/logos/"
        assertEquals("${p}7.png", HockeyTechMapper.logo("${p}7.png", p, "7"))
        assertEquals("${p}7_88.png", HockeyTechMapper.logo("${p}7_88.png", p, "7"))
        assertEquals("${p}7.png", HockeyTechMapper.logo("${p}7_88.jpeg", p, "7"))
        assertNull(HockeyTechMapper.logo("${p}50x50/7.png", p, "7"))
        assertNull(HockeyTechMapper.logo("${p}7.png?x=1", p, "7"))
        assertNull(HockeyTechMapper.logo("${p}../7.png", p, "7"))
        assertNull(HockeyTechMapper.logo("${p}7.gif", p, "7"))
        assertNull(HockeyTechMapper.logo("${p}7.PNG", p, "7"))
        assertNull(HockeyTechMapper.logo("https://elsewhere.example/ohl/logos/7.png", p, "7"))
        assertNull(HockeyTechMapper.logo("https://assets.leaguestat.com/whl/logos/7.png", p, "7"))
        assertNull(HockeyTechMapper.logo(null, p, "7"))
    }

    @Test
    fun teamsLeniencyAndGate() {
        val body = json(
            """{"SiteKit":{"Teamsbyseason":[
              {"id":"1","code":" bac ","city":"Baie-Comeau","nickname":"Drakkar"},
              {"id":2,"code":"Cap","name":"Cape Breton, Eagles"},
              {"id":"3","code":"TOOLONG","name":"X"},
              {"id":"x4","code":"ABC"},
              {"id":"5","code":"N-F"},
              {"id":"6","code":"ZZ"},
              7, null
            ]}}""",
        )
        val t = HockeyTechMapper.teams(body, "https://a/")!!
        assertEquals(setOf("1", "2", "6"), t.keys)
        assertEquals(ChlTeam("1", "BAC", "Baie-Comeau Drakkar", "Drakkar", null), t["1"])
        assertEquals(ChlTeam("2", "CAP", "Cape Breton Eagles", "Cape Breton Eagles", null), t["2"]) // comma long name
        assertEquals(ChlTeam("6", "ZZ", "ZZ", "ZZ", null), t["6"]) // no names: the code
        assertNull(HockeyTechMapper.teams(json("""{"SiteKit":{}}"""), "https://a/"))
        assertNull(HockeyTechMapper.teams(json("""{"x":1}"""), "https://a/"))
    }

    @Test
    fun seasons() {
        val s = HockeyTechMapper.seasons(ChlFixtures.seasons("ohl"))!!
        assertEquals(false, s["88"]) // 2026-27 Regular Season
        assertEquals(true, s["85"]) // 2026 Playoffs
        assertEquals(73, s.size)
        assertEquals(mapOf("1" to true, "2" to false, "3" to true),
            HockeyTechMapper.seasons(json("""{"SiteKit":{"Seasons":[{"season_id":"1","playoff":"1"},{"season_id":2,"playoff":0},{"season_id":"3","playoff":true},{"season_id":"z"}]}}""")))
        assertNull(HockeyTechMapper.seasons(json("""{"SiteKit":{"Seasons":"no"}}""")))
    }

    // ------------------------------------------------------------------ §3.2 boards

    @Test
    fun recordedOhlBoard() {
        val b = ChlFixtures.mappedBoard("ohl")
        assertEquals(League.OHL, b.board.league)
        assertEquals("2026-11-13", b.board.dayKey)
        assertEquals("2026-11-12", b.board.prevDay)
        assertEquals("2026-11-14", b.board.nextDay)
        assertEquals(7, b.board.games.size)
        assertEquals(setOf("88"), b.seasonIds)
        val g = b.board.games.associateBy { it.id.id }

        val flint = g.getValue("29187")
        assertEquals(GameId(League.OHL, "29187"), flint.id)
        assertEquals(ms("2026-11-13", "19:00", "America/New_York"), flint.startUtcMs)
        assertFalse(flint.startTbd)
        assertEquals(GamePhase.LIVE, flint.phase)
        assertEquals(3, flint.period)
        assertEquals(PeriodKind.REG, flint.periodKind)
        assertEquals(GameClock(812, running = false, inIntermission = false), flint.clock)
        // Card names are the nicknames (P10); logos from the teams (JPEG swap is not needed for these two).
        assertEquals(TeamLine("OTT", "67's", 0, "https://assets.leaguestat.com/ohl/logos/5_88.png", sog = 23), flint.away)
        assertEquals(TeamLine("FLNT", "Firebirds", 4, "https://assets.leaguestat.com/ohl/logos/13.png", sog = 24), flint.home)

        // Facts keep the full names and the period lengths.
        val f = b.facts.getValue("29187")
        assertEquals("Ottawa 67's", f.away.name)
        assertEquals("Flint Firebirds", f.home.name)
        assertEquals(0, f.away.score)
        assertEquals(mapOf(1 to 1200, 2 to 1200, 3 to 1200), f.periodLengthsS)
        assertEquals(false, f.playoff)
        assertEquals(2, f.status)
        assertFalse(f.final)
        assertEquals("88", f.seasonId)

        // "00:00 2nd" is the 2nd intermission (P4: play can follow): "2nd INT".
        assertEquals(GamePhase.INTERMISSION, g.getValue("29190").phase)
        assertEquals(2, g.getValue("29190").period)
        assertEquals(GameClock(0, running = false, inIntermission = true), g.getValue("29190").clock)
        // "20:00 3rd" (S12): the operator advanced the period during the break: period 2, remaining 0.
        val pbo = g.getValue("29191")
        assertEquals(GamePhase.INTERMISSION, pbo.phase)
        assertEquals(2, pbo.period)
        assertEquals(GameClock(0, running = false, inIntermission = true), pbo.clock)
        // BRAM's JPEG logo was swapped.
        assertEquals("https://assets.leaguestat.com/ohl/logos/18.png", g.getValue("29189").away.logo)
    }

    @Test
    fun recordedWhlBoard() {
        val g = ChlFixtures.mappedBoard("whl").board.games.associateBy { it.id.id }
        assertEquals(9, g.size)
        val pre = g.getValue("1023286")
        assertEquals(GamePhase.SCHEDULED, pre.phase)
        assertNull(pre.away.score)
        assertNull(pre.home.sog)
        assertNull(pre.clock)
        assertEquals(ms("2026-11-13", "19:00", "America/Vancouver"), pre.startUtcMs)
        assertEquals(ms("2026-11-13", "19:00", "America/Winnipeg"), g.getValue("1023285").startUtcMs)
        assertEquals(GamePhase.INTERMISSION, g.getValue("1023285").phase) // "20:00 3rd"
        assertEquals(2, g.getValue("1023285").period)
        assertEquals(GamePhase.INTERMISSION, g.getValue("1023287").phase) // "00:00 1st"
        assertEquals(1, g.getValue("1023287").period)
        assertEquals(listOf("MH", "MJ", "PA", "RD", "TC"), g.values.flatMap { listOf(it.away.abbrev, it.home.abbrev) }.filter { it.length == 2 }.sorted())
    }

    @Test
    fun recordedQmjhlBoard() {
        val b = ChlFixtures.mappedBoard("lhjmq")
        val g = b.board.games.associateBy { it.id.id }
        assertEquals(6, g.size)
        // The team code "NFL" (L2) stays a team code; codes upper-cased.
        val nfl = g.getValue("32721")
        assertEquals("NFL", nfl.away.abbrev)
        assertEquals("Regiment", nfl.away.name)
        assertEquals("Newfoundland Regiment", b.facts.getValue("32721").away.name)
        assertEquals("CAP", nfl.home.abbrev)
        assertEquals(League.QMJHL, nfl.id.league)
        assertEquals(GamePhase.FINAL, nfl.phase)
        assertEquals(PeriodKind.REG, nfl.finalKind)
        // A live shootout (QMJHL 32755): P4 0:00 stopped, 4–4, regular season (§3.3 (c)). No English text is read.
        val rim = g.getValue("32755")
        assertEquals(GamePhase.LIVE, rim.phase)
        assertEquals(PeriodKind.SO, rim.periodKind)
        assertNull(rim.otNumber)
        assertEquals(mapOf(1 to 1200, 2 to 1200, 3 to 1200, 4 to 300), b.facts.getValue("32755").periodLengthsS)
        // Final SO: the +1 is in the board's totals already (5–4); never added again.
        val so = g.getValue("32917")
        assertEquals(GamePhase.FINAL, so.phase)
        assertEquals(PeriodKind.SO, so.finalKind)
        assertEquals(4, so.away.score)
        assertEquals(5, so.home.score)
        assertEquals("MON", so.home.abbrev)
        assertEquals("VDO", g.getValue("33176").home.abbrev)
        assertEquals(ms("2026-11-13", "19:00", "America/Halifax"), so.startUtcMs)
    }

    /** The playoff schedule excerpt (WHL season 293): "Final OT3" = period 6, OT number 3; read as `gamesbydate` entries. */
    @Test
    fun playoffOvertimeFinals() {
        val sched = ChlFixtures.obj("${ChlFixtures.LSCLUSTER}/schedule-whl-293-playoff-ot-excerpt.json")
        val list = (sched["SiteKit"] as JsonObject)["Schedule"] as JsonArray
        val body = buildJsonObject { put("SiteKit", buildJsonObject { put("Gamesbydate", list) }) }
        val b = HockeyTechMapper.board(League.WHL, "2026-03-27", body, null, mapOf("293" to true))!!
        val byPeriod = b.board.games.map { Triple(it.period, it.finalKind, it.otNumber) }
        assertTrue(Triple(6, PeriodKind.OT, 3) in byPeriod, "$byPeriod")
        assertTrue(Triple(5, PeriodKind.OT, 2) in byPeriod, "$byPeriod")
        assertTrue(b.board.games.all { it.phase == GamePhase.FINAL })
        assertTrue(b.facts.values.all { it.playoff == true })
        // No teams map: the board's own names, no logo.
        val first = b.board.games.first()
        assertEquals(TeamLine("BDN", "Wheat Kings", 0, null, sog = first.away.sog), first.away)
        assertEquals("Brandon Wheat Kings", b.facts.getValue(first.id.id).away.name)
    }

    private fun entry(vararg kv: Pair<String, Any?>): String {
        val base = linkedMapOf<String, Any?>(
            "game_id" to "100", "season_id" to "88", "date_played" to "2026-11-13", "schedule_time" to "19:30:00",
            "timezone" to "America/Toronto", "time_tbd" to "0", "status" to "2", "started" to "1", "final" to "0",
            "period" to "2", "game_clock" to "00:10:00", "shootout" to "0", "playoff" to "0",
            "home_team" to "10", "home_team_code" to "KIT", "home_team_city" to "Kitchener", "home_team_nickname" to "Rangers",
            "visiting_team" to "14", "visiting_team_code" to "LDN", "visiting_team_city" to "London", "visiting_team_nickname" to "Knights",
            "home_goal_count" to "2", "visiting_goal_count" to "1", "home_shots" to "20", "visiting_shots" to "18",
            "periods" to mapOf("1" to mapOf("length" to "1200"), "2" to mapOf("length" to "1200"), "3" to mapOf("length" to "1200")),
        )
        for ((k, v) in kv) base[k] = v
        return toJson(base)
    }

    private fun toJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> JsonPrimitive(v).toString()
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> "${JsonPrimitive(k.toString())}:${toJson(x)}" }
        is List<*> -> v.joinToString(",", "[", "]") { toJson(it) }
        else -> error("bad $v")
    }

    private fun boardOf(vararg entries: String, league: League = League.OHL): ChlBoard =
        HockeyTechMapper.board(league, "2026-11-13", json("""{"SiteKit":{"Gamesbydate":[${entries.joinToString(",")}]}}"""), null, null)!!

    @Test
    fun boardStatusesAndForfeit() {
        fun phase(vararg kv: Pair<String, Any?>) = boardOf(entry(*kv)).board.games.single().phase
        assertEquals(GamePhase.SCHEDULED, phase("status" to "1", "started" to "0"))
        assertEquals(GamePhase.FINAL, phase("status" to "4", "final" to "1"))
        assertEquals(GamePhase.FINAL, phase("status" to "5")) // forfeit ("Home Forfeit")
        assertEquals(GamePhase.FINAL, phase("status" to "2", "final" to "1")) // final wins
        // S6: an unknown status before the start is SCHEDULED; after it, the clock decides.
        assertEquals(GamePhase.SCHEDULED, phase("status" to "3", "started" to "0", "home_goal_count" to "0", "visiting_goal_count" to "0"))
        assertEquals(GamePhase.LIVE, phase("status" to "3", "started" to "1"))
        assertEquals(GamePhase.LIVE, phase("status" to "9", "started" to "0")) // a score means started
        assertEquals(GamePhase.SCHEDULED, phase("status" to null, "started" to null, "home_goal_count" to "0", "visiting_goal_count" to "0"))
        val forfeit = boardOf(entry("status" to "5", "home_goal_count" to "0", "visiting_goal_count" to "1")).board.games.single()
        assertEquals(PeriodKind.REG, forfeit.finalKind)
        assertEquals(1, forfeit.away.score)
    }

    @Test
    fun boardStartTimes() {
        val tbd = boardOf(entry("time_tbd" to "1"), league = League.WHL).board.games.single()
        assertTrue(tbd.startTbd)
        assertEquals(LocalDate.parse("2026-11-13").atStartOfDay(ZoneId.of("America/Vancouver")).toInstant().toEpochMilli(), tbd.startUtcMs)
        val noTime = boardOf(entry("schedule_time" to "soon")).board.games.single()
        assertTrue(noTime.startTbd)
        // Unknown zone → the league's reference zone.
        assertEquals(ms("2026-11-13", "19:30", "America/Toronto"), boardOf(entry("timezone" to "Mars/Base")).board.games.single().startUtcMs)
        assertEquals(ms("2026-11-13", "19:30", "America/Toronto"), boardOf(entry("timezone" to null)).board.games.single().startUtcMs)
        assertEquals(ms("2026-11-13", "19:30", "America/Edmonton"), boardOf(entry("timezone" to "America/Edmonton")).board.games.single().startUtcMs)
        // dayKey: date_played, else the board's date.
        assertEquals("2026-11-12", boardOf(entry("date_played" to "2026-11-12")).board.games.single().dayKey)
        assertEquals("2026-11-13", boardOf(entry("date_played" to "garbage")).board.games.single().dayKey)
    }

    @Test
    fun boardCodeGateDropsGames() {
        val b = boardOf(
            entry("game_id" to "1"),
            entry("game_id" to "2", "home_team_code" to "TOOLONG"),
            entry("game_id" to "3", "visiting_team_code" to "N-F"),
            entry("game_id" to "x4"),
            entry("game_id" to "5", "home_team_code" to " nfl "),
            "7", "null",
        )
        assertEquals(listOf("1", "5"), b.board.games.map { it.id.id })
        assertEquals("NFL", b.board.games.last().home.abbrev)
        assertEquals(setOf("1", "5"), b.facts.keys)
    }

    @Test
    fun boardLeniency() {
        val g = boardOf(
            entry(
                "period" to 2, "status" to 2, "home_goal_count" to 3, "visiting_goal_count" to "02",
                "game_clock" to "10:00", "home_shots" to "250", "visiting_shots" to 7,
                "home_team_city" to null, "home_team_nickname" to null, "home_team_name" to "Kitchener, Rangers",
            ),
        ).board.games.single()
        assertEquals(GamePhase.LIVE, g.phase)
        assertEquals(GameClock(600, running = false, inIntermission = false), g.clock)
        assertEquals(2, g.away.score)
        assertEquals(3, g.home.score)
        assertNull(g.home.sog) // outside 0..SOG_MAX
        assertEquals(7, g.away.sog)
        assertEquals("Kitchener Rangers", g.home.name) // no nickname: the full name, comma removed
        // Absent list = an empty day; not a SiteKit = unmappable.
        assertEquals(0, HockeyTechMapper.board(League.OHL, "2026-11-13", json("""{"SiteKit":{}}"""), null, null)!!.board.games.size)
        assertEquals(League.OHL, HockeyTechMapper.board(League.OHL, "2026-11-13", json("""{"SiteKit":{}}"""), null, null)!!.board.league)
        assertNull(HockeyTechMapper.board(League.OHL, "2026-11-13", json("""{"error":"x"}"""), null, null))
        assertNull(HockeyTechMapper.board(League.OHL, "2026-11-13", json("""{"SiteKit":{"Gamesbydate":"x"}}"""), null, null))
        assertNull(HockeyTechMapper.board(League.OHL, "not a day", json("""{"SiteKit":{}}"""), null, null))
    }

    // ------------------------------------------------------------------ §3.4 the clock table

    private fun classify(
        p: Int?, r: Int?, running: Boolean = false, away: Int = 1, home: Int = 2, status: Int = 2,
        regular: Boolean = true, ot: Int? = 300,
    ) = HockeyTechMapper.classify(
        status, false, true, p, r, running, away, home, false, { q -> if (q <= 3) 1200 else ot }, regular,
    )

    @Test
    fun clockTable() {
        // running
        assertEquals(GamePhase.LIVE, classify(2, 600, running = true).phase)
        // a whistle
        classify(2, 600).let { assertEquals(GamePhase.LIVE, it.phase); assertEquals(GameClock(600, false, false), it.clock) }
        // decided at 0:00 (S3): "End 3rd"
        classify(3, 0, away = 4, home = 1).let {
            assertEquals(GamePhase.LIVE, it.phase); assertTrue(it.decided); assertEquals(GameClock(0, false, false), it.clock)
        }
        // decided at 0:00 in OT: "End OT"
        classify(4, 0, away = 3, home = 2).let { assertTrue(it.decided); assertEquals(PeriodKind.OT, it.periodKind); assertEquals(1, it.otNumber) }
        // regular season, P4, tied, 0:00: a shootout (§3.3 (c))
        classify(4, 0, away = 2, home = 2).let { assertEquals(PeriodKind.SO, it.periodKind); assertTrue(it.shootout) }
        // playoffs, tied after OT1: "OT INT"
        classify(4, 0, away = 2, home = 2, regular = false, ot = 1200).let {
            assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(PeriodKind.OT, it.periodKind); assertEquals(1, it.otNumber)
        }
        // play can follow: "{P} INT"
        classify(1, 0).let { assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(1, it.period); assertEquals(GameClock(0, false, true), it.clock) }
        classify(3, 0, away = 2, home = 2).let { assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(3, it.period) }
        // S12: "20:00 3rd" → 2nd INT
        classify(3, 1200).let { assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(2, it.period); assertEquals(GameClock(0, false, true), it.clock) }
        // S12 in OT: "5:00 OT" stopped before the OT → 3rd INT (needs the OT length)
        classify(4, 300, away = 2, home = 2).let { assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(3, it.period); assertEquals(PeriodKind.REG, it.periodKind) }
        // the playoff OT2 break form: "20:00 OT2" → OT1 INT
        classify(5, 1200, away = 2, home = 2, regular = false, ot = 1200).let {
            assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(4, it.period); assertEquals(1, it.otNumber)
        }
        // "20:00 1st": status 2 before the opening faceoff
        classify(1, 1200).let { assertEquals(GamePhase.LIVE, it.phase); assertEquals(1, it.period); assertEquals(GameClock(1200, false, false), it.clock) }
        // remaining outside 0..L → no clock
        classify(4, 301).let { assertEquals(GamePhase.LIVE, it.phase); assertNull(it.clock) }
        classify(2, -1).let { assertNull(it.clock) }
        // an unknown OT length: no S12 form, the clock is kept within 0..1200
        classify(4, 300, away = 2, home = 2, ot = null).let { assertEquals(GamePhase.LIVE, it.phase); assertEquals(4, it.period) }
        // no period
        classify(null, 600).let { assertEquals(GamePhase.LIVE, it.phase); assertNull(it.period) }
        // OT numbers (verified "Final OT2" = 5, "OT3" = 6)
        assertEquals(2, classify(5, 600, regular = false, ot = 1200).otNumber)
        assertEquals(3, classify(6, 600, regular = false, ot = 1200).otNumber)
    }

    // ------------------------------------------------------------------ §3.3 followed snapshots

    private fun facts(
        gameId: String = "100",
        away: String = "LDN",
        home: String = "KIT",
        playoff: Boolean? = false,
        lengths: Map<Int, Int> = mapOf(1 to 1200, 2 to 1200, 3 to 1200),
        status: Int? = 2,
        final: Boolean = false,
    ) = ChlGameFacts(
        gameId, "88", "2026-11-13", 1_794_612_600_000L,
        TeamLine(away, "London Knights", 1, "https://assets.leaguestat.com/ohl/logos/14.png"),
        TeamLine(home, "Kitchener Rangers", 2, "https://assets.leaguestat.com/ohl/logos/10.png"),
        playoff, lengths, status, final,
    )

    private fun rc(min: Any?, sec: Any?, period: Any?, running: Boolean = false, status: Any? = 2): JsonObject =
        json("""{"Clock":{"Minutes":${toJson(min)},"Running":$running,"Seconds":${toJson(sec)},"period":${toJson(period)}},"DatePlayed":"2026-11-13","status_id":${toJson(status)}}""")

    private fun gs(away: Any?, home: Any?, shootout: Boolean = false, attempts: Int = 1): JsonObject =
        json("""{"VisitorGoalTotal":${toJson(away)},"HomeGoalTotal":${toJson(home)},"Shootout":$shootout,"HomePowerPlayAttempts":$attempts,"VisitorPowerPlayAttempts":0}""")

    private fun pc(status: Int, started: Boolean = true, final: Boolean = false, period: Int = 2): JsonObject =
        json("""{"StatusId":$status,"Started":$started,"Final":$final,"PeriodId":$period,"PeriodLongName":"zzz","ProgressString":"zzz"}""")

    private fun pens(vararg rows: String): JsonObject = json("""{"DatePlayed":"","GamePenalties":{${rows.joinToString(",")}}}""")

    private fun penRow(
        id: Long, home: Any, period: Any, time: String, minutes: Any? = 2, pp: Any = 1, start: String? = time,
        on: String? = null, player: Any = id, ps: Any = 0,
    ) = "\"$id\":" + toJson(
        mapOf(
            "LSEventId" to id, "Home" to home, "Period" to period, "Time" to time, "Minutes" to minutes, "PowerPlay" to pp,
            "PenaltyShot" to ps, "TimeStart" to (start ?: "00:00"), "TimeStartSet" to if (start == null) 0 else 1,
            "TimeOn" to (on ?: "00:00"), "TimeOnSet" to if (on == null) 0 else 1, "PenalizedPlayerId" to player, "Bench" to 0,
        ),
    )

    private fun goalsNode(vararg rows: String): JsonObject = json("""{"DatePlayed":"2026-11-13","GameGoals":{${rows.joinToString(",")}}}""")

    private fun goalRow(id: Long, home: Boolean, period: Any, time: String, pp: Int = 0, sh: Int = 0) =
        "\"$id\":" + toJson(mapOf("LSEventId" to id, "IsHome" to home, "Period" to period, "Time" to time, "PowerPlay" to pp, "ShortHanded" to sh, "EmptyNet" to 0, "PenaltyShot" to 0))

    private fun followed(
        rc: JsonObject?, gs: JsonObject? = gs(1, 2), pc: JsonObject? = null, pens: JsonObject? = pens(),
        goals: JsonObject? = goalsNode(goalRow(1, false, 1, "05:00"), goalRow(2, true, 1, "07:00"), goalRow(3, true, 2, "01:00")),
        shots: JsonObject? = null, shootouts: JsonObject? = null, f: ChlGameFacts = facts(),
        penaltiesFailed: Boolean = false, goalsFailed: Boolean = false, league: League = League.OHL,
    ) = HockeyTechMapper.followed(league, f, ChlNodes(rc, pc, gs, shots, pens, goals, shootouts, penaltiesFailed, goalsFailed))

    @Test
    fun followedLiveSnapshot() {
        val s = assertNotNull(
            followed(
                rc("10", "00", "2", running = true),
                shots = json("""{"VisitorShotTotal":"12","HomeShotTotal":30}"""),
                pens = pens(penRow(9, home = 0, period = 2, time = "09:00")),
            ),
        )
        assertEquals(GameId(League.OHL, "100"), s.id)
        assertEquals("2026-11-13", s.dayKey)
        assertEquals(1_794_612_600_000L, s.startUtcMs)
        assertEquals(GamePhase.LIVE, s.phase)
        assertEquals(2, s.period)
        assertEquals(PeriodKind.REG, s.periodKind)
        assertNull(s.otNumber)
        assertEquals(GameClock(600, running = true, inIntermission = false), s.clock)
        assertEquals(TeamLine("LDN", "London Knights", 1, "https://assets.leaguestat.com/ohl/logos/14.png", sog = 12), s.away)
        assertEquals(TeamLine("KIT", "Kitchener Rangers", 2, "https://assets.leaguestat.com/ohl/logos/10.png", sog = 30), s.home)
        assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 60), s.powerPlay)
        assertFalse(s.powerPlayUnknown)
        assertEquals(GoalFact(Side.HOME, 1, 2, pp = false), s.lastGoal)
        assertNull(s.detail)
        assertFalse(s.startTbd)
    }

    @Test
    fun followedStatuses() {
        // publishedclock first, then runningclock, then the board.
        assertEquals(GamePhase.FINAL, followed(rc("00", "00", 3, status = 2), pc = pc(4, final = true))!!.phase)
        assertEquals(GamePhase.FINAL, followed(rc("00", "00", 3, status = 4))!!.phase)
        assertEquals(GamePhase.FINAL, followed(rc("00", "00", 3, status = 2), f = facts(final = true))!!.phase)
        assertEquals(GamePhase.FINAL, followed(rc("05", "00", 3, status = 5))!!.phase) // forfeit
        assertEquals(PeriodKind.REG, followed(rc("05", "00", 3, status = 5))!!.finalKind)
        assertEquals(GamePhase.LIVE, followed(rc("05", "00", 2, status = null), f = facts(status = 2))!!.phase)
        // S6: status 3 / unknown before the start → SCHEDULED; after it → the clock.
        assertEquals(GamePhase.SCHEDULED, followed(rc("-", "-", "1", status = 3), gs = gs(0, 0), pc = pc(3, started = false), f = facts(status = 3))!!.phase)
        assertEquals(GamePhase.LIVE, followed(rc("05", "00", 2, status = 3), pc = pc(3, started = true))!!.phase)
        assertEquals(GamePhase.LIVE, followed(rc("05", "00", 2, status = 7), gs = gs(0, 1), f = facts(status = 7))!!.phase)
        assertEquals(GamePhase.SCHEDULED, followed(rc("05", "00", 2, status = 7), gs = gs(0, 0), f = facts(status = 7))!!.phase)
        // Pre-game: the clock is "-"; no score; a null summary is fine before the start.
        val pre = followed(rc("-", "-", "1", status = 1), gs = null, pc = null, f = facts(status = 1))!!
        assertEquals(GamePhase.SCHEDULED, pre.phase)
        assertNull(pre.away.score)
        assertNull(pre.clock)
        assertNull(pre.lastGoal)
        assertFalse(pre.powerPlayUnknown)
    }

    @Test
    fun followedRequiredNodes() {
        assertNull(followed(rc = null)) // runningclock
        assertNull(followed(rc("05", "00", 2), gs = null)) // goalssummary once started
        assertNull(followed(rc("05", "00", 2), gs = json("""{"VisitorGoalTotal":1}""")))
        // The code gate on the facts' lines; codes upper-cased.
        assertNull(followed(rc("05", "00", 2), f = facts(away = "TOOLONG")))
        assertEquals("CHA", followed(rc("05", "00", 2), f = facts(home = "Cha"))!!.home.abbrev)
        assertEquals("NFL", followed(rc("05", "00", 2), f = facts(away = "NFL"), league = League.QMJHL)!!.away.abbrev)
        assertNull(followed(rc("05", "00", 2), f = facts(gameId = "abc")))
        // Optional nodes: no publishedclock → runningclock's status; no shots → sog null.
        val s = followed(rc("05", "00", 2), pc = null, shots = null)!!
        assertEquals(GamePhase.LIVE, s.phase)
        assertNull(s.away.sog)
        assertEquals(300, s.clock!!.secondsRemaining)
    }

    @Test
    fun followedLeniency() {
        // period int or string, Minutes / Seconds as strings or ints, status as a string.
        val a = followed(rc("01", "15", 2))!!
        val b = followed(rc(1, 15, "2", status = "2"))!!
        assertEquals(a, b)
        assertEquals(75, a.clock!!.secondsRemaining)
        // No clock period: publishedclock's PeriodId.
        assertEquals(3, followed(rc("05", "00", null), pc = pc(2, period = 3))!!.period)
        // A clock beyond the period: no clock, and the power play is unknown (the mapper's H1).
        val bad = followed(rc("21", "00", 2))!!
        assertNull(bad.clock)
        assertTrue(bad.powerPlayUnknown)
        assertNull(bad.powerPlay)
        // Seconds out of range.
        assertNull(followed(rc("01", "75", 2))!!.clock)
    }

    @Test
    fun followedBreaksAndEnds() {
        // "20:00 3rd" (S12): 2nd INT, period 2, remaining 0, and the PP frozen at the buzzer (E7 through the mapper).
        val brk = followed(rc("20", "00", 3), pens = pens(penRow(9, home = 0, period = 2, time = "19:30")))!!
        assertEquals(GamePhase.INTERMISSION, brk.phase)
        assertEquals(2, brk.period)
        assertEquals(GameClock(0, running = false, inIntermission = true), brk.clock)
        assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 90), brk.powerPlay)
        // E33 (S3): P3 0:00 stopped, status 2, 4–1, a flagged minor at 19:08: "End 3rd", no power play.
        val end = followed(
            rc("00", "00", 3), gs = gs(4, 1), pens = pens(penRow(9, home = 0, period = 3, time = "19:08")),
            goals = goalsNode(goalRow(1, false, 1, "01:00"), goalRow(2, false, 1, "02:00"), goalRow(3, false, 2, "03:00"), goalRow(4, false, 3, "04:00"), goalRow(5, true, 3, "05:00")),
        )!!
        assertEquals(GamePhase.LIVE, end.phase)
        assertEquals(3, end.period)
        assertEquals(GameClock(0, running = false, inIntermission = false), end.clock)
        assertNull(end.powerPlay)
        assertFalse(end.powerPlayUnknown)
        // "End OT": P4 0:00, 3–2.
        val endOt = followed(rc("00", "00", 4), gs = gs(3, 2), goals = null)!!
        assertEquals(GamePhase.LIVE, endOt.phase)
        assertEquals(PeriodKind.OT, endOt.periodKind)
        assertNull(endOt.powerPlay)
        assertFalse(endOt.powerPlayUnknown)
        // Sudden death: an OT whistle after the winning goal shows no power play either.
        val otGoal = followed(rc("02", "13", 4), gs = gs(3, 2), goals = null, pens = pens(penRow(9, home = 0, period = 4, time = "01:00")))!!
        assertNull(otGoal.powerPlay)
        assertFalse(otGoal.powerPlayUnknown)
        // Tied after the 3rd in the regular season: 3rd INT; P4 tied 0:00: a shootout (c); P4 "5:00" stopped: 3rd INT.
        assertEquals(GamePhase.INTERMISSION, followed(rc("00", "00", 3), gs = gs(2, 2), goals = null)!!.phase)
        assertEquals(PeriodKind.SO, followed(rc("00", "00", 4), gs = gs(2, 2), goals = null)!!.periodKind)
        followed(rc("05", "00", 4), gs = gs(2, 2), goals = null, f = facts(lengths = mapOf(4 to 300)))!!.let {
            assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(3, it.period)
        }
        // Playoffs, tied after OT1: OT INT, never a shootout.
        followed(rc("00", "00", 4), gs = gs(2, 2), goals = null, f = facts(playoff = true))!!.let {
            assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(PeriodKind.OT, it.periodKind)
        }
    }

    @Test
    fun followedShootoutsWithoutEnglishText() {
        // QMJHL 32755 at 20:56: a live shootout. Shootout (a) is false; (b) attempts; (c) P4 0:00 tied, regular season.
        val b = ChlFixtures.mappedBoard("lhjmq")
        val f = b.facts.getValue("32755")
        val rc = ChlFixtures.obj("${ChlFixtures.LIVE}/lhjmq_runningclock-2056.json")["32755"] as JsonObject
        val pc = ChlFixtures.obj("${ChlFixtures.LIVE}/lhjmq_publishedclock-2056.json")["32755"] as JsonObject
        val gs = ChlFixtures.finalsGame("lhjmq", "goalssummary", "32755")!!
        val so = ChlFixtures.finalsGame("lhjmq", "shootouts", "32755")!!
        fun snap(shootouts: JsonObject?, pcBody: JsonObject? = pc) = HockeyTechMapper.followed(
            League.QMJHL, f,
            ChlNodes(rc, pcBody, gs, ChlFixtures.finalsGame("lhjmq", "shotssummary", "32755"),
                ChlFixtures.finalsGame("lhjmq", "penalties", "32755"), ChlFixtures.finalsGame("lhjmq", "goals", "32755"), shootouts),
        )!!
        for (s in listOf(snap(so), snap(null))) {
            assertEquals(GamePhase.LIVE, s.phase)
            assertEquals(PeriodKind.SO, s.periodKind)
            assertNull(s.otNumber)
            assertNull(s.powerPlay)
            assertFalse(s.powerPlayUnknown)
            assertEquals(4, s.away.score)
            assertEquals(4, s.home.score)
            assertEquals("Rimouski Océanic", s.away.name)
        }
        // The English fields are never read: garbage in PeriodLongName / ProgressString / StatusName changes nothing.
        val garbled = JsonObject(pc + mapOf("PeriodLongName" to JsonPrimitive("??"), "ProgressString" to JsonPrimitive("Final"), "StatusName" to JsonPrimitive("Final")))
        assertEquals(snap(so), snap(so, garbled))
        // (b) alone, in a playoff-like OT length: attempts make it a shootout.
        val fPlayoff = f.copy(playoff = null, periodLengthsS = mapOf(4 to 1200))
        val s = HockeyTechMapper.followed(League.QMJHL, fPlayoff, ChlNodes(rc, pc, gs, null, null, null, so))!!
        assertEquals(PeriodKind.SO, s.periodKind)
    }

    @Test
    fun recordedFinals() {
        // QMJHL 32917 Final SO: Shootout (a) true; 4–5 already carries the +1 (never added again); lastGoal null.
        val q = ChlFixtures.mappedBoard("lhjmq")
        val so = HockeyTechMapper.followed(
            League.QMJHL, q.facts.getValue("32917"),
            ChlNodes(
                ChlFixtures.finalsGame("lhjmq", "runningclock", "32917"), ChlFixtures.finalsGame("lhjmq", "publishedclock", "32917"),
                ChlFixtures.finalsGame("lhjmq", "goalssummary", "32917"), ChlFixtures.finalsGame("lhjmq", "shotssummary", "32917"),
                ChlFixtures.finalsGame("lhjmq", "penalties", "32917"), ChlFixtures.finalsGame("lhjmq", "goals", "32917"),
                ChlFixtures.finalsGame("lhjmq", "shootouts", "32917"),
            ),
        )!!
        assertEquals(GamePhase.FINAL, so.phase)
        assertEquals(PeriodKind.SO, so.finalKind)
        assertEquals(4, so.away.score)
        assertEquals(5, so.home.score)
        assertNull(so.lastGoal) // 8 goals on record, 9 in the total
        assertNull(so.powerPlay)
        assertFalse(so.powerPlayUnknown)

        // WHL 1023275 Final OT (period "4" as a string), OT number 1; the last goal is the OT winner.
        val whl = recordedFollowed("whl", "1023275", "EVT", "SPO")
        assertEquals(GamePhase.FINAL, whl.phase)
        assertEquals(PeriodKind.OT, whl.finalKind)
        assertEquals(1, whl.otNumber)
        assertEquals(5, whl.away.score)
        assertEquals(4, whl.home.score)
        assertEquals(GoalFact(Side.AWAY, 5, 4, pp = false), whl.lastGoal)
        assertEquals(34, whl.away.sog)

        // OHL 29181 Final OT.
        assertEquals(PeriodKind.OT, recordedFollowed("ohl", "29181", "AAA", "BBB").finalKind)
        // OHL 29179 Final in regulation.
        recordedFollowed("ohl", "29179", "AAA", "BBB").let { assertEquals(PeriodKind.REG, it.finalKind); assertNull(it.otNumber) }
    }

    private fun recordedFollowed(client: String, gid: String, away: String, home: String): GameSnapshot {
        val node = { n: String -> ChlFixtures.finalsGame(client, n, gid) }
        return HockeyTechMapper.followed(
            ChlFixtures.league(client), facts(gameId = gid, away = away, home = home),
            ChlNodes(node("runningclock"), node("publishedclock"), node("goalssummary"), node("shotssummary"), node("penalties"), node("goals")),
        )!!
    }

    @Test
    fun h8_aFailedNodeIsUnknown() {
        val live = rc("10", "00", 2)
        val p = pens(penRow(9, home = 0, period = 2, time = "09:00"))
        assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 60), followed(live, pens = p)!!.powerPlay)
        // E26: penalties failed this poll.
        followed(live, pens = null, penaltiesFailed = true)!!.let { assertTrue(it.powerPlayUnknown); assertNull(it.powerPlay) }
        // goals failed: unknown, and no last goal.
        followed(live, pens = p, goals = null, goalsFailed = true)!!.let {
            assertTrue(it.powerPlayUnknown); assertNull(it.powerPlay); assertNull(it.lastGoal)
        }
        // A null node is not a failure: E27 / H6 / H7 decide.
        assertFalse(followed(live, pens = null, gs = gs(1, 2, attempts = 0))!!.powerPlayUnknown) // E27
        assertTrue(followed(live, pens = null, gs = gs(1, 2, attempts = 3))!!.powerPlayUnknown) // E28 / H7
        assertTrue(followed(live, pens = p, goals = null)!!.powerPlayUnknown) // H6
        // A 304 is the body it confirmed: the same snapshot.
        assertEquals(followed(live, pens = p), followed(live, pens = p))
        // Out of play, a failure holds nothing.
        assertFalse(followed(rc("00", "00", 3, status = 4), penaltiesFailed = true)!!.powerPlayUnknown)
    }

    @Test
    fun penaltyRowsThatCannotBePlaced() {
        val live = rc("10", "00", 2)
        // A flagged row without a side (or period) → unknown.
        assertTrue(followed(live, pens = pens(penRow(9, home = "x", period = 2, time = "09:00")))!!.powerPlayUnknown)
        assertTrue(followed(live, pens = pens(penRow(9, home = 0, period = "?", time = "09:00")))!!.powerPlayUnknown)
        // An unparsable time on a flagged row → H2.
        assertTrue(followed(live, pens = pens(penRow(9, home = 0, period = 2, time = "9:0x", start = null)))!!.powerPlayUnknown)
        // An unflagged misconduct with no side is not needed.
        assertFalse(followed(live, pens = pens(penRow(9, home = "x", period = 2, time = "09:00", minutes = 10, pp = 0)))!!.powerPlayUnknown)
        // Booleans and ints in every form: "1", 1, true.
        val forms = listOf(penRow(9, home = "0", period = "2", time = "09:00", pp = "1"), penRow(9, home = false, period = 2, time = "09:00", pp = true))
        for (r in forms) assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 60), followed(live, pens = pens(r))!!.powerPlay, r)
        // PenalizedPlayerId 0 = a bench minor (never chained): two at one stoppage are two seats.
        val bench = pens(penRow(8, home = 0, period = 2, time = "09:00", player = 0), penRow(9, home = 0, period = 2, time = "09:00", player = 0))
        assertEquals(PowerPlayKind.TWO_MAN, followed(live, pens = bench)!!.powerPlay!!.kind)
        val chained = pens(penRow(8, home = 0, period = 2, time = "09:00", player = 7), penRow(9, home = 0, period = 2, time = "09:00", player = 7))
        assertEquals(PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 180), followed(live, pens = chained)!!.powerPlay)
    }

    @Test
    fun parsePenaltiesFields() {
        val p = HockeyTechMapper.parsePenalties(
            pens(
                penRow(5, home = 1, period = 1, time = "05:36", start = "07:36", on = "09:36", player = 8774),
                penRow(6, home = 0, period = 1, time = "06:32", start = null, player = 0),
            ),
        )
        assertFalse(p.unplaceable)
        assertEquals(
            listOf(
                ChlPenalty(5, home = true, period = 1, timeS = 336, startS = 456, onS = 576, minutes = 2, ppFlag = true, penaltyShot = false, playerId = 8774),
                ChlPenalty(6, home = false, period = 1, timeS = 392, startS = null, onS = null, minutes = 2, ppFlag = true, penaltyShot = false, playerId = null),
            ),
            p.rows,
        )
        // Firebase's array form, with null holes.
        val arr = HockeyTechMapper.parsePenalties(json("""{"GamePenalties":[null,{"LSEventId":1,"Home":1,"Period":1,"Time":"01:00","Minutes":2,"PowerPlay":1}]}"""))
        assertEquals(1, arr.rows.size)
        assertEquals(60, arr.rows.single().timeS)
        assertNull(arr.rows.single().startS) // no TimeStart
        assertEquals(0, HockeyTechMapper.parsePenalties(json("""{"DatePlayed":""}""")).rows.size)
    }

    @Test
    fun lastGoal() {
        val node = goalsNode(goalRow(11, false, 1, "05:00"), goalRow(9, true, 2, "01:00", pp = 1), goalRow(10, true, 1, "19:59"))
        assertEquals(GoalFact(Side.HOME, 1, 2, pp = true), HockeyTechMapper.lastGoal(node, 1, 2))
        // Ties on time: the greater LSEventId.
        val tie = goalsNode(goalRow(20, false, 1, "05:00"), goalRow(21, true, 1, "05:00"))
        assertEquals(Side.HOME, HockeyTechMapper.lastGoal(tie, 1, 1)!!.side)
        // Counts must equal the score (a node behind the summary, or a shootout's +1).
        assertNull(HockeyTechMapper.lastGoal(node, 1, 3))
        assertNull(HockeyTechMapper.lastGoal(node, 2, 2))
        // A goal that does not parse: no last goal.
        assertNull(HockeyTechMapper.lastGoal(goalsNode(goalRow(1, true, 1, "xx")), 0, 1))
        assertNull(HockeyTechMapper.lastGoal(goalsNode(), 0, 0))
        assertNull(HockeyTechMapper.lastGoal(null, 0, 0))
    }

    // ------------------------------------------------------------------ §2.3 the Today overlay (B4)

    @Test
    fun overlayReplacesClockStatusAndScores() {
        val b = ChlFixtures.mappedBoard("ohl")
        val rc = ChlFixtures.obj("${ChlFixtures.LIVE}/ohl_runningclock-2056.json")
        val gs = ChlFixtures.finals("ohl", "goalssummary") // the whole node: games of other dates are ignored
        val o = HockeyTechMapper.overlay(b, rc, gs)
        assertEquals(b.facts, o.facts)
        assertEquals(b.seasonIds, o.seasonIds)
        assertEquals(b.board.dayKey, o.board.dayKey)
        assertEquals(b.board.league, o.board.league)
        val g = o.board.games.associateBy { it.id.id }
        // 29188 running 16:44 3rd.
        assertEquals(GameClock(1004, running = true, inIntermission = false), g.getValue("29188").clock)
        assertEquals(GamePhase.LIVE, g.getValue("29188").phase)
        val gs29188 = gs["29188"] as JsonObject
        assertEquals((gs29188["VisitorGoalTotal"] as JsonPrimitive).content.toInt(), g.getValue("29188").away.score)
        assertEquals((gs29188["HomeGoalTotal"] as JsonPrimitive).content.toInt(), g.getValue("29188").home.score)
        // 29191 "20:00 3rd" → 2nd INT; 29190 "00:00 2nd" → 2nd INT.
        assertEquals(GamePhase.INTERMISSION, g.getValue("29191").phase)
        assertEquals(2, g.getValue("29191").period)
        assertEquals(GamePhase.INTERMISSION, g.getValue("29190").phase)
        // Names, codes and logos stay the board's.
        assertEquals(b.board.games.map { it.away.name }, o.board.games.map { it.away.name })
        assertEquals(b.board.games.map { it.home.logo }, o.board.games.map { it.home.logo })
    }

    @Test
    fun overlayUsesTheFacts() {
        // A tied 0:00 period 4 is a shootout in the regular season and an intermission in the playoffs (B4).
        val reg = boardOf(entry("game_id" to "7", "period" to "3", "game_clock" to "00:05:00"))
        val q = json("""{"7":{"Clock":{"Minutes":"00","Seconds":"00","Running":false,"period":4},"DatePlayed":"2026-11-13","status_id":2}}""")
        val tie = json("""{"7":{"VisitorGoalTotal":2,"HomeGoalTotal":2,"Shootout":false,"DatePlayed":"2026-11-13"}}""")
        assertEquals(PeriodKind.SO, HockeyTechMapper.overlay(reg, q, tie).board.games.single().periodKind)
        val playoff = reg.copy(facts = reg.facts.mapValues { it.value.copy(playoff = true) })
        HockeyTechMapper.overlay(playoff, q, tie).board.games.single().let {
            assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(PeriodKind.OT, it.periodKind)
        }
        // The "5:00 OT" break form needs the OT length.
        val ot = json("""{"7":{"Clock":{"Minutes":"05","Seconds":"00","Running":false,"period":4},"DatePlayed":"2026-11-13","status_id":2}}""")
        HockeyTechMapper.overlay(reg, ot, tie).board.games.single().let { assertEquals(GamePhase.INTERMISSION, it.phase); assertEquals(3, it.period) }
        // Scores only: the board's clock stays.
        val scores = HockeyTechMapper.overlay(reg, null, json("""{"7":{"VisitorGoalTotal":5,"HomeGoalTotal":0}}""")).board.games.single()
        assertEquals(5, scores.away.score)
        assertEquals(reg.board.games.single().clock, scores.clock)
        // Another day's entry, an unknown id, a malformed entry: ignored.
        val other = json("""{"7":{"Clock":{"Minutes":"10","Seconds":"00","Running":true,"period":1},"DatePlayed":"2026-11-12","status_id":2},"8":{"x":1}}""")
        assertEquals(reg.board.games, HockeyTechMapper.overlay(reg, other, json("""{"7":[1,2]}""")).board.games)
        assertEquals(reg, HockeyTechMapper.overlay(reg, null, null))
        // A board game becomes final.
        val fin = json("""{"7":{"Clock":{"Minutes":"00","Seconds":"00","Running":false,"period":3},"DatePlayed":"2026-11-13","status_id":4}}""")
        assertEquals(GamePhase.FINAL, HockeyTechMapper.overlay(reg, fin, null).board.games.single().phase)
        // A scheduled game that has started.
        val sched = boardOf(entry("game_id" to "7", "status" to "1", "started" to "0", "home_goal_count" to "0", "visiting_goal_count" to "0"))
        assertEquals(GamePhase.SCHEDULED, sched.board.games.single().phase)
        val started = HockeyTechMapper.overlay(sched, json("""{"7":{"Clock":{"Minutes":"19","Seconds":"40","Running":true,"period":1},"status_id":2}}"""), json("""{"7":{"VisitorGoalTotal":0,"HomeGoalTotal":0}}"""))
        started.board.games.single().let { assertEquals(GamePhase.LIVE, it.phase); assertEquals(0, it.away.score) }
    }

    // ------------------------------------------------------------------ §2.4 wantsShootouts (L6)

    @Test
    fun wantsShootouts() {
        val f = facts()
        val stopped0 = GameClock(0, running = false, inIntermission = false)
        val tiedP4 = SportsFixtures.chlGame(
            period = 4, periodKind = PeriodKind.OT, otNumber = 1, clock = stopped0,
            away = SportsFixtures.chlTeam("LDN", "London Knights", 3), home = SportsFixtures.chlTeam("KIT", "Kitchener Rangers", 3),
        )
        assertFalse(HockeyTechMapper.wantsShootouts(null, f))
        assertTrue(HockeyTechMapper.wantsShootouts(tiedP4, f)) // (c)
        assertTrue(HockeyTechMapper.wantsShootouts(tiedP4.copy(periodKind = PeriodKind.SO), f.copy(playoff = true)))
        assertFalse(HockeyTechMapper.wantsShootouts(tiedP4, f.copy(playoff = true)))
        assertFalse(HockeyTechMapper.wantsShootouts(tiedP4.copy(clock = stopped0.copy(secondsRemaining = 12)), f))
        assertFalse(HockeyTechMapper.wantsShootouts(tiedP4.copy(clock = stopped0.copy(running = true)), f))
        assertFalse(HockeyTechMapper.wantsShootouts(tiedP4.copy(period = 3), f))
        assertFalse(HockeyTechMapper.wantsShootouts(tiedP4.copy(home = tiedP4.home.copy(score = 4)), f))
        assertFalse(HockeyTechMapper.wantsShootouts(tiedP4.copy(phase = GamePhase.FINAL, periodKind = PeriodKind.SO), f))
        assertFalse(HockeyTechMapper.wantsShootouts(SportsFixtures.chlGame(), f))
        // The mapper's own snapshot of a live shootout asks for the node.
        val so = followed(rc("00", "00", 4), gs = gs(2, 2), goals = null)!!
        assertTrue(HockeyTechMapper.wantsShootouts(so, f))
    }
}
