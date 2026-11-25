package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.Market
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.TvBroadcast
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-sports-hub.md §4.1, §10.2: the final and future fixtures in full, partial bodies, the box score. */
class NhlDetailMapperTest {
    private val uta = GameId(League.NHL, "2026020382")
    private val vgk = GameId(League.NHL, "2026020385")

    private fun obj(path: String) = Json.parseToJsonElement(SportsFixtures.text(path)) as JsonObject
    private fun body(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun final382(landing: Boolean = true): GameDetail = assertNotNull(
        NhlDetailMapper.detail(uta, obj(HubFixtures.GC_382_RIGHT_RAIL), if (landing) obj(HubFixtures.GC_382_LANDING) else null, 42L),
    )

    @Test fun finalGameInFull() {
        val d = final382()
        assertEquals(GamePhase.FINAL, d.fetchedPhase)
        assertEquals(42L, d.serverDateMs)
        assertEquals(2, d.awayScore)
        assertEquals(4, d.homeScore)
        assertEquals("Canada Life Centre", d.venue)
        assertEquals(6408795671112L, d.recapId)
        assertEquals(6408795191112L, d.condensedId)
        assertEquals(
            listOf(
                StatRow(StatKind.SOG, StatValue.Count(35), StatValue.Count(15)),
                StatRow(StatKind.POWER_PLAY, StatValue.Fraction(0, 2), StatValue.Fraction(0, 2)),
                StatRow(StatKind.HITS, StatValue.Count(20), StatValue.Count(31)),
                StatRow(StatKind.BLOCKED, StatValue.Count(8), StatValue.Count(12)),
                StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(0.727273), StatValue.Pct(0.272727)),
                StatRow(StatKind.PIM, StatValue.Count(16), StatValue.Count(14)),
                StatRow(StatKind.GIVEAWAYS, StatValue.Count(24), StatValue.Count(16)),
                StatRow(StatKind.TAKEAWAYS, StatValue.Count(2), StatValue.Count(8)),
            ),
            d.gameStats,
        )
        assertEquals(
            listOf(
                PeriodScore(PeriodLabel(PeriodKind.REG, 1), 2, 3),
                PeriodScore(PeriodLabel(PeriodKind.REG, 2), 0, 0),
                PeriodScore(PeriodLabel(PeriodKind.REG, 3), 0, 1),
            ),
            d.periods,
        )
        assertEquals(SeriesFacts(awayWins = 0, homeWins = 1, meetingsPlayed = 1), d.series)
        assertNull(d.season, "a final right-rail has no season block and the final landing no record")
        assertEquals(listOf(TvBroadcast("SN+", Market.NATIONAL, "CA"), TvBroadcast("Mammoth+", Market.AWAY, "US"), TvBroadcast("Utah16", Market.AWAY, "US")), d.broadcasts)
        assertEquals(emptyList(), d.leaders, "the final landing has no matchup")
        assertNull(d.goalies)
    }

    @Test fun finalGoalsAndStars() {
        val d = final382()
        assertEquals(6, d.goals.size)
        assertEquals(
            GoalItem(
                period = PeriodLabel(PeriodKind.REG, 1), timeInPeriod = "05:11", side = Side.HOME, scorer = "G. Vilardi",
                scorerLast = "Vilardi", scorerTotal = 4, assistsLast = listOf("Connor", "Morrissey"), strength = Strength.EV,
                emptyNet = false, away = 0, home = 1, clipId = 6408791393112L,
                headshot = "https://assets.nhle.com/mugs/nhl/20262027/WPG/8480014.png",
            ),
            d.goals.first(),
        )
        val last = d.goals.last()
        assertEquals(PeriodLabel(PeriodKind.REG, 3), last.period)
        assertTrue(last.emptyNet)
        assertEquals(listOf("Scheifele"), last.assistsLast)
        assertEquals(2 to 4, last.away to last.home)
        assertEquals(Side.AWAY, d.goals[1].side)
        assertEquals(listOf("Björck", "Ferraro"), d.goals[4].assistsLast)
        assertTrue(d.goals.all { it.clipId != null })

        assertEquals(listOf(1, 2, 3), d.stars.map { it.rank })
        assertEquals(Star(1, "S. Skinner", "Skinner", "WPG", "https://assets.nhle.com/mugs/nhl/20262027/WPG/8479973.png"), d.stars[0])
        assertEquals("Vilardi", d.stars[1].lastName)
    }

    @Test fun futureGameInFull() {
        val d = assertNotNull(NhlDetailMapper.detail(vgk, obj(HubFixtures.GC_385_RIGHT_RAIL), obj(HubFixtures.GC_385_LANDING), null))
        assertEquals(GamePhase.SCHEDULED, d.fetchedPhase)
        assertNull(d.awayScore)
        assertNull(d.homeScore)
        assertEquals("Rogers Place", d.venue)
        assertEquals(emptyList(), d.gameStats)
        assertEquals(emptyList(), d.periods)
        assertEquals(emptyList(), d.goals)
        assertEquals(emptyList(), d.stars)
        assertNull(d.recapId)
        assertEquals(SeriesFacts(0, 0, 0), d.series)
        assertEquals(
            SeasonComparison(
                awayRecord = "14-6-5", homeRecord = "17-5-4",
                awayLast10 = Last10("5-1-4", "W", 1), homeLast10 = Last10("6-3-1", "O", 1),
                awayGf = Ranked(3.32, 11), homeGf = Ranked(3.85, 3),
                awayGa = Ranked(2.88, 10), homeGa = Ranked(2.85, 8),
                awayPp = Ranked(0.272727, 4), homePp = Ranked(0.325843, 2),
                awayPk = Ranked(0.790323, 16), homePk = Ranked(0.776471, 17),
            ),
            d.season,
        )
        assertEquals(listOf("points", "goals", "assists"), d.leaders.map { it.category })
        assertEquals(Leader("J. Eichel", 5, "https://assets.nhle.com/mugs/nhl/20262027/VGK/8478403.png"), d.leaders[0].away)
        assertEquals(9, d.leaders[0].home?.value)
        assertEquals("C. McDavid", d.leaders[2].home?.name)
        val goalies = assertNotNull(d.goalies)
        assertEquals(listOf("C. Hart", "A. Hill"), goalies.away.map { it.name })
        assertEquals(Goalie("T. Jarry", "6-1-4", 2.57, 0.907, "https://assets.nhle.com/mugs/nhl/20262027/EDM/8477465.png"), goalies.home[0])
        assertEquals(4, d.broadcasts.size)
    }

    @Test fun landingFailedKeepsTheRightRailHalf() {
        val d = final382(landing = false)
        assertEquals(GamePhase.FINAL, d.fetchedPhase, "the phase from this game's seasonSeries entry")
        assertEquals(8, d.gameStats.size)
        assertEquals(3, d.periods.size)
        assertEquals(emptyList(), d.goals)
        assertEquals(emptyList(), d.stars)
        assertEquals(emptyList(), d.leaders)
        assertNull(d.goalies)
        assertNull(d.venue)
        assertNull(d.awayScore)
        assertEquals(emptyList(), d.broadcasts)
        assertEquals(6408795671112L, d.recapId)
    }

    @Test fun phaseFallsBackToTheHintThenScheduled() {
        val rr = body("""{"linescore":{"byPeriod":[]}}""")
        assertEquals(GamePhase.LIVE, NhlDetailMapper.detail(uta, rr, null, null, GamePhase.LIVE)?.fetchedPhase)
        assertEquals(GamePhase.SCHEDULED, NhlDetailMapper.detail(uta, rr, null, null)?.fetchedPhase)
        // Another game's landing is not used.
        val other = NhlDetailMapper.detail(vgk, obj(HubFixtures.GC_382_RIGHT_RAIL), obj(HubFixtures.GC_382_LANDING), null, GamePhase.PRE)
        assertEquals(GamePhase.PRE, other?.fetchedPhase)
        assertNull(other?.awayScore)
    }

    @Test fun aRightRailWithNothingKnownIsUnmappable() {
        assertNull(NhlDetailMapper.detail(uta, body("""{"message":"x"}"""), null, null))
        assertNull(NhlDetailMapper.detail(uta, body("{}"), obj(HubFixtures.GC_382_LANDING), null))
    }

    @Test fun oddFieldsDropOnlyThemselves() {
        val rr = body(
            """{"teamGameStats":[{"category":"sog","awayValue":"12","homeValue":-1},{"category":"powerPlay","awayValue":"3/2","homeValue":"1/4"},
               {"category":"powerPlay","awayValue":"1/2","homeValue":"0/3"},{"category":"hits","awayValue":4,"homeValue":5},
               {"category":"faceoffWinningPctg","awayValue":1.5,"homeValue":0.5},{"category":"unknown","awayValue":1,"homeValue":2},"x"],
               "linescore":{"byPeriod":[{"periodDescriptor":{"number":4,"periodType":"OT","maxRegulationPeriods":3},"away":1,"home":0},
                 {"periodDescriptor":{"number":5,"periodType":"SO"},"away":0,"home":1},{"periodDescriptor":{"number":2,"periodType":"XX"},"away":0,"home":0},
                 {"away":1,"home":1}]},
               "seasonSeriesWins":{"awayTeamWins":"x","homeTeamWins":1},
               "gameVideo":{"threeMinRecap":"6408795671112","condensedGame":null},
               "teamSeasonStats":{"awayTeam":{"ppPctg":"bad","pkPctg":0.8,"pkPctgRank":99}}}""",
        )
        val landing = body(
            """{"id":2026020382,"summary":{"scoring":[{"periodDescriptor":{"number":1,"periodType":"REG"},"goals":[
               {"isHome":false,"awayScore":1,"homeScore":0,"name":{"default":"A. Peeke"},"strength":"pp","assists":[{"name":{"default":"L. But"}}]},
               {"isHome":true,"awayScore":1},{"awayScore":1,"homeScore":1,"name":{"default":"X"}}]}],
               "threeStars":[{"star":4,"name":{"default":"A"},"teamAbbrev":"WPG"},{"star":1,"name":{"default":"B. Cee"},"teamAbbrev":{"default":"UTA"},"headshot":"http://insecure"}]}}""",
        )
        val d = assertNotNull(NhlDetailMapper.detail(uta, rr, landing, null))
        assertEquals(
            listOf(
                StatRow(StatKind.POWER_PLAY, StatValue.Fraction(1, 2), StatValue.Fraction(0, 3)),
                StatRow(StatKind.HITS, StatValue.Count(4), StatValue.Count(5)),
            ),
            d.gameStats,
        )
        assertEquals(listOf(PeriodScore(PeriodLabel(PeriodKind.OT, 1), 1, 0), PeriodScore(PeriodLabel(PeriodKind.SO, 1), 0, 1)), d.periods)
        assertNull(d.series)
        assertEquals(6408795671112L, d.recapId)
        assertNull(d.condensedId)
        assertEquals(Ranked(0.8, null), d.season?.awayPk)
        assertNull(d.season?.awayPp)
        val g = d.goals.single()
        assertEquals(Strength.PP, g.strength)
        assertEquals("Peeke", g.scorerLast)
        assertEquals(listOf("But"), g.assistsLast)
        assertNull(g.scorerTotal)
        assertNull(g.clipId)
        assertEquals(listOf(Star(1, "B. Cee", "Cee", "UTA", null)), d.stars)
    }

    @Test fun boxScoreFinalAndFuture() {
        val b = assertNotNull(NhlDetailMapper.boxScore(obj(HubFixtures.GC_382_BOXSCORE)))
        assertEquals(18, b.away.skaters.size)
        assertEquals(18, b.home.skaters.size)
        assertEquals(SkaterLine("C. Keller", "R", 0, 0, 0, -3, 0, "16:59"), b.away.skaters.first())
        assertEquals(GoalieLine("S. Cossa", 14, 11, 0.785714, "57:50"), b.away.goalies.first())
        assertEquals(GoalieLine("N. Daws", 0, 0, null, "00:00"), b.home.goalies.first())
        assertEquals("N. Pionk", b.home.skaters.first { it.pos == "D" }.name)

        val f = assertNotNull(NhlDetailMapper.boxScore(obj(HubFixtures.GC_385_BOXSCORE)), "before the game: empty, not a failure")
        assertTrue(f.away.skaters.isEmpty() && f.home.goalies.isEmpty())
        assertNull(NhlDetailMapper.boxScore(body("""{"message":"Not found"}""")))
    }

    // ------------------------------------------------------------------ the live recording (A-S9; gated)

    @Test fun recordedLiveBodiesMap() {
        val live = HubFixtures.LIVE
        if (live.isEmpty()) return // gated until the recording lands (§10.5)
        for (rrPath in live.filter { it.contains("right-rail") }) {
            val landingPath = rrPath.replace("right-rail", "landing")
            val landing = if (landingPath in live) obj(landingPath) else null
            val d = assertNotNull(NhlDetailMapper.detail(vgk, obj(rrPath), landing, null), rrPath)
            if (landing != null) {
                assertTrue(d.fetchedPhase != GamePhase.SCHEDULED, rrPath)
                assertNotNull(d.awayScore, rrPath)
                assertEquals(d.goals.size, (d.awayScore ?: 0) + (d.homeScore ?: 0) - shootoutWinner(d), rrPath)
            }
            if (d.fetchedPhase == GamePhase.LIVE || d.fetchedPhase == GamePhase.INTERMISSION || d.fetchedPhase == GamePhase.FINAL) {
                assertTrue(d.gameStats.any { it.kind == StatKind.SOG }, rrPath)
                assertTrue(d.periods.isNotEmpty(), rrPath)
            }
        }
        for (box in live.filter { it.contains("boxscore") }) {
            val b = assertNotNull(NhlDetailMapper.boxScore(obj(box)), box)
            assertTrue(b.away.skaters.isNotEmpty() && b.home.goalies.isNotEmpty(), box)
        }
    }

    @Test fun recordedFirstPeriodAndIntermission() {
        if (HubFixtures.LIVE.isEmpty()) return
        fun at(phase: String) = assertNotNull(
            NhlDetailMapper.detail(
                vgk, obj(HubFixtures.LIVE.single { it.endsWith("right-rail-$phase.json") }),
                obj(HubFixtures.LIVE.single { it.endsWith("landing-$phase.json") }), null,
            ),
        )
        val p1 = at("p1")
        assertEquals(GamePhase.LIVE, p1.fetchedPhase)
        assertEquals(2 to 0, p1.awayScore to p1.homeScore)
        assertEquals(listOf(Strength.PP, Strength.EV), p1.goals.map { it.strength })
        assertEquals(listOf(6408849287112L, null), p1.goals.map { it.clipId })
        assertEquals(listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 2, 0)), p1.periods)
        assertEquals(8, p1.gameStats.size)
        assertEquals(emptyList(), p1.stars, "threeStars is an empty list while live")
        assertEquals(emptyList(), p1.leaders, "a live landing has no matchup")
        assertNull(p1.goalies)
        assertNull(p1.season, "a live right-rail has no season block and a live landing no record")
        assertNull(p1.recapId)
        assertEquals(SeriesFacts(0, 0, 0), p1.series)
        val int = at("int")
        assertEquals(GamePhase.INTERMISSION, int.fetchedPhase)
        assertEquals(2, int.goals.size)
    }

    /** (Integration) The home power play in the 2nd period and the 3rd period of the recorded game. */
    @Test fun recordedPowerPlayAndThirdPeriod() {
        fun at(phase: String) = assertNotNull(
            NhlDetailMapper.detail(
                vgk, obj(HubFixtures.LIVE.single { it.endsWith("right-rail-$phase.json") }),
                obj(HubFixtures.LIVE.single { it.endsWith("landing-$phase.json") }), null,
            ),
        )
        val pp = at("pp")
        assertEquals(GamePhase.LIVE, pp.fetchedPhase)
        assertEquals(2 to 1, pp.awayScore to pp.homeScore)
        assertEquals(listOf(Strength.PP, Strength.EV, Strength.EV), pp.goals.map { it.strength })
        assertEquals(listOf(6408849287112L, 6408850179112L, 6408851594112L), pp.goals.map { it.clipId })
        assertEquals(
            listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 2, 0), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 0, 1)),
            pp.periods,
        )
        assertTrue(pp.gameStats.any { it.kind == StatKind.SOG })

        val p3 = at("p3")
        assertEquals(GamePhase.LIVE, p3.fetchedPhase)
        assertEquals(4 to 3, p3.awayScore to p3.homeScore)
        assertEquals(7, p3.goals.size)
        assertEquals(listOf(null, null, null), p3.goals.takeLast(3).map { it.clipId }, "the last three goals' clips are not posted yet")
        assertEquals(listOf(2 to 0, 0 to 2, 2 to 1), p3.periods.map { it.away to it.home })
        assertEquals(listOf(1, 2, 3), p3.periods.map { it.label.number })
        assertNull(p3.recapId)
        assertEquals(emptyList(), p3.stars, "threeStars is empty while live")

        // The final in regulation, before the recap is posted.
        val fin = at("final")
        assertEquals(GamePhase.FINAL, fin.fetchedPhase)
        assertEquals(5 to 3, fin.awayScore to fin.homeScore)
        assertEquals(8, fin.goals.size)
        assertNull(fin.goals.last().clipId, "the last goal's clip is not posted yet")
        assertEquals(listOf(2 to 0, 0 to 2, 3 to 1), fin.periods.map { it.away to it.home })
        assertTrue(fin.periods.all { it.label.kind == PeriodKind.REG })
        assertEquals(3, fin.stars.size)
        assertNull(fin.recapId, "no recap minutes after the final")
    }

    private fun shootoutWinner(d: GameDetail): Int = if (d.periods.any { it.label.kind == PeriodKind.SO }) 1 else 0
}
