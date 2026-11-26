package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-hub.md §2.2, §2.4, §3.1, §3.5, §3.6, §6.1, §8.2 (P1): every reduced fixture, each phase, sides by
 * team id (the reversed-order trap), the duplicated stat, ties, the safety and the defensive TD, fractional leaders,
 * missing sections, and clips offered at an injected clock (no test reads the system clock).
 */
class EspnDetailMapperTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The evening after MNF: every MNF clip is past its embargo and a day from its expiry. */
    private val mnfNight = ms("2026-11-25T00:00:00Z")

    private fun id(event: String) = GameId(League.NFL, event)
    private val mnfId = id("401873078")

    private fun map(path: String, event: String, atMs: Long? = mnfNight): GameDetail =
        assertNotNull(EspnDetailMapper.detail(NflHubFixtures.summary(path), id(event), atMs), path)

    private fun decode(o: JsonElement): EspnSummaryDto = NflHubFixtures.JSON.decodeFromJsonElement(EspnSummaryDto.serializer(), o)

    private fun tree(path: String): JsonObject = NflHubFixtures.JSON.parseToJsonElement(SportsFixtures.text(path)).jsonObject

    // ------------------------------------------------------------------ every fixture

    @Test fun everySummaryFixtureMapsAndBoxes() {
        val ids = mapOf(
            NflHubFixtures.FINAL_MIA_BUF to "401873070", NflHubFixtures.FINAL_PIT_PHI to "401873076",
            NflHubFixtures.FINAL_MNF to "401873078", NflHubFixtures.FINAL_SAFETY to "401873066",
            NflHubFixtures.FINAL_DEF_TD to "401873077", NflHubFixtures.PRE_GB_LAR to "401873079",
        )
        for (path in NflHubFixtures.SUMMARIES) {
            val d = map(path, ids.getValue(path))
            // §6.1: the NFL detail leaves the NHL fields empty; the board's score stays the headline.
            assertNull(d.awayScore, path)
            assertNull(d.homeScore, path)
            assertNull(d.recapId, path)
            assertNull(d.condensedId, path)
            assertTrue(d.periods.isEmpty() && d.goals.isEmpty() && d.stars.isEmpty() && d.broadcasts.isEmpty(), path)
            assertNull(d.season, path)
            assertNull(d.goalies, path)
            assertNull(d.series, path)
            assertNotNull(d.venue, path)
            assertNotNull(EspnDetailMapper.box(NflHubFixtures.summary(path)), path)
        }
    }

    @Test fun aSummaryOfAnotherEventIsNotUsed() {
        assertNull(EspnDetailMapper.detail(NflHubFixtures.summary(NflHubFixtures.FINAL_MNF), id("401873079"), mnfNight))
    }

    // ------------------------------------------------------------------ final

    @Test fun mnfFinalRowsLeadersRecapAndScoring() {
        val d = map(NflHubFixtures.FINAL_MNF, "401873078")
        assertEquals(GamePhase.FINAL, d.fetchedPhase)
        assertEquals("Northwest Stadium", d.venue)
        // Away CIN, home WSH (boxscore lists CIN first, the header WSH first).
        assertEquals(
            listOf(
                StatRow(StatKind.TOTAL_YARDS, StatValue.Count(312), StatValue.Count(428)),
                StatRow(StatKind.PASS_YARDS, StatValue.Count(281), StatValue.Count(278)),
                StatRow(StatKind.RUSH_YARDS, StatValue.Count(31), StatValue.Count(150)),
                StatRow(StatKind.FIRST_DOWNS, StatValue.Count(17), StatValue.Count(23)),
                StatRow(StatKind.THIRD_DOWN, StatValue.Fraction(4, 12, dash = true), StatValue.Fraction(2, 10, dash = true)),
                StatRow(StatKind.TURNOVERS, StatValue.Count(0), StatValue.Count(0)),
                StatRow(StatKind.POSSESSION, StatValue.Clock(28 * 60 + 29), StatValue.Clock(31 * 60 + 31)),
            ),
            d.gameStats,
        )
        assertTrue(d.seasonStats.isEmpty(), "season rows are pre-game only")
        assertTrue(d.recent.isEmpty(), "no last five after the game")
        assertEquals(
            "Jayden Daniels rallies Commanders in final seconds to set up winning kick in 22-20 win over Bengals",
            d.recapHeadline,
        )
        val pass = d.leaders.first { it.category == "passingYards" }
        assertEquals(Leader("J. Burrow", 300, "https://img.invalid/h/3915511.png", "28/40, 300 YDS, 2 TD"), pass.away)
        assertEquals("J. Daniels", pass.home?.name)
        assertEquals(289, pass.home?.value)
        val sacks = d.leaders.first { it.category == "sacks" }
        assertEquals(1, sacks.home?.value, "the whole part only")
        assertEquals("1.5", sacks.home?.line, "the fraction lives in line")
        assertEquals(listOf("passingYards", "rushingYards", "receivingYards", "sacks", "totalTackles"), d.leaders.map { it.category })

        assertEquals(10, d.plays.size)
        val first = d.plays.first()
        assertEquals(
            ScoringPlay("4018730781322", PeriodLabel(PeriodKind.REG, 2), "6:17", Side.AWAY, PlayScore.FG, "Evan McPherson 38 Yd Field Goal", 3, 0),
            first,
        )
        val higgins = d.plays[1]
        assertEquals(PlayScore.TD, higgins.kind)
        assertEquals("Tee Higgins 31 Yd pass from Joe Burrow (Evan McPherson Kick)", higgins.text)
        val winner = d.plays.last()
        assertEquals(Side.HOME, winner.side)
        assertEquals(PlayScore.FG, winner.kind)
        assertEquals("Drew Stevens 26 Yd Field Goal", winner.text, "trimmed")
        assertEquals(20 to 22, winner.away to winner.home)
        assertEquals(d.plays, d.scoring, "the league-neutral list is the plays")
    }

    @Test fun mnfInjuriesFilterAndSides() {
        val d = map(NflHubFixtures.FINAL_MNF, "401873078")
        // The final lists next week's report: IR dropped, away CIN first although ESPN lists WSH first.
        assertEquals(
            listOf("Dalton Risner" to Side.AWAY, "Kyle Dugger" to Side.AWAY, "Stefon Diggs" to Side.HOME, "Amik Robertson" to Side.HOME),
            d.injuries.map { it.name to it.side },
        )
    }

    @Test fun duplicatedInterceptionsAndZeroClipsOnMiaBuf() {
        val d = map(NflHubFixtures.FINAL_MIA_BUF, "401873070")
        assertEquals(7, d.gameStats.size, "the 25 rows reduce to the seven drawn kinds, the duplicate read once")
        assertTrue(d.clips.isEmpty())
        assertEquals("J. Cook III", d.leaders.first { it.category == "rushingYards" }.home?.name)
        assertEquals("Cook III", EspnDetailMapper.leaderSurname("J. Cook III"))
        assertEquals("Bills' defense feasts in 26-9 win over injury-depleted Dolphins", d.recapHeadline)
        assertEquals(9, d.plays.size)
        assertEquals(listOf(PlayScore.FG, PlayScore.FG, PlayScore.TD), d.plays.take(3).map { it.kind })
    }

    @Test fun safetyIsToldApartFromATouchdown() {
        val d = map(NflHubFixtures.FINAL_SAFETY, "401873066")
        val sf = d.plays.single { it.kind == PlayScore.SAFETY }
        assertEquals(Side.HOME, sf.side)
        assertEquals(PeriodLabel(PeriodKind.REG, 3), sf.period)
        assertEquals(3 to 19, sf.away to sf.home)
        assertTrue(d.plays.none { it.kind == PlayScore.DEF_TD })
    }

    @Test fun interceptionReturnIsADefensiveTouchdown() {
        val d = map(NflHubFixtures.FINAL_DEF_TD, "401873077")
        val def = d.plays.single { it.kind == PlayScore.DEF_TD }
        assertTrue(def.text.startsWith("Ji'Ayir Brown 18 Yd Interception Return"))
        assertEquals(Side.HOME, def.side)
        assertEquals(5, d.plays.count { it.kind == PlayScore.TD }, "passing and rushing TDs stay TD")
    }

    @Test fun playScoreRules() {
        fun kind(abbr: String?, text: String?, typeId: String? = null): PlayScore {
            val dto = EspnSummaryDto(
                header = header(),
                scoringPlays = listOf(
                    EspnScoringPlayDto(
                        id = JsonPrimitive("1"), type = EspnPlayTypeDto(typeId?.let(::JsonPrimitive), abbr, text), text = "x",
                        period = EspnPeriodDto(JsonPrimitive(1)), awayScore = JsonPrimitive(6), homeScore = JsonPrimitive(0),
                        team = EspnTeamRefDto(JsonPrimitive("4")),
                    ),
                ),
            )
            return EspnDetailMapper.detail(dto, mnfId, null)!!.plays.single().kind
        }
        assertEquals(PlayScore.DEF_TD, kind("TD", "Fumble Return Touchdown"))
        assertEquals(PlayScore.DEF_TD, kind("TD", "Something", typeId = "36"))
        assertEquals(PlayScore.TD, kind("TD", "Punt Return Touchdown"))
        assertEquals(PlayScore.OTHER, kind("XP", "Two-point"))
        assertEquals(PlayScore.OTHER, kind(null, null))
    }

    @Test fun overtimePlaysAndAMissingTeamIdFallBackToTheScore() {
        val dto = EspnSummaryDto(
            header = header(),
            scoringPlays = listOf(
                play("a", period = 4, away = 0, home = 3, team = null),
                play("b", period = 5, away = 6, home = 3, team = null),
                play("c", period = 5, away = 6, home = 3, team = "999"), // neither side, no change: dropped
            ),
        )
        val plays = EspnDetailMapper.detail(dto, mnfId, null)!!.plays
        assertEquals(listOf("a" to Side.HOME, "b" to Side.AWAY), plays.map { it.id to it.side })
        assertEquals(PeriodLabel(PeriodKind.OT, 1), plays[1].period)
    }

    @Test fun playsAreCappedKeepingTheLatest() {
        val many = (1..70).map { play("p$it", period = 1, away = it, home = 0, team = "4") }
        val plays = EspnDetailMapper.detail(EspnSummaryDto(header = header(), scoringPlays = many), mnfId, null)!!.plays
        assertEquals(SportsConstants.HUB_NFL_MAX_PLAYS, plays.size)
        assertEquals("p70", plays.last().id)
    }

    // ------------------------------------------------------------------ clips at a fixed clock (§4.3)

    @Test fun clipsOfferedAtTheInjectedClockHighlightFirstThenPlaysOldestFirst() {
        val d = map(NflHubFixtures.FINAL_MNF, "401873078", atMs = mnfNight)
        assertEquals(listOf(50579688L, 50578630L, 50578740L, 50579083L), d.clips.map { it.videoId })
        assertEquals(listOf(ClipKind.RECAP, ClipKind.PLAY, ClipKind.PLAY, ClipKind.PLAY), d.clips.map { it.kind })
        val hl = d.clips.first()
        assertEquals(74_000L, hl.durationMs)
        assertEquals(ms("2026-11-26T04:32:10Z"), hl.expiresMs)
        assertEquals("https://img.invalid/50579688.jpg", hl.thumbUrl)
    }

    @Test fun embargoedClipsAreNotOffered() {
        // 03:00Z on MNF night: the highlight (04:32) and the lights-out clip (03:21) are still embargoed.
        val d = map(NflHubFixtures.FINAL_MNF, "401873078", atMs = ms("2026-11-24T03:00:00Z"))
        assertEquals(listOf(50578630L, 50578740L), d.clips.map { it.videoId })
    }

    @Test fun expiredAndMarginClipsAreNotOffered() {
        // 02:40Z two days on: 50578630 expired at 02:30; 50578740 expires 02:46, 6 min away, still offered; 02:42 is
        // inside the 5 min margin.
        assertEquals(
            listOf(50579688L, 50578740L, 50579083L),
            map(NflHubFixtures.FINAL_MNF, "401873078", atMs = ms("2026-11-26T02:40:00Z")).clips.map { it.videoId },
        )
        assertEquals(
            listOf(50579688L, 50579083L),
            map(NflHubFixtures.FINAL_MNF, "401873078", atMs = ms("2026-11-26T02:42:00Z")).clips.map { it.videoId },
        )
        assertTrue(map(NflHubFixtures.FINAL_MNF, "401873078", atMs = ms("2026-11-27T00:00:00Z")).clips.isEmpty())
        assertTrue(map(NflHubFixtures.FINAL_PIT_PHI, "401873076", atMs = ms("2026-11-26T21:25:00Z")).clips.isEmpty(), "inside the margin")
        assertEquals(1, map(NflHubFixtures.FINAL_PIT_PHI, "401873076", atMs = ms("2026-11-26T21:20:00Z")).clips.size)
    }

    @Test fun nullClockSkipsTheTimeCheckAndAnalysisIsDropped() {
        assertEquals(4, map(NflHubFixtures.FINAL_MNF, "401873078", atMs = null).clips.size)
        assertTrue(map(NflHubFixtures.PRE_GB_LAR, "401873079", atMs = null).clips.isEmpty(), "Analysis is never offered")
    }

    @Test fun httpClipsOnlyWithTheDebugOverload() {
        val t = tree(NflHubFixtures.FINAL_PIT_PHI)
        val http = decode(rewriteStrings(t) { s -> s.replace("https://clips.invalid", "http://10.0.2.2:8080") })
        assertTrue(EspnDetailMapper.detail(http, id("401873076"), null)!!.clips.isEmpty())
        assertEquals(1, EspnDetailMapper.detail(http, id("401873076"), null, httpOk = true)!!.clips.size)
    }

    // ------------------------------------------------------------------ pre-game

    @Test fun preGameSeasonRowsLastFiveLeadersInjuries() {
        val d = map(NflHubFixtures.PRE_GB_LAR, "401873079")
        assertEquals(GamePhase.SCHEDULED, d.fetchedPhase)
        assertEquals("SoFi Stadium", d.venue)
        assertTrue(d.gameStats.isEmpty(), "pre-game averages are not game totals")
        assertTrue(d.plays.isEmpty(), "no scoringPlays key")
        assertNull(d.recapHeadline, "a Preview article is not a recap")
        // Away GB, home LAR.
        assertEquals(
            listOf(
                StatRow(StatKind.RECORD, StatValue.Text("6-4"), StatValue.Text("6-4")),
                StatRow(StatKind.LAST_FIVE, StatValue.Text("3-2"), StatValue.Text("4-1")),
                StatRow(StatKind.POINTS_PG, StatValue.Decimal(22.6, 1), StatValue.Decimal(24.8, 1)),
                StatRow(StatKind.POINTS_ALLOWED_PG, StatValue.Decimal(22.1, 1), StatValue.Decimal(18.7, 1)),
                StatRow(StatKind.YARDS_PG, StatValue.Decimal(351.1, 1), StatValue.Decimal(396.6, 1)),
                StatRow(StatKind.YARDS_ALLOWED_PG, StatValue.Decimal(340.4, 1), StatValue.Decimal(295.8, 1)),
            ),
            d.seasonStats,
        )
        val gb = d.recent.filter { it.side == Side.AWAY }
        assertEquals(5, gb.size)
        assertEquals(RecentGame(Side.AWAY, false, "DAL", GameResult.W, "23-13", ms("2026-10-19T00:20:00Z")), gb[0])
        assertEquals(RecentGame(Side.AWAY, true, "DET", GameResult.L, "20-17", ms("2026-10-25T20:25:00Z")), gb[1])
        assertEquals(listOf(Side.AWAY, Side.AWAY, Side.AWAY, Side.AWAY, Side.AWAY, Side.HOME), d.recent.take(6).map { it.side }, "away first")
        assertEquals(ms("2026-10-18T20:05:00Z"), d.recent.first { it.side == Side.HOME }.dateMs)

        val sacks = d.leaders.first { it.category == "sacks" }
        assertEquals("B. Young", sacks.home?.name)
        assertEquals(5, sacks.home?.value)
        assertEquals("5.5", sacks.home?.line, "a fractional season value only from displayValue")
        assertEquals("L. Van Ness", sacks.away?.name)
        assertEquals("Van Ness", EspnDetailMapper.leaderSurname(sacks.away!!.name))
        assertEquals(2838, d.leaders.first { it.category == "passingYards" }.away?.value)

        // IR is a season list and is left out (§3.6); away GB first.
        assertEquals(
            listOf(
                InjuryLine(Side.AWAY, "Kevin Zeitler", "G", InjuryStatus.QUESTIONABLE, "Triceps"),
                InjuryLine(Side.AWAY, "Sean Rhyan", "G", InjuryStatus.QUESTIONABLE, "Ankle"),
                InjuryLine(Side.AWAY, "Travis Glover", "OT", InjuryStatus.QUESTIONABLE, "Shoulder"),
                InjuryLine(Side.HOME, "Coleman Shelton", "C", InjuryStatus.OUT, "Hamstring"),
            ),
            d.injuries,
        )
    }

    @Test fun tiesInRecordsAndInTheLastFive() {
        val t = tree(NflHubFixtures.PRE_GB_LAR)
        // Both records become "4-5-1"; GB's loss at DET becomes a tie (W T W W L).
        val tied = replaceFirstResult(rewriteStrings(t) { s -> if (s == "6-4") "4-5-1" else s }, from = "L", to = "T")
        val d = EspnDetailMapper.detail(decode(tied), id("401873079"), null)!!
        assertEquals(StatRow(StatKind.RECORD, StatValue.Text("4-5-1"), StatValue.Text("4-5-1")), d.seasonStats[0])
        assertEquals(StatRow(StatKind.LAST_FIVE, StatValue.Text("3-1-1"), StatValue.Text("4-1")), d.seasonStats[1])
        assertEquals(GameResult.T, d.recent[1].result)
    }

    @Test fun injuriesAreCappedPerTeamAndOtherStatusesDropped() {
        val list = (1..20).map { EspnInjuryDto("Out", EspnInjuredAthleteDto("Player $it", EspnPositionDto("WR")), EspnInjuryDetailsDto("Knee")) } +
            EspnInjuryDto("Day-To-Day", EspnInjuredAthleteDto("X", null), null) +
            EspnInjuryDto("Doubtful", EspnInjuredAthleteDto(null, null), null)
        val dto = EspnSummaryDto(header = header(), injuries = listOf(EspnTeamInjuriesDto(EspnTeamRefDto(JsonPrimitive("28")), list)))
        val d = EspnDetailMapper.detail(dto, mnfId, null)!!
        assertEquals(2 * SportsConstants.HUB_NFL_INJURIES_PER_TEAM, d.injuries.size)
        assertTrue(d.injuries.all { it.side == Side.HOME && it.status == InjuryStatus.OUT })
    }

    // ------------------------------------------------------------------ the team-order trap (arch m1)

    @Test fun reversedSectionsMapIdentically() {
        for ((path, event) in listOf(
            NflHubFixtures.FINAL_MNF to "401873078",
            NflHubFixtures.PRE_GB_LAR to "401873079",
            NflHubFixtures.FINAL_SAFETY to "401873066",
        )) {
            val t = tree(path)
            val reversed = reverseTeamLists(t)
            assertTrue(reversed != t, "the copy differs")
            val a = EspnDetailMapper.detail(decode(t), id(event), mnfNight)
            val b = EspnDetailMapper.detail(decode(reversed), id(event), mnfNight)
            assertEquals(a, b, path)
            assertEquals(EspnDetailMapper.box(decode(t)), EspnDetailMapper.box(decode(reversed)), path)
        }
    }

    // ------------------------------------------------------------------ partial and odd bodies

    @Test fun missingSectionsNeverFail() {
        val bare = EspnSummaryDto(header = header())
        val d = assertNotNull(EspnDetailMapper.detail(bare, mnfId, mnfNight))
        assertEquals(GamePhase.FINAL, d.fetchedPhase)
        assertTrue(d.gameStats.isEmpty() && d.plays.isEmpty() && d.leaders.isEmpty() && d.clips.isEmpty())
        assertNull(d.venue)
        assertNull(d.recapHeadline)
        val box = assertNotNull(EspnDetailMapper.box(bare))
        assertTrue(box.away.tables.isEmpty() && box.home.tables.isEmpty())
    }

    @Test fun anUnusableHeaderIsNull() {
        assertNull(EspnDetailMapper.detail(EspnSummaryDto(), mnfId, null), "no header")
        assertNull(EspnDetailMapper.box(EspnSummaryDto()))
        assertNull(EspnDetailMapper.detail(EspnSummaryDto(header = header(homeId = "4")), mnfId, null), "the same team twice")
        assertNull(EspnDetailMapper.detail(EspnSummaryDto(header = header(state = null)), mnfId, null), "no state")
        assertNull(EspnDetailMapper.detail(EspnSummaryDto(header = header(state = "weird")), mnfId, null))
    }

    @Test fun headerPhases() {
        fun phase(state: String?, name: String?) = EspnDetailMapper.detail(EspnSummaryDto(header = header(state, name)), mnfId, null)?.fetchedPhase
        assertEquals(GamePhase.SCHEDULED, phase("pre", "STATUS_SCHEDULED"))
        assertEquals(GamePhase.LIVE, phase("in", "STATUS_IN_PROGRESS"))
        assertEquals(GamePhase.LIVE, phase("in", "STATUS_DELAYED"))
        assertEquals(GamePhase.INTERMISSION, phase("in", "STATUS_HALFTIME"))
        assertEquals(GamePhase.INTERMISSION, phase("in", "STATUS_END_PERIOD"))
        assertEquals(GamePhase.FINAL, phase("post", "STATUS_FINAL"))
        assertEquals(GamePhase.POSTPONED, phase("post", "STATUS_POSTPONED"))
    }

    @Test fun oddStatValuesDropTheirRowOnly() {
        val teams = listOf(
            EspnBoxTeamDto(EspnTeamRefDto(JsonPrimitive("4")), stats("totalYards" to "abc", "thirdDownEff" to "4/12", "possessionTime" to "28:29", "turnovers" to "1")),
            EspnBoxTeamDto(EspnTeamRefDto(JsonPrimitive("28")), stats("totalYards" to "400", "thirdDownEff" to "2-10", "possessionTime" to "31:31", "turnovers" to "2")),
            EspnBoxTeamDto(EspnTeamRefDto(JsonPrimitive("77")), stats("totalYards" to "1")), // neither side
        )
        val d = EspnDetailMapper.detail(EspnSummaryDto(header = header(state = "in", name = "STATUS_IN_PROGRESS"), boxscore = EspnSumBoxDto(teams)), mnfId, null)!!
        assertEquals(listOf(StatKind.TURNOVERS, StatKind.POSSESSION), d.gameStats.map { it.kind })
    }

    @Test fun negativePassingYardsParse() {
        val teams = listOf(
            EspnBoxTeamDto(EspnTeamRefDto(JsonPrimitive("4")), stats("netPassingYards" to "-5")),
            EspnBoxTeamDto(EspnTeamRefDto(JsonPrimitive("28")), stats("netPassingYards" to "1,204")),
        )
        val d = EspnDetailMapper.detail(EspnSummaryDto(header = header(state = "in"), boxscore = EspnSumBoxDto(teams)), mnfId, null)!!
        assertEquals(listOf(StatRow(StatKind.PASS_YARDS, StatValue.Count(-5), StatValue.Count(1204))), d.gameStats)
    }

    // ------------------------------------------------------------------ box score (§3.5)

    @Test fun mnfBoxTablesBySideWithTheDesignColumns() {
        val box = assertNotNull(EspnDetailMapper.box(NflHubFixtures.summary(NflHubFixtures.FINAL_MNF)))
        assertEquals(listOf(BoxCategory.PASSING, BoxCategory.RUSHING, BoxCategory.RECEIVING), box.away.tables.map { it.category })
        val pass = box.away.tables[0]
        assertEquals(listOf("C/ATT", "YDS", "TD", "INT", "RTG"), pass.columns)
        assertEquals(BoxLine("Joe Burrow", listOf("28/40", "300", "2", "0", "108.3")), pass.lines.single())
        val rush = box.away.tables[1]
        assertEquals(listOf("CAR", "YDS", "AVG", "TD", "LONG"), rush.columns)
        assertEquals(BoxLine("Samaje Perine", listOf("6", "26", "4.3", "0", "11")), rush.lines[0])
        val rec = box.away.tables[2]
        assertEquals(listOf("REC", "YDS", "AVG", "TD", "LONG"), rec.columns)
        assertEquals(BoxLine("Mike Gesicki", listOf("5", "77", "15.4", "0", "32")), rec.lines[0])
        assertTrue(box.home.tables.isNotEmpty())
        assertTrue(box.home.tables[0].lines.none { it.name == "Joe Burrow" }, "WSH's table is WSH's")
        assertTrue(box.away.skaters.isEmpty() && box.away.goalies.isEmpty())
    }

    @Test fun boxLinesAreCappedAndMissingColumnsDash() {
        val athletes = (1..12).map { EspnBoxAthleteDto(EspnBoxAthleteNameDto("R $it"), listOf("1", "2")) }
        val cat = EspnBoxCategoryDto("rushing", listOf("rushingAttempts", "rushingYards"), listOf("CAR", "YDS"), athletes)
        val dto = EspnSummaryDto(header = header(), boxscore = EspnSumBoxDto(players = listOf(EspnBoxPlayersDto(EspnTeamRefDto(JsonPrimitive("4")), listOf(cat)))))
        val box = EspnDetailMapper.box(dto)!!
        val t = box.away.tables.single()
        assertEquals(SportsConstants.HUB_NFL_BOX_LINES, t.lines.size)
        assertEquals(listOf("1", "2", "–", "–", "–"), t.lines[0].values)
        assertTrue(box.home.tables.isEmpty())
    }

    @Test fun preGameBoxIsEmptyNotNull() {
        val box = assertNotNull(EspnDetailMapper.box(NflHubFixtures.summary(NflHubFixtures.PRE_GB_LAR)))
        assertTrue(box.away.tables.isEmpty() && box.home.tables.isEmpty())
    }

    @Test fun surnames() {
        assertEquals("St. Brown", EspnDetailMapper.leaderSurname("A. St. Brown"))
        assertEquals("Stroud", EspnDetailMapper.leaderSurname("C.J. Stroud"))
        assertEquals("Burrow", EspnDetailMapper.leaderSurname("Burrow"))
        assertEquals("J.", EspnDetailMapper.leaderSurname("J. "), "nothing after the dot: the name as is")
    }

    // ------------------------------------------------------------------ live (PROVISIONAL, §8.1)
    //
    // The three `summary-live-401873079-*.json` fixtures are hand-built from the final and pre-game shapes (no live capture
    // existed when P1 ran). Every test here is named `…_provisional` and is replaced with the first real capture.

    private fun live(n: Int, atMs: Long? = ms("2026-11-26T03:00:00Z")): GameDetail =
        assertNotNull(EspnDetailMapper.detail(NflHubFixtures.summary("sports/nfl/hub/summary-live-401873079-$n.json"), id("401873079"), atMs))

    @Test fun liveFirstQuarter_provisional() {
        val d = live(1)
        assertEquals(GamePhase.LIVE, d.fetchedPhase)
        assertTrue(d.seasonStats.isEmpty(), "no season rows once the game is on")
        assertTrue(d.recent.isEmpty())
        assertEquals(
            listOf(StatKind.TOTAL_YARDS, StatKind.PASS_YARDS, StatKind.RUSH_YARDS, StatKind.FIRST_DOWNS, StatKind.THIRD_DOWN, StatKind.TURNOVERS, StatKind.POSSESSION),
            d.gameStats.map { it.kind },
        )
        assertEquals(StatRow(StatKind.TOTAL_YARDS, StatValue.Count(88), StatValue.Count(31)), d.gameStats[0], "away GB first")
        assertEquals(StatRow(StatKind.THIRD_DOWN, StatValue.Fraction(1, 3, true), StatValue.Fraction(0, 2, true)), d.gameStats[4])
        assertEquals(StatValue.Clock(6 * 60 + 12), d.gameStats[6].away)
        assertEquals(
            listOf(ScoringPlay("4018730791101", PeriodLabel(PeriodKind.REG, 1), "6:41", Side.AWAY, PlayScore.FG, "Brandon McManus 44 Yd Field Goal", 3, 0)),
            d.plays,
        )
        assertNull(d.recapHeadline, "the Preview article stays up during the game")
        assertTrue(d.clips.isEmpty())
        // Game leaders: the categories ESPN lists; one side may be missing (GB has no sacks leader yet).
        val sacks = d.leaders.first { it.category == "sacks" }
        assertNull(sacks.away)
        assertEquals(0, sacks.home?.value)
        assertEquals("0.5", sacks.home?.line)
        assertTrue(d.leaders.none { it.category == "totalTackles" })
        assertEquals(4, d.injuries.size, "the pre-game report is still listed")
    }

    @Test fun liveHalftimeWithAnInGameClip_provisional() {
        val d = live(2)
        assertEquals(GamePhase.INTERMISSION, d.fetchedPhase)
        assertEquals(3, d.plays.size)
        assertEquals(listOf(Side.AWAY, Side.HOME, Side.AWAY), d.plays.map { it.side })
        assertEquals(listOf(PlayScore.FG, PlayScore.TD, PlayScore.TD), d.plays.map { it.kind })
        assertEquals(10 to 7, d.plays.last().away to d.plays.last().home)
        val clip = d.clips.single()
        assertEquals(50590001L, clip.videoId)
        assertEquals(ClipKind.PLAY, clip.kind)
        assertEquals(21_000L, clip.durationMs)
        assertEquals(StatRow(StatKind.TURNOVERS, StatValue.Count(1), StatValue.Count(0)), d.gameStats.first { it.kind == StatKind.TURNOVERS })
    }

    @Test fun liveFourthQuarterSafetyAndAnEmbargoedClip_provisional() {
        val d = live(3, atMs = ms("2026-11-26T02:50:00Z"))
        assertEquals(GamePhase.LIVE, d.fetchedPhase)
        assertEquals(PlayScore.SAFETY, d.plays[3].kind)
        assertEquals(Side.HOME, d.plays[3].side)
        assertEquals(10 to 12, d.plays.last().away to d.plays.last().home)
        assertEquals(listOf(50590001L), d.clips.map { it.videoId }, "the safety clip is embargoed until 02:58")
        assertEquals(listOf(50590001L, 50590002L), live(3, atMs = ms("2026-11-26T03:00:00Z")).clips.map { it.videoId }, "oldest first")
        assertEquals(StatRow(StatKind.PASS_YARDS, StatValue.Count(-2), StatValue.Count(199)), d.gameStats.first { it.kind == StatKind.PASS_YARDS })
    }

    @Test fun liveBoxAndReversedOrder_provisional() {
        for (n in 1..3) {
            val path = "sports/nfl/hub/summary-live-401873079-$n.json"
            val box = assertNotNull(EspnDetailMapper.box(NflHubFixtures.summary(path)))
            assertEquals("Jordan Love", box.away.tables.first { it.category == BoxCategory.PASSING }.lines.single().name)
            assertEquals("Matthew Stafford", box.home.tables.first { it.category == BoxCategory.PASSING }.lines.single().name)
            val t = tree(path)
            assertEquals(EspnDetailMapper.detail(decode(t), id("401873079"), mnfNight), EspnDetailMapper.detail(decode(reverseTeamLists(t)), id("401873079"), mnfNight), path)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A CIN (away, "4") at WSH (home, "28") header, home listed first as ESPN does. */
    private fun header(state: String? = "post", name: String? = "STATUS_FINAL", homeId: String = "28") = EspnSumHeaderDto(
        id = JsonPrimitive("401873078"),
        competitions = listOf(
            EspnSumCompetitionDto(
                status = EspnSumStatusDto(EspnSumStatusTypeDto(name = name, state = state)),
                competitors = listOf(
                    EspnSumCompetitorDto(JsonPrimitive(homeId), "home", null, EspnTeamRefDto(JsonPrimitive(homeId), "WSH")),
                    EspnSumCompetitorDto(JsonPrimitive("4"), "away", null, EspnTeamRefDto(JsonPrimitive("4"), "CIN")),
                ),
            ),
        ),
    )

    private fun play(id: String, period: Int, away: Int, home: Int, team: String?) = EspnScoringPlayDto(
        id = JsonPrimitive(id), type = EspnPlayTypeDto(null, "FG", "Field Goal Good"), text = "kick",
        period = EspnPeriodDto(JsonPrimitive(period)), clock = EspnClockDto("1:00"),
        awayScore = JsonPrimitive(away), homeScore = JsonPrimitive(home), team = team?.let { EspnTeamRefDto(JsonPrimitive(it)) },
    )

    private fun stats(vararg p: Pair<String, String>) = p.map { EspnNamedStatDto(it.first, it.second) }

    /** Every team-bearing list reversed: header competitors, boxscore teams and players, leaders, injuries, last five. */
    private fun reverseTeamLists(o: JsonObject): JsonObject {
        fun rev(e: JsonElement?): JsonElement? = (e as? JsonArray)?.let { JsonArray(it.reversed()) } ?: e
        val m = o.toMutableMap()
        for (k in listOf("leaders", "injuries", "lastFiveGames")) m[k]?.let { m[k] = rev(it)!! }
        (m["boxscore"] as? JsonObject)?.let { b ->
            val bm = b.toMutableMap()
            for (k in listOf("teams", "players")) bm[k]?.let { bm[k] = rev(it)!! }
            m["boxscore"] = JsonObject(bm)
        }
        (m["header"] as? JsonObject)?.let { h ->
            val hm = h.toMutableMap()
            val comps = (hm["competitions"] as JsonArray).map { c ->
                val cm = (c as JsonObject).toMutableMap()
                cm["competitors"] = rev(cm["competitors"])!!
                JsonObject(cm)
            }
            hm["competitions"] = JsonArray(comps)
            m["header"] = JsonObject(hm)
        }
        return JsonObject(m)
    }

    /** A deep copy with every string value passed through [f]. */
    private fun rewriteStrings(e: JsonObject, f: (String) -> String): JsonObject = rewrite(e, f) as JsonObject

    private fun rewrite(e: JsonElement, f: (String) -> String): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { rewrite(it.value, f) })
        is JsonArray -> JsonArray(e.map { rewrite(it, f) })
        is JsonPrimitive -> if (e.isString) JsonPrimitive(f(e.content)) else e
    }

    /** The first `lastFiveGames` event of the away team (GB, id "9") whose result is [from] gets [to]. */
    private fun replaceFirstResult(o: JsonObject, from: String, to: String): JsonObject {
        var done = false
        val m = o.toMutableMap()
        m["lastFiveGames"] = JsonArray((o["lastFiveGames"] as JsonArray).map { t ->
            val tm = (t as JsonObject).toMutableMap()
            val teamId = ((tm["team"] as JsonObject)["id"] as JsonPrimitive).content
            if (teamId == "9") {
                tm["events"] = JsonArray((tm["events"] as JsonArray).map { ev ->
                    val em = (ev as JsonObject).toMutableMap()
                    if (!done && (em["gameResult"] as? JsonPrimitive)?.content == from) {
                        em["gameResult"] = JsonPrimitive(to)
                        done = true
                    }
                    JsonObject(em)
                })
            }
            JsonObject(tm)
        })
        return JsonObject(m)
    }
}
