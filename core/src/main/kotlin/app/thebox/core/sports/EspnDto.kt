// Contract (NFL commit 0, docs/design/m6-nfl.md §1.3, B3): the boundary between WP-2 (streams bytes into these) and
// WP-1 (maps them). Changes go through the lead.
package app.thebox.core.sports

import app.thebox.core.sports.hub.EspnVideoDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The lenient ESPN site-scoreboard DTO. Only the spine is typed; every leaf the bug reads is a `JsonElement?`, read by
 * the mapper's lenient helpers (a string, a number or a missing value all work; anything else is null for that field
 * only, ruling 21). A spine that changes type (`events` not a list) fails the decode: `BAD_BODY`, the existing stale
 * path. Decoded with `Json { ignoreUnknownKeys = true }`, so `odds`, `headlines`, `geoBroadcasts` and the rest are
 * skipped by the reader and never built.
 *
 * (Sports Hub WP-1, docs/design/m6-sports-hub.md §7.1, A-N4) `competitors[].records` / `linescores` and
 * `competitions[].leaders` / `broadcasts` are typed as `JsonElement?` (a few hundred bytes to ~3 KB per game) and read by
 * path; an absent or odd value can never fail a board. Verified against recorded boards (pre, in and post) before use.
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
    /** (Sports Hub §7.1) The game leaders: `[{name: "passingYards" | …, leaders: [{displayValue, athlete, team}]}]`. */
    val leaders: JsonElement? = null,
    /** (Sports Hub §7.2) `[{market: "national" | "home" | "away", names: ["CBS"]}]`. */
    val broadcasts: JsonElement? = null,
    /**
     * (NFL hub commit 0, §5.1) The Final Game Highlight only (≤ 1 per game), the summary's video shape; mapped by
     * `EspnVideoMapper` at the board's server date (P1). (§16 #18, §17.1 P1 note 5) Decoded by
     * [TolerantVideoListSerializer]: a value that is not a list, or an entry that is not a video object, is dropped, so
     * this field can never fail the board.
     */
    @Serializable(with = TolerantVideoListSerializer::class)
    val highlights: List<EspnVideoDto>? = null,
)

/** One side: [id] the team id, [score] a string ("7"); [team] has `abbreviation`, `displayName`, `shortDisplayName`, `logo`. */
@Serializable
class EspnCompetitorDto(
    val id: JsonElement? = null,
    val homeAway: JsonElement? = null,
    val score: JsonElement? = null,
    val team: JsonElement? = null,
    /** (Sports Hub §7.1) `[{type: "total", summary: "8-3"}, …]`. */
    val records: JsonElement? = null,
    /** (Sports Hub §7.1) `[{value: 7.0, period: 1}, …]`, one per quarter played. */
    val linescores: JsonElement? = null,
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

/**
 * (NFL hub §16 #18, §17.1 P1 note 5) The board's `highlights`, decoded so that no shape can fail the board: the value is
 * read as a JSON element; anything but an array is an empty list, and each entry that is not an object, or does not
 * decode as an [EspnVideoDto] (a leaf of the wrong type), is dropped on its own. Only the highlight is lost, never the
 * scores. Encoding (tests only) is the plain list.
 */
internal object TolerantVideoListSerializer : KSerializer<List<EspnVideoDto>> {
    private val list = ListSerializer(EspnVideoDto.serializer())
    override val descriptor: SerialDescriptor = list.descriptor

    override fun deserialize(decoder: Decoder): List<EspnVideoDto> {
        val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        val arr = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            try {
                json.json.decodeFromJsonElement(EspnVideoDto.serializer(), o)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<EspnVideoDto>) = list.serialize(encoder, value)
}
