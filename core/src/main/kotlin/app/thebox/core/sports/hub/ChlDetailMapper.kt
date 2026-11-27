// Contract (CHL hub commit 0): docs/design/m6-chl-hub.md §4.3, §4.4 and "Commit 0 rulings". The signatures below are
// FROZEN: P1 fills the bodies, P2 calls them (through `ChlDetailSource`'s mapper parameters), P3 draws what they emit.
// Changes go through the lead.
package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * (CHL hub §4.3) HockeyTech `lscluster` bodies → the hub's [GameDetail] and [BoxScore]. Pure (no clock, no I/O, no
 * logging), lenient, and total: a missing or odd field gives null / "–" in the output, never an exception; a body that
 * does not map at all gives null (the source turns it into `BAD_BODY`). Fixtures: `core/src/testFixtures/resources/
 * sports/chl/hub/` (`ChlHubFixtures` loads them). Commit 0 is a stub: every function returns null.
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
    @Suppress("UNUSED_PARAMETER")
    fun preview(
        id: GameId,
        pv: JsonObject,
        awayGoalies: JsonObject?,
        homeGoalies: JsonObject?,
        ranks: ChlRanks?,
        playoff: Boolean,
        headshot: (playerId: String) -> String?,
    ): GameDetail? = null

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
    @Suppress("UNUSED_PARAMETER")
    fun summary(id: GameId, gs: JsonObject, headshot: (playerId: String) -> String?): Pair<GameDetail, BoxScore>? = null

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
    @Suppress("UNUSED_PARAMETER")
    fun ranks(standard: JsonArray?, special: JsonArray?): ChlRanks? = null
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
