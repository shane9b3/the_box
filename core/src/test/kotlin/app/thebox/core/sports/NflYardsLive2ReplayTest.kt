package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (NFL YDS integration; code review "Tests") The real live2 sequence of 2026-11-16 (LAC @ BAL, 401873065) replayed on its
 * own 10 s board grid through the real `EspnMapper` and `NflYardsPlanner`, with the statistics answering what the sampler
 * read from the per-team feed at that time (`core`): the end of Q1 into the change of ends and the first Q2 play (S1);
 * a team timeout whose yards arrive 20 s late (21:39:14); back-to-back team timeouts (21:43:24, 21:44:24); halftime with
 * the 3/3 reset (21:46:44); the Q3 kick-off (21:59:45–22:00:25). Fixture: `scoreboard-live2-replay-2026-11-16.json`
 * (one trimmed base event; per distinct sample only the status, the situation and the scores).
 */
class NflYardsLive2ReplayTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val doc = json.parseToJsonElement(NflYardsFixtures.text(NflYardsFixtures.LIVE2_REPLAY)).jsonObject
    private val base = doc["base"]!!.jsonObject

    private fun ms(t: String): Long = t.split(":").map { it.toLong() }.let { (h, m, s) -> ((h * 60 + m) * 60 + s) * 1_000 }

    private val samples = doc["samples"]!!.jsonArray.map { it.jsonObject }
    private val core = doc["core"]!!.jsonArray.map { r -> r.jsonArray.let { Triple(ms(it[0].jsonPrimitive.content), it[1].jsonPrimitive.int, it[2].jsonPrimitive.int) } }
    private val polls = doc["polls"]!!.jsonArray.map { it.jsonPrimitive.content }

    /** The board as of [t]: the base event with the last sample at or before [t]. */
    private fun board(t: Long): EspnGameDto {
        val smp = samples.last { ms(it["at"]!!.jsonPrimitive.content) <= t }
        val comp0 = base["competitions"]!!.jsonArray[0].jsonObject
        val competitors = JsonArray(
            comp0["competitors"]!!.jsonArray.map { c ->
                val o = c.jsonObject
                JsonObject(o + ("score" to smp[o["homeAway"]!!.jsonPrimitive.content]!!))
            },
        )
        val comp = JsonObject(comp0 + mapOf("competitors" to competitors, "status" to smp["status"]!!, "situation" to smp["situation"]!!))
        val event = JsonObject(base + ("competitions" to JsonArray(listOf(comp))))
        return json.decodeFromJsonElement(EspnGameDto.serializer(), JsonObject(mapOf("events" to JsonArray(listOf(event)))))
    }

    private class Poll(val at: String, val snap: GameSnapshot, val key: String?, val fetched: Boolean) {
        val d get() = snap.detail as LeagueDetail.Nfl
        val yards get() = d.awayYards to d.homeYards
    }

    /** Every poll of the recording through the mapper and the planner (one follow), as `EspnScoreSource` runs the step. */
    private val replay: Map<String, Poll> by lazy {
        val p = NflYardsPlanner()
        polls.associateWith { at ->
            val t = ms(at)
            val s = requireNotNull(EspnMapper.game(board(t), NflYardsFixtures.LAC_AT_BAL, null, "https://a.espncdn.com/")) { at }
            val key = p.due(s, t)
            if (key != null) {
                val (_, a, h) = core.last { it.first <= t } // what the per-team feed answered at that moment
                p.fetched(key, t + 300, YardsResult.Ok(a, h))
            }
            Poll(at, p.attach(s, t), NflYards.stoppageKey(s), key != null)
        }
    }

    private fun at(t: String) = requireNotNull(replay[t]) { t }
    private fun fetches(from: String, to: String) = replay.values.count { it.fetched && it.at >= from && it.at <= to }
    private val none = null to null

    @Test fun theEndOfQ1ChangeOfEndsAndFirstPlayAreOneE1Stoppage() {
        assertNull(at("20:58:03").key)
        val change = polls.filter { it in "20:58:13".."21:01:13" }
        assertEquals(19, change.size)
        for (t in change) {
            val p = at(t)
            assertEquals(GamePhase.INTERMISSION, p.snap.phase, t)
            assertEquals(1, p.snap.period, t)
            assertEquals(NflBreak.END_PERIOD, p.d.breakKind, t)
            assertEquals("E1", p.key, t)
            assertEquals(112 to 40, p.yards, t)
        }
        assertEquals(2, fetches("20:58:03", "21:01:23"), "one fetch and one refresh")
        val first = at("21:01:23")
        assertEquals(GamePhase.LIVE, first.snap.phase)
        assertEquals(2, first.snap.period)
        assertNull(first.key)
        assertEquals(none, first.yards)
    }

    @Test fun aTeamTimeoutWithLateYardsShowsTheCorrectedFigureBeforeThePlay() {
        assertEquals(none, at("21:39:04").yards)
        // 21:39:14: the type 21 is on the board; the feed still reads 110 (the 9-yard scramble is not in it yet).
        for (t in listOf("21:39:14", "21:39:24", "21:39:34")) assertEquals(175 to 110, at(t).yards, t)
        // The refresh rides the 21:39:34 poll (≥ 12 s) and reads 118; used from 21:39:44 (≥ 5 s), before the next play.
        for (t in listOf("21:39:44", "21:39:54")) assertEquals(175 to 118, at(t).yards, t)
        assertEquals(none, at("21:40:04").yards)
        assertEquals(2, fetches("21:39:14", "21:40:04"))
    }

    @Test fun backToBackTeamTimeoutsAreTwoStoppages() {
        assertTrue(at("21:43:24").key!!.startsWith("T"))
        for (t in listOf("21:43:24", "21:43:54", "21:44:14")) assertEquals(175 to 143, at(t).yards, t)
        assertEquals(at("21:43:24").key, at("21:44:14").key, "the clock moving on the same lastPlay keeps the key")
        val second = at("21:44:24")
        assertTrue(second.key!!.startsWith("T") && second.key != at("21:44:14").key)
        assertEquals(175 to 143, second.yards, "a new key reads afresh: the feed still had 143")
        assertEquals(175 to 143, at("21:44:34").yards)
        // Refresh on 21:44:44 (161 in the feed since 21:44:34), used on 21:44:54: 10 s before the field goal.
        assertEquals(175 to 161, at("21:44:54").yards)
        assertEquals(175 to 161, at("21:45:04").yards)
        assertEquals(none, at("21:45:14").yards)
        assertEquals(4, fetches("21:43:14", "21:45:14"))
    }

    @Test fun halftimeKeepsItsYardsThroughTheTimeoutResetAndQ3StartsWithout() {
        val ht = at("21:45:24")
        assertEquals(GamePhase.INTERMISSION, ht.snap.phase)
        assertEquals(NflBreak.HALFTIME, ht.d.breakKind)
        assertEquals("H2", ht.key)
        assertEquals("65", ht.d.lastPlay?.typeId)
        assertEquals(175 to 161, ht.yards, "settled at the first sample")
        assertEquals(1 to 0, ht.d.awayTimeouts to ht.d.homeTimeouts)
        val reset = at("21:46:44")
        assertEquals(3 to 3, reset.d.awayTimeouts to reset.d.homeTimeouts, "the counts reset about 80 s into halftime")
        assertEquals("H2", reset.key)
        assertEquals(175 to 161, reset.yards)
        assertEquals(2, fetches("21:45:24", "21:47:04"))
        // 21:59:45: still STATUS_HALFTIME, with the Q3 kick-off as lastPlay: the same H2 stoppage.
        val kick = at("21:59:45")
        assertEquals(NflBreak.HALFTIME, kick.d.breakKind)
        assertEquals("53", kick.d.lastPlay?.typeId)
        assertEquals("H2", kick.key)
        assertEquals(175 to 161, kick.yards)
        for (t in listOf("21:59:55", "22:00:05", "22:00:25")) {
            assertEquals(GamePhase.LIVE, at(t).snap.phase, t)
            assertEquals(3, at(t).snap.period, t)
            assertNull(at(t).key, t)
            assertEquals(none, at(t).yards, t)
        }
        assertEquals(0, fetches("21:59:35", "22:00:25"), "no fetch after halftime's two")
    }
}
