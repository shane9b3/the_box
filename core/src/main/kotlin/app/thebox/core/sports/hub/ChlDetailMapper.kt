// Contract (CHL hub commit 0): docs/design/m6-chl-hub.md §4.3, §4.4 and "Commit 0 rulings". The signatures below are
// FROZEN: P1 fills the bodies, P2 calls them (through `ChlDetailSource`'s mapper parameters), P3 draws what they emit.
// Changes go through the lead.
package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.HockeyTechMapper
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ChlJson.a
import app.thebox.core.sports.hub.ChlJson.d
import app.thebox.core.sports.hub.ChlJson.flag
import app.thebox.core.sports.hub.ChlJson.i
import app.thebox.core.sports.hub.ChlJson.o
import app.thebox.core.sports.hub.ChlJson.s
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.util.Locale

/**
 * (CHL hub §4.3) HockeyTech `lscluster` bodies → the hub's [GameDetail] and [BoxScore]. Pure (no clock, no I/O, no
 * logging), lenient, and total: a missing or odd field gives null / "–" in the output, never an exception; a body that
 * does not map at all gives null (the source turns it into `BAD_BODY`). Fixtures: `core/src/testFixtures/resources/
 * sports/chl/hub/` (`ChlHubFixtures` loads them).
 *
 * **Rules every function follows (P1):**
 * - **Lenient scalars.** Every number may arrive as a JSON int or a string (`"13"`, `"+1"`, `"-1"`, `"3.60"`, `".879"`);
 *   blank, null, `"-"` or unparsable = absent. Never compare numeric strings as strings (`games_played` "13" > "8").
 * - **Names.** Every `name` field (Goalie, Leader, Star, GoalItem.scorer, SkaterLine, GoalieLine) is
 *   `"{first_name[0]}. {last_name}"` ("K. Dietsch"), as NHL.com writes them; `first_name` blank → `last_name` alone;
 *   `last_name` blank → the entry is skipped. Every `*Last` / `lastName` field is `last_name` trimmed. Never parse
 *   `topgoalies.name` (the QMJHL writes "Denault, Louis-Antoine").
 * - **Rates are 0..1** (B3e): PP%, PK%, FO% ([StatValue.Pct]), SV% ([Goalie.svPct], [GoalieLine.svPct]). A zero
 *   denominator → null (no row value, no rank), never 0.0.
 * - **Sides.** `V` = visitor = [app.thebox.core.sports.Side.AWAY]; `H` = home. In `pv` the visitor's team facts are
 *   `pv.visitor_team`, but its **lineup** is `pv.visiting_team.lineup` (a separate top-level key); the home's are both
 *   under `pv.home_team`.
 * - **Ids.** Team ids, player ids and season ids are kept as the feed's decimal strings (`"14"`), the keys of [ChlRanks].
 * - **Team codes** ([Star.team], [RecentGame.opponent]) are trimmed and upper-cased (`Locale.ROOT`), as the board's
 *   `TeamLine.abbrev` (`HockeyTechMapper.code`): the QMJHL's "Chi" → "CHI".
 * - **Expected output:** `ChlHubFixtures` (`core/src/testFixtures`) holds the fixture games' expected [GameDetail] /
 *   [BoxScore] under these rules; `ChlDetailMapperTest` compares against them (the design's worked examples win).
 * - **Headshots** come only from the [headshot] builder passed in (`HockeyTechEndpoints.headshot` in `:data`; a test
 *   passes a fake), called with the entry's `player_id`; never a URL read from the body (`photo`, `namelink`).
 * - **Score.** [GameDetail.awayScore] / [GameDetail.homeScore] stay null and [GameDetail.serverDateMs] null (the source
 *   sets it from the response); the board gives the headline.
 * - **Never read** `GC.Parameters` / `SiteKit.Parameters` (the echo carries the key), and never keep a body or a URL in
 *   the output.
 */
object ChlDetailMapper {
    /**
     * The pre-game detail from `feed=gc&tab=preview` (`pv` = the decoded body's `GC.Preview` object; the caller passes the
     * **whole decoded body**, `{"GC":{"Preview":…}}`), the two sides' `topgoalies` bodies (`{"SiteKit":{"Statviewtype":
     * [...]}}`, null when that read failed or was skipped), the cached [ranks] (null = none known: no ranks), and
     * [playoff] (`pv.current_season.playoff == "1"`, which the source also reads to skip the tables).
     *
     * Fills, all else at its default / empty / null:
     * - [GameDetail.id] = [id]; [GameDetail.fetchedPhase] = `SCHEDULED`; [GameDetail.venue] null (the board's venue is
     *   the pre-game caption, §2.3).
     * - [GameDetail.season] (§2.1): records `"wins-losses-ot_losses-shootout_losses"` (null when any part is absent);
     *   [Last10] `(last10, null, null)` (null when absent or `gp == 0`); [Ranked] GF = `goals_for / gp`, GA =
     *   `goals_against / gp` (raw doubles, formatting is the UI's), PP = `power_play_goals / power_plays`, PK =
     *   `1 − power_play_goals_against / times_short_handed` (0..1); `gp == 0` / a zero denominator → that [Ranked] null.
     *   [Ranked.rank] from [ranks] by the side's `team_id` (`gf`, `ga`, `pp`, `pk`); null when [ranks] is null, the team
     *   is absent, or [playoff]. The `pv` ranks / `*_percentage` strings are never used.
     * - [GameDetail.series] (§2.2): `SeriesFacts(V.w, H.w, meetings)` from `pv.HeadToHeadRecord.{visiting_team,
     *   home_team}.w` (already including `otw`); meetings = `previous_meetings.size`, or the sum of wins when that list
     *   is empty but wins > 0. A first meeting is `SeriesFacts(0, 0, 0)`. Null when [playoff] or the record is absent.
     * - [GameDetail.leaders]: one `LeaderPair("points", V, H)` from `pv.*.leadingScorers[0]` (`Leader(name, points,
     *   headshot)`, entry [0] even on a tie); no pair when both are absent. [GameDetail.leadersSeason] = true whenever
     *   the pair is present.
     * - [GameDetail.goalies] (§3.1, B1), at most 2 per side:
     *   1. When the side's lineup lists goalies: those, ordered **starter first** (`start == "1"`, then
     *      [Goalie.starting] = true; nobody else is starting), then by that goalie's `topgoalies` `games_played` (int)
     *      descending, goalies without a `topgoalies` row last, ties and unknowns in feed order (a stable sort). Each is
     *      joined to the side's `topgoalies` by `player_id` for the stats; no row → name and headshot only.
     *   2. Else the side's `topgoalies` rows with `active == "1"` and `latest_team_id == ` the side's `team_id`, by
     *      `games_played` (int) descending; none starting.
     *   Stats: [Goalie.record] `"wins-losses-ot_losses-shootout_losses"` (`losses` = regulation only), [Goalie.gaa] =
     *   `goals_against_average`, [Goalie.svPct] = `save_percentage` (0..1). Null [GoaliePair] when both sides are empty.
     *   Worked examples: LDN Cooke, Jaswal; NIAG Haffner (starting), Yermolenko; NFL Denault (starting), Proulx; Chi Lee
     *   (starting), Plumins; MH Casey, Christie.
     * - [GameDetail.recent] (§3.1): per side, `pv.*.lastGames[]` oldest first, at most 5: [RecentGame.result] W when
     *   `winner == ` the side's `team_id`, else L (no OT mark: the feed has none); [RecentGame.atOpponent] = this side
     *   was the visitor; [RecentGame.opponent] = the other team's code (`homeCode` / `visitingCode`); [RecentGame.score]
     *   winner's goals first, `"6-5"`; [RecentGame.dateMs] from `game_date_iso_8601`. Away side first, then home.
     *
     * Null when [pv] has no `GC.Preview` object, or neither side's `team_id`.
     */
    fun preview(
        id: GameId,
        pv: JsonObject,
        awayGoalies: JsonObject?,
        homeGoalies: JsonObject?,
        ranks: ChlRanks?,
        playoff: Boolean,
        headshot: (playerId: String) -> String?,
    ): GameDetail? = guard { ChlPreviewMapping.map(id, pv, awayGoalies, homeGoalies, ranks, playoff, headshot) }

    /**
     * The final's detail and box score from **one** parse of `feed=gc&tab=gamesummary` (the caller passes the whole decoded
     * body, `{"GC":{"Gamesummary":…}}`). The league is [id]'s.
     *
     * [GameDetail] fills, all else at its default:
     * - [GameDetail.fetchedPhase] by [app.thebox.core.sports.HockeyTechMapper]'s own classifier (`classify`) on the int
     *   of `meta.status` ("4" in a final; 4 and 5 final; status 3 unverified, not final), never restated here.
     *   `status_value` is display text ("Final") and is not read.
     * - [GameDetail.gameStats] (§2.1), in this order: SOG `totalShots` ([StatValue.Count]); POWER_PLAY
     *   `powerPlayGoals` / `powerPlayCount` ([StatValue.Fraction]); FACEOFF_PCT `totalFaceoffs.*.won / att`
     *   ([StatValue.Pct] 0..1) only when both `att > 0`; PIM `pimTotal`; HITS `totalHits` only when the league is in
     *   `SportsConstants.CHL_HITS_LEAGUES`. `totalShotsOn` is never read (0 in the WHL).
     * - [GameDetail.goals] (§3.2) in feed order: [GoalItem.period] from `period_id` (1–3 REG n, 4 → OT 1, 5 → OT 2 …);
     *   `timeInPeriod` = `time`; side from `home == "1"`; scorer from `goal_scorer`; [GoalItem.scorerTotal] =
     *   `scorer_goal_num`; assists from `assist1_player`, `assist2_player`, each kept only when its `last_name` is
     *   non-blank (an unassisted slot is an object of nulls); strength PP (`power_play == "1"`), SH (`short_handed ==
     *   "1"`), else EV; [GoalItem.emptyNet] `empty_net == "1"`; the running score computed in order; [GoalItem.clipId]
     *   null; [GoalItem.gameWinning] `game_winning == "1"`; headshot from `goal_scorer.player_id`.
     * - [GameDetail.stars] (§2.2): `mvps[0..2]`, null entries skipped; [Star.rank] = index + 1; [Star.team] = that
     *   side's `team_code` (`mvps[i].home`: 1 → `gs.home.team_code`, else `gs.visitor.team_code`).
     * - [GameDetail.periods]: goals per period from `goalsByPeriod` (as [BoxScore.periods]).
     * - [GameDetail.venue] = `venue` trimmed; [GameDetail.attendance] = `meta.attendance` when > 0.
     *
     * [BoxScore] (§3.3, B2):
     * - skaters from `gs.{visitor,home}_team_lineup.players[]` in feed order: name, `pos` = `position_str` trimmed, G/A
     *   ints, P = G + A (lineup `points` is null), [SkaterLine.plusMinus] (int or "+1"), [SkaterLine.sog] = `shots`,
     *   [SkaterLine.toi] = `toi` or null (no league sends it: the sheet hides the column when all are null);
     * - goalies from `gs.*_team_lineup.goalies[]` with `seconds > 0` only (one line per goalie, stints added; never
     *   `gs.goalies`): `toi` = `time` ("58:21"), SA `shots_against`, SV `saves`, SV% `saves / shots_against` (0..1; SA 0
     *   → null);
     * - [BoxScore.periods] / [BoxScore.periodShots] from `goalsByPeriod` / `shotsByPeriod`, keyed `"1"`, `"2"`, `"3"`,
     *   `"4"`… → the [GoalItem.period] label rule, in key order, a period kept only when both sides have it.
     *
     * Null when the body has no `GC.Gamesummary` object or no visitor / home.
     */
    fun summary(id: GameId, gs: JsonObject, headshot: (playerId: String) -> String?): Pair<GameDetail, BoxScore>? =
        guard { ChlSummaryMapping.map(id, gs, headshot) }

    /**
     * (§2.1 rank rule, S1, B3f) The league's competition ranks from the two decoded `statviewfeed&view=teams` arrays
     * ([standard]: `special=false`, GF and GA; [special]: `special=true`, PP and PK; either may be null, a failed or
     * skipped read). The JSONP `( … )` wrapper is already stripped (`HockeyTechHttp.element` in `:data`).
     *
     * - Rows: every `sections[].data[]` entry; the team key is `prop.team_code.teamLink` (= `pv.*.team_id`), never
     *   `team_code` or `name`. Values from `row` (strings of ints in the fixtures): `games_played`, `goals_for`,
     *   `goals_against` (standard); `games_played`, `power_play_goals`, `power_plays`, `power_play_goals_against`,
     *   `times_short_handed` (special). GF = `goals_for / games_played`, GA = `goals_against / games_played`, PP =
     *   `power_play_goals / power_plays`, PK = `1 − power_play_goals_against / times_short_handed` (compare PK by the
     *   goals-against rate, lower is better). `power_play_pct`, `penalty_kill_pct`, `percentage`, `rank` and
     *   `overall_rank` are never read.
     * - Compared as integer fractions, cross-multiplied, never the rounded strings or the feed's `rank` (the sort
     *   position): GF, PP, PK higher is better; GA lower is better.
     * - Standard competition ranking: ties share a rank and the next rank skips (LDN and SOO both 19/87 → both 9th).
     * - Left out of a map: `games_played == 0`, a zero denominator, a row without a team key.
     *
     * Null when both arrays are null or neither yields a row; otherwise a [ChlRanks] whose maps may be empty.
     */
    fun ranks(standard: JsonArray?, special: JsonArray?): ChlRanks? = guard { ChlRankMapping.map(standard, special) }

    /**
     * Totality backstop: the mappings read every field through safe casts, so nothing here is expected to throw; a
     * runtime exception from an unforeseen shape still gives null (BAD_BODY), never a crash. [Error]s propagate.
     */
    private inline fun <T> guard(block: () -> T?): T? = try {
        block()
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * (CHL hub §4.3) Competition ranks by team id (`prop.team_code.teamLink`, the feed's decimal string); a team absent
 * from a map has no rank in that row. Mapped values only: `ChlDetailSource` caches these per (league, season), never a
 * table body (m6-chl-leagues §2.2).
 */
data class ChlRanks(
    val gf: Map<String, Int>,
    val ga: Map<String, Int>,
    val pp: Map<String, Int>,
    val pk: Map<String, Int>,
)

// ------------------------------------------------------------------ P1: the mappings (private to this file)

/** Lenient JSON access shared by the three mappings: safe casts only, never an exception on an odd shape. */
private object ChlJson {
    fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject

    fun JsonObject?.a(key: String): JsonArray? = this?.get(key) as? JsonArray

    /** A trimmed, non-blank string (a JSON string or number); null, blank and `"-"` are absent. */
    fun JsonObject?.s(key: String): String? = text(this?.get(key))

    fun text(el: JsonElement?): String? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content.trim().takeIf { it.isNotEmpty() && it != "-" }
    }

    /** `13`, `"13"`, `"+1"`, `"-1"`, `"13.0"` → the int; anything else (null, `"-"`, `"3.5"`, objects) → null. */
    fun JsonObject?.i(key: String): Int? = intOf(this?.get(key))

    fun intOf(el: JsonElement?): Int? {
        val c = text(el) ?: return null
        c.toIntOrNull()?.let { return it }
        val d = c.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    /** `"3.60"`, `".879"`, `3.6` → the double (finite only). */
    fun JsonObject?.d(key: String): Double? = text(this?.get(key))?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** `"1"` / `1` / `true` → true; anything else false. */
    fun JsonObject?.flag(key: String): Boolean = when (text(this?.get(key))?.lowercase(Locale.ROOT)) {
        "1", "true" -> true
        else -> false
    }

    fun objects(arr: JsonArray?): List<JsonObject> = arr?.mapNotNull { it as? JsonObject }.orEmpty()

    /** `"{first_name[0]}. {last_name}"`; `first_name` blank → `last_name`; `last_name` blank → null (skip the entry). */
    fun name(o: JsonObject?): String? {
        val last = o.s("last_name") ?: return null
        val first = o.s("first_name") ?: return last
        return String(Character.toChars(first.codePointAt(0))) + ". " + last
    }

    fun lastName(o: JsonObject?): String? = o.s("last_name")

    /** A team code as the board writes it: trimmed, upper-cased (`Locale.ROOT`). */
    fun code(raw: String?): String? = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun headshot(o: JsonObject?, build: (String) -> String?): String? = o.s("player_id")?.let(build)

    /** A 0..1 rate from a feed value that is already a fraction (".879"); a percent (87.9) is scaled; else null. */
    fun rate(v: Double?): Double? = when {
        v == null || v < 0.0 -> null
        v <= 1.0 -> v
        v <= 100.0 -> v / 100.0
        else -> null
    }

    /** `period_id` / a period key: 1–3 → REG n, 4 → OT 1, 5 → OT 2 …; anything else null. */
    fun period(n: Int?): PeriodLabel? = when {
        n == null || n < 1 || n > 99 -> null
        n <= 3 -> PeriodLabel(PeriodKind.REG, n)
        else -> PeriodLabel(PeriodKind.OT, n - 3)
    }
}

/** (§2.1, §2.2, §3.1) `feed=gc&tab=preview` (+ `topgoalies`) → the pre-game [GameDetail]. */
private object ChlPreviewMapping {
    fun map(
        id: GameId,
        body: JsonObject,
        awayGoalies: JsonObject?,
        homeGoalies: JsonObject?,
        ranks: ChlRanks?,
        playoff: Boolean,
        headshot: (String) -> String?,
    ): GameDetail? {
        val pv = body.o("GC").o("Preview") ?: return null
        val v = pv.o("visitor_team")
        val h = pv.o("home_team")
        val vId = v.s("team_id")
        val hId = h.s("team_id")
        if (vId == null && hId == null) return null
        val useRanks = if (playoff) null else ranks

        val vs = SideFacts(v, vId, useRanks)
        val hs = SideFacts(h, hId, useRanks)
        val season = SeasonComparison(
            awayRecord = vs.record, homeRecord = hs.record,
            awayLast10 = vs.last10, homeLast10 = hs.last10,
            awayGf = vs.gf, homeGf = hs.gf,
            awayGa = vs.ga, homeGa = hs.ga,
            awayPp = vs.pp, homePp = hs.pp,
            awayPk = vs.pk, homePk = hs.pk,
        )

        val vLeader = leader(v, headshot)
        val hLeader = leader(h, headshot)
        val leaders = if (vLeader == null && hLeader == null) emptyList() else listOf(LeaderPair("points", vLeader, hLeader))

        // The visitor's lineup is the separate top-level `visiting_team`; the home's is under `home_team`.
        val awayG = goalies(pv.o("visiting_team").o("lineup"), awayGoalies, vId, headshot)
        val homeG = goalies(h.o("lineup"), homeGoalies, hId, headshot)
        val goalies = if (awayG.isEmpty() && homeG.isEmpty()) null else GoaliePair(awayG, homeG)

        return GameDetail(
            id = id,
            fetchedPhase = GamePhase.SCHEDULED,
            serverDateMs = null,
            awayScore = null,
            homeScore = null,
            venue = null,
            gameStats = emptyList(),
            season = season,
            periods = emptyList(),
            goals = emptyList(),
            stars = emptyList(),
            leaders = leaders,
            goalies = goalies,
            series = if (playoff) null else series(pv),
            recapId = null,
            condensedId = null,
            broadcasts = emptyList(),
            recent = recent(Side.AWAY, v, vId) + recent(Side.HOME, h, hId),
            leadersSeason = leaders.isNotEmpty(),
        )
    }

    /** One side's §2.1 rows, with the zero guards. */
    private class SideFacts(t: JsonObject?, teamId: String?, ranks: ChlRanks?) {
        private val gp = t.i("gp") ?: t.i("gamesPlayed")

        val record: String? = record(t)
        val last10: Last10? = if (gp != null && gp > 0) t.s("last10")?.let { Last10(it, null, null) } else null
        val gf: Ranked? = per(t.i("goals_for"), gp)?.let { Ranked(it, rank(ranks?.gf, teamId)) }
        val ga: Ranked? = per(t.i("goals_against"), gp)?.let { Ranked(it, rank(ranks?.ga, teamId)) }
        val pp: Ranked? = per(t.i("power_play_goals"), t.i("power_plays"))?.let { Ranked(it, rank(ranks?.pp, teamId)) }
        val pk: Ranked? =
            per(t.i("power_play_goals_against"), t.i("times_short_handed"))?.let { Ranked(1.0 - it, rank(ranks?.pk, teamId)) }

        private fun per(n: Int?, d: Int?): Double? = if (n == null || d == null || d <= 0 || n < 0) null else n.toDouble() / d

        private fun rank(m: Map<String, Int>?, teamId: String?): Int? = if (teamId == null) null else m?.get(teamId)
    }

    /** `"wins-losses-ot_losses-shootout_losses"`; null when any part is absent. */
    fun record(o: JsonObject?): String? {
        val parts = listOf("wins", "losses", "ot_losses", "shootout_losses").map { o.i(it) ?: return null }
        return parts.joinToString("-")
    }

    private fun leader(t: JsonObject?, headshot: (String) -> String?): Leader? {
        val first = ChlJson.objects(t.a("leadingScorers")).firstOrNull() ?: return null
        val name = ChlJson.name(first) ?: return null
        val points = first.i("points") ?: return null
        return Leader(name, points, ChlJson.headshot(first, headshot))
    }

    private fun series(pv: JsonObject): SeriesFacts? {
        val h2h = pv.o("HeadToHeadRecord") ?: return null
        val vw = h2h.o("visiting_team").i("w")
        val hw = h2h.o("home_team").i("w")
        if (vw == null && hw == null) return null
        val awayWins = (vw ?: 0).coerceAtLeast(0)
        val homeWins = (hw ?: 0).coerceAtLeast(0)
        val listed = pv.a("previous_meetings")?.size ?: 0
        return SeriesFacts(awayWins, homeWins, if (listed > 0) listed else awayWins + homeWins)
    }

    /** §3.1 rules 1 and 2 (B1, R5), at most 2 per side. */
    private fun goalies(lineup: JsonObject?, tgBody: JsonObject?, teamId: String?, headshot: (String) -> String?): List<Goalie> {
        val tg = ChlJson.objects(tgBody.o("SiteKit").a("Statviewtype"))
        val listed = ChlJson.objects(lineup.a("goalies")).filter { ChlJson.name(it) != null }
        if (listed.isNotEmpty()) {
            val byId = HashMap<String, JsonObject>()
            for (row in tg) row.s("player_id")?.let { byId.putIfAbsent(it, row) }
            fun statsOf(g: JsonObject): JsonObject? = g.s("player_id")?.let { byId[it] }
            val starter = listed.indexOfFirst { it.flag("start") } // only the first marked goalie is the starter
            val ordered = listed.withIndex().sortedWith(
                compareBy<IndexedValue<JsonObject>> { if (it.index == starter) 0 else 1 }
                    // games played descending; no `topgoalies` row (or no GP) after; a stable sort keeps feed order
                    .thenBy { iv -> statsOf(iv.value)?.i("games_played")?.let { -it.toLong() } ?: Long.MAX_VALUE },
            )
            return ordered.take(2).map { (index, g) -> goalie(g, statsOf(g), starting = index == starter, headshot) }
        }
        if (teamId == null) return emptyList()
        return tg.filter { it.s("active") == "1" && it.s("latest_team_id") == teamId && ChlJson.name(it) != null }
            .sortedByDescending { it.i("games_played") ?: -1 }
            .take(2)
            .map { goalie(it, it, starting = false, headshot) }
    }

    private fun goalie(who: JsonObject, stats: JsonObject?, starting: Boolean, headshot: (String) -> String?): Goalie = Goalie(
        name = ChlJson.name(who) ?: "",
        record = stats?.let(::record),
        gaa = stats.d("goals_against_average")?.takeIf { it >= 0.0 },
        svPct = ChlJson.rate(stats.d("save_percentage")),
        headshot = ChlJson.headshot(who, headshot),
        starting = starting,
    )

    /** §3.1 Last 5 of one side: oldest first, at most 5. */
    private fun recent(side: Side, t: JsonObject?, teamId: String?): List<RecentGame> {
        if (teamId == null) return emptyList()
        val games = ChlJson.objects(t.a("lastGames")).mapNotNull { recentGame(side, it, teamId) }
        // The feed lists the newest first: order by date when every game has one, else reverse the feed order.
        val oldestFirst = if (games.all { it.dateMs != null }) games.sortedBy { it.dateMs } else games.reversed()
        return oldestFirst.takeLast(5)
    }

    private fun recentGame(side: Side, g: JsonObject, teamId: String): RecentGame? {
        val atOpponent = when (teamId) {
            g.s("visiting_team") -> true
            g.s("home_team") -> false
            else -> return null
        }
        val opponent = ChlJson.code(if (atOpponent) g.s("homeCode") else g.s("visitingCode")) ?: return null
        val us = (if (atOpponent) g.i("visiting_goal_count") else g.i("home_goal_count")) ?: return null
        val them = (if (atOpponent) g.i("home_goal_count") else g.i("visiting_goal_count")) ?: return null
        val winner = g.s("winner")
        val result = when {
            winner == teamId -> GameResult.W
            winner != null -> GameResult.L
            us > them -> GameResult.W
            us < them -> GameResult.L
            else -> GameResult.T
        }
        return RecentGame(side, atOpponent, opponent, result, "${maxOf(us, them)}-${minOf(us, them)}", date(g.s("game_date_iso_8601")))
    }

    private fun date(s: String?): Long? = try {
        s?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (_: DateTimeException) {
        null
    }
}

/** (§2.1 final, §2.2, §3.2, §3.3) `feed=gc&tab=gamesummary` → the final's [GameDetail] and [BoxScore], one parse. */
private object ChlSummaryMapping {
    fun map(id: GameId, body: JsonObject, headshot: (String) -> String?): Pair<GameDetail, BoxScore>? {
        val gs = body.o("GC").o("Gamesummary") ?: return null
        val home = gs.o("home") ?: return null
        val visitor = gs.o("visitor") ?: return null
        val meta = gs.o("meta")

        // (CHL hub fix pass, C2) A shootout: `meta.shootout`, the body's own `shootout` flag, or a period whose `short_name`
        // is "SO". No shootout body has been captured, so this is defensive (see [shootoutPeriods], [withShootout]).
        val soKeys = shootoutPeriods(gs)
        val shootout = meta.flag("shootout") || gs.flag("shootout") || soKeys.isNotEmpty()
        fun label(n: Int): PeriodLabel? = if (n in soKeys) PeriodLabel(PeriodKind.SO, 1) else ChlJson.period(n)

        // HockeyTechMapper's classifier, not a restated rule: 4 and 5 are final, 1 scheduled, 3 unverified. (CHL hub fix pass,
        // C3) `meta.final` is passed as the board passes its own final flag, so a status-3 game the feed marks final is final.
        val phase = HockeyTechMapper.classify(
            status = meta.i("status"), final = meta.flag("final"), started = false, feedPeriod = null, remainingS = null,
            running = false, away = null, home = null, shootout = shootout, lengthOf = { null }, regularSeason = true,
        ).phase

        val periods = withShootout(byPeriod(gs.o("goalsByPeriod"), ::label), shootout, gs)
        val detail = GameDetail(
            id = id,
            fetchedPhase = phase,
            serverDateMs = null,
            awayScore = null,
            homeScore = null,
            venue = gs.s("venue"),
            gameStats = gameStats(id, gs),
            season = null,
            periods = periods,
            goals = goals(gs, home.s("team_id"), headshot, ::label),
            stars = stars(gs, visitor, home, headshot),
            leaders = emptyList(),
            goalies = null,
            series = null,
            recapId = null,
            condensedId = null,
            broadcasts = emptyList(),
            attendance = meta.i("attendance")?.takeIf { it > 0 },
        )
        val box = BoxScore(
            away = teamBox(gs.o("visitor_team_lineup")),
            home = teamBox(gs.o("home_team_lineup")),
            periods = periods,
            // A shootout's attempts are not shots on goal: no SO column for the shots.
            periodShots = byPeriod(gs.o("shotsByPeriod"), ::label).filter { it.label.kind != PeriodKind.SO },
        )
        return detail to box
    }

    private fun pair(gs: JsonObject, key: String): Pair<Int, Int>? {
        val o = gs.o(key) ?: return null
        val v = o.i("visitor") ?: return null
        val h = o.i("home") ?: return null
        return v to h
    }

    private fun gameStats(id: GameId, gs: JsonObject): List<StatRow> {
        val rows = ArrayList<StatRow>(5)
        pair(gs, "totalShots")?.let { (v, h) -> rows += StatRow(StatKind.SOG, StatValue.Count(v), StatValue.Count(h)) }
        val ppg = pair(gs, "powerPlayGoals")
        val ppc = pair(gs, "powerPlayCount")
        if (ppg != null && ppc != null) {
            rows += StatRow(StatKind.POWER_PLAY, StatValue.Fraction(ppg.first, ppc.first), StatValue.Fraction(ppg.second, ppc.second))
        }
        val fo = gs.o("totalFaceoffs")
        val vw = fo.o("visitor").i("won")
        val va = fo.o("visitor").i("att")
        val hw = fo.o("home").i("won")
        val ha = fo.o("home").i("att")
        if (vw != null && va != null && hw != null && ha != null && va > 0 && ha > 0 && vw in 0..va && hw in 0..ha) {
            rows += StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(vw.toDouble() / va), StatValue.Pct(hw.toDouble() / ha))
        }
        pair(gs, "pimTotal")?.let { (v, h) -> rows += StatRow(StatKind.PIM, StatValue.Count(v), StatValue.Count(h)) }
        if (id.league in SportsConstants.CHL_HITS_LEAGUES) {
            pair(gs, "totalHits")?.let { (v, h) -> rows += StatRow(StatKind.HITS, StatValue.Count(v), StatValue.Count(h)) }
        }
        return rows
    }

    /**
     * (CHL hub fix pass, C2) The period keys whose `periods[k].short_name` is "SO" (any case). Assumed, not captured: the
     * QMJHL final in overtime names its period 4 "OT1", so a shootout period is expected to read "SO".
     */
    private fun shootoutPeriods(gs: JsonObject): Set<Int> {
        val periods = gs.o("periods") ?: return emptySet()
        return periods.entries.mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            if (o.s("short_name")?.equals("SO", ignoreCase = true) != true) return@mapNotNull null
            (o.i("period_id") ?: k.trim().toIntOrNull())?.takeIf { it >= 1 }
        }.toSet()
    }

    /**
     * (CHL hub fix pass, C2) A shootout final's line score: an SO column the feed gave is kept as such. Without one, when
     * `totalGoals` is ahead of the periods' sums by exactly one goal for one side (the shootout winner's goal, as the NHL
     * counts it), an SO column of 1–0 is added, so the line's total agrees with the headline. Otherwise nothing is added
     * (the sheet's total is the board's final score either way). Not captured: whether `totalGoals` carries that goal.
     */
    private fun withShootout(periods: List<PeriodScore>, shootout: Boolean, gs: JsonObject): List<PeriodScore> {
        if (!shootout || periods.isEmpty() || periods.any { it.label.kind == PeriodKind.SO }) return periods
        val total = pair(gs, "totalGoals") ?: return periods
        val da = total.first - periods.sumOf { it.away }
        val dh = total.second - periods.sumOf { it.home }
        return if ((da == 1 && dh == 0) || (da == 0 && dh == 1)) periods + PeriodScore(PeriodLabel(PeriodKind.SO, 1), da, dh) else periods
    }

    private fun goals(gs: JsonObject, homeId: String?, headshot: (String) -> String?, label: (Int) -> PeriodLabel?): List<GoalItem> {
        var away = 0
        var home = 0
        val out = ArrayList<GoalItem>()
        for (g in ChlJson.objects(gs.a("goals"))) {
            val team = g.s("team_id")
            val side = when (ChlJson.text(g["home"])) {
                "1" -> Side.HOME
                "0" -> Side.AWAY
                else -> if (homeId != null && team != null) (if (team == homeId) Side.HOME else Side.AWAY) else continue
            }
            // (CHL hub fix pass, C2) A goal row in a shootout period (if the feed ever lists one) is no goal: not counted.
            val n = g.i("period_id")
            if (n != null && label(n)?.kind == PeriodKind.SO) continue
            // The running score counts every placed goal, even one whose row is dropped below.
            if (side == Side.HOME) home++ else away++
            val period = n?.let(label) ?: continue
            val scorer = g.o("goal_scorer")
            val name = ChlJson.name(scorer) ?: continue
            out += GoalItem(
                period = period,
                timeInPeriod = g.s("time") ?: "",
                side = side,
                scorer = name,
                scorerLast = ChlJson.lastName(scorer) ?: name,
                scorerTotal = g.i("scorer_goal_num")?.takeIf { it > 0 },
                // An unassisted slot is an object of nulls: kept only with a non-blank last name.
                assistsLast = listOf("assist1_player", "assist2_player").mapNotNull { ChlJson.lastName(g.o(it)) },
                strength = when {
                    g.flag("power_play") -> Strength.PP
                    g.flag("short_handed") -> Strength.SH
                    else -> Strength.EV
                },
                emptyNet = g.flag("empty_net"),
                away = away,
                home = home,
                clipId = null,
                headshot = ChlJson.headshot(scorer, headshot),
                gameWinning = g.flag("game_winning"),
            )
        }
        return out
    }

    private fun stars(gs: JsonObject, visitor: JsonObject, home: JsonObject, headshot: (String) -> String?): List<Star> {
        val mvps = gs.a("mvps") ?: return emptyList()
        val homeCode = ChlJson.code(home.s("team_code") ?: home.s("code")) ?: ""
        val visCode = ChlJson.code(visitor.s("team_code") ?: visitor.s("code")) ?: ""
        return mvps.take(3).mapIndexedNotNull { i, el ->
            val m = el as? JsonObject ?: return@mapIndexedNotNull null
            val name = ChlJson.name(m) ?: return@mapIndexedNotNull null
            Star(i + 1, name, ChlJson.lastName(m) ?: name, if (m.flag("home")) homeCode else visCode, ChlJson.headshot(m, headshot))
        }
    }

    /** `{"visitor":{"1":n,…},"home":{…}}` → [PeriodScore]s in key order, a period kept only when both sides have it. */
    private fun byPeriod(o: JsonObject?, label: (Int) -> PeriodLabel?): List<PeriodScore> {
        val v = o.o("visitor") ?: return emptyList()
        val h = o.o("home") ?: return emptyList()
        return v.keys.mapNotNull { k -> k.trim().toIntOrNull()?.let { it to k } }
            .sortedBy { it.first }
            .distinctBy { it.first }
            .mapNotNull { (n, k) ->
                val label = label(n) ?: return@mapNotNull null
                val a = v.i(k)?.takeIf { it >= 0 } ?: return@mapNotNull null
                val b = (h.i(k) ?: h.i(n.toString()))?.takeIf { it >= 0 } ?: return@mapNotNull null
                PeriodScore(label, a, b)
            }
    }

    private fun teamBox(lineup: JsonObject?): TeamBox {
        val skaters = ChlJson.objects(lineup.a("players")).mapNotNull { p ->
            val name = ChlJson.name(p) ?: return@mapNotNull null
            val g = p.i("goals")?.coerceAtLeast(0) ?: 0
            val a = p.i("assists")?.coerceAtLeast(0) ?: 0
            SkaterLine(
                name = name,
                pos = p.s("position_str") ?: "",
                g = g,
                a = a,
                p = g + a, // lineup `points` is null
                plusMinus = p.i("plusminus"),
                sog = p.i("shots"),
                toi = p.s("toi"),
            )
        }
        // One line per goalie with the stints added (never `gs.goalies`, which has one entry per stint); dressed only = 0 s.
        val goalies = ChlJson.objects(lineup.a("goalies")).mapNotNull { gl ->
            val secs = gl.i("seconds") ?: return@mapNotNull null
            if (secs <= 0) return@mapNotNull null
            val name = ChlJson.name(gl) ?: return@mapNotNull null
            val sa = gl.i("shots_against")
            val sv = gl.i("saves")
            GoalieLine(
                name = name,
                sa = sa,
                saves = sv,
                svPct = if (sa != null && sv != null && sa > 0 && sv in 0..sa) sv.toDouble() / sa else null,
                toi = gl.s("time"),
            )
        }
        return TeamBox(skaters, goalies)
    }
}

/** (§2.1 rank rule, S1, B3f) Competition ranks from integers. */
private object ChlRankMapping {
    /** A rate as the integer fraction [n] / [d], [d] > 0. */
    private class Frac(val n: Long, val d: Long)

    fun map(standard: JsonArray?, special: JsonArray?): ChlRanks? {
        if (standard == null && special == null) return null
        val std = rows(standard)
        val spc = rows(special)
        if (std.isEmpty() && spc.isEmpty()) return null
        val gf = HashMap<String, Frac>()
        val ga = HashMap<String, Frac>()
        for ((team, r) in std) {
            val gp = r.i("games_played") ?: continue
            if (gp <= 0) continue
            r.i("goals_for")?.takeIf { it >= 0 }?.let { gf[team] = Frac(it.toLong(), gp.toLong()) }
            r.i("goals_against")?.takeIf { it >= 0 }?.let { ga[team] = Frac(it.toLong(), gp.toLong()) }
        }
        val pp = HashMap<String, Frac>()
        val pkAgainst = HashMap<String, Frac>()
        for ((team, r) in spc) {
            val gp = r.i("games_played")
            if (gp != null && gp <= 0) continue
            val ppg = r.i("power_play_goals")
            val pps = r.i("power_plays")
            if (ppg != null && pps != null && pps > 0 && ppg in 0..pps) pp[team] = Frac(ppg.toLong(), pps.toLong())
            val ppga = r.i("power_play_goals_against")
            val tsh = r.i("times_short_handed")
            if (ppga != null && tsh != null && tsh > 0 && ppga in 0..tsh) pkAgainst[team] = Frac(ppga.toLong(), tsh.toLong())
        }
        return ChlRanks(
            gf = compete(gf, higherBetter = true),
            ga = compete(ga, higherBetter = false),
            pp = compete(pp, higherBetter = true),
            pk = compete(pkAgainst, higherBetter = false), // PK% = 1 − against: fewer against is better
        )
    }

    /** Every `sections[].data[]` row with a team key (`prop.team_code.teamLink`); the first row per team is kept. */
    private fun rows(arr: JsonArray?): List<Pair<String, JsonObject>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, JsonObject>>()
        for (top in ChlJson.objects(arr)) {
            for (section in ChlJson.objects(top.a("sections"))) {
                for (entry in ChlJson.objects(section.a("data"))) {
                    val team = entry.o("prop").o("team_code").s("teamLink") ?: continue
                    val row = entry.o("row") ?: continue
                    if (seen.add(team)) out += team to row
                }
            }
        }
        return out
    }

    /** Standard competition ranking: 1 + the number of teams strictly better, compared cross-multiplied (exact). */
    private fun compete(values: Map<String, Frac>, higherBetter: Boolean): Map<String, Int> {
        val all = values.values.toList()
        return values.mapValues { (_, mine) ->
            1 + all.count { other ->
                val cmp = (other.n * mine.d).compareTo(mine.n * other.d) // other's rate against mine
                if (higherBetter) cmp > 0 else cmp < 0
            }
        }
    }
}
