package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Readers for the recorded CHL fixtures (`core/src/testFixtures/resources/sports/chl/`, design §7.1) used by the WP-1
 * tests. Firebase files are the whole league node (`{game id: body}`); HockeyTech's client codes appear only in the file
 * names here.
 */
internal object ChlFixtures {
    const val ROOT = "sports/chl"
    const val FINALS = "$ROOT/firebase/finals-2026-11-13"
    const val LIVE = "$ROOT/firebase/live-2026-11-13"
    const val LSCLUSTER = "$ROOT/lscluster"
    const val PARITY = "$ROOT/pp-parity-2026-11-13.tsv"

    /** The fixture file prefix of a league (HockeyTech's client code). */
    val CLIENTS = listOf("ohl", "whl", "lhjmq")

    fun league(client: String): League = when (client) {
        "ohl" -> League.OHL
        "whl" -> League.WHL
        "lhjmq" -> League.QMJHL
        else -> error("unknown client $client")
    }

    fun logoPrefix(client: String): String = "https://assets.leaguestat.com/$client/logos/"

    fun element(path: String): JsonElement = Json.parseToJsonElement(SportsFixtures.text(path))

    fun obj(path: String): JsonObject = element(path) as JsonObject

    /** A Firebase finals node for every recorded game of [client] (empty when the file is `null`). */
    fun finals(client: String, node: String): JsonObject =
        (element("$FINALS/${client}_$node.json").takeUnless { it is JsonNull } as? JsonObject) ?: JsonObject(emptyMap())

    /** One game's body inside a finals node, or null. */
    fun finalsGame(client: String, node: String, gameId: String): JsonObject? = finals(client, node)[gameId] as? JsonObject

    /** The recorded penalty rows of a game, through the mapper's own reader. */
    fun penalties(client: String, gameId: String): List<ChlPenalty>? =
        finalsGame(client, "penalties", gameId)?.let { HockeyTechMapper.parsePenalties(it).rows }

    /** The recorded goals of a game, through the mapper's own reader. */
    fun goals(client: String, gameId: String): List<ChlGoal> =
        finalsGame(client, "goals", gameId)?.let(HockeyTechMapper::parseGoals).orEmpty()

    fun board(client: String): JsonObject = obj("$LSCLUSTER/gamesbydate-$client-2026-11-13.json")
    fun teams(client: String): JsonObject = obj("$LSCLUSTER/teamsbyseason-$client-2026-27.json")
    fun seasons(client: String): JsonObject = obj("$LSCLUSTER/seasons-$client.json")

    /** The mapped board of [client] on 2026-11-13, with its teams and seasons. */
    fun mappedBoard(client: String): ChlBoard {
        val teams = HockeyTechMapper.teams(teams(client), logoPrefix(client))
        val seasons = HockeyTechMapper.seasons(seasons(client))
        return HockeyTechMapper.board(league(client), "2026-11-13", board(client), teams, seasons)
            ?: error("board $client did not map")
    }

    /** The reference script's game time (`pp_replay.g`): 1200 s periods, 300 s overtimes. */
    val REG_CLOCK: ChlPowerPlay.GameClockMap = ChlPowerPlay.GameClockMap.regularSeason()

    /** `"p:mm:ss"` elapsed (the reference script's probe notation) → game seconds. */
    fun at(s: String): Int {
        val (p, m, x) = s.split(':').map(String::toInt)
        return REG_CLOCK.g(p, m * 60 + x)!!
    }

    /**
     * The reference script's `Sim` for one game, run to its `end` (max row start / goal + 900): every counted row, every
     * goal with a time, the H9 windows. [useTimeOn] false is the duration audit's model.
     */
    class Replay(val client: String, val gameId: String, useTimeOn: Boolean = true) {
        val pens: List<ChlPenalty> = penalties(client, gameId).orEmpty()
        val goals: List<ChlGoal> = goals(client, gameId)
        val rows: List<ChlPowerPlay.Row> = ChlPowerPlay.buildRows(pens, REG_CLOCK, useTimeOn, Int.MAX_VALUE).rows
        val placed: List<ChlPowerPlay.ModelGoal> = goals.mapNotNull { g ->
            val t = if (g.period != null && g.timeS != null) REG_CLOCK.g(g.period, g.timeS) else null
            t?.let { ChlPowerPlay.ModelGoal(g, it) }
        }
        val model = ChlPowerPlay.Model(rows, placed, ChlPowerPlay.h9Windows(pens, REG_CLOCK), REG_CLOCK)
        val end: Int = ((rows.map { it.s } + placed.map { it.t }).maxOrNull() ?: 0).coerceAtLeast(0) + 900

        init {
            model.run(end)
        }
    }
}
