package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.math.abs

/**
 * (CHL §7.4, S8; WP-3 fix pass, lead ruling S3) The live power-play release gate, on the **shipped Kotlin code**.
 *
 * For one `tools/chl/record_live.py` recording (a folder with `meta.json`, `polls.jsonl` and `board-start.json` /
 * `board-end.json`) it rebuilds every poll exactly as the app's source would hand it over (the latest body of each node,
 * a failed `penalties` / `goals` request as null with its `…Failed` flag, a failed display node as its last body) and maps
 * it with `HockeyTechMapper.followed`, which runs `ChlPowerPlay`. The bar it would show is compared, poll by poll, with
 * the §5 model run on the game's **final** data (the last poll's `penalties` and `goals`, without `TimeOn`, as the §5.8
 * duration audit) at the same game second:
 * - **wrong-bar seconds**: a bar shown whose side or kind differs from the final model, or a bar where the model has none;
 * - **hidden seconds**: `powerPlayUnknown` while the final model has a power play;
 * - **missing seconds**: no bar while the final model has one;
 * - **seconds agreement**: of the seconds where the bar matches, the share whose clock is within ±[SECONDS_TOLERANCE_S]
 *   of the final model's.
 *
 * Each poll counts the wall time until the next poll (at most two intervals). Only in-play polls count (LIVE or
 * INTERMISSION, not a shootout, not a decided game at 0:00). Seconds where the final model is inside an H9 window are not
 * judged. The machine's 30 s unknown hold is not modelled: this measures what the source hands to it.
 *
 * Gates per league (lead ruling S3): wrong ≤ [WRONG_SHARE_MAX] of the final model's power-play seconds; no wrong stretch
 * longer than [WRONG_STRETCH_MAX_S]; hidden + missing ≤ [GAP_SHARE_MAX]; seconds agreement ≥ [SECONDS_SHARE_MIN].
 *
 * Why in `:core`'s tests: the mapper's parsers and the model (`parsePenalties`, `ChlPowerPlay.Model`) are `internal` to
 * `:core`, and `:core` tests are plain JVM (fast, no Android). Run by `ChlLiveGateTest`; on real recordings with
 * `CHL_LIVE_RECORDINGS=<folder> ./gradlew :core:test --tests '*ChlLiveGateTest*'` (see `tools/chl/README.md`).
 */
internal object ChlLiveGate {
    const val WRONG_SHARE_MAX = 0.05
    const val WRONG_STRETCH_MAX_S = 30.0
    const val GAP_SHARE_MAX = 0.25
    const val SECONDS_TOLERANCE_S = 5
    const val SECONDS_SHARE_MIN = 0.95

    class Game(
        val league: League,
        val gameId: String,
        val polls: Int,
        val inPlayS: Double,
        val modelPpS: Double,
        val wrongS: Double,
        val longestWrongS: Double,
        val hiddenS: Double,
        val missingS: Double,
        val matchedS: Double,
        val matchedWithinS: Double,
        val unjudgedS: Double,
    )

    class Verdict(val text: String, val pass: Boolean)

    private val JSON = Json { ignoreUnknownKeys = true }

    private fun leagueOf(code: String): League = when (code.lowercase()) {
        "ohl" -> League.OHL
        "whl" -> League.WHL
        "qmjhl", "lhjmq" -> League.QMJHL
        else -> error("unknown league \"$code\" in meta.json")
    }

    /** A shown bar's identity and its seconds. */
    private data class Bar(val side: Side, val kind: PowerPlayKind, val secs: Int?)

    private sealed interface Shown {
        data object None : Shown
        data object Unknown : Shown
        data class At(val bar: Bar) : Shown
    }

    fun game(dir: File): Game {
        val meta = JSON.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
        val league = leagueOf((meta["league"] as JsonPrimitive).content)
        val gameId = (meta["game"] as JsonPrimitive).content
        val interval = (meta["interval_s"] as? JsonPrimitive)?.doubleOrNull ?: 5.0
        val bodies = listOf("board-start.json", "board-end.json").map { File(dir, it) }.filter { it.isFile }
            .map { JSON.parseToJsonElement(it.readText()).jsonObject }
        require(bodies.isNotEmpty()) { "$dir: no board-start.json / board-end.json (record with the key set, tools/chl/README.md)" }
        val date = (meta["date"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            ?: (((bodies[0]["SiteKit"] as? JsonObject)?.get("Parameters") as? JsonObject)?.get("fetch_date") as? JsonPrimitive)?.content
            ?: error("$dir: no date in meta.json")
        val all = bodies.mapNotNull { HockeyTechMapper.board(league, date, it, null, null)?.facts?.get(gameId) }
        require(all.isNotEmpty()) { "$dir: game $gameId is not on the board" }
        // The facts as the follow had them during the game: the board's status and final flag are left to the nodes (the
        // end board says final); the period lengths are the union (the OT length is listed only once OT starts).
        val facts = all.first().copy(
            status = null, final = false,
            periodLengthsS = all.fold(emptyMap<Int, Int>()) { acc, f -> acc + f.periodLengthsS },
        )

        // The polls, each with every node's latest body (record_live.py writes only the changed ones).
        class Poll(val unix: Double, val nodes: Map<String, JsonElement>, val failed: Set<String>)
        val polls = ArrayList<Poll>()
        val cur = HashMap<String, JsonElement>()
        File(dir, "polls.jsonl").forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val p = JSON.parseToJsonElement(line).jsonObject
            (p["nodes"] as? JsonObject)?.forEach { (k, v) -> cur[k] = v }
            val failed = (p["status"] as? JsonObject)?.filterValues { (it as? JsonPrimitive)?.intOrNull !in setOf(200, 304) }?.keys.orEmpty()
            polls += Poll((p["unix"] as JsonPrimitive).doubleOrNull ?: 0.0, HashMap(cur), failed)
        }
        require(polls.isNotEmpty()) { "$dir: no polls" }

        // The final-data model (§5), on the last poll's nodes.
        val last = polls.last().nodes
        val clock = ChlPowerPlay.GameClockMap.of(HockeyTechMapper.periodLengths(facts), 12)
        val fPens = (last["penalties"] as? JsonObject)?.let { HockeyTechMapper.parsePenalties(it).rows }.orEmpty()
        val fGoals = (last["goals"] as? JsonObject)?.let(HockeyTechMapper::parseGoals).orEmpty()
        // Without `TimeOn` (as the §5.8 duration audit): a recorded release is known in the final data from the start, so
        // with it the model's seat ends would differ from anything a live poll could know before the release.
        val rows = ChlPowerPlay.buildRows(fPens, clock, useTimeOn = false, feedPeriod = Int.MAX_VALUE).rows
        val placed = fGoals.mapNotNull { g ->
            val t = if (g.period != null && g.timeS != null) clock.g(g.period, g.timeS) else null
            t?.let { ChlPowerPlay.ModelGoal(g, it) }
        }
        val model = ChlPowerPlay.Model(rows, placed, ChlPowerPlay.h9Windows(fPens, clock), clock)
        model.run(1_000_000)

        var inPlay = 0.0; var modelPp = 0.0; var wrong = 0.0; var stretch = 0.0; var longest = 0.0
        var hidden = 0.0; var missing = 0.0; var matched = 0.0; var within = 0.0; var unjudged = 0.0
        for ((i, poll) in polls.withIndex()) {
            val dt = minOf((polls.getOrNull(i + 1)?.unix ?: (poll.unix + interval)) - poll.unix, 2 * interval)
            fun obj(n: String) = poll.nodes[n] as? JsonObject
            if ("runningclock" in poll.failed || "goalssummary" in poll.failed) { stretch = 0.0; continue } // no new answer
            val nodes = ChlNodes(
                runningclock = obj("runningclock"),
                publishedclock = obj("publishedclock"),
                goalssummary = obj("goalssummary"),
                shotssummary = obj("shotssummary"),
                penalties = if ("penalties" in poll.failed) null else obj("penalties"),
                goals = if ("goals" in poll.failed) null else obj("goals"),
                shootouts = obj("shootouts"),
                penaltiesFailed = "penalties" in poll.failed,
                goalsFailed = "goals" in poll.failed,
            )
            val snap = HockeyTechMapper.followed(league, facts, nodes)
            if (snap == null || !inPlay(snap)) { stretch = 0.0; continue }
            val period = snap.period ?: continue
            val rem = snap.clock?.secondsRemaining ?: continue
            val len = clock.length(period) ?: continue
            val nowG = clock.g(period, len - rem) ?: continue
            inPlay += dt
            val live: Shown = when {
                snap.powerPlayUnknown -> Shown.Unknown
                snap.powerPlay != null -> snap.powerPlay.let { Shown.At(Bar(it.side, it.kind, it.secondsRemaining)) }
                else -> Shown.None
            }
            if (model.hidden(nowG)) { unjudged += dt; stretch = 0.0; continue }
            val st = model.stateAt(nowG)
            val final: Bar? = if (st.away == st.home) null else {
                val side = if (st.away > st.home) Side.HOME else Side.AWAY
                val ends = if (side == Side.HOME) st.awayEnds else st.homeEnds
                Bar(side, if (abs(st.away - st.home) >= 2) PowerPlayKind.TWO_MAN else PowerPlayKind.ONE_MAN, ends.min() - nowG)
            }
            if (final != null) modelPp += dt
            when (live) {
                Shown.Unknown -> { if (final != null) hidden += dt; stretch = 0.0 }
                Shown.None -> { if (final != null) missing += dt; stretch = 0.0 }
                is Shown.At -> if (final == null || final.side != live.bar.side || final.kind != live.bar.kind) {
                    wrong += dt; stretch += dt; longest = maxOf(longest, stretch)
                } else {
                    stretch = 0.0
                    matched += dt
                    val a = live.bar.secs
                    val b = final.secs
                    if (a != null && b != null && abs(a - b) <= SECONDS_TOLERANCE_S) within += dt
                }
            }
        }
        return Game(league, gameId, polls.size, inPlay, modelPp, wrong, longest, hidden, missing, matched, within, unjudged)
    }

    /** In play for the power play: LIVE or INTERMISSION, no shootout, not a decided game at 0:00 (S3). */
    private fun inPlay(s: GameSnapshot): Boolean {
        if (s.phase != GamePhase.LIVE && s.phase != GamePhase.INTERMISSION) return false
        if (s.periodKind == PeriodKind.SO) return false
        val c = s.clock
        val a = s.away.score
        val h = s.home.score
        val decided = s.phase == GamePhase.LIVE && c != null && c.secondsRemaining == 0 && !c.running &&
            (s.period ?: 0) >= 3 && a != null && h != null && a != h
        return !decided
    }

    /** Every recording folder under [root] (a folder holding `polls.jsonl`, at any depth). */
    fun recordings(root: File): List<File> =
        root.walkTopDown().filter { it.isDirectory && File(it, "polls.jsonl").isFile }.sortedBy { it.path }.toList()

    fun verdict(games: List<Game>): Verdict {
        val sb = StringBuilder()
        var pass = true
        fun pct(x: Double, of: Double) = if (of > 0) 100.0 * x / of else 0.0
        for (g in games) {
            sb.appendLine(
                "%s %s: %d polls, in play %.0f s; final-model PP %.0f s; wrong %.0f s (%.1f %%), longest wrong stretch %.0f s; hidden %.0f s, missing %.0f s (%.1f %%); seconds within ±%d s %.1f %%; not judged (H9) %.0f s".format(
                    g.league, g.gameId, g.polls, g.inPlayS, g.modelPpS, g.wrongS, pct(g.wrongS, g.modelPpS), g.longestWrongS,
                    g.hiddenS, g.missingS, pct(g.hiddenS + g.missingS, g.modelPpS), SECONDS_TOLERANCE_S,
                    if (g.matchedS > 0) 100.0 * g.matchedWithinS / g.matchedS else 100.0, g.unjudgedS,
                ),
            )
        }
        for ((league, gs) in games.groupBy { it.league }) {
            val pp = gs.sumOf { it.modelPpS }
            val wrong = gs.sumOf { it.wrongS }
            val gap = gs.sumOf { it.hiddenS + it.missingS }
            val matched = gs.sumOf { it.matchedS }
            val within = gs.sumOf { it.matchedWithinS }
            val longest = gs.maxOf { it.longestWrongS }
            val secsShare = if (matched > 0) within / matched else 1.0
            val fails = buildList {
                if (pp > 0 && wrong / pp > WRONG_SHARE_MAX) add("wrong ${"%.1f".format(pct(wrong, pp))} % > ${(WRONG_SHARE_MAX * 100).toInt()} %")
                if (longest > WRONG_STRETCH_MAX_S) add("wrong stretch ${longest.toInt()} s > ${WRONG_STRETCH_MAX_S.toInt()} s")
                if (pp > 0 && gap / pp > GAP_SHARE_MAX) add("hidden + missing ${"%.1f".format(pct(gap, pp))} % > ${(GAP_SHARE_MAX * 100).toInt()} %")
                if (secsShare < SECONDS_SHARE_MIN) add("seconds within ±$SECONDS_TOLERANCE_S s ${"%.1f".format(100 * secsShare)} % < ${(SECONDS_SHARE_MIN * 100).toInt()} %")
            }
            if (fails.isNotEmpty()) pass = false
            sb.appendLine("$league (${gs.size} game(s)): ${if (fails.isEmpty()) "PASS" else "FAIL: " + fails.joinToString("; ")}")
        }
        return Verdict(sb.toString().trimEnd(), pass)
    }
}
