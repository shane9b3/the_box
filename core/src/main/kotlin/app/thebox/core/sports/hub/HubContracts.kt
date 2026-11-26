// Contract (Sports Hub commit 0): docs/design/m6-sports-hub.md §4.1 (detail), §5.1 (channel link), §6.1 (video).
// (NFL hub commit 0) docs/design/m6-nfl-hub.md §6.1: appended, defaulted members for the NFL; the NHL leaves them at
// their defaults.
// Names are frozen; changes go through the lead. Every enum here is append-only.
package app.thebox.core.sports.hub

import app.thebox.core.common.Redact
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.Market
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.TvBroadcast
import kotlinx.coroutines.flow.StateFlow

// ------------------------------------------------------------------ §4.1 game detail (NHL, NFL)

/**
 * The shown game's detail. (NFL hub §6.1) The NFL's use of the existing fields: [awayScore] / [homeScore] null (the
 * board's score is the headline); [recapId] and [condensedId] null (the highlight is in [clips]); [goals], [stars],
 * [goalies], [series], [season] empty or null; [leaders] the NFL categories; [venue] `gameInfo.venue.fullName`;
 * [periods] empty (the box uses the board's); [broadcasts] empty.
 */
data class GameDetail(
    val id: GameId,
    val fetchedPhase: GamePhase,
    /** Server `Date` of the newest answer used (right-rail or landing); the panel's score source rule, §4.3. */
    val serverDateMs: Long?,
    /** Landing scores, for the newest-wins rule. */
    val awayScore: Int?,
    val homeScore: Int?,
    val venue: String?,
    /** Right-rail `teamGameStats` (live, final). */
    val gameStats: List<StatRow>,
    /** Right-rail `teamSeasonStats` + `last10Record` + the landing's team record. */
    val season: SeasonComparison?,
    /** Right-rail `linescore.byPeriod`. */
    val periods: List<PeriodScore>,
    /** Landing `summary.scoring`. */
    val goals: List<GoalItem>,
    /** Landing `summary.threeStars`. */
    val stars: List<Star>,
    /** Landing `matchup.skaterComparison.leaders`. */
    val leaders: List<LeaderPair>,
    /** Landing `matchup.goalieComparison`. */
    val goalies: GoaliePair?,
    /** Right-rail `seasonSeries` + `seasonSeriesWins`. */
    val series: SeriesFacts?,
    val recapId: Long?,
    val condensedId: Long?,
    val broadcasts: List<TvBroadcast>,
    /** (NFL) `scoringPlays[]`, game order. */
    val plays: List<ScoringPlay> = emptyList(),
    /** (NFL) Pre-game rows RECORD, LAST_FIVE, POINTS_PG, POINTS_ALLOWED_PG, YARDS_PG, YARDS_ALLOWED_PG, in order. */
    val seasonStats: List<StatRow> = emptyList(),
    /** (NFL) RECAP and PLAY clips offered at parse time (§4.3); no URL (URLs stay in `:data`'s catalog). */
    val clips: List<GameClip> = emptyList(),
    /** (NFL) `article.headline` of a Recap article. */
    val recapHeadline: String? = null,
    /** (NFL, pre-game) `lastFiveGames`, both teams, oldest first. */
    val recent: List<RecentGame> = emptyList(),
    /** (NFL, pre-game) Out, Doubtful, Questionable; IR excluded. */
    val injuries: List<InjuryLine> = emptyList(),
    /** (NFL) serverDate − wall clock at receive, clamped to ±HUB_CLOCK_SKEW_MAX_MS; 0 without a Date header. */
    val clockSkewMs: Long = 0,
    /**
     * (NFL, integration §18) Every qualifying (Out, Doubtful, Questionable) injury ESPN listed per side, of which [injuries]
     * keeps at most `HUB_NFL_INJURIES_PER_TEAM`; the Matchup sheet's "+n more" is this count minus the lines kept. A side
     * absent from the map counts its kept lines.
     */
    val injuryCounts: Map<Side, Int> = emptyMap(),
) {
    /** (NFL hub §6.1) The league-neutral scoring list: the NHL's goals, else the NFL's scoring plays. */
    val scoring: List<ScoringEvent> get() = if (goals.isNotEmpty()) goals else plays
}

enum class StatKind {
    SOG, POWER_PLAY, HITS, BLOCKED, FACEOFF_PCT, PIM, GIVEAWAYS, TAKEAWAYS,
    // (NFL hub §2.2) game: only the kinds a row draws
    TOTAL_YARDS, PASS_YARDS, RUSH_YARDS, FIRST_DOWNS, THIRD_DOWN, TURNOVERS, POSSESSION,
    // (NFL hub §2.2) season
    RECORD, LAST_FIVE, POINTS_PG, POINTS_ALLOWED_PG, YARDS_PG, YARDS_ALLOWED_PG,
}

sealed interface StatValue {
    data class Count(val n: Int) : StatValue
    /** [dash] (NFL hub §2.2): drawn "4-12" (NFL efficiency) instead of "1/3". */
    data class Fraction(val made: Int, val of: Int, val dash: Boolean = false) : StatValue
    data class Pct(val value: Double) : StatValue
    /** (NFL) "22.6": [places] decimals. */
    data class Decimal(val value: Double, val places: Int = 1) : StatValue
    /** (NFL) Time of possession, "31:31". */
    data class Clock(val seconds: Int) : StatValue
    /** (NFL) Records and forms ("6-4", "4-5-1", "3-2"): never compared. */
    data class Text(val text: String) : StatValue
}

data class StatRow(val kind: StatKind, val away: StatValue, val home: StatValue)

/**
 * (NFL hub commit 0, §6.1) Which side a stat row highlights. Pure. It replaces the UI's PIM / GIVEAWAYS special case:
 * the comparison is the one `HubPanel` made (counts and fractions by `made`, percentages by value), with the NFL rules
 * added.
 */
object StatRules {
    /**
     * The better side of [row], or null for none: a tie, a [StatValue.Text] or [StatValue.Clock] row, or mixed value
     * types. THIRD_DOWN compares the rate made / of (of = 0 on either side → null), then made as the tie-break.
     */
    fun better(row: StatRow): Side? {
        if (row.kind == StatKind.THIRD_DOWN) return thirdDown(row.away, row.home)
        val a = num(row.away) ?: return null
        val h = num(row.home) ?: return null
        if (a == h) return null
        val awayHigher = a > h
        return if (awayHigher != lowerIsBetter(row.kind)) Side.AWAY else Side.HOME
    }

    /** PIM, GIVEAWAYS, TURNOVERS, POINTS_ALLOWED_PG, YARDS_ALLOWED_PG. */
    fun lowerIsBetter(kind: StatKind): Boolean = when (kind) {
        StatKind.PIM, StatKind.GIVEAWAYS, StatKind.TURNOVERS, StatKind.POINTS_ALLOWED_PG, StatKind.YARDS_ALLOWED_PG -> true
        StatKind.SOG, StatKind.POWER_PLAY, StatKind.HITS, StatKind.BLOCKED, StatKind.FACEOFF_PCT, StatKind.TAKEAWAYS,
        StatKind.TOTAL_YARDS, StatKind.PASS_YARDS, StatKind.RUSH_YARDS, StatKind.FIRST_DOWNS, StatKind.THIRD_DOWN,
        StatKind.POSSESSION, StatKind.RECORD, StatKind.LAST_FIVE, StatKind.POINTS_PG, StatKind.YARDS_PG -> false
    }

    /** The compared number: a count, a fraction's made, a percentage, a decimal; null for Text and Clock (never compared). */
    private fun num(v: StatValue): Double? = when (v) {
        is StatValue.Count -> v.n.toDouble()
        is StatValue.Fraction -> v.made.toDouble()
        is StatValue.Pct -> v.value
        is StatValue.Decimal -> v.value
        is StatValue.Clock, is StatValue.Text -> null
    }

    private fun thirdDown(away: StatValue, home: StatValue): Side? {
        val a = away as? StatValue.Fraction ?: return null
        val h = home as? StatValue.Fraction ?: return null
        if (a.of <= 0 || h.of <= 0) return null
        // Cross-multiplied, so equal rates compare exactly ("2-4" against "4-8").
        val cmp = (a.made.toLong() * h.of).compareTo(h.made.toLong() * a.of)
        return when {
            cmp > 0 -> Side.AWAY
            cmp < 0 -> Side.HOME
            a.made > h.made -> Side.AWAY
            a.made < h.made -> Side.HOME
            else -> null
        }
    }
}

data class Ranked(val value: Double, val rank: Int?)

data class Last10(val record: String, val streakType: String?, val streak: Int?)

data class SeasonComparison(
    val awayRecord: String?,
    val homeRecord: String?,
    val awayLast10: Last10?,
    val homeLast10: Last10?,
    val awayGf: Ranked?,
    val homeGf: Ranked?,
    val awayGa: Ranked?,
    val homeGa: Ranked?,
    val awayPp: Ranked?,
    val homePp: Ranked?,
    val awayPk: Ranked?,
    val homePk: Ranked?,
)

/** One period's (or NFL quarter's) score. Also used by `BoardExtras.periods` in `SportsContracts.kt`. */
data class PeriodScore(val label: PeriodLabel, val away: Int, val home: Int)

enum class Strength { EV, PP, SH }

/** (NFL hub commit 0, §6.1) The facts every scoring row shares (NHL goals, NFL scoring plays). */
interface ScoringEvent {
    val period: PeriodLabel
    val clock: String
    val side: Side
    val away: Int
    val home: Int
}

data class GoalItem(
    override val period: PeriodLabel,
    val timeInPeriod: String,
    override val side: Side,
    val scorer: String,
    val scorerLast: String,
    val scorerTotal: Int?,
    val assistsLast: List<String>,
    val strength: Strength,
    val emptyNet: Boolean,
    override val away: Int,
    override val home: Int,
    val clipId: Long?,
    val headshot: String?,
) : ScoringEvent {
    override val clock: String get() = timeInPeriod
}

/**
 * (NFL hub §6.1) From `type`: abbreviation "FG" → FG; "SF" → SAFETY; "TD" → DEF_TD when `type.text` contains
 * "Interception Return" or "Fumble Return" (id 36 seen), else TD; anything else → OTHER. No two-point member: never seen
 * as its own play.
 */
enum class PlayScore { TD, FG, SAFETY, DEF_TD, OTHER }

/** (NFL hub §6.1) One NFL scoring play. [id]: ESPN's play id, a string ("4018730781583"); the row key. */
data class ScoringPlay(
    val id: String,
    override val period: PeriodLabel,
    override val clock: String,
    override val side: Side,
    val kind: PlayScore,
    /** ESPN's text, trimmed ("Tee Higgins 31 Yd pass from Joe Burrow (Evan McPherson Kick)"). */
    val text: String,
    override val away: Int,
    override val home: Int,
) : ScoringEvent

/** (NFL hub §6.1, §4.3) Clip metadata carried by the detail. [expiresMs] is the effective expiry. No media URL. */
data class GameClip(
    val videoId: Long,
    val kind: ClipKind,
    val title: String,
    val durationMs: Long?,
    val publishedMs: Long?,
    val embargoMs: Long?,
    val expiresMs: Long,
    /** `thumbnail` (1920 × 1080). */
    val thumbUrl: String?,
)

enum class GameResult { W, L, T }

/** (NFL hub §3.6) One of the last five games. [score]: ESPN's, winner first ("20-17"). */
data class RecentGame(
    val side: Side,
    val atOpponent: Boolean,
    val opponent: String,
    val result: GameResult,
    val score: String,
    val dateMs: Long?,
)

enum class InjuryStatus { OUT, DOUBTFUL, QUESTIONABLE }

/** (NFL hub §3.6) One injury-report line. */
data class InjuryLine(val side: Side, val name: String, val position: String?, val status: InjuryStatus, val detail: String?)

data class Star(val rank: Int, val name: String, val lastName: String, val team: String, val headshot: String?)

/**
 * [line] (NFL hub §6.1): ESPN's `displayValue` ("248/372, 2,623 YDS, 19 TD, 10 INT", "5.5"). [value] stays an Int
 * (yards); fractional values (sacks) are drawn only from [line]. NFL `LeaderPair` categories: "passingYards",
 * "rushingYards", "receivingYards", "sacks", "totalTackles".
 */
data class Leader(val name: String, val value: Int, val headshot: String?, val line: String? = null)

data class LeaderPair(val category: String, val away: Leader?, val home: Leader?)

data class Goalie(val name: String, val record: String?, val gaa: Double?, val svPct: Double?, val headshot: String?)

data class GoaliePair(val away: List<Goalie>, val home: List<Goalie>)

data class SeriesFacts(val awayWins: Int, val homeWins: Int, val meetingsPlayed: Int)

data class BoxScore(val away: TeamBox, val home: TeamBox)

/** [tables] (NFL hub §3.5): the NFL's Passing, Rushing and Receiving tables; the NHL keeps [skaters] / [goalies]. */
data class TeamBox(val skaters: List<SkaterLine>, val goalies: List<GoalieLine>, val tables: List<BoxTable> = emptyList())

enum class BoxCategory { PASSING, RUSHING, RECEIVING }

/**
 * [columns]: ESPN's stat keys (`completions/passingAttempts`, `passingYards`, `QBRating`, …), stable keys, never words;
 * the Box score sheet words them from its resources (integration §18). [BoxLine.values] follow [columns].
 */
data class BoxTable(val category: BoxCategory, val columns: List<String>, val lines: List<BoxLine>)

data class BoxLine(val name: String, val values: List<String>)

data class SkaterLine(
    val name: String,
    val pos: String,
    val g: Int,
    val a: Int,
    val p: Int,
    val plusMinus: Int?,
    val sog: Int?,
    val toi: String?,
)

data class GoalieLine(val name: String, val sa: Int?, val saves: Int?, val svPct: Double?, val toi: String?)

/** `:data` `NhlDetailSource` (WP-1). */
interface GameDetailSource {
    suspend fun detail(id: GameId): Fetch<GameDetail>
    suspend fun boxScore(id: GameId): Fetch<BoxScore>
}

sealed interface DetailState {
    data object None : DetailState
    data class Loading(val id: GameId) : DetailState
    /** [stale]: older than 2 × the phase's poll interval (A-S7). */
    data class Ready(val detail: GameDetail, val fetchedAtMono: Long, val failing: Boolean, val stale: Boolean) : DetailState
    data class Failed(val id: GameId, val kind: FailKind) : DetailState
    /** CHL: the panel uses the board's `BoardExtras` only. */
    data class BoardOnly(val id: GameId) : DetailState
}

/** `:data` `GameDetailRepositoryImpl` (WP-1). */
interface GameDetailRepository {
    val detail: StateFlow<DetailState>
    /** The panel's game; null = page closed. */
    fun show(game: GameSnapshot?)
    /** True while a clip plays. */
    fun pause(paused: Boolean)
    fun retry()
    suspend fun boxScore(id: GameId): Fetch<BoxScore>
    fun trim()
}

// ------------------------------------------------------------------ §5.1 channel link

enum class MatchConfidence { CONFIRMED, LIKELY, POSSIBLE }

sealed interface MatchReason {
    /** The channel's guide names the game in the window. */
    data class Guide(val title: String, val startMs: Long, val endMs: Long, val inArchive: Boolean) : MatchReason
    /** The channel's guide has a sport-word airing in the window that names no teams (A-S8). */
    data class SportListing(val title: String, val startMs: Long) : MatchReason
    /** The channel's name equals a broadcaster alias NHL.com (or ESPN) lists. Never a local affiliate (R-S3). */
    data class Broadcaster(val network: String, val market: Market, val country: String?) : MatchReason
    /**
     * (Event channels commit 0, docs/design/m6-event-channels.md §8.2) The channel is [side]'s team channel: its name
     * equals the team's name and its group or tvg id says so. [team]: the full name ("Edmonton Oilers"); [nickname]:
     * "Oilers".
     */
    data class TeamChannel(val team: String, val nickname: String, val side: Side) : MatchReason
    /**
     * (Event channels commit 0, §8.3, §8.4) The channel's name lists the game as an event. [title]: the cleaned event
     * title; [startMs]: its start; [citiesOnly]: both sides matched by place only; [zoneAssumed]: the name had no zone
     * (`EventZoneSource.ASSUMED`), so the hub shows no time.
     */
    data class EventName(val title: String, val startMs: Long, val citiesOnly: Boolean, val zoneAssumed: Boolean) : MatchReason
}

data class ChannelCandidate(
    val channel: GuideChannel,
    val playlistName: String,
    val confidence: MatchConfidence,
    val reasons: List<MatchReason>,
    /**
     * Collapsed quality variants ("Also: FHD, 4K"). (Owner ruling 2026-11-25, event channels §18) The matcher no longer
     * folds any channel: every feed is its own row, so it leaves this empty; the sheet still draws it when set.
     */
    val variants: List<GuideChannel>,
    /** For catch-up (§5.6). */
    val airing: ProgrammeInfo?,
    /** (Event channels commit 0, §9.1) The provider group's name: line 3 for duplicate short names, "Also in". */
    val group: String? = null,
    /**
     * (Owner ruling 2026-11-25, event channels §18) Another feed of the channel in the row above (the same guide channel,
     * alias or name residue): the sheet's reason says so instead of repeating the evidence.
     */
    val anotherFeed: Boolean = false,
)

/** `:data` `GameChannelMatcherImpl` (WP-3). */
interface GameChannelMatcher {
    suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate>
    /**
     * (Event channels commit 0, docs/design/m6-event-channels.md §8.3, §8.4; re-review E14) [match] with [board]: the
     * other games the hub has loaded for the shown league's day (`SportsViewModel` passes `repo.day`), for the
     * cities-only upgrade of an event name. The default body ignores [board] so commit 0 compiles; WP-4 overrides it.
     */
    suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess, board: List<GameSnapshot>): List<ChannelCandidate> =
        match(game, nowMs, access)
    /** Page open, access change. */
    fun clear()
    /**
     * (Event channels commit 0, §8.5) The end of usable guide data, epoch ms (`EpgGenerationInfo.dataToMin`, which an
     * older generation computes in memory; code review 2026-11-25: else the build time plus a day, capped at
     * `windowToMin`), or null without a guide. Read by `ChannelHorizon.beyondGuide`.
     */
    fun guideEndMs(): Long? = null
    /** (Event channels commit 0, §8.5) [game]'s two team channels only (stage T), for the beyond-guide line. */
    suspend fun teamChannels(game: GameSnapshot, access: ParentalAccess): List<ChannelCandidate> = emptyList()
}

// ------------------------------------------------------------------ §6.1 video

enum class ClipKind {
    RECAP,
    CONDENSED,
    GOAL,
    /** (NFL hub §4.2) One play, or any single clip of the game. */
    PLAY,
}

data class ClipRef(val videoId: Long, val kind: ClipKind, val gameId: GameId)

/** Metadata; cached ≤ HUB_CLIP_META_TTL_MS in memory; URLs are signed and never persisted. */
data class ClipInfo(
    val ref: ClipRef,
    val title: String,
    val description: String?,
    val durationMs: Long?,
    val posterUrl: String?,
    val thumbnailUrl: String?,
)

/** From a lookup (NHL) or the in-memory catalog (ESPN, unsigned URLs); never stored on disk or logged. */
data class ClipStream(val url: String, val format: ClipFormat, val captionsUrl: String?) {
    override fun toString() = "ClipStream(${Redact.host(url)}, $format)"
}

enum class ClipFormat { HLS, MP4 }

sealed interface ClipResult<out T> {
    data class Ok<T>(val value: T) : ClipResult<T>
    data object NotFound : ClipResult<Nothing>
    data object Geo : ClipResult<Nothing>
    data object Refused : ClipResult<Nothing>
    /**
     * [retryAfterS] (verification review L-a, additive): the seconds the service asked to wait (its `Retry-After`, or the
     * rest of the key read's back-off it set); null when it named none.
     */
    data class Failed(val kind: FailKind, val retryAfterS: Int? = null) : ClipResult<Nothing>
    /** (NFL hub §4.3) Past its expiry margin, or embargoed: never played. */
    data object Expired : ClipResult<Nothing>
}

/** `:data` `NhlClipResolver` (WP-2); (NFL hub §4.2) `ClipResolvers` routes by league. */
interface ClipResolver {
    suspend fun info(ref: ClipRef): ClipResult<ClipInfo>
    /** [fresh] (NFL hub §4.2): bypass any cache (the expired-link re-resolve and Try again). */
    suspend fun stream(ref: ClipRef, fresh: Boolean = false): ClipResult<Pair<ClipInfo, ClipStream>>
}

/** (NFL hub commit 0, §4.3) The one clip validity rule, shared by `:core`, `:data` and `:ui`. Pure. */
object ClipOffer {
    /** Not embargoed at [nowMs], and [nowMs] + `HUB_CLIP_EXPIRY_MARGIN_MS` is before [expiresMs]. */
    fun offered(embargoMs: Long?, expiresMs: Long, nowMs: Long): Boolean =
        (embargoMs == null || embargoMs <= nowMs) && nowMs + SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS < expiresMs

    /**
     * (NFL hub code review 2026-11-26, data #3 / UI m2) Not expired at [nowMs] (`nowMs + margin < expiresMs`), embargoed
     * or not: whether a clip is kept in a list, and the opposite of "ESPN removed it". [offered] implies this.
     */
    fun listed(expiresMs: Long, nowMs: Long): Boolean = nowMs + SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS < expiresMs

    /**
     * The next instant after [nowMs] at which [offered] changes (the embargo lifting, or `expiresMs − margin`); null for
     * none (the clip is past its offer window for good).
     */
    fun changesAt(embargoMs: Long?, expiresMs: Long, nowMs: Long): Long? {
        val off = expiresMs - SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS // offered turns false at this instant
        if (off <= nowMs) return null
        if (embargoMs != null && embargoMs > nowMs) return embargoMs.takeIf { it < off } // never offered: no change
        return off
    }
}

/** What ClipSession resumes (§6.6). */
sealed interface ResumePoint {
    data object Nothing : ResumePoint
    data class Live(val identity: ChannelIdentity) : ResumePoint
    /**
     * (R-N3) [rawEndMin] is derived from [programme] (`rawStartMin + (endMin − startMin)`; `ArchiveStatus` has none);
     * [timeShift] is `ArchiveStatus.timeShift`, so a session started from live pause or rewind comes back with −MM:SS,
     * not the CATCH-UP pill; [playheadMs] is re-read from `status.atMs` just before `playClip`, not before the resolve.
     */
    data class Archive(
        val channel: GuideChannel,
        val programme: ProgrammeInfo,
        val rawEndMin: Int,
        val timeShift: Boolean,
        val playheadMs: Long,
    ) : ResumePoint
}
