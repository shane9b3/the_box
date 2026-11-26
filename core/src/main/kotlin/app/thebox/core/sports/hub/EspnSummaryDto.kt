// Contract (NFL hub commit 0, docs/design/m6-nfl-hub.md §5.3): the boundary between P2 (streams ESPN's game summary into
// these) and P1 (maps them). FROZEN: changes go through the lead.
package app.thebox.core.sports.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * ESPN's `summary?event={id}`, typed down to the leaves the mapper reads, with no `JsonElement` for any list;
 * `JsonElement?` only for scalars whose type ESPN varies (`id`, `value`, `duration`). Every section that names a team
 * declares `team.id`, because team order differs by section (header, `leaders`, `injuries` home first; `boxscore` away
 * first): sides come from the header's `homeAway` by team id, never by index. No code may depend on key order.
 *
 * Decoded with `Json { ignoreUnknownKeys = true }` through `EspnHttp`'s capped stream (`ESPN_SUMMARY_MAX_BYTES`), so the
 * undeclared top-level keys are skipped by the lexer and never built: `drives`, `news`, `winprobability`, `standings`,
 * `pickcenter`, `odds`, `againstTheSpread`, `predictor`, `ticketsInfo`, `meta`, `format`, `wallclockAvailable`,
 * `broadcasts` and `header.broadcasts`; inside the kept sections every other `links`, `logos`, `ad`, the rest of
 * `tracking`, `gameInfo.weather` / `officials` and every `article` key but `type` and `headline`. No odds or gambling
 * field is declared anywhere (§2.4).
 */

/** The summary's kept top-level keys (§5.3). Every one is optional: the pre-game summary has no `scoringPlays`. */
@Serializable
class EspnSummaryDto(
    val header: EspnSumHeaderDto? = null,
    val boxscore: EspnSumBoxDto? = null,
    val scoringPlays: List<EspnScoringPlayDto>? = null,
    val leaders: List<EspnTeamLeadersDto>? = null,
    val gameInfo: EspnGameInfoDto? = null,
    val article: EspnArticleDto? = null,
    val videos: List<EspnVideoDto>? = null,
    /** Pre-game only. */
    val lastFiveGames: List<EspnLastFiveDto>? = null,
    val injuries: List<EspnTeamInjuriesDto>? = null,
)

/** A team reference: [id] ("28") and [abbreviation] ("WSH"). */
@Serializable
class EspnTeamRefDto(val id: JsonElement? = null, val abbreviation: String? = null)

/** `{href}` (a headshot, an HLS master). */
@Serializable
class EspnHrefDto(val href: String? = null)

// ---- header

/** [id] is the event id. */
@Serializable
class EspnSumHeaderDto(val id: JsonElement? = null, val competitions: List<EspnSumCompetitionDto>? = null)

@Serializable
class EspnSumCompetitionDto(
    val status: EspnSumStatusDto? = null,
    /** Home first in every sample. */
    val competitors: List<EspnSumCompetitorDto>? = null,
)

@Serializable
class EspnSumStatusDto(val type: EspnSumStatusTypeDto? = null)

/** [state]: "pre", "in", "post"; [name]: "STATUS_FINAL", "STATUS_SCHEDULED", … */
@Serializable
class EspnSumStatusTypeDto(
    val id: JsonElement? = null,
    val name: String? = null,
    val state: String? = null,
    val completed: Boolean? = null,
)

/** [homeAway]: "home" | "away". [record]: `[{type: "total", summary: "4-5-1"}, {type: "home", …}]`. */
@Serializable
class EspnSumCompetitorDto(
    val id: JsonElement? = null,
    val homeAway: String? = null,
    val record: List<EspnRecordDto>? = null,
    val team: EspnTeamRefDto? = null,
)

@Serializable
class EspnRecordDto(val type: String? = null, val summary: String? = null)

// ---- boxscore

/** [teams] and [players] are away first in every sample. */
@Serializable
class EspnSumBoxDto(val teams: List<EspnBoxTeamDto>? = null, val players: List<EspnBoxPlayersDto>? = null)

/** Team stats: pre-game season averages (`totalPointsPerGame`, …) or game totals (`totalYards`, `thirdDownEff`, …). */
@Serializable
class EspnBoxTeamDto(val team: EspnTeamRefDto? = null, val statistics: List<EspnNamedStatDto>? = null)

/** [name] ("thirdDownEff"), [displayValue] ("4-12"). */
@Serializable
class EspnNamedStatDto(val name: String? = null, val displayValue: String? = null)

@Serializable
class EspnBoxPlayersDto(val team: EspnTeamRefDto? = null, val statistics: List<EspnBoxCategoryDto>? = null)

/** One category ("passing", "rushing", "receiving", …): [keys] and [labels] name the columns of each athlete's stats. */
@Serializable
class EspnBoxCategoryDto(
    val name: String? = null,
    val keys: List<String>? = null,
    val labels: List<String>? = null,
    val athletes: List<EspnBoxAthleteDto>? = null,
)

@Serializable
class EspnBoxAthleteDto(val athlete: EspnBoxAthleteNameDto? = null, val stats: List<String>? = null)

/** Box athletes carry `displayName` and no `shortName`. */
@Serializable
class EspnBoxAthleteNameDto(val displayName: String? = null)

// ---- scoring plays

/** [id]: a string ("4018730781583"). [type]: `{id: "36", abbreviation: "TD", text: "Interception Return Touchdown"}`. */
@Serializable
class EspnScoringPlayDto(
    val id: JsonElement? = null,
    val type: EspnPlayTypeDto? = null,
    val text: String? = null,
    val period: EspnPeriodDto? = null,
    val clock: EspnClockDto? = null,
    val awayScore: JsonElement? = null,
    val homeScore: JsonElement? = null,
    val team: EspnTeamRefDto? = null,
)

@Serializable
class EspnPlayTypeDto(val id: JsonElement? = null, val abbreviation: String? = null, val text: String? = null)

@Serializable
class EspnPeriodDto(val number: JsonElement? = null)

@Serializable
class EspnClockDto(val displayValue: String? = null)

// ---- leaders (game leaders after kick-off, season leaders before)

/** Home first. */
@Serializable
class EspnTeamLeadersDto(val team: EspnTeamRefDto? = null, val leaders: List<EspnLeaderCategoryDto>? = null)

/** [name]: "passingYards", "rushingYards", "receivingYards", "sacks", "totalTackles". */
@Serializable
class EspnLeaderCategoryDto(val name: String? = null, val leaders: List<EspnLeaderDto>? = null)

/** [displayValue]: "248/372, 2,623 YDS, 19 TD, 10 INT" or "5.5"; [value]: a number, fractional for sacks. */
@Serializable
class EspnLeaderDto(
    val displayValue: String? = null,
    val value: JsonElement? = null,
    val athlete: EspnLeaderAthleteDto? = null,
)

/** [shortName]: "J. Cook III"; [headshot]: `{href}`. */
@Serializable
class EspnLeaderAthleteDto(val shortName: String? = null, val headshot: EspnHrefDto? = null)

// ---- game info, article

@Serializable
class EspnGameInfoDto(val venue: EspnVenueDto? = null)

@Serializable
class EspnVenueDto(val fullName: String? = null)

/** [type]: "Recap" after the game, "Preview" before; only a Recap's [headline] is shown. */
@Serializable
class EspnArticleDto(val type: String? = null, val headline: String? = null)

// ---- videos (also the board's `highlights[]` and the clip record's `videos[]`)

/**
 * One ESPN video object (§4.2, §4.5), the same shape in the summary's `videos[]`, the board's `highlights[]` and the
 * clip record. [id]: a number (50578630). [duration]: seconds. Dates are ISO Z. Only `links.source.HLS.href` is
 * declared: never the MP4s, `HLS.cmaf`, `HLS.9x16`, `HLS.cmb`, `HLS.shield` or `links.mobile`. Mapped only by
 * `EspnVideoMapper`.
 */
@Serializable
class EspnVideoDto(
    val id: JsonElement? = null,
    val headline: String? = null,
    val duration: JsonElement? = null,
    val originalPublishDate: String? = null,
    val timeRestrictions: EspnTimeRestrictionsDto? = null,
    val deviceRestrictions: EspnDeviceRestrictionsDto? = null,
    /** 1920 × 1080. */
    val thumbnail: String? = null,
    val tracking: EspnTrackingDto? = null,
    val links: EspnVideoLinksDto? = null,
)

@Serializable
class EspnTimeRestrictionsDto(val embargoDate: String? = null, val expirationDate: String? = null)

/** [type]: "whitelist"; [devices]: "settop", "tablet", … */
@Serializable
class EspnDeviceRestrictionsDto(val type: String? = null, val devices: List<String>? = null)

/** [coverageType]: "Final Game Highlight", "OnePlay", "Analysis", … */
@Serializable
class EspnTrackingDto(val coverageType: String? = null)

@Serializable
class EspnVideoLinksDto(val source: EspnVideoSourceDto? = null)

@Serializable
class EspnVideoSourceDto(@SerialName("HLS") val hls: EspnHrefDto? = null)

/** `content.core …/video/clips/{id}`: a one-item list wrapper (FACTS correction 1). */
@Serializable
class EspnClipRecordDto(val videos: List<EspnVideoDto>? = null)

// ---- pre-game: last five games, injuries

/** Home first in the samples; mapped by [team] id. [events] oldest first. */
@Serializable
class EspnLastFiveDto(val team: EspnTeamRefDto? = null, val events: List<EspnRecentEventDto>? = null)

/** [atVs]: "vs" | "@"; [gameResult]: "W" | "L" | "T"; [score]: winner first ("20-17"). */
@Serializable
class EspnRecentEventDto(
    val gameDate: String? = null,
    val atVs: String? = null,
    val opponent: EspnTeamRefDto? = null,
    val gameResult: String? = null,
    val score: String? = null,
)

/** Home first. */
@Serializable
class EspnTeamInjuriesDto(val team: EspnTeamRefDto? = null, val injuries: List<EspnInjuryDto>? = null)

/** [status]: "Out", "Doubtful", "Questionable", "Injured Reserve", … */
@Serializable
class EspnInjuryDto(
    val status: String? = null,
    val athlete: EspnInjuredAthleteDto? = null,
    val details: EspnInjuryDetailsDto? = null,
)

@Serializable
class EspnInjuredAthleteDto(val displayName: String? = null, val position: EspnPositionDto? = null)

@Serializable
class EspnPositionDto(val abbreviation: String? = null)

/** [type]: the injury ("Hamstring"). */
@Serializable
class EspnInjuryDetailsDto(val type: String? = null)
