// Contract (score bug commit 0): docs/design/m6-score-bug.md §1.1, §2.3, §2.6, §2.7, §4.4, §4.5, §6. Changes go
// through the lead; every enum here is append-only.
package app.thebox.core.sports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// ------------------------------------------------------------------ §1.1 the league seam

/** Persisted by name ([FollowedGame]); append-only. */
@Serializable
enum class League { NHL }

/** [id] is the league's own game id (NHL "2026020251"). */
data class GameId(val league: League, val id: String)

enum class GamePhase { SCHEDULED, PRE, LIVE, INTERMISSION, FINAL, POSTPONED }

enum class PeriodKind { REG, OT, SO }

/** [name] is the common name ("Canadiens", U-N8); [score] is null before the game; [logo] the validated dark logo URL. */
data class TeamLine(val abbrev: String, val name: String, val score: Int?, val logo: String?)

/** [secondsRemaining] in the period; it counts down in intermission too (facts §4). */
data class GameClock(val secondsRemaining: Int, val running: Boolean, val inIntermission: Boolean)

/**
 * The normalised game every layer above the [ScoreSource] speaks. [dayKey] is the league's game day ("2026-11-05",
 * Eastern for the NHL); [otNumber] is the overtime number (2 → "2OT"); [finalKind] is how a FINAL game ended. A final
 * game's [clock] is stale and never shown (§2.1).
 */
data class GameSnapshot(
    val id: GameId,
    val dayKey: String,
    val startUtcMs: Long,
    val phase: GamePhase,
    val away: TeamLine,
    val home: TeamLine,
    val period: Int?,
    val periodKind: PeriodKind?,
    val otNumber: Int?,
    val clock: GameClock?,
    val finalKind: PeriodKind?,
)

/** One league day. [prevDay]/[nextDay] come from the body (`prevDate`/`nextDate`); `currentDate` is authoritative (A-N10). */
data class DayBoard(val dayKey: String, val prevDay: String?, val nextDay: String?, val games: List<GameSnapshot>)

/** One HTTP answer. [Ok.serverDateMs]/[Ok.ageS] come from `Date`/`Age` (Age clamped to [0, AGE_MAX_S]); `receivedMono` is stamped by the source. */
sealed interface Fetch<out T> {
    data class Ok<T>(val value: T, val receivedMono: Long, val serverDateMs: Long?, val ageS: Int) : Fetch<T>
    /** A 404 with a JSON or empty body (an HTML 404 is `Failed(HTTP_OTHER)`, A-S12). */
    data object NotFound : Fetch<Nothing>
    data class Failed(val kind: FailKind, val retryAfterS: Int?, val receivedMono: Long) : Fetch<Nothing>
}

/**
 * Why a request failed. (Commit 0 addition) [OFFLINE] is never returned by a source: the repository uses it in
 * [DayState.Failed] when there is no default network, so the UI can pick the §5.4 "No internet connection" text.
 */
enum class FailKind { TIMEOUT, IO, HTTP_429, HTTP_5XX, HTTP_OTHER, BAD_BODY, OFFLINE }

interface ScoreSource {
    val league: League
    /** NHL: `/v1/score/now` (OkHttp follows the 307). */
    suspend fun today(): Fetch<DayBoard>
    suspend fun day(dayKey: String): Fetch<DayBoard>
    suspend fun game(id: GameId): Fetch<GameSnapshot>
    /** The dark-background logo variant, validated against `SportsEndpoints.logoBase` (A-N2). */
    fun logoUrl(team: TeamLine): String
}

// ------------------------------------------------------------------ §2.3 demand

/** §2.3. NONE = zero requests. */
enum class Demand { NONE, UNSEEN, SEEN }

/** Who asks for `SEEN` ([ScoreBugSource.reportDemand]). */
enum class DemandOwner { FULL_WITH_FOLLOW, SPORTS_TAB, SCORE_PICKER }

// ------------------------------------------------------------------ §2.6 the followed game

/** Persisted (domain `sports_follow`, not backed up). [away]/[home] are abbreviations; times are UTC ms (skew-corrected). */
@Serializable
data class FollowedGame(
    val league: League,
    val gameId: String,
    val startUtcMs: Long,
    val away: String,
    val home: String,
    val finalSeenUtcMs: Long? = null,
) {
    val id: GameId get() = GameId(league, gameId)
}

/**
 * The `sports_follow` document (A-S9: a non-null domain default).
 *
 * (Commit 0 additions; device-bound, so they live in this not-backed-up document) [pollPhaseMs] is `POLL_PHASE_MS`,
 * drawn once per install in [SportsConstants.POLL_PHASE_MIN_MS]..[SportsConstants.POLL_PHASE_MAX_MS] (null = not drawn
 * yet); [firstFollowShown] picks the short "Following CGY – EDM." toast after the first follow (§5.3). **Clearing a
 * follow is `update { it.copy(game = null) }`**, never `SportsFollowDoc()`, so both survive.
 */
@Serializable
data class SportsFollowDoc(
    val game: FollowedGame? = null,
    val pollPhaseMs: Long? = null,
    val firstFollowShown: Boolean = false,
)

enum class RemoveReason { NOT_LISTED, POSTPONED, SPORTS_OFF }

/** `ScoreRepository.events`; each becomes a toast with a reason and a next step (U-S12). Expiry after the final is silent. */
sealed interface ScoreEvent {
    data class Removed(val why: RemoveReason, val game: FollowedGame) : ScoreEvent
    /** The followed game was already final when followed: "That game is over. Its score shows for 5 minutes." (§5.3) */
    data class FollowedFinal(val game: FollowedGame) : ScoreEvent
    /**
     * (Commit 0 addition) A follow started, from the Sports tab or the quick-bar sheet (§5.3 toasts). [first]: the first
     * follow on this install (the long toast). Not raised for [FollowedFinal] games.
     */
    data class Followed(val game: FollowedGame, val first: Boolean) : ScoreEvent
}

// ------------------------------------------------------------------ §2.7 the machine's outputs

enum class Side { AWAY, HOME }

/** "1st" / "2nd" / "3rd" (REG, [number] = period), "OT" / "2OT" (OT, [number] = OT number, 1 → "OT"), "SO". */
data class PeriodLabel(val kind: PeriodKind, val number: Int)

/**
 * One line of the centre column (§4.5). `:core` emits these; the words are in `:ui` (`strings_sports.xml`).
 * One-line states ([Shootout], [Final]) are a `top` with a null `bottom`.
 */
sealed interface CentreText {
    /** The clock (`m:ss` below 10:00, `mm:ss` from 10:00): [ScoreBugView.clockSecs], the snapshot's own value. */
    data object Clock : CentreText
    /** Bottom line: "1st", "OT", "2OT". */
    data class Period(val period: PeriodLabel) : CentreText
    /** Bottom line: "1st INT" (U-N2). */
    data class Intermission(val period: PeriodLabel) : CentreText
    /** Bottom line at 0:00 before the intermission: "End 1st". */
    data class EndOf(val period: PeriodLabel) : CentreText
    /** One line: "SO". */
    data object Shootout : CentreText
    /** One line: "Final", "Final/OT", "Final/SO". */
    data class Final(val kind: PeriodKind) : CentreText
    /** Bottom line while stale (the column is dimmed). */
    data object NoUpdate : CentreText
    /** Bottom line while the delay has no snapshot yet (dimmed). */
    data object Delayed : CentreText
}

/**
 * What the bug draws (§4.5). `ScoreBugMachine.view()` is null before LIVE (owner ruling 2026-11-05).
 *
 * [clockSecs] (fix pass, owner ruling 6 "no local countdown"): the clock exactly as the shown snapshot reports it, floored
 * at 0; it changes only when a new snapshot becomes visible on the (possibly delayed) timeline. Null when [top] is not
 * [CentreText.Clock] or the snapshot has no clock.
 *
 * (Goal animation, docs/design/m6-goal-animation.md §5.1, N12) [goalSide] is the *goal mark*: non-null for
 * `GOAL_MARK_MS` after a goal becomes visible on the delayed timeline; the bottom line is never replaced. [goalSeq] bumps
 * once per goal and is monotonic for the machine's lifetime. [gameId] is the shown game (null for the place-mode
 * sample), so the UI keys its takeover on the game.
 */
data class ScoreBugView(
    val away: TeamLine,
    val home: TeamLine,
    val top: CentreText,
    val bottom: CentreText?,
    val dim: Boolean,
    val goalSeq: Int,
    val goalSide: Side?,
    val clockSecs: Int? = null,
    val gameId: GameId? = null,
)

/**
 * The day list the Sports tab and the picker show (A-N11). [Board.failing]: the last re-poll failed ("Updated 2 min ago").
 * [Board.isToday] (fix pass, U-B1/U-N5): the board is the league's today (learned from `/score/now`), not a day the
 * Sports tab picked.
 */
sealed interface DayState {
    data object Loading : DayState
    data class Board(val board: DayBoard, val fetchedAtMono: Long, val failing: Boolean, val isToday: Boolean = true) : DayState
    /** [FailKind.OFFLINE] = no default network (§5.4 "No internet connection"). */
    data class Failed(val kind: FailKind) : DayState
}

/** What `FullScreenHost`'s `ScoreBug` reads (A-B2). */
interface ScoreBugSource {
    /**
     * What the bug shows, clock included (owner ruling 6): re-emitted only when a new snapshot becomes visible or a state
     * changes (stale, goal mark, hide), never per second.
     */
    val bug: StateFlow<ScoreBugView?>
    /**
     * (Commit 0 addition) The `sports` prefs (corner, size, delay, enabled), so the overlay and the player VM read one
     * flow without a store of their own.
     */
    val prefs: StateFlow<SportsPrefs>
    fun reportDemand(owner: DemandOwner, on: Boolean)
    /**
     * (Fix pass, C-S5) The full-screen player's screen-off layer is showing: demand is NONE (zero requests, §2.3) while
     * it is on, whatever the owners say.
     */
    fun reportScreenOff(on: Boolean)
}

/** `ScoreRepositoryImpl` (`:data`, WP-2) is the one implementation; `FakeScoreRepository` (core testFixtures) for tests. */
interface ScoreRepository : ScoreBugSource {
    val day: StateFlow<DayState>
    val followed: StateFlow<FollowedGame?>
    /**
     * (Fix pass, U-S4) The followed game's last known view, kept while the bug is hidden for want of updates (bottom
     * "No update"); null before LIVE and with nothing followed. The quick-bar value line and the picker read it.
     */
    val lastScore: StateFlow<ScoreBugView?>
    val events: Flow<ScoreEvent>
    /** null = stop following. Following another game replaces the current one. */
    fun follow(game: GameSnapshot?)
    /** null = today (`/score/now`, once per open). */
    fun showDay(dayKey: String?)
    fun retry()
}

// ------------------------------------------------------------------ §4.4 placement

/** A rectangle in design px (1920 × 1080). */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun intersects(o: Box): Boolean = l < o.r && o.l < r && t < o.b && o.t < b
}

/** Persisted by name (`SportsPrefs`); append-only. */
@Serializable
enum class ScoreCorner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

/** Persisted by name (`SportsPrefs`); append-only. No Small (the period text would fall under 18 px). */
@Serializable
enum class ScoreBugSize { STANDARD, LARGE }

/** NONE | PASSIVE_INFO (after a zap, `InfoBarState.active == false`) | USER_BAR (active info bar, scrub bar) | QUICK. */
enum class BottomBar { NONE, PASSIVE_INFO, USER_BAR, QUICK }

/** Built in `:ui` by `PlayerUi.overlayGeometry()`; `:core` never imports PlayerUi. */
data class OverlayGeometry(
    val channelList: Box,
    val stats: Box,
    val groupCard: Box,
    val picker: Box,
    val numberEntry: Box,
    val infoBarTop: Float,
    val quickBarTop: Float,
)

data class PlacementInput(
    val corner: ScoreCorner,
    val size: ScoreBugSize,
    val placing: Boolean,
    val bar: BottomBar,
    val channelList: Boolean,
    val stats: Boolean,
    val groupCard: Boolean,
    val picker: Boolean,
    val numberEntry: Boolean,
    /** The catch-up probing line's rect while it shows. */
    val probing: Box?,
    val toast: Boolean,
    val reminder: Boolean,
    /** Exit dialog, PIN pad. */
    val modal: Boolean,
    val textTrackOn: Boolean,
    val screenOff: Boolean,
)

/** What `AppRoot` knows and `FullScreenHost` does not (A-B2): a toast, the reminder card, a modal (exit dialog, PIN pad). */
data class ScoreBugHostFacts(val toast: Boolean = false, val reminder: Boolean = false, val modal: Boolean = false)

// ------------------------------------------------------------------ §6 the player machine port

/**
 * `PlayerStateMachine.scores(...)` (A-B2): what the quick-bar item, the picker sheet and place mode read. [followed] is the
 * bug's view (null before LIVE); [game] the followed game (null = none); [enabled] is "Show Sports".
 */
data class ScoreFacts(
    val followed: ScoreBugView? = null,
    /** (Fix pass, U-S4) `ScoreRepository.lastScore`: the last known score while [followed] is null after puck drop. */
    val lastScore: ScoreBugView? = null,
    val game: FollowedGame? = null,
    val day: DayState = DayState.Loading,
    val corner: ScoreCorner = ScoreCorner.TOP_RIGHT,
    val delaySec: Int = 0,
    val enabled: Boolean = true,
)

/** `PlayerUiState.placeScore` while `PlayerLayer.PLACE_SCORE` (§6, U-B1). [sample]: no followed game, draw the sample bug. */
data class ScorePlaceState(val corner: ScoreCorner, val sample: Boolean)
