package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.hub.ChlHubFixtures.GOALIES_OHL_T14
import app.thebox.core.sports.hub.ChlHubFixtures.GOALIES_QMJHL_T2
import app.thebox.core.sports.hub.ChlHubFixtures.GOALIES_WHL_T206
import app.thebox.core.sports.hub.ChlHubFixtures.PREVIEW_AFTER_FINAL_OHL_29239
import app.thebox.core.sports.hub.ChlHubFixtures.PREVIEW_OHL_29246
import app.thebox.core.sports.hub.ChlHubFixtures.PREVIEW_QMJHL_32953
import app.thebox.core.sports.hub.ChlHubFixtures.PREVIEW_WHL_1023348
import app.thebox.core.sports.hub.ChlHubFixtures.SUMMARY_OHL_29239
import app.thebox.core.sports.hub.ChlHubFixtures.SUMMARY_OT_QMJHL_32952
import app.thebox.core.sports.hub.ChlHubFixtures.SUMMARY_PRE_OHL_29246
import app.thebox.core.sports.hub.ChlHubFixtures.SUMMARY_WHL_1023337
import app.thebox.core.sports.hub.ChlHubFixtures.TABLE_OHL
import app.thebox.core.sports.hub.ChlHubFixtures.TABLE_QMJHL
import app.thebox.core.sports.hub.ChlHubFixtures.TABLE_SPECIAL_OHL
import app.thebox.core.sports.hub.ChlHubFixtures.TABLE_SPECIAL_WHL
import app.thebox.core.sports.hub.ChlHubFixtures.obj
import app.thebox.core.sports.hub.ChlHubFixtures.table
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (CHL hub P1, docs/design/m6-chl-hub.md §2–§3, §4.3, §8; review B1, B2, B4, S1, S2) [ChlDetailMapper] against the six
 * fixture games (equal to the frozen `ChlHubFixtures` fakes), the design's worked examples restated by hand from the
 * fixture bodies, and small inline bodies for every tolerance the design and the review list.
 */
class ChlDetailMapperTest {
    private val ohl29246 = GameId(League.OHL, "29246")
    private val whl1023348 = GameId(League.WHL, "1023348")
    private val qmjhl32953 = GameId(League.QMJHL, "32953")
    private val ohl29239 = GameId(League.OHL, "29239")
    private val whl1023337 = GameId(League.WHL, "1023337")
    private val qmjhl32952 = GameId(League.QMJHL, "32952")

    private val shotOhl = ChlHubFixtures.HEADSHOT(League.OHL)

    private fun preOhl(ranks: ChlRanks? = ohlRanks(), playoff: Boolean = false, pv: JsonObject = obj(PREVIEW_OHL_29246)) =
        ChlDetailMapper.preview(ohl29246, pv, null, obj(GOALIES_OHL_T14), ranks, playoff, shotOhl)!!

    private fun ohlRanks() = ChlDetailMapper.ranks(table(TABLE_OHL), table(TABLE_SPECIAL_OHL))!!

    private fun finalOhl() = ChlDetailMapper.summary(ohl29239, obj(SUMMARY_OHL_29239), shotOhl)!!

    private fun finalQmjhl() = ChlDetailMapper.summary(qmjhl32952, obj(SUMMARY_OT_QMJHL_32952), ChlHubFixtures.HEADSHOT(League.QMJHL))!!

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun arr(s: String): JsonArray = Json.parseToJsonElement(s).jsonArray

    // ------------------------------------------------------------------ the six fixture games equal the frozen fakes

    @Test fun preOhl29246EqualsTheFake() {
        assertEquals(ChlHubFixtures.preOhl29246(), preOhl())
    }

    @Test fun preWhl1023348EqualsTheFake() {
        // The WHL standard table was not captured: special only, so PP/PK ranked and GF/GA not.
        val ranks = ChlDetailMapper.ranks(null, table(TABLE_SPECIAL_WHL))
        val got = ChlDetailMapper.preview(
            whl1023348, obj(PREVIEW_WHL_1023348), null, obj(GOALIES_WHL_T206), ranks, false, ChlHubFixtures.HEADSHOT(League.WHL),
        )
        assertEquals(ChlHubFixtures.preWhl1023348(), got)
    }

    @Test fun preQmjhl32953EqualsTheFake() {
        // The QMJHL special table was not captured: standard only, so GF/GA ranked and PP/PK not.
        val ranks = ChlDetailMapper.ranks(table(TABLE_QMJHL), null)
        val got = ChlDetailMapper.preview(
            qmjhl32953, obj(PREVIEW_QMJHL_32953), null, obj(GOALIES_QMJHL_T2), ranks, false, ChlHubFixtures.HEADSHOT(League.QMJHL),
        )
        assertEquals(ChlHubFixtures.preQmjhl32953(), got)
    }

    @Test fun finalOhl29239EqualsTheFakes() {
        val (detail, box) = finalOhl()
        assertEquals(ChlHubFixtures.finalOhl29239(), detail)
        assertEquals(ChlHubFixtures.boxOhl29239(), box)
    }

    @Test fun finalWhl1023337EqualsTheFakes() {
        val (detail, box) = ChlDetailMapper.summary(whl1023337, obj(SUMMARY_WHL_1023337), ChlHubFixtures.HEADSHOT(League.WHL))!!
        assertEquals(ChlHubFixtures.finalWhl1023337(), detail)
        assertEquals(ChlHubFixtures.boxWhl1023337(), box)
    }

    @Test fun finalOtQmjhl32952EqualsTheFakes() {
        val (detail, box) = finalQmjhl()
        assertEquals(ChlHubFixtures.finalOtQmjhl32952(), detail)
        assertEquals(ChlHubFixtures.boxOtQmjhl32952(), box)
    }

    // ------------------------------------------------------------------ §2.1 worked examples (by hand from the bodies)

    @Test fun ohl29246SeasonRows() {
        val s = preOhl().season!!
        // NIAG: 10 W, 10 L, 3 OTL, 0 SOL; GF 71 / 23, GA 93 / 23; PP 15 / 88; PK 1 − 23 / 79.
        assertEquals("10-10-3-0", s.awayRecord)
        assertEquals(Last10("3-5-2-0", null, null), s.awayLast10)
        assertEquals(Ranked(71.0 / 23, 15), s.awayGf)
        assertEquals(Ranked(93.0 / 23, 18), s.awayGa)
        assertEquals(Ranked(15.0 / 88, 19), s.awayPp)
        assertEquals(Ranked(1.0 - 23.0 / 79, 18), s.awayPk)
        // LDN: 13-8-1-2; GF 90 / 24 = 3.75 (8th); GA 87 / 24 = 3.625 → "3.63" (12th); PP 19 / 87 (9th); PK 1 − 32 / 101 (20th).
        assertEquals("13-8-1-2", s.homeRecord)
        assertEquals(Last10("4-5-0-1", null, null), s.homeLast10)
        assertEquals(Ranked(3.75, 8), s.homeGf)
        assertEquals(Ranked(3.625, 12), s.homeGa)
        assertEquals(Ranked(19.0 / 87, 9), s.homePp)
        assertEquals(Ranked(1.0 - 32.0 / 101, 20), s.homePk)
        assertEquals("3.63", String.format(java.util.Locale.ROOT, "%.2f", java.math.BigDecimal(s.homeGa!!.value).setScale(2, java.math.RoundingMode.HALF_UP)))
        // Rates are 0..1.
        listOf(s.awayPp, s.homePp, s.awayPk, s.homePk).forEach { assertTrue(it!!.value in 0.0..1.0) }
    }

    @Test fun ldnAndSooShareNinthOnThePowerPlayNotTheFeedsTenth() {
        val r = ohlRanks()
        // LDN (14) and SOO (16) are both 19/87; the feed's `rank` says 9 and 10. Both are 9th; KGN (2, 20/93) is next, 11th.
        assertEquals(9, r.pp["14"])
        assertEquals(9, r.pp["16"])
        assertEquals(11, r.pp["2"])
        assertFalse(r.pp.values.contains(10))
        assertEquals(20, r.pp.size)
        // The top of each table, by hand: GF GUE 105/24 1st; GA BFD 67/25 1st; PP SAR 30/101 1st; PK BFD 12/103 1st.
        assertEquals(1, r.gf["9"])
        assertEquals(1, r.ga["1"])
        assertEquals(1, r.pp["15"])
        assertEquals(1, r.pk["1"])
    }

    @Test fun whlAndQmjhlWorkedRanks() {
        val whl = ChlDetailMapper.ranks(null, table(TABLE_SPECIAL_WHL))!!
        assertEquals(mapOf("202" to 5, "206" to 8), whl.pp.filterKeys { it in setOf("202", "206") }) // CGY 25.3% 5th, MH 24.6% 8th
        assertEquals(mapOf("202" to 6, "206" to 4), whl.pk.filterKeys { it in setOf("202", "206") }) // CGY 82.2% 6th, MH 82.5% 4th
        assertTrue(whl.gf.isEmpty() && whl.ga.isEmpty())
        val q = ChlDetailMapper.ranks(table(TABLE_QMJHL), null)!!
        assertEquals(7, q.gf["10"]) // Chi 80/22 = 3.64
        assertEquals(9, q.ga["10"]) // Chi 74/22 = 3.36
        assertEquals(11, q.gf["2"]) // NFL 88/26 = 3.38
        assertEquals(7, q.ga["2"]) // NFL 82/26 = 3.15
        assertTrue(q.pp.isEmpty() && q.pk.isEmpty())
    }

    @Test fun theQmjhlRoundingEdgeIsTheRawFraction() {
        val q = ChlDetailMapper.preview(qmjhl32953, obj(PREVIEW_QMJHL_32953), null, null, null, false) { null }!!
        assertEquals(17.0 / 80, q.season!!.awayPp!!.value) // 0.2125: the UI rounds, not the mapper
        assertNull(q.season!!.awayPp!!.rank)
    }

    // ------------------------------------------------------------------ §2.2 summary lines

    @Test fun seriesLines() {
        assertEquals(SeriesFacts(0, 0, 0), preOhl().series) // "First meeting this season"
        val whl = ChlDetailMapper.preview(whl1023348, obj(PREVIEW_WHL_1023348), null, null, null, false) { null }!!
        assertEquals(SeriesFacts(2, 1, 3), whl.series) // "CGY leads 2–1": CGY is the visitor
        val q = ChlDetailMapper.preview(qmjhl32953, obj(PREVIEW_QMJHL_32953), null, null, null, false) { null }!!
        assertEquals(SeriesFacts(1, 0, 1), q.series) // "Chi leads 1–0": the OT win is in `w`
    }

    @Test fun pointsLeadersAreEntryZeroOfTheSeason() {
        val d = preOhl()
        assertTrue(d.leadersSeason)
        val p = d.leaders.single()
        assertEquals("points", p.category)
        assertEquals("Edgar" to 22, p.away!!.name.substringAfter(". ") to p.away!!.value) // Edgar/Smith tie → [0]
        assertEquals("Zocco" to 27, p.home!!.name.substringAfter(". ") to p.home!!.value) // Zocco/Brown tie → [0]
        val whl = ChlDetailMapper.preview(whl1023348, obj(PREVIEW_WHL_1023348), null, null, null, false) { null }!!
        assertEquals(listOf("C. Sanderson" to 31, "M. Ruck" to 33), whl.leaders.single().let { listOf(it.away!!, it.home!!) }.map { it.name to it.value })
        val q = ChlDetailMapper.preview(qmjhl32953, obj(PREVIEW_QMJHL_32953), null, null, null, false) { null }!!
        assertEquals(listOf("N. Lecompte" to 29, "T. Goguen" to 27), q.leaders.single().let { listOf(it.away!!, it.home!!) }.map { it.name to it.value })
    }

    @Test fun ohlFinalGameWinnerAndStars() {
        val d = finalOhl().first
        assertEquals(GamePhase.FINAL, d.fetchedPhase)
        val gw = d.goals.first { it.gameWinning }
        // "Game-winning goal: Dietsch (2nd) from Houben, 3rd period 10:53."
        assertEquals(PeriodLabel(PeriodKind.REG, 3), gw.period)
        assertEquals("10:53", gw.timeInPeriod)
        assertEquals("Dietsch", gw.scorerLast)
        assertEquals(2, gw.scorerTotal)
        assertEquals(listOf("Houben"), gw.assistsLast)
        assertEquals(listOf("Manza", "Bowes", "Dietsch"), d.stars.map { it.lastName })
        assertEquals(listOf(1, 2, 3), d.stars.map { it.rank })
        assertEquals("Sadlon Arena", d.venue)
        assertEquals(2801, d.attendance)
        assertNull(d.awayScore)
        assertNull(d.homeScore)
        assertNull(d.serverDateMs)
    }

    @Test fun whlAndQmjhlFinalGameWinners() {
        val whl = ChlDetailMapper.summary(whl1023337, obj(SUMMARY_WHL_1023337)) { null }!!.first
        val w = whl.goals.single { it.gameWinning }
        // "Hamilton (17th) from Andersen and Kuzma, 3rd period 19:25."
        assertEquals(listOf("Hamilton", "17", "Andersen,Kuzma", "19:25", "REG3", "PP"), listOf(w.scorerLast, "${w.scorerTotal}", w.assistsLast.joinToString(","), w.timeInPeriod, "${w.period.kind}${w.period.number}", "${w.strength}"))
        assertEquals(listOf("Hamilton", "Kuzma", "Anaka"), whl.stars.map { it.lastName })
        assertEquals("Scotiabank Saddledome - Calgary, AB", whl.venue)
        assertEquals(2226, whl.attendance)
        val q = finalQmjhl().first
        val o = q.goals.single { it.gameWinning }
        // "Dubé (4th) from Dufour and Hartl, OT period 4:16."
        assertEquals(PeriodLabel(PeriodKind.OT, 1), o.period)
        assertEquals(listOf("Dubé", "4", "Dufour,Hartl", "4:16"), listOf(o.scorerLast, "${o.scorerTotal}", o.assistsLast.joinToString(","), o.timeInPeriod))
        assertEquals(listOf("Dubé", "Lecompte", "Norman"), q.stars.map { it.lastName })
        assertEquals(listOf("CHI", "CHI", "NFL"), q.stars.map { it.team }) // the QMJHL's "Chi" upper-cased
        assertEquals("Mary Brown's Centre", q.venue)
        assertEquals(4852, q.attendance)
    }

    // ------------------------------------------------------------------ §2.1 final team stats

    @Test fun finalTeamStatRows() {
        val ohl = finalOhl().first.gameStats
        assertEquals(listOf(StatKind.SOG, StatKind.POWER_PLAY, StatKind.FACEOFF_PCT, StatKind.PIM), ohl.map { it.kind }) // no hits in the OHL
        assertEquals(StatRow(StatKind.SOG, StatValue.Count(38), StatValue.Count(21)), ohl[0])
        assertEquals(StatRow(StatKind.POWER_PLAY, StatValue.Fraction(2, 4), StatValue.Fraction(0, 2)), ohl[1])
        assertEquals(StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(33.0 / 62), StatValue.Pct(29.0 / 62)), ohl[2]) // 53.2%–46.8%
        assertEquals(StatRow(StatKind.PIM, StatValue.Count(6), StatValue.Count(10)), ohl[3])
        // WHL: `totalShotsOn` is 0 there; SOG is `totalShots` 36–28.
        val whl = ChlDetailMapper.summary(whl1023337, obj(SUMMARY_WHL_1023337)) { null }!!.first.gameStats
        assertEquals(StatRow(StatKind.SOG, StatValue.Count(36), StatValue.Count(28)), whl[0])
        assertEquals(StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(27.0 / 61), StatValue.Pct(34.0 / 61)), whl[2]) // 44.3%–55.7%
        assertFalse(whl.any { it.kind == StatKind.HITS })
        val q = finalQmjhl().first.gameStats
        assertEquals(StatRow(StatKind.HITS, StatValue.Count(2), StatValue.Count(10)), q.last())
        assertEquals(StatRow(StatKind.POWER_PLAY, StatValue.Fraction(1, 4), StatValue.Fraction(0, 3)), q[1])
        assertEquals(StatRow(StatKind.PIM, StatValue.Count(14), StatValue.Count(16)), q[3])
    }

    // ------------------------------------------------------------------ §3.1 Matchup goalies and Last 5 (B1, R5)

    @Test fun ldnGoaliesAreCookeThenJaswal() {
        val home = preOhl().goalies!!.home
        assertEquals(listOf("B. Cooke", "A. Jaswal"), home.map { it.name }) // the feed lists Jaswal first
        assertEquals(Goalie("B. Cooke", "8-4-0-0", 3.60, 0.879, "https://headshots.test/ohl/9924.jpg"), home[0])
        assertEquals(Goalie("A. Jaswal", "1-1-0-1", 2.89, 0.893, "https://headshots.test/ohl/9189.jpg"), home[1])
        assertTrue(home.none { it.starting }) // no starter marked
    }

    @Test fun ldnRuleTwoGivesTheSamePairWithoutMedvedevOrNadalin() {
        val pv = obj(PREVIEW_OHL_29246)
        val noLineup = editPreview(pv) { put("home_team", JsonObject(it["home_team"]!!.jsonObject - "lineup")) }
        val home = preOhl(pv = noLineup).goalies!!.home
        // Medvedev (8 GP, latest_team_id 6) traded; Nadalin inactive; Weiss 1 GP; string order would put "8" > "4" > "13".
        assertEquals(listOf("B. Cooke", "A. Jaswal"), home.map { it.name })
        assertTrue(home.none { it.starting })
    }

    @Test fun startersComeFirstAndAreMarked() {
        val niag = preOhl().goalies!!.away
        assertEquals(listOf("C. Haffner" to true, "V. Yermolenko" to false), niag.map { it.name to it.starting })
        assertTrue(niag.all { it.record == null && it.gaa == null && it.svPct == null }) // NIAG `topgoalies` not captured
        val q = ChlDetailMapper.preview(
            qmjhl32953, obj(PREVIEW_QMJHL_32953), null, obj(GOALIES_QMJHL_T2), null, false, ChlHubFixtures.HEADSHOT(League.QMJHL),
        )!!.goalies!!
        assertEquals(listOf("L. Denault" to true, "A. Proulx" to false), q.home.map { it.name to it.starting })
        assertEquals(Goalie("L. Denault", "6-6-1-1", 2.84, 0.907, "https://headshots.test/lhjmq/20480.jpg", starting = true), q.home[0])
        assertEquals("8-2-1-1", q.home[1].record)
        assertEquals(listOf("J. Lee" to true, "P. Plumins" to false), q.away.map { it.name to it.starting }) // Lee is the backup by GP
    }

    @Test fun mhGoaliesFromTopGoaliesWhenTheLineupIsNull() {
        val g = ChlDetailMapper.preview(whl1023348, obj(PREVIEW_WHL_1023348), null, obj(GOALIES_WHL_T206), null, false) { null }!!.goalies!!
        assertEquals(listOf("C. Casey", "C. Christie"), g.home.map { it.name })
        assertEquals(listOf("12-5-0-1", "2-1-1-1"), g.home.map { it.record })
        assertEquals(listOf(2.77, 3.65), g.home.map { it.gaa })
        assertEquals(listOf(0.898, 0.872), g.home.map { it.svPct })
        assertTrue(g.away.isEmpty()) // CGY: lineup null, no `topgoalies` read
    }

    @Test fun ldnLastFive() {
        val home = preOhl().recent.filter { it.side == Side.HOME }
        // L 6–5 at SAG, W 4–3 vs SOO, L 4–2 at GUE, L 5–0 vs PBO, W 2–0 at ER (oldest first).
        assertEquals(
            listOf("L 6-5 at SAG", "W 4-3 vs SOO", "L 4-2 at GUE", "L 5-0 vs PBO", "W 2-0 at ER"),
            home.map { "${it.result} ${it.score} ${if (it.atOpponent) "at" else "vs"} ${it.opponent}" },
        )
        // 2026-11-25T19:00:00-05:00, the newest.
        assertEquals(java.time.OffsetDateTime.parse("2026-11-25T19:00:00-05:00").toInstant().toEpochMilli(), home.last().dateMs)
        assertEquals(Side.AWAY, preOhl().recent.first().side) // away side first
    }

    // ------------------------------------------------------------------ §3.2 goals, §3.3 box score (B2)

    @Test fun ohlGoalList() {
        val goals = finalOhl().first.goals
        assertEquals(8, goals.size)
        val fifth = goals[4]
        assertEquals(listOf("Mogull", "8", "Matheson"), listOf(fifth.scorerLast, "${fifth.scorerTotal}", fifth.assistsLast.joinToString()))
        assertEquals(listOf(4, 3), goals[6].let { listOf(it.away, it.home) }) // Dietsch makes it 4–3
        assertEquals(5 to 3, goals.last().away to goals.last().home)
        assertEquals(listOf(Strength.PP, Strength.PP), goals.filter { it.strength == Strength.PP }.map { it.strength }) // Manza 15:36, Whitehead 8:42
        assertTrue(goals.all { it.clipId == null })
    }

    @Test fun qmjhlUnassistedGoalsAreObjectsOfNulls() {
        val goals = finalQmjhl().first.goals
        val arsenault = goals.first { it.scorerLast == "Arsenault" }
        assertEquals(emptyList(), arsenault.assistsLast)
        assertEquals(Strength.SH, arsenault.strength)
        assertEquals(emptyList(), goals.first { it.scorerLast == "Dufour" }.assistsLast)
    }

    @Test fun nikonovichOnOneLine() {
        val box = finalOhl().second
        assertEquals(listOf(GoalieLine("M. Nikonovich", 38, 33, 33.0 / 38, "58:21")), box.home.goalies) // .868
        assertEquals(listOf(GoalieLine("J. Nelson", 21, 18, 18.0 / 21, "60:00")), box.away.goalies) // .857
        // LaGambina and Bach dressed (0 s) and are not shown; `gs.goalies.home` lists Nikonovich twice (two stints).
        val stints = obj(SUMMARY_OHL_29239)["GC"]!!.jsonObject["Gamesummary"]!!.jsonObject["goalies"]!!.jsonObject["home"]!!.jsonArray
        assertEquals(2, stints.count { (it as JsonObject)["last_name"].toString().contains("Nikonovich") })
    }

    @Test fun lineScores() {
        val ohl = finalOhl().second
        assertEquals(listOf(1, 1, 3), ohl.periods.map { it.away })
        assertEquals(listOf(2, 1, 0), ohl.periods.map { it.home })
        assertEquals(listOf(15, 17, 6), ohl.periodShots.map { it.away })
        assertEquals(listOf(11, 5, 5), ohl.periodShots.map { it.home })
        assertEquals(ohl.periods, finalOhl().first.periods)
        val q = finalQmjhl().second
        val labels = listOf(PeriodLabel(PeriodKind.REG, 1), PeriodLabel(PeriodKind.REG, 2), PeriodLabel(PeriodKind.REG, 3), PeriodLabel(PeriodKind.OT, 1))
        assertEquals(labels, q.periods.map { it.label })
        assertEquals(labels, q.periodShots.map { it.label })
        assertEquals(listOf(0, 3, 1, 1), q.periods.map { it.away })
        assertEquals(listOf(1, 1, 2, 0), q.periods.map { it.home })
        assertEquals(listOf(5, 11, 9, 3), q.periodShots.map { it.away })
        assertEquals(listOf(17, 4, 5, 1), q.periodShots.map { it.home })
    }

    @Test fun skaterLinesHaveNoToiAndComputedPoints() {
        val box = finalOhl().second
        assertTrue((box.away.skaters + box.home.skaters).all { it.toi == null })
        val dietsch = box.away.skaters.single { it.name == "K. Dietsch" }
        assertEquals(SkaterLine("K. Dietsch", "RD", 1, 1, 2, 1, 3, null), dietsch)
        assertEquals("D", finalQmjhl().second.away.skaters.first().pos) // QMJHL `position_str` already "D"
    }

    // ------------------------------------------------------------------ other fixture states

    @Test fun preGameSummaryIsScheduledWithNoStars() {
        val (d, box) = ChlDetailMapper.summary(ohl29246, obj(SUMMARY_PRE_OHL_29246), shotOhl)!!
        assertEquals(GamePhase.SCHEDULED, d.fetchedPhase) // status 1
        assertTrue(d.stars.isEmpty()) // mvps [null, null, null]
        assertTrue(d.goals.isEmpty())
        assertTrue(box.home.goalies.isEmpty() && box.away.goalies.isEmpty())
    }

    @Test fun previewReadAfterTheFinalIsStillAsOfTheStart() {
        val d = ChlDetailMapper.preview(ohl29239, obj(PREVIEW_AFTER_FINAL_OHL_29239), null, null, null, false, shotOhl)!!
        assertEquals("5-17-3-0", d.season!!.homeRecord) // BAR: the board says 5-18
        assertEquals("10-10-3-0", d.season!!.awayRecord)
        assertEquals(GamePhase.SCHEDULED, d.fetchedPhase)
    }

    @Test fun playoffGivesNoRanksAndNoSeries() {
        val d = preOhl(playoff = true)
        assertNull(d.series)
        val s = d.season!!
        assertTrue(listOf(s.awayGf, s.homeGf, s.awayGa, s.homeGa, s.awayPp, s.homePp, s.awayPk, s.homePk).all { it != null && it.rank == null })
        assertEquals(3.75, s.homeGf!!.value) // every other row as usual
        assertEquals(2, d.goalies!!.home.size)
    }

    @Test fun noRanksKnownGivesValuesWithoutRanks() {
        val s = preOhl(ranks = null).season!!
        assertEquals(Ranked(3.75, null), s.homeGf)
    }

    // ------------------------------------------------------------------ S1 zero guards (synthetic gp0)

    private fun previewBody(visitor: String, home: String, extra: String = ""): JsonObject =
        json("""{"GC":{"Preview":{"visitor_team":$visitor,"home_team":$home$extra}}}""")

    @Test fun zeroGamesPlayedGivesDashesAndNoRank() {
        val zero = """{"team_id":"1","wins":0,"losses":0,"ot_losses":0,"shootout_losses":0,"gp":"0","goals_for":0,
            "goals_against":0,"power_plays":0,"power_play_goals":0,"times_short_handed":0,"power_play_goals_against":0,
            "last10":"0-0-0-0","lastGames":[]}"""
        val ranks = ChlRanks(mapOf("1" to 1), mapOf("1" to 1), mapOf("1" to 1), mapOf("1" to 1))
        val d = ChlDetailMapper.preview(ohl29246, previewBody(zero, zero.replace("\"1\"", "\"2\"")), null, null, ranks, false) { null }!!
        val s = d.season!!
        assertEquals("0-0-0-0", s.awayRecord)
        assertNull(s.awayLast10) // "–", not "0-0-0-0"
        assertNull(s.awayGf)
        assertNull(s.awayGa)
        assertNull(s.awayPp) // never a false 0.0%
        assertNull(s.awayPk)
        assertTrue(d.recent.isEmpty())
    }

    @Test fun zeroPowerPlaysAloneBlanksOnlyThatRow() {
        val t = """{"team_id":"1","wins":"3","losses":"1","ot_losses":"0","shootout_losses":"0","gp":4,"goals_for":"12",
            "goals_against":"8","power_plays":"0","power_play_goals":"0","times_short_handed":"5","power_play_goals_against":"0"}"""
        val s = ChlDetailMapper.preview(ohl29246, previewBody(t, t), null, null, null, false) { null }!!.season!!
        assertEquals(Ranked(3.0, null), s.awayGf)
        assertNull(s.awayPp)
        assertEquals(Ranked(1.0, null), s.awayPk) // 0 against in 5: 100%, a 0..1 rate of 1.0
    }

    @Test fun recordMissingAPartIsAbsent() {
        val t = """{"team_id":"1","wins":"3","losses":"-","ot_losses":"0","shootout_losses":"","gp":"x"}"""
        val s = ChlDetailMapper.preview(ohl29246, previewBody(t, t), null, null, null, false) { null }!!.season!!
        assertNull(s.awayRecord)
        assertNull(s.awayLast10)
        assertNull(s.awayGf)
    }

    @Test fun seriesFromWinsWhenMeetingsAreNotListed() {
        val t = """{"team_id":"1"}"""
        val h2h = ""","HeadToHeadRecord":{"visiting_team":{"w":"2"},"home_team":{"w":1}},"previous_meetings":[]"""
        val d = ChlDetailMapper.preview(ohl29246, previewBody(t, """{"team_id":"2"}""", h2h), null, null, null, false) { null }!!
        assertEquals(SeriesFacts(2, 1, 3), d.series)
        assertNull(ChlDetailMapper.preview(ohl29246, previewBody(t, """{"team_id":"2"}"""), null, null, null, false) { null }!!.series)
        assertNull(ChlDetailMapper.preview(ohl29246, previewBody(t, """{"team_id":"2"}""", h2h), null, null, null, true) { null }!!.series)
    }

    // ------------------------------------------------------------------ S1 ranks

    private fun tableOf(vararg rows: String): JsonArray =
        arr("""[{"sections":[{"title":"t","data":[${rows.joinToString(",")}]}]}]""")

    private fun std(team: String?, gp: Any?, gf: Any?, ga: Any?, rank: Int = 1): String {
        val prop = if (team == null) "{}" else """{"team_code":{"teamLink":"$team"}}"""
        return """{"prop":$prop,"row":{"games_played":${q(gp)},"goals_for":${q(gf)},"goals_against":${q(ga)},"rank":$rank}}"""
    }

    private fun spc(team: String, gp: Any?, ppg: Any?, pp: Any?, ppga: Any?, tsh: Any?): String =
        """{"prop":{"team_code":{"teamLink":"$team"}},"row":{"games_played":${q(gp)},"power_play_goals":${q(ppg)},""" +
            """"power_plays":${q(pp)},"power_play_goals_against":${q(ppga)},"times_short_handed":${q(tsh)},"rank":1,"power_play_pct":"99.9%"}}"""

    private fun q(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        else -> v.toString()
    }

    @Test fun ranksAreCompetitionRanksFromIntegers() {
        // A 2/3 and B 4/6 tie (cross-multiplied exactly); C 1/2 is next and skips to 3rd; D 0 GP is left out; E has no key.
        val r = ChlDetailMapper.ranks(
            tableOf(std("A", 3, 2, 9, rank = 4), std("B", "6", "4", "6"), std("C", 2, 1, 1), std("D", 0, 0, 0), std(null, 1, 9, 0)),
            null,
        )!!
        assertEquals(mapOf("A" to 1, "B" to 1, "C" to 3), r.gf)
        assertEquals(mapOf("C" to 1, "B" to 2, "A" to 3), r.ga) // GA lower is better: 1/2, 6/6, 9/3
        assertTrue(r.pp.isEmpty() && r.pk.isEmpty())
    }

    @Test fun specialRanksSkipZeroDenominatorsAndCompareThePkByGoalsAgainst() {
        val r = ChlDetailMapper.ranks(
            null,
            tableOf(spc("A", 10, 5, 20, 4, 20), spc("B", 10, 0, 0, 2, 20), spc("C", 10, 3, 10, 0, 0), spc("D", 0, 9, 10, 0, 10), spc("E", null, 1, 10, 1, 20)),
        )!!
        assertEquals(mapOf("C" to 1, "A" to 2, "E" to 3), r.pp) // 3/10, 5/20, 1/10; B (0 PP) and D (0 GP) left out
        assertEquals(mapOf("E" to 1, "B" to 2, "A" to 3), r.pk) // against 1/20, 2/20, 4/20; C (0 short-handed) left out
    }

    @Test fun ranksNullAndEmpty() {
        assertNull(ChlDetailMapper.ranks(null, null))
        assertNull(ChlDetailMapper.ranks(arr("[]"), arr("[]")))
        assertNull(ChlDetailMapper.ranks(arr("""[1, "x", null, {"sections":{}}]"""), null))
        assertNull(ChlDetailMapper.ranks(arr("""[{"sections":[{"data":[{"prop":{"team_code":{"teamLink":""}},"row":{}}]}]}]"""), null))
        // Rows exist but all are left out: a ChlRanks with empty maps (KDoc), not null.
        val empty = ChlDetailMapper.ranks(tableOf(std("A", 0, 0, 0)), null)
        assertEquals(ChlRanks(emptyMap(), emptyMap(), emptyMap(), emptyMap()), empty)
    }

    // ------------------------------------------------------------------ B1 goalies, synthetic

    private fun tg(vararg rows: String) = json("""{"SiteKit":{"Statviewtype":[${rows.joinToString(",")}]}}""")

    private fun tgRow(id: String, last: String, gp: Any, active: String = "1", latest: String = "1") =
        """{"player_id":"$id","first_name":"F$id","last_name":"$last","name":"$last, F$id","active":"$active",
            "latest_team_id":"$latest","team_id":"1","games_played":${q(gp)},"wins":"1","losses":"2","ot_losses":"0",
            "shootout_losses":"1","goals_against_average":"3.00","save_percentage":".900","minutes_played":"844:58"}"""

    @Test fun ruleTwoFiltersTradedAndInactiveAndSortsGamesPlayedAsInts() {
        val team = """{"team_id":"1"}"""
        val g = tg(tgRow("1", "Eight", "8"), tgRow("2", "Thirteen", "13"), tgRow("3", "Four", 4), tgRow("4", "Traded", "20", latest = "6"), tgRow("5", "Out", "30", active = "0"))
        val d = ChlDetailMapper.preview(ohl29246, previewBody(team, team), g, null, null, false) { null }!!
        assertEquals(listOf("F. Thirteen", "F. Eight"), d.goalies!!.away.map { it.name })
        assertEquals(Goalie("F. Thirteen", "1-2-0-1", 3.0, 0.9, null), d.goalies!!.away[0]) // ".900" → 0.9; QMJHL "844:58" ignored
        assertTrue(d.goalies!!.home.isEmpty())
    }

    @Test fun ruleOneJoinsByPlayerIdAndMarksOnlyTheFirstStarter() {
        val lineup = ""","visiting_team":{"lineup":{"goalies":[
            {"player_id":"9","first_name":"Nine","last_name":"Unknown","start":"0"},
            {"player_id":"1","first_name":"One","last_name":"Low","start":"0"},
            {"player_id":"2","first_name":"","last_name":"High","start":"1"},
            {"player_id":"3","first_name":"Three","last_name":"Also","start":"1"},
            {"player_id":"4","first_name":"Four","last_name":"","start":"0"}]}}"""
        val g = tg(tgRow("1", "Low", "2"), tgRow("3", "Also", "10"))
        val d = ChlDetailMapper.preview(ohl29246, previewBody("""{"team_id":"1"}""", """{"team_id":"2"}""", lineup), g, null, null, false) { "h$it" }!!
        val away = d.goalies!!.away
        // "High" is the first marked starter; then by GP: Also (10) before Low (2); Unknown (no row) after; at most 2.
        assertEquals(listOf("High" to true, "T. Also" to false), away.map { it.name to it.starting })
        assertEquals(listOf(null, "1-2-0-1"), away.map { it.record }) // High has no `topgoalies` row: name and headshot only
        assertEquals("h2", away[0].headshot)
    }

    @Test fun ruleOneWithoutTopGoaliesKeepsFeedOrder() {
        val lineup = ""","visiting_team":{"lineup":{"goalies":[{"player_id":"7","first_name":"A","last_name":"First"},{"player_id":"8","first_name":"B","last_name":"Second"}]}}"""
        val d = ChlDetailMapper.preview(ohl29246, previewBody("""{"team_id":"1"}""", """{"team_id":"2"}""", lineup), null, null, null, false) { null }!!
        assertEquals(listOf("A. First", "B. Second"), d.goalies!!.away.map { it.name })
        assertTrue(d.goalies!!.away.none { it.starting })
    }

    // ------------------------------------------------------------------ S2 summary shapes, synthetic

    private fun summaryBody(inner: String): JsonObject = json(
        """{"GC":{"Gamesummary":{"meta":{"status":"4","attendance":"0"},"home":{"team_id":"2","team_code":"hh"},
            "visitor":{"team_id":"1","team_code":"vv"}$inner}}}""",
    )

    @Test fun skaterShapesPlusMinusIntOrStringAndShotsAsString() {
        val d = ChlDetailMapper.summary(ohl29239, summaryBody(""","home_team_lineup":{"players":[
            {"first_name":"A","last_name":"Plus","position_str":" C ","goals":1,"assists":"2","points":null,"plusminus":"+1","shots":"4"},
            {"first_name":"B","last_name":"Minus","position_str":"LD","goals":"0","assists":0,"plusminus":-1,"shots":0},
            {"first_name":"C","last_name":"Dash","position_str":"RW","goals":null,"assists":"-","plusminus":"-","shots":"","toi":"12:34"},
            {"first_name":"D","last_name":null,"goals":1}],"goalies":[]}"""), shotOhl)!!
        val s = d.second.home.skaters
        assertEquals(SkaterLine("A. Plus", "C", 1, 2, 3, 1, 4, null), s[0])
        assertEquals(SkaterLine("B. Minus", "LD", 0, 0, 0, -1, 0, null), s[1])
        assertEquals(SkaterLine("C. Dash", "RW", 0, 0, 0, null, null, "12:34"), s[2])
        assertEquals(3, s.size) // no last name: skipped
        assertEquals(TeamBox(emptyList(), emptyList()), d.second.away) // missing lineup
        assertNull(d.first.attendance) // "0"
    }

    @Test fun pulledAndReturnedGoalieIsOneLineAndSaZeroHasNoSvPct() {
        val d = ChlDetailMapper.summary(ohl29239, summaryBody(""",
            "goalies":{"home":[{"last_name":"Twice","secs":"3474"},{"last_name":"Twice","secs":"27"}]},
            "home_team_lineup":{"goalies":[
                {"first_name":"T","last_name":"Twice","seconds":3501,"time":"58:21","shots_against":"38","saves":"33"},
                {"first_name":"E","last_name":"Extra","seconds":"99","time":"1:39","shots_against":0,"saves":0},
                {"first_name":"B","last_name":"Bench","seconds":0,"time":null,"shots_against":0,"saves":0},
                {"first_name":"N","last_name":"NoSecs","time":"60:00"}]}"""), shotOhl)!!
        assertEquals(
            listOf(GoalieLine("T. Twice", 38, 33, 33.0 / 38, "58:21"), GoalieLine("E. Extra", 0, 0, null, "1:39")),
            d.second.home.goalies,
        )
    }

    @Test fun goalShapes() {
        val d = ChlDetailMapper.summary(qmjhl32952, summaryBody(""","goals":[
            {"home":"1","period_id":"1","time":"1:00","scorer_goal_num":"3","goal_scorer":{"player_id":"5","first_name":"Émile","last_name":"Un"},
             "assist1_player":{"player_id":null,"first_name":null,"last_name":null},"assist2_player":{"last_name":" "},"empty_net":"1"},
            {"team_id":"1","period_id":5,"time":"2:00","goal_scorer":{"first_name":"B","last_name":"Two"},"assist1_player":{"last_name":"Helper"},"power_play":1},
            {"home":"0","period_id":"x","goal_scorer":{"last_name":"Lost"}},
            {"home":"0","period_id":"2","goal_scorer":{"first_name":"C","last_name":"Three"},"game_winning":"1"},
            "garbage", null, 7]"""), ChlHubFixtures.HEADSHOT(League.QMJHL))!!.first
        val g = d.goals
        assertEquals(3, g.size)
        assertEquals(GoalItem(PeriodLabel(PeriodKind.REG, 1), "1:00", Side.HOME, "É. Un", "Un", 3, emptyList(), Strength.EV, emptyNet = true, away = 0, home = 1, clipId = null, headshot = "https://headshots.test/lhjmq/5.jpg"), g[0])
        // No `home` flag: side from team_id; period 5 → OT 2.
        assertEquals(listOf(PeriodLabel(PeriodKind.OT, 2), Side.AWAY, listOf("Helper"), Strength.PP), listOf(g[1].period, g[1].side, g[1].assistsLast, g[1].strength))
        // The dropped goal (unparsable period) still counts in the running score.
        assertEquals(Triple("Three", 3, 1), Triple(g[2].scorerLast, g[2].away, g[2].home))
        assertTrue(g[2].gameWinning)
        assertNull(g[1].scorerTotal)
    }

    @Test fun mvpsWithNullEntriesAndTeamCodes() {
        val d = ChlDetailMapper.summary(ohl29239, summaryBody(""","mvps":[null,{"player_id":"12","first_name":"Ann","last_name":"Two","home":1},
            {"first_name":"Bo","last_name":"Three","home":"0"},{"first_name":"X","last_name":"Fourth","home":0}]"""), shotOhl)!!.first
        assertEquals(
            listOf(Star(2, "A. Two", "Two", "HH", "https://headshots.test/ohl/12.jpg"), Star(3, "B. Three", "Three", "VV", null)),
            d.stars,
        )
    }

    @Test fun faceoffsWithNoAttemptsAndHitsOutsideTheQmjhl() {
        val inner = ""","totalShots":{"visitor":"30","home":20},"totalFaceoffs":{"visitor":{"att":0,"won":0},"home":{"att":"10","won":"6"}},
            "totalHits":{"visitor":3,"home":4},"pimTotal":{"visitor":"-","home":2}"""
        val ohl = ChlDetailMapper.summary(ohl29239, summaryBody(inner), shotOhl)!!.first.gameStats
        assertEquals(listOf(StatRow(StatKind.SOG, StatValue.Count(30), StatValue.Count(20))), ohl) // no FO (att 0), no PIM ("-"), no hits
        val q = ChlDetailMapper.summary(qmjhl32952, summaryBody(inner), shotOhl)!!.first.gameStats
        assertEquals(listOf(StatKind.SOG, StatKind.HITS), q.map { it.kind })
    }

    @Test fun statusGoesThroughTheBoardClassifier() {
        fun phase(status: String) = ChlDetailMapper.summary(
            ohl29239, json("""{"GC":{"Gamesummary":{"meta":{"status":$status},"home":{},"visitor":{}}}}"""), shotOhl,
        )!!.first.fetchedPhase
        assertEquals(GamePhase.FINAL, phase("\"4\""))
        assertEquals(GamePhase.FINAL, phase("5"))
        assertEquals(GamePhase.SCHEDULED, phase("\"1\""))
        assertEquals(GamePhase.LIVE, phase("\"2\""))
        assertFalse(phase("\"3\"") == GamePhase.FINAL) // status 3 is unverified, never final
    }

    @Test fun periodsKeptOnlyWhenBothSidesHaveThem() {
        val d = ChlDetailMapper.summary(ohl29239, summaryBody(""",
            "goalsByPeriod":{"visitor":{"2":1,"1":"0","4":1,"x":3,"0":1},"home":{"1":1,"2":"2"}},
            "shotsByPeriod":{"visitor":[1,2],"home":{"1":1}}"""), shotOhl)!!
        assertEquals(listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 0, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 1, 2)), d.second.periods)
        assertTrue(d.second.periodShots.isEmpty())
    }

    // ------------------------------------------------------------------ garbage in: null, never a throw

    @Test fun garbageBodiesGiveNull() {
        val bodies = listOf(
            "{}", """{"GC":[]}""", """{"GC":{"Preview":[]}}""", """{"GC":{"Preview":"x"}}""", """{"GC":{"Preview":{}}}""",
            """{"GC":{"Preview":{"visitor_team":[],"home_team":{"team_id":""}}}}""", """{"GC":{"Gamesummary":{}}}""",
            """{"GC":{"Gamesummary":{"home":[],"visitor":{}}}}""", """{"SiteKit":{}}""",
        ).map(::json)
        for (b in bodies) {
            assertNull(ChlDetailMapper.preview(ohl29246, b, b, b, null, false) { it }, b.toString())
            assertNull(ChlDetailMapper.summary(ohl29239, b) { it }, b.toString())
        }
    }

    @Test fun truncatedBodiesMapWhatIsThere() {
        val pre = ChlDetailMapper.preview(ohl29246, json("""{"GC":{"Preview":{"home_team":{"team_id":"14"}}}}"""), json("{}"), json("""{"SiteKit":[]}"""), null, false) { null }
        assertNotNull(pre)
        assertNull(pre.series)
        assertNull(pre.goalies)
        assertTrue(pre.leaders.isEmpty())
        assertFalse(pre.leadersSeason)
        assertEquals(SeasonComparison(null, null, null, null, null, null, null, null, null, null, null, null), pre.season)
        val fin = ChlDetailMapper.summary(ohl29239, json("""{"GC":{"Gamesummary":{"home":{},"visitor":{},"goals":{},"mvps":"x","venue":"  "}}}"""), shotOhl)
        assertNotNull(fin)
        assertTrue(fin.first.goals.isEmpty() && fin.first.stars.isEmpty() && fin.first.gameStats.isEmpty())
        assertNull(fin.first.venue)
        assertNotEquals(GamePhase.FINAL, fin.first.fetchedPhase) // no status
    }

    /** Every top-level field of every fixture body replaced by each odd shape: the mapper never throws. */
    @Test fun noFieldShapeThrows() {
        val odd: List<JsonElement> = listOf(JsonNull, JsonPrimitive(""), JsonPrimitive("-"), JsonPrimitive(7), JsonArray(listOf(JsonNull)), JsonObject(emptyMap()))
        val previews = listOf(PREVIEW_OHL_29246, PREVIEW_WHL_1023348, PREVIEW_QMJHL_32953, PREVIEW_AFTER_FINAL_OHL_29239)
        val summaries = listOf(SUMMARY_OHL_29239, SUMMARY_WHL_1023337, SUMMARY_OT_QMJHL_32952, SUMMARY_PRE_OHL_29246)
        var calls = 0
        for (path in previews) {
            val body = obj(path)
            val inner = body["GC"]!!.jsonObject["Preview"]!!.jsonObject
            for (key in inner.keys) for (v in odd) {
                val mutated = JsonObject(mapOf("GC" to JsonObject(mapOf("Preview" to JsonObject(inner + (key to v))))))
                ChlDetailMapper.preview(ohl29246, mutated, obj(GOALIES_OHL_T14), obj(GOALIES_OHL_T14), ohlRanks(), false, shotOhl)
                calls++
            }
        }
        for (path in summaries) {
            val inner = obj(path)["GC"]!!.jsonObject["Gamesummary"]!!.jsonObject
            for (key in inner.keys) for (v in odd) {
                ChlDetailMapper.summary(qmjhl32952, JsonObject(mapOf("GC" to JsonObject(mapOf("Gamesummary" to JsonObject(inner + (key to v)))))), shotOhl)
                calls++
            }
        }
        for (path in listOf(TABLE_OHL, TABLE_SPECIAL_OHL)) {
            val row = table(path)[0].jsonObject["sections"]!!.jsonArray[0].jsonObject["data"]!!.jsonArray[0].jsonObject["row"]!!.jsonObject
            for (key in row.keys) for (v in odd) {
                val t = tableOf("""{"prop":{"team_code":{"teamLink":"1"}},"row":${JsonObject(row + (key to v))}}""")
                ChlDetailMapper.ranks(t, t)
                calls++
            }
        }
        assertTrue(calls > 500)
    }

    // ------------------------------------------------------------------ helpers

    private fun editPreview(body: JsonObject, edit: MutableMap<String, JsonElement>.(JsonObject) -> Unit): JsonObject {
        val pv = body["GC"]!!.jsonObject["Preview"]!!.jsonObject
        val m = pv.toMutableMap()
        m.edit(pv)
        return JsonObject(mapOf("GC" to JsonObject(mapOf("Preview" to JsonObject(m)))))
    }

    private fun assertNotEquals(a: Any?, b: Any?) = kotlin.test.assertNotEquals(a, b)
}
