// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §2.3, §2.4, §3): the seam between `HockeyTechMapper` (WP-1, `:core`)
// and `HockeyTechScoreSource` (WP-3, `:data`), which are built in parallel. Every type here is frozen after commit 0;
// a package that needs another field asks the lead. The HockeyTech client code (`ohl`, `whl`, `lhjmq`) never appears in
// `:core`: the source passes the full validated logo prefix instead.
package app.thebox.core.sports

import kotlinx.serialization.json.JsonObject

/**
 * (CHL §3.5, §6.1) One `teamsbyseason` entry: [teamId] the HockeyTech team id; [code] upper-cased and gated
 * (`[A-Z0-9]{2,4}`); [fullName] "City Nickname" (a followed snapshot's `TeamLine.name`, P10); [cardName] the nickname (a
 * day board's); [logo] the validated logo URL after the JPEG swap (§3.5), null = no logo (the disc / capsule).
 */
data class ChlTeam(
    val teamId: String,
    val code: String,
    val fullName: String,
    val cardName: String,
    val logo: String?,
)

/**
 * (CHL §3.2, §3.3) What a `gamesbydate` entry knows that the Firebase nodes do not, kept per game by the source (§2.3) and
 * handed back to [HockeyTechMapper.followed]:
 * - [startUtcMs] from `date_played` + `schedule_time` in `timezone` (the league's reference zone when unknown);
 * - [away] / [home] the teams with their **full name** ([TeamLine.name] = "City Nickname", P10), not the board's card name
 *   (the nickname that `ChlBoard.board`'s own lines carry); code, logo and the scores as on the board. In board mode (S5)
 *   the source answers the board's game with `away` / `home` replaced by these lines, so a followed snapshot always
 *   carries the full names;
 * - [playoff] the season's playoff flag (`seasons`), null = unknown;
 * - [periodLengthsS] `periods{n}.length` by period number (OT is listed once it starts; §3.3 fallbacks apply otherwise);
 * - [status] / [final] the board's `status` and `final == "1"` (§3.4: the last fallback for the status; S5 board mode).
 */
data class ChlGameFacts(
    val gameId: String,
    val seasonId: String?,
    val dayKey: String,
    val startUtcMs: Long,
    val away: TeamLine,
    val home: TeamLine,
    val playoff: Boolean?,
    val periodLengthsS: Map<Int, Int>,
    val status: Int?,
    val final: Boolean,
)

/**
 * (CHL §2.3) A mapped `gamesbydate` body: the [board] (`DayBoard.league` set; card names; games failing the code gate
 * dropped), the [facts] of each listed game by `game_id`, and the [seasonIds] the games belong to (for `teamsbyseason`
 * and `seasons`).
 */
data class ChlBoard(
    val board: DayBoard,
    val facts: Map<String, ChlGameFacts>,
    val seasonIds: Set<String>,
)

/**
 * (CHL §2.4) One live-mode poll's Firebase nodes for the followed game, as decoded `JsonObject`s. A null node answered
 * `null` (or, for [shootouts], was not requested); a 304 is passed as the body it confirmed. [penaltiesFailed] /
 * [goalsFailed]: that request failed this poll (H8, `powerPlayUnknown`), as distinct from a `null` node (H6, H7). A
 * failed optional node other than those two is passed as null.
 */
data class ChlNodes(
    val runningclock: JsonObject?,
    val publishedclock: JsonObject?,
    val goalssummary: JsonObject?,
    val shotssummary: JsonObject?,
    val penalties: JsonObject?,
    val goals: JsonObject?,
    val shootouts: JsonObject? = null,
    val penaltiesFailed: Boolean = false,
    val goalsFailed: Boolean = false,
)
