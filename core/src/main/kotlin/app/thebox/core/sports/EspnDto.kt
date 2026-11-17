// Contract (NFL commit 0, docs/design/m6-nfl.md §1.3, B3): the boundary between WP-2 (streams bytes into these) and
// WP-1 (maps them). Changes go through the lead.
package app.thebox.core.sports

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The lenient ESPN site-scoreboard DTO. Only the spine is typed; every leaf the bug reads is a `JsonElement?`, read by
 * the mapper's lenient helpers (a string, a number or a missing value all work; anything else is null for that field
 * only, ruling 21). A spine that changes type (`events` not a list) fails the decode: `BAD_BODY`, the existing stale
 * path. Decoded with `Json { ignoreUnknownKeys = true }`, so `leaders`, `odds`, `headlines`, `broadcasts` and the rest
 * are skipped by the reader and never built.
 */

/** `scoreboard` (the week) or `scoreboard?dates=` / `?seasontype=&week=`: the list and the week's calendar. */
@Serializable
class EspnBoardDto(
    val leagues: List<EspnLeagueDto>? = null,
    val season: JsonElement? = null,
    val week: JsonElement? = null,
    val events: List<EspnEventDto>? = null,
)

/** (N10) `game()`: no leagues, so the 4 KB calendar is skipped by the reader. */
@Serializable
class EspnGameDto(val events: List<EspnEventDto>? = null)

/** `leagues[0]`: [calendar] lists each season type's weeks (`label`, `value`, `startDate`, `endDate`). */
@Serializable
class EspnLeagueDto(val calendar: JsonElement? = null)

/** One game: [id] digits ("401873038"), [date] ISO Z. */
@Serializable
class EspnEventDto(
    val id: JsonElement? = null,
    val date: JsonElement? = null,
    val competitions: List<EspnCompetitionDto>? = null,
)

/**
 * `competitions[0]`: [status] (`type.{id, name, state, completed, shortDetail}`, `period`, `clock`, `displayClock`) and
 * [situation] (present only once the game is `in`).
 */
@Serializable
class EspnCompetitionDto(
    val competitors: List<EspnCompetitorDto>? = null,
    val status: JsonElement? = null,
    val situation: JsonElement? = null,
)

/** One side: [id] the team id, [score] a string ("7"); [team] has `abbreviation`, `displayName`, `shortDisplayName`, `logo`. */
@Serializable
class EspnCompetitorDto(
    val id: JsonElement? = null,
    val homeAway: JsonElement? = null,
    val score: JsonElement? = null,
    val team: JsonElement? = null,
)

/*
 * (NFL YDS §1.7, review S3) ESPN's per-team statistics
 * (`sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/{id}/competitions/{id}/competitors/{team}/statistics`):
 * only `splits.categories[].{name, stats[].{name, value, displayValue}}` is typed; the rest (`$ref`, `athletes`,
 * descriptions) is skipped by the reader (`ignoreUnknownKeys`). Decoded by `EspnScoreSource` through its usual capped,
 * drained `call()`; read by `NflYards.totalYards`.
 */
@Serializable
class EspnStatsDto(val splits: EspnStatsSplitsDto? = null)

@Serializable
class EspnStatsSplitsDto(val categories: List<EspnStatsCategoryDto>? = null)

@Serializable
class EspnStatsCategoryDto(val name: JsonElement? = null, val stats: List<EspnStatDto>? = null)

/** One stat: [name] ("totalYards"), [value] (a number, 123.0) and [displayValue] (a string, "123"; "-2"). */
@Serializable
class EspnStatDto(val name: JsonElement? = null, val value: JsonElement? = null, val displayValue: JsonElement? = null)
