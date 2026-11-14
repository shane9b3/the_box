package app.thebox.core.sports

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * (CHL §3.1) Lenient parsing never throws: every recorded body, mutated at random (fields dropped, strings truncated,
 * types swapped, values replaced with extremes), through every mapper entry point and the derivation. Deterministic
 * (fixed seeds).
 */
class HockeyTechMapperFuzzTest {

    private val extremes: List<JsonElement> = listOf(
        JsonNull, JsonPrimitive(""), JsonPrimitive("-"), JsonPrimitive(":"), JsonPrimitive("::"), JsonPrimitive("99:99:99"),
        JsonPrimitive("-1:-1"), JsonPrimitive(-1), JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive(Int.MAX_VALUE),
        JsonPrimitive(Long.MAX_VALUE), JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(1e300), JsonPrimitive(-0.5),
        JsonPrimitive(Double.NaN.toString()), JsonPrimitive(true), JsonPrimitive(false), JsonPrimitive("1"), JsonPrimitive("0"),
        JsonPrimitive("99999999999999999999"), JsonPrimitive("2026-13-45"), JsonPrimitive("25:61:00"), JsonPrimitive("Mars/Base"),
        JsonPrimitive("\u0000"), JsonPrimitive("é".repeat(40)), JsonObject(emptyMap()), JsonArray(emptyList()),
        JsonArray(listOf(JsonNull, JsonPrimitive(3))),
    )

    private fun mutate(e: JsonElement, rnd: Random, depth: Int = 0): JsonElement {
        if (rnd.nextInt(100) < 4) return extremes[rnd.nextInt(extremes.size)]
        return when (e) {
            is JsonObject -> {
                val out = LinkedHashMap<String, JsonElement>()
                for ((k, v) in e) {
                    when (rnd.nextInt(100)) {
                        in 0..4 -> Unit // dropped
                        in 5..9 -> out[k] = swap(v, rnd)
                        else -> out[k] = if (depth < 8) mutate(v, rnd, depth + 1) else v
                    }
                }
                JsonObject(out)
            }
            is JsonArray -> {
                val items = e.filter { rnd.nextInt(100) >= 5 }.map { if (depth < 8) mutate(it, rnd, depth + 1) else it }
                JsonArray(if (rnd.nextInt(100) < 5) items.take(rnd.nextInt(items.size + 1)) else items)
            }
            is JsonPrimitive -> if (rnd.nextInt(100) < 15) swap(e, rnd) else e
        }
    }

    /** A type swap or a truncation of a primitive. */
    private fun swap(v: JsonElement, rnd: Random): JsonElement {
        val p = v as? JsonPrimitive
        return when (rnd.nextInt(6)) {
            0 -> JsonPrimitive(p?.content?.let { it.take(rnd.nextInt(it.length + 1)) } ?: "x") // truncated
            1 -> p?.content?.toLongOrNull()?.let { JsonPrimitive(it.toString()) } ?: JsonPrimitive(rnd.nextInt(-5, 5000))
            2 -> p?.content?.let { c -> c.toLongOrNull()?.let { JsonPrimitive(it) } } ?: JsonPrimitive(p?.content ?: "")
            3 -> JsonArray(listOf(v))
            4 -> JsonObject(mapOf("v" to v))
            else -> extremes[rnd.nextInt(extremes.size)]
        }
    }

    private fun obj(e: JsonElement): JsonObject? = e as? JsonObject

    @Test
    fun mutatedBodiesNeverThrow() {
        val rnd = Random(20261113)
        var calls = 0
        for (client in ChlFixtures.CLIENTS) {
            val league = ChlFixtures.league(client)
            val prefix = ChlFixtures.logoPrefix(client)
            val boardBody = ChlFixtures.board(client)
            val teamsBody = ChlFixtures.teams(client)
            val seasonsBody = ChlFixtures.seasons(client)
            val good = ChlFixtures.mappedBoard(client)
            val nodes = listOf("runningclock", "publishedclock", "goalssummary", "shotssummary", "penalties", "goals", "shootouts")
                .associateWith { ChlFixtures.finals(client, it) }
            val gameIds = nodes.getValue("runningclock").keys.toList()
            repeat(150) { i ->
                val teams = obj(mutate(teamsBody, rnd))?.let { HockeyTechMapper.teams(it, prefix) }
                val seasons = obj(mutate(seasonsBody, rnd))?.let(HockeyTechMapper::seasons)
                val board = obj(mutate(boardBody, rnd))?.let { HockeyTechMapper.board(league, "2026-11-13", it, teams, seasons) }
                calls += 3
                val b = board ?: good
                HockeyTechMapper.overlay(b, obj(mutate(nodes.getValue("runningclock"), rnd)), obj(mutate(nodes.getValue("goalssummary"), rnd)))
                calls++
                val gid = gameIds[i % gameIds.size]
                val facts = b.facts.values.firstOrNull()?.copy(gameId = gid)
                    ?: good.facts.values.first().copy(gameId = gid)
                fun node(n: String): JsonObject? = nodes.getValue(n)[gid]?.let { obj(mutate(it, rnd)) }
                val snap = HockeyTechMapper.followed(
                    league, facts,
                    ChlNodes(
                        node("runningclock"), node("publishedclock"), node("goalssummary"), node("shotssummary"),
                        node("penalties"), node("goals"), node("shootouts"), rnd.nextBoolean() && rnd.nextBoolean(), rnd.nextInt(5) == 0,
                    ),
                )
                HockeyTechMapper.wantsShootouts(snap, facts)
                calls += 2
            }
        }
        assertTrue(calls > 2_000)
    }

    @Test
    fun randomDerivationInputsNeverThrow() {
        val rnd = Random(7)
        val phases = GamePhase.entries
        repeat(5_000) {
            val pens = if (rnd.nextInt(10) == 0) null else List(rnd.nextInt(0, 14)) { i ->
                ChlPenalty(
                    id = rnd.nextLong(-3, 30), home = rnd.nextBoolean(), period = rnd.nextInt(-1, 8),
                    timeS = if (rnd.nextInt(10) == 0) null else rnd.nextInt(-10, 1300),
                    startS = if (rnd.nextInt(4) == 0) null else rnd.nextInt(-10, 1300),
                    onS = if (rnd.nextInt(3) == 0) null else rnd.nextInt(-10, 1300),
                    minutes = listOf(null, 0, 2, 2, 2, 4, 5, 10, 20, -2)[rnd.nextInt(10)],
                    ppFlag = rnd.nextInt(4) != 0, penaltyShot = rnd.nextInt(15) == 0,
                    playerId = if (rnd.nextInt(3) == 0) null else rnd.nextLong(1, 4) + i % 2,
                )
            }
            val goals = if (rnd.nextInt(10) == 0) null else List(rnd.nextInt(0, 8)) {
                ChlGoal(
                    rnd.nextLong(0, 40), rnd.nextBoolean(), if (rnd.nextInt(12) == 0) null else rnd.nextInt(-1, 7),
                    if (rnd.nextInt(12) == 0) null else rnd.nextInt(-5, 1300), rnd.nextBoolean(), rnd.nextInt(5) == 0, rnd.nextInt(20) == 0,
                )
            }
            val period = rnd.nextInt(-1, 8)
            val now = ChlNow(period, rnd.nextInt(-5, 1300), period + rnd.nextInt(0, 2), phases[rnd.nextInt(phases.size)],
                rnd.nextInt(10) == 0, rnd.nextInt(10) == 0)
            val ot = listOf(null, 300, 1200, 0, -5)[rnd.nextInt(5)]
            val r = ChlPowerPlay.derive(pens, goals, rnd.nextInt(-1, 10), if (rnd.nextBoolean()) null else rnd.nextInt(-1, 6), now) { q ->
                if (q <= 3) 1200 else ot
            }
            if (r is ChlPp.Shown) {
                val s = r.pp.secondsRemaining
                assertTrue(s != null && s in 1..SportsConstants.PP_SECS_MAX, "$r")
            }
        }
    }
}
