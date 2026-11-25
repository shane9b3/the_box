// Contract (Sports Hub commit 0): docs/design/m6-sports-hub.md §4.1 (detail), §5.1 (channel link), §6.1 (video).
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
import app.thebox.core.sports.TvBroadcast
import kotlinx.coroutines.flow.StateFlow

// ------------------------------------------------------------------ §4.1 game detail (NHL)

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
)

enum class StatKind { SOG, POWER_PLAY, HITS, BLOCKED, FACEOFF_PCT, PIM, GIVEAWAYS, TAKEAWAYS }

sealed interface StatValue {
    data class Count(val n: Int) : StatValue
    data class Fraction(val made: Int, val of: Int) : StatValue
    data class Pct(val value: Double) : StatValue
}

data class StatRow(val kind: StatKind, val away: StatValue, val home: StatValue)

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

data class GoalItem(
    val period: PeriodLabel,
    val timeInPeriod: String,
    val side: Side,
    val scorer: String,
    val scorerLast: String,
    val scorerTotal: Int?,
    val assistsLast: List<String>,
    val strength: Strength,
    val emptyNet: Boolean,
    val away: Int,
    val home: Int,
    val clipId: Long?,
    val headshot: String?,
)

data class Star(val rank: Int, val name: String, val lastName: String, val team: String, val headshot: String?)

data class Leader(val name: String, val value: Int, val headshot: String?)

data class LeaderPair(val category: String, val away: Leader?, val home: Leader?)

data class Goalie(val name: String, val record: String?, val gaa: Double?, val svPct: Double?, val headshot: String?)

data class GoaliePair(val away: List<Goalie>, val home: List<Goalie>)

data class SeriesFacts(val awayWins: Int, val homeWins: Int, val meetingsPlayed: Int)

data class BoxScore(val away: TeamBox, val home: TeamBox)

data class TeamBox(val skaters: List<SkaterLine>, val goalies: List<GoalieLine>)

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
    /** NFL and CHL: the panel uses the board's `BoardExtras` only. */
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
}

data class ChannelCandidate(
    val channel: GuideChannel,
    val playlistName: String,
    val confidence: MatchConfidence,
    val reasons: List<MatchReason>,
    /** Collapsed quality variants ("Also: FHD, 4K"). */
    val variants: List<GuideChannel>,
    /** For catch-up (§5.6). */
    val airing: ProgrammeInfo?,
)

/** `:data` `GameChannelMatcherImpl` (WP-3). */
interface GameChannelMatcher {
    suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate>
    /** Page open, access change. */
    fun clear()
}

// ------------------------------------------------------------------ §6.1 video

enum class ClipKind { RECAP, CONDENSED, GOAL }

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

/** From a lookup made just before playing; never cached, stored or logged. */
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
}

/** `:data` `NhlClipResolver` (WP-2). */
interface ClipResolver {
    suspend fun info(ref: ClipRef): ClipResult<ClipInfo>
    suspend fun stream(ref: ClipRef): ClipResult<Pair<ClipInfo, ClipStream>>
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
