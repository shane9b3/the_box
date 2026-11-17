package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/**
 * (NFL YDS §1.12, S1) ESPN's change-of-ends shape (IN_PROGRESS, period 2 or 4, 15:00, `lastPlay` type 2) is mapped as
 * the `STATUS_END_PERIOD` body of the previous quarter, and ends at the first new `lastPlay`. The existing `EspnMapper*`
 * tests are unedited.
 */
class EspnMapperChangeOfEndsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun map(name: String): GameSnapshot = NflYardsFixtures.snapshot(name) ?: error("$name did not map")

    /** The change-of-ends body with [edit] applied to its text. */
    private fun variant(edit: (String) -> String): GameSnapshot {
        val text = edit(NflYardsFixtures.text(NflYardsFixtures.CHANGE_OF_ENDS))
        val dto = json.decodeFromString(EspnGameDto.serializer(), text)
        return EspnMapper.game(dto, NflYardsFixtures.LAC_AT_BAL, null, "https://a.espncdn.com/")!!
    }

    private fun nfl(s: GameSnapshot) = s.detail as LeagueDetail.Nfl

    @Test fun theEndPeriodSampleIsTheEndOfTheFirst() {
        val s = map(NflYardsFixtures.END_PERIOD)
        assertEquals(GamePhase.INTERMISSION, s.phase)
        assertEquals(NflBreak.END_PERIOD, nfl(s).breakKind)
        assertEquals(1, s.period)
        assertEquals(GameClock(0, running = false, inIntermission = true), s.clock)
    }

    @Test fun theChangeOfEndsIsMappedExactlyAsTheEndPeriodBody() {
        val end = map(NflYardsFixtures.END_PERIOD)
        val coe = map(NflYardsFixtures.CHANGE_OF_ENDS)
        assertEquals(GamePhase.INTERMISSION, coe.phase)
        assertEquals(NflBreak.END_PERIOD, nfl(coe).breakKind)
        assertEquals(1, coe.period)
        assertEquals(PeriodKind.REG, coe.periodKind)
        assertNull(coe.otNumber)
        assertNull(coe.finalKind)
        assertEquals(GameClock(0, running = false, inIntermission = true), coe.clock)
        // The situation is mapped as usual: counts, possession and the same type-2 lastPlay.
        assertEquals("4018730651101", nfl(coe).lastPlay?.id)
        assertEquals(SportsConstants.NFL_TYPE_END_PERIOD, nfl(coe).lastPlay?.typeId)
        assertEquals(3, nfl(coe).awayTimeouts)
        assertEquals(3, nfl(coe).homeTimeouts)
        // Everything but the situation's own fields equals the END_PERIOD sample's mapping.
        assertEquals(end.copy(detail = null), coe.copy(detail = null))
        assertEquals(nfl(end).breakKind, nfl(coe).breakKind)
        assertEquals(nfl(end).lastPlay, nfl(coe).lastPlay)
    }

    @Test fun theFirstSnapAfterItIsLiveInTheSecondAtFifteen() {
        val s = map(NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS)
        assertEquals(GamePhase.LIVE, s.phase)
        assertEquals(2, s.period)
        assertEquals(900, s.clock?.secondsRemaining)
        assertEquals("5", nfl(s).lastPlay?.typeId)
        assertNull(nfl(s).breakKind)
    }

    @Test fun theFourthQuarterShapeIsTheEndOfTheThird() {
        val s = variant { it.replace("\"period\":2", "\"period\":4") }
        assertEquals(GamePhase.INTERMISSION, s.phase)
        assertEquals(NflBreak.END_PERIOD, nfl(s).breakKind)
        assertEquals(3, s.period)
        assertEquals(0, s.clock?.secondsRemaining)
    }

    @Test fun otherPeriodsAndTheHalftimeStayAsToday() {
        // Period 3 (the start of Q3) and period 5 (overtime): no change-of-ends rule.
        val p3 = variant { it.replace("\"period\":2", "\"period\":3") }
        assertEquals(GamePhase.LIVE, p3.phase)
        assertEquals(3, p3.period)
        assertEquals(900, p3.clock?.secondsRemaining)
        val p5 = variant { it.replace("\"period\":2", "\"period\":5") }
        assertEquals(GamePhase.LIVE, p5.phase)
        assertEquals(5, p5.period)
        assertEquals(PeriodKind.OT, p5.periodKind)
        // A clock below 900 ends it (a play ESPN failed to log).
        val run = variant { it.replace("\"clock\":900.0,\"displayClock\":\"15:00\"", "\"clock\":899.0,\"displayClock\":\"14:59\"") }
        assertEquals(GamePhase.LIVE, run.phase)
        assertEquals(2, run.period)
        assertEquals(899, run.clock?.secondsRemaining)
        // STATUS_HALFTIME keeps its own mapping (period 2, HALFTIME).
        val half = variant { it.replace("STATUS_IN_PROGRESS", "STATUS_HALFTIME") }
        assertEquals(GamePhase.INTERMISSION, half.phase)
        assertEquals(NflBreak.HALFTIME, nfl(half).breakKind)
        assertEquals(2, half.period)
        // A last play of another type at 15:00 is in play.
        val other = variant { it.replace("\"type\":{\"id\":\"2\",\"text\":\"End Period\"", "\"type\":{\"id\":\"5\",\"text\":\"Rush\"") }
        assertEquals(GamePhase.LIVE, other.phase)
        assertEquals(2, other.period)
        // Postponed / cancelled `in` bodies are not touched.
        val ppd = variant { it.replace("STATUS_IN_PROGRESS", "STATUS_POSTPONED") }
        assertEquals(GamePhase.LIVE, ppd.phase)
        assertEquals(2, ppd.period)
    }

    @Test fun theSequenceEndsAtTheFirstNewLastPlay() {
        val seq = listOf(NflYardsFixtures.END_PERIOD, NflYardsFixtures.CHANGE_OF_ENDS, NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS)
            .map { map(it) }
        assertEquals(listOf(GamePhase.INTERMISSION, GamePhase.INTERMISSION, GamePhase.LIVE), seq.map { it.phase })
        assertEquals(listOf(1, 1, 2), seq.map { it.period })
    }
}
