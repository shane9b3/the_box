// Contract (score bug commit 0): docs/design/m6-score-bug.md §1.1, §2.3, §2.6, §2.7, §4.4, §4.5, §6; power-play commit 0:
// docs/design/m6-power-play.md §1.3; NFL commit 0: docs/design/m6-nfl.md §2; Stacked commit 0:
// docs/design/m6-score-bug-stacked-theme.md §1.3, §2.1, §3.3. Changes go through the lead; every enum here is append-only.
package app.thebox.core.sports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// ------------------------------------------------------------------ §1.1 the league seam

/**
 * Persisted by name ([FollowedGame], [SportsPrefs.league], [SportsPrefs.chlLeague], `SportsFollowDoc.meters`); append-only.
 * (CHL commit 0, docs/design/m6-chl-leagues.md §1.1) [OHL], [WHL] and [QMJHL] are the CHL's three leagues. The stored
 * name `QMJHL` is the enum name; HockeyTech's client code (`lhjmq`) exists only inside `:data`.
 *
 * **A team code is never a league (L2):** a [League] is read only from a persisted enum name or from the source binding,
 * never from a [TeamLine.abbrev] or [FollowedGame.away]/[FollowedGame.home] (the QMJHL's Newfoundland Regiment is "NFL").
 * Every branch over leagues is an exhaustive `when` over [League], [LeagueGroup] or [Sport], never `== NHL` / `else`.
 */
@Serializable
enum class League { NHL, NFL, OHL, WHL, QMJHL }

/** (CHL §1.1) The sport a league plays. Not persisted. */
enum class Sport { HOCKEY, FOOTBALL }

/** (CHL §1.1) The Sports tab chip a league sits under (owner decision 3). Not persisted; derived from [League]. */
enum class LeagueGroup {
    NHL, NFL, CHL;

    val members: List<League>
        get() = when (this) {
            NHL -> listOf(League.NHL)
            NFL -> listOf(League.NFL)
            CHL -> CHL_LEAGUES
        }
}

/** (CHL §1.1; Update interval §3.5) How a league's live polls are timed: the NHL bucket-aligned; every other from completion. */
enum class PollTiming { BUCKET, COMPLETION }

val League.sport: Sport
    get() = when (this) {
        League.NFL -> Sport.FOOTBALL
        League.NHL, League.OHL, League.WHL, League.QMJHL -> Sport.HOCKEY
    }

/** Every hockey league takes the NHL path: Pill, Stacked, power-play tab, goal takeover, Style row (CHL §0.1). */
val League.isHockey: Boolean get() = sport == Sport.HOCKEY

val League.group: LeagueGroup
    get() = when (this) {
        League.NHL -> LeagueGroup.NHL
        League.NFL -> LeagueGroup.NFL
        League.OHL, League.WHL, League.QMJHL -> LeagueGroup.CHL
    }

val League.isChl: Boolean get() = group == LeagueGroup.CHL

val League.timing: PollTiming
    get() = when (this) {
        League.NHL -> PollTiming.BUCKET
        League.NFL, League.OHL, League.WHL, League.QMJHL -> PollTiming.COMPLETION
    }

/** (CHL §1.1) The CHL's leagues in chip and cycle order. */
val CHL_LEAGUES: List<League> = listOf(League.OHL, League.WHL, League.QMJHL)

/** [id] is the league's own game id (NHL "2026020251"). */
data class GameId(val league: League, val id: String)

enum class GamePhase { SCHEDULED, PRE, LIVE, INTERMISSION, FINAL, POSTPONED }

enum class PeriodKind { REG, OT, SO }

/**
 * [name] is the team's name as the source gives it: the landing gives the full name ("Edmonton Oilers"), not the common
 * name (power-play design N4); the spoken strings use it as is. [score] is null before the game; [logo] the validated
 * dark logo URL.
 */
data class TeamLine(
    val abbrev: String,
    val name: String,
    val score: Int?,
    val logo: String?,
    /** (NFL §1.3, S5) The short name ("Chiefs", "Commanders") for the cards, the live region and `Describe`; always null for the NHL. */
    val shortName: String? = null,
    /**
     * (Stacked §3.3) Shots on goal: the feed's `sog` (regulation + overtime; shootout attempts are not counted), kept only
     * in `0..SportsConstants.SOG_MAX`, else null. Null for the NFL (`EspnMapper`) and before puck drop (absent on FUT /
     * PRE). Set by `NhlMapper.team()` (Stacked WP-1). The Pill ignores it.
     */
    val sog: Int? = null,
)

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
    /**
     * (Power play, docs/design/m6-power-play.md §1.2, §1.3) The power play decoded from `situation.situationCode`, or
     * null at even strength, 4-on-4, a delayed penalty or a bad code.
     */
    val powerPlay: PowerPlay? = null,
    /** (Power play §1.2) The latest entry of the landing's `summary.scoring` (landing only; null from the day list). */
    val lastGoal: GoalFact? = null,
    /** (NFL §1.3, §2) The league-specific detail; null for the NHL, which keeps its own fields ([powerPlay], [lastGoal]). */
    val detail: LeagueDetail? = null,
    /**
     * (CHL B3, append-only) True when the source could not decide the power play this poll (a §5.6 hide rule fired, or the
     * penalties / goals node failed). [powerPlay] is then null and means "unknown", not "none". Always false for the NHL
     * and the NFL. `ScoreBugMachine` holds the last shown power play for at most `SportsConstants.PP_UNKNOWN_HOLD_MS` of
     * consecutive unknown snapshots, then closes it silently (CHL §4.1).
     */
    val powerPlayUnknown: Boolean = false,
    /**
     * (CHL fix pass B2, append-only; §3.2) The league lists the start time as to be decided (`time_tbd == "1"`):
     * [startUtcMs] is then local midnight of [dayKey] in the league's reference zone, a placeholder for ordering and the
     * follow's expiry only, and the cards and picker rows show "Time TBD" (`sports_time_tbd`) instead of a time. CHL only;
     * always false for the NHL and the NFL.
     */
    val startTbd: Boolean = false,
)

// ------------------------------------------------------------------ NFL §2 the league detail

/** (NFL §1.3) League-specific snapshot detail. The NHL keeps its own fields (powerPlay, lastGoal). */
sealed interface LeagueDetail {
    /**
     * [possession] is the competitor whose id equals `situation.possession` (ESPN omits it after every kick-off);
     * [redZone] is `isRedZone && play is Down` (the machine adds its own checks, §3.5); [breakKind] is set in
     * INTERMISSION.
     */
    data class Nfl(
        val play: NflPlay,
        val possession: Side?,
        val redZone: Boolean,
        val breakKind: NflBreak?,
        val lastPlay: NflLastPlay?,
    ) : LeagueDetail
}

/** (NFL §1.3) The kind of an NFL INTERMISSION. Append-only. */
enum class NflBreak { END_PERIOD, HALFTIME }

/** (NFL §1.3) The shown play, from `situation`. */
sealed interface NflPlay {
    /** A real down (1..4); [distance] 1..99 or null. */
    data class Down(val down: Int, val distance: Int?, val goalToGo: Boolean, val field: FieldPos?) : NflPlay
    /** The dead ball after a TD (the try) or a FG: the same shape, so the mapper does not call it a try (§3.3). */
    data class Dead(val scorer: Side?, val scoreValue: Int?) : NflPlay
    data class Kickoff(val kicker: Side?, val field: FieldPos?) : NflPlay
    /** No situation, or before the opening kick-off. */
    data object None : NflPlay
}

/** (B1) Whose half the ball is in ([territory] null = midfield, yard 50); [yard] 1..50. Never OWN/OPP. */
data class FieldPos(val territory: Side?, val yard: Int)

/** (NFL §1.3) `situation.lastPlay`: [side] from `team.id`. */
data class NflLastPlay(val id: String, val typeId: String?, val typeText: String?, val side: Side?, val scoreValue: Int?)

/** (Power play §1.2) [TWO_MAN] is always 5-on-3. Append-only. */
enum class PowerPlayKind { ONE_MAN, TWO_MAN }

/**
 * (Power play §1.2) [side] has the man advantage. [secondsRemaining] is the feed's own value (owner ruling 13: no local
 * countdown), kept only in `0..SportsConstants.PP_SECS_MAX`, else null.
 */
data class PowerPlay(val side: Side, val kind: PowerPlayKind, val secondsRemaining: Int?)

/** (Power play §1.2) One `summary.scoring` goal: the scorer's [side], the score after it, and whether it was `strength == "pp"`. */
data class GoalFact(val side: Side, val away: Int, val home: Int, val pp: Boolean)

/** One league day. [prevDay]/[nextDay] come from the body (`prevDate`/`nextDate`); `currentDate` is authoritative (A-N10). */
data class DayBoard(
    val dayKey: String,
    val prevDay: String?,
    val nextDay: String?,
    val games: List<GameSnapshot>,
    /**
     * (CHL §1.2, append-only) The board's league. Every CHL board sets it, so an empty CHL board is never read as an NHL
     * one (`SportsViewModel.boardLeague` infers the league from the games); null = infer (the NHL and NFL sources).
     */
    val league: League? = null,
)

/** One HTTP answer. [Ok.serverDateMs]/[Ok.ageS] come from `Date`/`Age` (Age clamped to [0, AGE_MAX_S]); `receivedMono` is stamped by the source. */
sealed interface Fetch<out T> {
    /**
     * (Update interval §4.1, additive) [maxAgeS]: `Cache-Control` `s-maxage`, else `max-age` (null = absent); [wireBytes]:
     * the body bytes on the wire (compressed), from the source's `EventListener` (null = unknown).
     */
    data class Ok<T>(
        val value: T,
        val receivedMono: Long,
        val serverDateMs: Long?,
        val ageS: Int,
        val maxAgeS: Int? = null,
        val wireBytes: Long? = null,
    ) : Fetch<T>
    /** A 404 with a JSON or empty body (an HTML 404 is `Failed(HTTP_OTHER)`, A-S12). */
    data object NotFound : Fetch<Nothing>
    data class Failed(val kind: FailKind, val retryAfterS: Int?, val receivedMono: Long) : Fetch<Nothing>
}

/**
 * Why a request failed. (Commit 0 addition) [OFFLINE] is never returned by a source: the repository uses it in
 * [DayState.Failed] when there is no default network, so the UI can pick the §5.4 "No internet connection" text.
 *
 * (CHL §1.2, §2.6, S7; appended) [REFUSED]: the source refused this app (a refused key, or the database's "Permission
 * denied"). The follow and the day list retry it only at `SportsConstants.BACKOFF_MAX_MS`. Not persisted.
 */
enum class FailKind { TIMEOUT, IO, HTTP_429, HTTP_5XX, HTTP_OTHER, BAD_BODY, OFFLINE, REFUSED }

interface ScoreSource {
    val league: League
    /** NHL: `/v1/score/now` (OkHttp follows the 307). */
    suspend fun today(): Fetch<DayBoard>
    suspend fun day(dayKey: String): Fetch<DayBoard>
    suspend fun game(id: GameId): Fetch<GameSnapshot>
    /**
     * (Fix pass 2026-11-08, additive) [game] with the follow's [FollowedGame.startUtcMs] as a hint, so a source that
     * finds games by date (ESPN) can read the right dated board after an app restart. The default ignores the hint.
     */
    suspend fun game(id: GameId, startUtcMs: Long?): Fetch<GameSnapshot> = game(id)
    /** The dark-background logo variant, validated against `SportsEndpoints.logoBase` (A-N2). */
    fun logoUrl(team: TeamLine): String

    /**
     * (Update interval §3.2, additive) The endpoint [id]'s last follow answer came from ("nhl-landing", "nfl-event",
     * "nfl-board"): the meter's window resets when a good poll names another one. Null (fix pass 2) = that answer was a
     * one-off check on another endpoint (the S4 board read after a per-event 404) while the follow has not switched:
     * not a sample of either window.
     */
    fun followEndpoint(id: GameId): String? = league.name.lowercase()
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
    /**
     * (Update interval §3.2, additive) This box's poll measurements per league: device-bound, so in this not-backed-up
     * document. Written by the repository at most once a minute, and at once when [PollMeasure.slowed] or the endpoint
     * changes.
     */
    val meters: Map<League, PollMeasure> = emptyMap(),
)

/**
 * (Update interval §3.2, §4.1) One league's poll meter ([PollMeter] reads it): the [endpoint] the samples are from, the last
 * [SportsConstants.METER_WINDOW] [workMs] samples (fetch + decode + map + machine + view; a failed poll at its elapsed
 * time, capped), the good polls' [wireBytes], the largest [maxAgeS] seen, the hysteresis-held [recommendedS] and its
 * [downStreak], and whether a 429 (or 403) has [slowed] the current follow (review S2, S10).
 */
@Serializable
data class PollMeasure(
    val endpoint: String = "",
    val workMs: List<Int> = emptyList(),
    val wireBytes: List<Int> = emptyList(),
    val maxAgeS: Int? = null,
    val recommendedS: Int? = null,
    val downStreak: Int = 0,
    val slowed: Boolean = false,
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
    /** (NFL §2) One line: "Halftime". */
    data object Halftime : CentreText
}

// ------------------------------------------------------------------ NFL §2 what the bug draws

/** (NFL §3.2) The kind of an NFL score takeover. Append-only. */
enum class NflScoreKind { TOUCHDOWN, EXTRA_POINT, FIELD_GOAL, SAFETY, TWO_POINT }

/** One takeover: its kind, its points (6, 1, 3, 2) and the score after it (the takeover's tally). */
data class NflScore(val kind: NflScoreKind, val points: Int, val away: Int, val home: Int)

/** (NFL §4.2) The down column: "1st & 10", "4th & Goal", "3rd" (no distance), "PAT", "KICKOFF". */
sealed interface DownText {
    data class Down(val down: Int, val distance: Int?, val goal: Boolean) : DownText
    data object Pat : DownText
    data object Kickoff : DownText
}

/** (NFL §4.3) The tab's lead: "KC BALL" or "KC KICKOFF". */
enum class TabLead { BALL, KICKOFF }

/** (NFL §3.4, B1) The field relative to the tab's side. */
enum class FieldZone { OWN, OPP, MIDFIELD }

/** (NFL §4.3) The tab's tail. */
sealed interface TabTail {
    /** "OWN 35", "OPP 12", "MIDFIELD". */
    data class Field(val zone: FieldZone, val yard: Int) : TabTail
    data object ExtraPoint : TabTail
    /** (Ruling 26) "RED ZONE" on red. */
    data object RedZone : TabTail
}

/** (NFL §4.3) The possession tab; a null [tail] shows the lead alone, centred. */
data class NflTab(val side: Side, val lead: TabLead, val tail: TabTail?)

/** [scores]: empty, one score, or two for a +7/+8 update (ruling 25), in play order; set with the goal mark. */
data class NflView(val down: DownText?, val tab: NflTab?, val scores: List<NflScore> = emptyList())

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
    /**
     * (Power play §1.4) The shown snapshot's power play while LIVE or INTERMISSION, not SO, neither stale nor waiting;
     * else null (`knownView()` always null).
     */
    val powerPlay: PowerPlay? = null,
    /** (Power play §1.4) The goal flagged by [goalSide] was a power-play goal; set and cleared with the goal mark. */
    val goalPp: Boolean = false,
    /** (Power play §1.4, §7) The previous fresh view had a power play, this fresh view has none, and it flagged no goal. */
    val ppEnded: Boolean = false,
    /** (NFL §2, §4.6) The shown game's league; the `ScoreBug(...)` branch keys on it. */
    val league: League = League.NHL,
    /** (NFL §3.1) Set on `view()` only, and only for an NFL game; its fields are gated by NFL §3.4. */
    val nfl: NflView? = null,
    /**
     * (Stacked §2.1, B1) The shown snapshot's period label on the NHL path of `ScoreBugMachine.view()`, whatever the state:
     * kept in stale and waiting views, where [bottom] is overwritten with [CentreText.NoUpdate] / [CentreText.Delayed].
     * `PeriodLabel(SO, 1)` in the shootout; the final game's last label at FINAL. Null for the NFL, `knownView()`'s own
     * fallback and the place-mode sample. Changes only when the period changes. The Pill ignores it.
     */
    val period: PeriodLabel? = null,
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
    /**
     * (CHL integration, WP-3 review L10) The followed game's last poll failure: the kind of the last `Fetch.Failed` the
     * machine took for this follow; null after an `Ok` or `NotFound`, on a follow change and with nothing followed. Not
     * persisted. The Sports tab's followed card and the quick-bar sheet show [FailKind.REFUSED] only (the reason and the
     * next step, `sports_failed_refused`); every other kind keeps the bug's own dim-and-hide, as before.
     */
    val followFailure: StateFlow<FailKind?>
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

/**
 * (Stacked §1, §1.3) The hockey score bug's look ("Score bug style"): [PILL] (today's bug, the default) or [STACKED] (the
 * teams one above the other, docs/design/m6-score-bug-stacked-theme.md). Persisted by name in `SportsPrefs.nhlStyle`
 * (the stored name is unchanged); append-only. A plain `@Serializable` enum: an unknown name in a stored or restored
 * document decodes to the property's default through the settings store's `coerceInputValues` (S7). Hockey only (CHL
 * P12): every `League.isHockey` game (NHL, OHL, WHL, QMJHL) uses it; an NFL game draws the NFL bug whatever the style (§9).
 */
@Serializable
enum class NhlBugStyle { PILL, STACKED }

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
    /** (NFL §4.4, §4.6) The followed (or shown) league; set by `FullScreenHost` from the follow, else the prefs. */
    val league: League = League.NHL,
    /**
     * (Stacked §2.1, §4.3; CHL §1.6 #3) The hockey bug's style; set by `FullScreenHost.scorePlacementInput` from
     * `SportsPrefs.nhlStyle`, and forced to [NhlBugStyle.PILL] by `ScoreBug(...)` whenever the drawn league is not hockey
     * (the NFL). [ScoreBugPlacement] sizes the box with it (the Stacked footprint); ignored for the NFL.
     */
    val style: NhlBugStyle = NhlBugStyle.PILL,
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
    /** (NFL WP-5 amendment, §7.1) The shown league (`SportsPrefs.league`); null = not reported, and the picker has no League row. */
    val league: League? = null,
    /**
     * (Stacked §1.1, §1.3, S8) `SportsPrefs.nhlStyle`, filled by `PlayerViewModel` (Stacked WP-4). Null = not reported:
     * the sheet has no Style row. The Style row's gating on the followed game's league is WP-4's (§1.1).
     */
    val style: NhlBugStyle? = null,
    /**
     * (CHL fix pass B3, §6.3, §1.6 #33) The remembered CHL sub-league (`SportsPrefs.chlShown`: OHL, WHL or QMJHL), filled
     * by `PlayerViewModel` (CHL WP-5, row 50) and read by the sheet (WP-2, rows 31–34): the League row cycles NHL → NFL →
     * CHL, and its CHL step calls `setScoreLeague(chlLeague)`, a concrete league. Null = not reported: the cycle's CHL
     * step falls back to OHL.
     */
    val chlLeague: League? = null,
    /**
     * (CHL integration, WP-3 review L10; appended) `ScoreRepository.followFailure`: the followed game's last poll failure.
     * The sheet adds the followed league's failure row after the followed game for [FailKind.REFUSED] only.
     */
    val followFailure: FailKind? = null,
)

/** `PlayerUiState.placeScore` while `PlayerLayer.PLACE_SCORE` (§6, U-B1). [sample]: no followed game, draw the sample bug. */
data class ScorePlaceState(val corner: ScoreCorner, val sample: Boolean)
