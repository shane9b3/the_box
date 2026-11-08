package app.thebox.ui.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreRepository
import app.thebox.core.sports.SportsPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The three day chips (docs/design/m6-score-bug.md §5.2). For the NFL (ruling 28) they are Last week · This week · Next
 * week: YESTERDAY / TODAY / TOMORROW stand for the previous, current and next ESPN week.
 */
enum class SportsDay { YESTERDAY, TODAY, TOMORROW }

/** One card: the snapshot, whether it is the followed game, and the mono instant the board was fetched. */
data class GameCardModel(val game: GameSnapshot, val followed: Boolean, val anchorMono: Long)

/** What the games area shows (§5.4). */
sealed interface SportsBody {
    /** Five static skeleton cards. */
    data object Loading : SportsBody
    data class Games(val cards: List<GameCardModel>) : SportsBody
    /** A loaded day without games: "No NHL games {yesterday / today / tomorrow}. Pick another day above." */
    data class Empty(val dayKey: String, val day: SportsDay) : SportsBody
    /** [FailKind.OFFLINE] reads "No internet connection…"; everything else "NHL.com did not answer…". */
    data class Failed(val kind: FailKind) : SportsBody
    /**
     * Show Sports is off (integration pass 2026-11-05): the tab keeps its place in the top bar (no index shift) and says
     * how to turn it back on. The repository makes no request while it is off.
     */
    data object Off : SportsBody
}

/**
 * [updatedMono]: set while a board is shown and its last re-poll failed ("Updated 2 min ago", §5.4); the screen turns it
 * into minutes against its own tick.
 */
data class SportsUiState(
    val day: SportsDay = SportsDay.TODAY,
    val body: SportsBody = SportsBody.Loading,
    val updatedMono: Long? = null,
    val prefs: SportsPrefs = SportsPrefs(),
    /**
     * (NFL §7.2, S6) Whether the Yesterday / Last week and the Tomorrow / Next week chips can be pressed. The NHL's are
     * always on (they fall back to the Eastern date); the NFL's are on only when the board named that neighbour.
     */
    val canPrev: Boolean = true,
    val canNext: Boolean = true,
    /** (NFL §7.2) The shown board's key ("w2026-2-9"): the screen turns it into the week label. Null before a board. */
    val boardKey: String? = null,
)

/**
 * The Sports tab's view model (docs/design/m6-score-bug.md §5; WP-4a). It reads the optional [ScoreRepository]
 * (absent = the feature is not bound: the tab shows the failure text and makes no request), asks for `SEARCH`-style
 * demand (`DemandOwner.SPORTS_TAB`) while the screen is on, and maps the day list to cards: live, then pre-game, then
 * scheduled by start time, then final. Follow toasts come from the repository's events (the player view model shows
 * them in every mode); nothing here toasts.
 *
 * Day chips: Today is `showDay(null)` (`/score/now`, once per open); Yesterday and Tomorrow are the `prevDay` /
 * `nextDay` of the Today board (falling back to date arithmetic in the NHL's Eastern zone before one has loaded).
 *
 * (NFL WP-5, docs/design/m6-nfl.md §7) The league chips write `SportsPrefs.league`. The NFL's chips are weeks, taken only
 * from the board's `prevDay` / `nextDay` (week keys): **no date fallback** (S6), so before the first board, on an empty
 * board or at a season edge the chip is disabled, and an NFL request is never a single date.
 */
@HiltViewModel
class SportsViewModel internal constructor(
    private val repo: ScoreRepository?,
    private val store: SettingsStore?,
    /** `SystemClock.elapsedRealtime()` in production: the time base of `DayState.Board.fetchedAtMono`. */
    private val nowMono: () -> Long,
    private val easternToday: () -> LocalDate,
) : ViewModel() {
    @Inject constructor(scores: Optional<ScoreRepository>, store: SettingsStore) :
        this(scores.orElse(null), store, android.os.SystemClock::elapsedRealtime, { LocalDate.now(EASTERN) })

    private val selected = MutableStateFlow(SportsDay.TODAY)

    /** Day keys learned from the Today board (null until one loaded). */
    private var yesterdayKey: String? = null
    private var tomorrowKey: String? = null

    val state: StateFlow<SportsUiState> = if (repo == null) {
        MutableStateFlow(SportsUiState(body = SportsBody.Failed(FailKind.IO)))
    } else {
        combine(repo.day, repo.followed, repo.prefs, selected) { day, followed, prefs, sel ->
            if (!prefs.enabled) SportsUiState(sel, SportsBody.Off, null, prefs)
            else {
                val body = bodyOf(day, followed?.id, sel, prefs.league)
                val nfl = prefs.league == League.NFL
                SportsUiState(
                    sel, body, (day as? DayState.Board)?.takeIf { it.failing }?.fetchedAtMono, prefs,
                    canPrev = !nfl || yesterdayKey != null, canNext = !nfl || tomorrowKey != null,
                    boardKey = (day as? DayState.Board)?.board?.dayKey?.takeIf { nfl },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SportsUiState())
    }

    /** The screen came on: today, once per open, and the day list is wanted (`SEEN`). */
    fun onEntered() {
        selected.value = SportsDay.TODAY
        // The NHL relearns its neighbours from the next Today board. The NFL keeps the week keys it learned (S6: there is no
        // date to fall back to, and an unchanged board does not re-emit); a Today board overwrites them.
        if (state.value.prefs.league != League.NFL) {
            yesterdayKey = null
            tomorrowKey = null
        }
        val r = repo ?: return
        r.reportDemand(DemandOwner.SPORTS_TAB, true)
        r.showDay(null)
    }

    fun onLeft() {
        repo?.reportDemand(DemandOwner.SPORTS_TAB, false)
    }

    /**
     * OK on a league chip (NFL §7.3, ruling 24): writes the shown league, which the repository answers by loading that
     * league's today. The chip row restarts at This week / Today with nothing learned.
     */
    fun onLeague(league: League) {
        if (state.value.prefs.league == league) return
        selected.value = SportsDay.TODAY
        yesterdayKey = null
        tomorrowKey = null
        store?.update(SportsDomain) { it.copy(league = league) }
    }

    /** OK on a day chip. */
    fun onDay(day: SportsDay) {
        val r = repo ?: return
        // S6: an NFL neighbour that the board did not name is not requested (the chip is disabled; this is the guard).
        if (state.value.prefs.league == League.NFL && day != SportsDay.TODAY && dayKeyFor(day, League.NFL) == null) return
        if (selected.value == day) {
            // Pressing the chip you are on is a refresh request only when nothing is showing (a failure or a skeleton).
            if (state.value.body is SportsBody.Failed || state.value.body is SportsBody.Loading) r.retry()
            return
        }
        selected.value = day
        r.showDay(dayKeyFor(day, state.value.prefs.league))
    }

    /** OK on a card: Follow, or Stop following when it is the followed game. Following another game replaces the current one. */
    fun onCard(card: GameCardModel) {
        repo?.follow(if (card.followed) null else card.game)
    }

    /** OK on Retry (or on a failure's text). */
    fun onRetry() {
        repo?.retry()
    }

    fun setCorner(corner: ScoreCorner) {
        store?.update(SportsDomain) { it.copy(corner = corner) }
    }

    fun setDelay(seconds: Int) {
        store?.update(SportsDomain) { it.copy(delaySec = seconds) }
    }

    /** The key to ask the repository for; null = today (`/score/now`). */
    private fun dayKeyFor(day: SportsDay, league: League): String? = when (day) {
        SportsDay.TODAY -> null
        SportsDay.YESTERDAY -> yesterdayKey ?: dateFallback(-1, league)
        SportsDay.TOMORROW -> tomorrowKey ?: dateFallback(1, league)
    }

    /** The NHL's Eastern-date neighbour; the NFL has none (S6), so its chip is disabled instead. */
    private fun dateFallback(days: Long, league: League): String? =
        if (league == League.NFL) null else easternToday().plusDays(days).toString()

    private fun bodyOf(day: DayState, followedId: app.thebox.core.sports.GameId?, sel: SportsDay, league: League): SportsBody = when (day) {
        DayState.Loading -> SportsBody.Loading
        is DayState.Failed -> SportsBody.Failed(day.kind)
        is DayState.Board -> {
            val b = day.board
            // A board of the other league (the repository is still switching) never passes for the shown one.
            if (boardLeague(b) != league) SportsBody.Loading else {
                // U-N5: only a board the repository says is today's teaches the neighbours, whatever the order of events.
                if (sel == SportsDay.TODAY && day.isToday) learn(b, league)
                if (!isFor(day, sel, league)) SportsBody.Loading
                else if (b.games.isEmpty()) SportsBody.Empty(b.dayKey, sel)
                else SportsBody.Games(order(b.games, league).map { GameCardModel(it, it.id == followedId, day.fetchedAtMono) })
            }
        }
    }

    private fun boardLeague(b: DayBoard): League =
        b.games.firstOrNull()?.id?.league ?: if (SportsText.isWeekKey(b.dayKey)) League.NFL else League.NHL

    private fun learn(b: DayBoard, league: League) {
        if (league == League.NFL) {
            // S6: the week keys the board named, or none (an edge, the off-season or an empty board): never a date.
            // (Fix pass U2) Always from a This-week board: once ESPN's week rolls over, the new This week is the key
            // learned as Next week, and the guard below would skip it forever (the view model lives for days).
            yesterdayKey = b.prevDay.takeIf { b.games.isNotEmpty() }
            tomorrowKey = b.nextDay.takeIf { b.games.isNotEmpty() }
            return
        }
        // A board shown for Today that is not already known as another day's: remember its neighbours.
        if (b.dayKey != yesterdayKey && b.dayKey != tomorrowKey) {
            yesterdayKey = b.prevDay ?: runCatching { LocalDate.parse(b.dayKey).minusDays(1).toString() }.getOrNull()
            tomorrowKey = b.nextDay ?: runCatching { LocalDate.parse(b.dayKey).plusDays(1).toString() }.getOrNull()
        }
    }

    /** A stale board of another chip's day must not pass for the one just chosen while its load is in flight. */
    private fun isFor(day: DayState.Board, sel: SportsDay, league: League): Boolean {
        val b = day.board
        return when (sel) {
            // (Fix pass U2) The NFL trusts the repository's isToday: a week key is never a calendar date, and after a
            // week rollover the new This week equals the stale Next week key.
            SportsDay.TODAY -> day.isToday && (league == League.NFL || (b.dayKey != yesterdayKey && b.dayKey != tomorrowKey))
            SportsDay.YESTERDAY -> dayKeyFor(sel, league)?.let { b.dayKey == it } ?: false
            SportsDay.TOMORROW -> dayKeyFor(sel, league)?.let { b.dayKey == it } ?: false
        }
    }

    companion object {
        val EASTERN: ZoneId = ZoneId.of("America/New_York")

        /** Live (and intermission), then pre-game, then scheduled (and postponed) by start time, then final (§5.2). */
        fun order(games: List<GameSnapshot>, league: League = League.NHL): List<GameSnapshot> =
            games.sortedWith(compareBy<GameSnapshot> { if (league == League.NFL) nflRank(it.phase) else rank(it.phase) }.thenBy { it.startUtcMs }.thenBy { it.id.id })

        /** (NFL §7.2) Live, then upcoming by start, then final, then postponed (the picker's `groupOf`). */
        private fun nflRank(p: GamePhase): Int = when (p) {
            GamePhase.LIVE, GamePhase.INTERMISSION -> 0
            GamePhase.PRE, GamePhase.SCHEDULED -> 1
            GamePhase.FINAL -> 2
            GamePhase.POSTPONED -> 3
        }

        private fun rank(p: GamePhase): Int = when (p) {
            GamePhase.LIVE, GamePhase.INTERMISSION -> 0
            GamePhase.PRE -> 1
            GamePhase.SCHEDULED, GamePhase.POSTPONED -> 2
            GamePhase.FINAL -> 3
        }

        /** The card to focus first: the followed game, else the first live one, else null (the Today chip). */
        fun initialFocusIndex(cards: List<GameCardModel>): Int? {
            val followed = cards.indexOfFirst { it.followed }
            if (followed >= 0) return followed
            val live = cards.indexOfFirst { it.game.phase == GamePhase.LIVE || it.game.phase == GamePhase.INTERMISSION }
            return live.takeIf { it >= 0 }
        }

        /**
         * The seconds a live card shows: the snapshot's own clock, floored at 0 (owner ruling 6, the bug's rule: no local
         * countdown; it changes when the day list re-polls). Null = no clock to show.
         */
        fun clockSecs(card: GameCardModel): Int? = card.game.clock?.secondsRemaining?.coerceAtLeast(0)

        /** Whole minutes since [updatedMono]; 0 reads "Updated just now". */
        fun minutesSince(updatedMono: Long, nowMono: Long): Int = ((nowMono - updatedMono) / 60_000L).coerceAtLeast(0L).toInt()
    }
}
