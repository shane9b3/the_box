package app.thebox.core.sports

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** (NFL YDS §1.5, §1.7) The stoppage keys, the competitor ids and the total-yards read. */
class NflYardsTest {
    private fun stacked(name: String): GameSnapshot = NflStackedFixtures.snapshot(name) ?: error("$name did not map")
    private fun yds(name: String): GameSnapshot = NflYardsFixtures.snapshot(name) ?: error("$name did not map")

    private fun withLastPlay(s: GameSnapshot, id: String, type: String): GameSnapshot {
        val d = s.detail as LeagueDetail.Nfl
        return s.copy(detail = d.copy(lastPlay = NflLastPlay(id, type, null, null, null)))
    }

    // ---------------------------------------------------------------- stoppageKey (§1.5)

    @Test fun aTeamTimeoutIsKeyedByItsPlayAndEndsWithTheNextPlay() {
        assertEquals("T401873061520", NflYards.stoppageKey(stacked(NflStackedFixtures.TEAM_TIMEOUT_TYPE21)))
        assertEquals("T401873061520", NflYards.stoppageKey(stacked(NflStackedFixtures.TEAM_TIMEOUT_HELD)))
        assertNull(NflYards.stoppageKey(stacked(NflStackedFixtures.AFTER_TIMEOUT)))
    }

    @Test fun theEightSecondFallbackTimeoutMarkHasNoKeyOfItsOwn() {
        // The machine's fallback mark is a dropped count without a type-21 play: nothing is fetched for it.
        val t21 = stacked(NflStackedFixtures.TEAM_TIMEOUT_TYPE21)
        val fallback = withLastPlay(t21, "401873061521", "24")
        assertEquals(2, (fallback.detail as LeagueDetail.Nfl).awayTimeouts)
        assertNull(NflYards.stoppageKey(fallback))
    }

    @Test fun officialTimeoutsAndTheTwoMinuteWarningAreStoppages() {
        assertEquals("O401873065704", NflYards.stoppageKey(yds(NflYardsFixtures.OFFICIAL_TIMEOUT)))
        assertEquals("O401873065704", NflYards.stoppageKey(yds(NflYardsFixtures.OFFICIAL_TIMEOUT_HELD)))
        // RC9: the kick-off after a score with a 74.
        assertEquals("O401873062388", NflYards.stoppageKey(stacked(NflStackedFixtures.OFFICIAL_TIMEOUT_KICKOFF_REDZONE)))
        val twoMinute = withLastPlay(yds(NflYardsFixtures.OFFICIAL_TIMEOUT), "401873065999", SportsConstants.NFL_TYPE_TWO_MINUTE_WARNING)
        assertEquals("O401873065999", NflYards.stoppageKey(twoMinute))
    }

    @Test fun theEndOfAQuarterIncludingTheChangeOfEndsIsE1() {
        assertEquals("E1", NflYards.stoppageKey(yds(NflYardsFixtures.END_PERIOD)))
        assertEquals("E1", NflYards.stoppageKey(yds(NflYardsFixtures.CHANGE_OF_ENDS)))
        assertNull(NflYards.stoppageKey(yds(NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS)))
    }

    @Test fun halftimeAndFinal() {
        val end = yds(NflYardsFixtures.END_PERIOD)
        val half = end.copy(period = 2, detail = (end.detail as LeagueDetail.Nfl).copy(breakKind = NflBreak.HALFTIME))
        assertEquals("H2", NflYards.stoppageKey(half))
        val fin = end.copy(phase = GamePhase.FINAL, period = 4, finalKind = PeriodKind.REG, detail = (end.detail as LeagueDetail.Nfl).copy(breakKind = null))
        assertEquals("F", NflYards.stoppageKey(fin))
        assertEquals("F", NflYards.stoppageKey(SportsFixtures.nflGame(phase = GamePhase.FINAL, detail = SportsFixtures.nflDetail())))
    }

    @Test fun inPlayPreAndTheNhlHaveNoKey() {
        assertNull(NflYards.stoppageKey(stacked(NflStackedFixtures.PENALTY_TYPE8)))
        val live = yds(NflYardsFixtures.OFFICIAL_TIMEOUT)
        assertNull(NflYards.stoppageKey(withLastPlay(live, "1", "2"))) // a type 2 outside the S1 shape
        assertNull(NflYards.stoppageKey(withLastPlay(live, "1", "65")))
        assertNull(NflYards.stoppageKey(withLastPlay(live, "1", "66")))
        assertNull(NflYards.stoppageKey(live.copy(detail = (live.detail as LeagueDetail.Nfl).copy(lastPlay = null))))
        assertNull(NflYards.stoppageKey(live.copy(phase = GamePhase.PRE)))
        assertNull(NflYards.stoppageKey(live.copy(phase = GamePhase.SCHEDULED)))
        assertNull(NflYards.stoppageKey(SportsFixtures.game()))
        assertNull(NflYards.stoppageKey(SportsFixtures.game(phase = GamePhase.FINAL)))
    }

    // ---------------------------------------------------------------- teamIds (§1.7)

    @Test fun teamIdsAreAwayThenHome() {
        val e = NflYardsFixtures.board(NflYardsFixtures.OFFICIAL_TIMEOUT).events!!.single()
        assertEquals(NflYardsFixtures.LAC_ID to NflYardsFixtures.BAL_ID, NflYards.teamIds(e))
    }

    private fun event(vararg competitors: EspnCompetitorDto) =
        EspnEventDto(JsonPrimitive("1"), JsonPrimitive("2026-11-16T18:00Z"), listOf(EspnCompetitionDto(competitors.toList())))

    private fun competitor(id: JsonElement?, side: String, teamId: String? = null) = EspnCompetitorDto(
        id = id,
        homeAway = JsonPrimitive(side),
        team = teamId?.let { JsonObject(mapOf("id" to JsonPrimitive(it))) },
    )

    @Test fun teamIdsFallBackToTheTeamAndRefuseAnythingElse() {
        assertEquals("12" to "7", NflYards.teamIds(event(competitor(null, "away", "12"), competitor(JsonPrimitive(7), "home"))))
        assertNull(NflYards.teamIds(event(competitor(JsonPrimitive("12"), "away"))))
        assertNull(NflYards.teamIds(event(competitor(JsonPrimitive("1234567"), "away"), competitor(JsonPrimitive("7"), "home"))))
        assertNull(NflYards.teamIds(event(competitor(JsonPrimitive("../x"), "away"), competitor(JsonPrimitive("7"), "home"))))
        assertNull(NflYards.teamIds(event(competitor(JsonObject(emptyMap()), "away"), competitor(JsonPrimitive("7"), "home"))))
        assertNull(NflYards.teamIds(EspnEventDto(JsonPrimitive("1"), null, null)))
    }

    // ---------------------------------------------------------------- totalYards (§1.7, S3)

    private fun total(value: JsonElement?, display: JsonElement?) = EspnStatsDto(
        EspnStatsSplitsDto(
            listOf(
                EspnStatsCategoryDto(JsonPrimitive("general"), listOf(EspnStatDto(JsonPrimitive("fumbles"), JsonPrimitive(1.0), JsonPrimitive("1")))),
                EspnStatsCategoryDto(JsonPrimitive("passing"), listOf(EspnStatDto(JsonPrimitive("totalYards"), value, display))),
            ),
        ),
    )

    @Test fun theFixturesRead() {
        assertEquals(123, NflYards.totalYards(NflYardsFixtures.stats(NflYardsFixtures.STATS_FINAL)))
        assertEquals(-2, NflYards.totalYards(NflYardsFixtures.stats(NflYardsFixtures.STATS_NEGATIVE)))
        assertNull(NflYards.totalYards(NflYardsFixtures.stats(NflYardsFixtures.STATS_NO_TOTAL)))
    }

    @Test fun theValueWinsThenTheDisplayValue() {
        assertEquals(142, NflYards.totalYards(total(JsonPrimitive(142.0), JsonPrimitive("999"))))
        assertEquals(142, NflYards.totalYards(total(JsonPrimitive("142"), null)))
        assertEquals(87, NflYards.totalYards(total(JsonPrimitive(87.5), JsonPrimitive("87"))))
        assertEquals(87, NflYards.totalYards(total(null, JsonPrimitive("87"))))
        assertEquals(-2, NflYards.totalYards(total(null, JsonPrimitive("-2"))))
        assertNull(NflYards.totalYards(total(null, JsonPrimitive("1,234"))))
        assertNull(NflYards.totalYards(total(null, JsonPrimitive("x"))))
        assertNull(NflYards.totalYards(total(JsonPrimitive(Double.NaN), null)))
        assertNull(NflYards.totalYards(total(JsonObject(emptyMap()), null)))
        assertNull(NflYards.totalYards(EspnStatsDto(null)))
    }

    @Test fun valuesOutsideTheRangeAreNull() {
        assertEquals(999, NflYards.totalYards(total(JsonPrimitive(999), null)))
        assertEquals(-99, NflYards.totalYards(total(JsonPrimitive(-99), null)))
        assertNull(NflYards.totalYards(total(JsonPrimitive(1000), null)))
        assertNull(NflYards.totalYards(total(JsonPrimitive(-100), null)))
        assertNull(NflYards.totalYards(total(null, JsonPrimitive("1000"))))
        assertNull(NflYards.totalYards(total(JsonPrimitive(1e12), null)))
    }

    @Test fun theFirstTotalInAnyCategoryIsTaken() {
        val dto = EspnStatsDto(
            EspnStatsSplitsDto(
                listOf(
                    EspnStatsCategoryDto(JsonPrimitive("rushing"), listOf(EspnStatDto(JsonPrimitive("totalYards"), JsonPrimitive(50), null))),
                    EspnStatsCategoryDto(JsonPrimitive("passing"), listOf(EspnStatDto(JsonPrimitive("totalYards"), JsonPrimitive(60), null))),
                ),
            ),
        )
        assertEquals(50, NflYards.totalYards(dto))
    }
}
