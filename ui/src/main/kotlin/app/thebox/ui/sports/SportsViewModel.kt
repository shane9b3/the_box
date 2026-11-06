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
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreRepository
import app.thebox.core.sports.SportsConstants
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

/** The three day chips (docs/design/m6-score-bug.md §5.2). */
enum class SportsDay { YESTERDAY, TODAY, TOMORROW }

/** One card: the snapshot, whether it is the followed game, and the mono instant the board was fetched (the clock's anchor). */
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
            SportsUiState(sel, bodyOf(day, followed?.id, sel), (day as? DayState.Board)?.takeIf { it.failing }?.fetchedAtMono, prefs)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SportsUiState())
    }

    /** The screen came on: today, once per open, and the day list is wanted (`SEEN`). */
    fun onEntered() {
        selected.value = SportsDay.TODAY
        yesterdayKey = null
        tomorrowKey = null
        val r = repo ?: return
        r.reportDemand(DemandOwner.SPORTS_TAB, true)
        r.showDay(null)
    }

    fun onLeft() {
        repo?.reportDemand(DemandOwner.SPORTS_TAB, false)
    }

    /** OK on a day chip. */
    fun onDay(day: SportsDay) {
        val r = repo ?: return
        if (selected.value == day) {
            // Pressing the chip you are on is a refresh request only when nothing is showing (a failure or a skeleton).
            if (state.value.body is SportsBody.Failed || state.value.body is SportsBody.Loading) r.retry()
            return
        }
        selected.value = day
        r.showDay(dayKeyFor(day))
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
    private fun dayKeyFor(day: SportsDay): String? = when (day) {
        SportsDay.TODAY -> null
        SportsDay.YESTERDAY -> yesterdayKey ?: easternToday().minusDays(1).toString()
        SportsDay.TOMORROW -> tomorrowKey ?: easternToday().plusDays(1).toString()
    }

    private fun bodyOf(day: DayState, followedId: app.thebox.core.sports.GameId?, sel: SportsDay): SportsBody = when (day) {
        DayState.Loading -> SportsBody.Loading
        is DayState.Failed -> SportsBody.Failed(day.kind)
        is DayState.Board -> {
            val b = day.board
            if (sel == SportsDay.TODAY) learn(b)
            if (!isFor(b, sel)) SportsBody.Loading
            else if (b.games.isEmpty()) SportsBody.Empty(b.dayKey, sel)
            else SportsBody.Games(order(b.games).map { GameCardModel(it, it.id == followedId, day.fetchedAtMono) })
        }
    }

    private fun learn(b: DayBoard) {
        // A board shown for Today that is not already known as another day's: remember its neighbours.
        if (b.dayKey != yesterdayKey && b.dayKey != tomorrowKey) {
            yesterdayKey = b.prevDay ?: runCatching { LocalDate.parse(b.dayKey).minusDays(1).toString() }.getOrNull()
            tomorrowKey = b.nextDay ?: runCatching { LocalDate.parse(b.dayKey).plusDays(1).toString() }.getOrNull()
        }
    }

    /** A stale board of another chip's day must not pass for the one just chosen while its load is in flight. */
    private fun isFor(b: DayBoard, sel: SportsDay): Boolean = when (sel) {
        SportsDay.TODAY -> b.dayKey != yesterdayKey && b.dayKey != tomorrowKey
        SportsDay.YESTERDAY -> b.dayKey == (yesterdayKey ?: easternToday().minusDays(1).toString())
        SportsDay.TOMORROW -> b.dayKey == (tomorrowKey ?: easternToday().plusDays(1).toString())
    }

    companion object {
        val EASTERN: ZoneId = ZoneId.of("America/New_York")

        /** Live (and intermission), then pre-game, then scheduled (and postponed) by start time, then final (§5.2). */
        fun order(games: List<GameSnapshot>): List<GameSnapshot> =
            games.sortedWith(compareBy<GameSnapshot> { rank(it.phase) }.thenBy { it.startUtcMs }.thenBy { it.id.id })

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
         * The seconds a live card shows: the snapshot's clock, counted down from [GameCardModel.anchorMono] while it was
         * running, for at most `EXTRAPOLATE_CAP_S` since the response (the bug's rule, §5.2). Null = no clock to show.
         */
        fun clockSecs(card: GameCardModel, nowMono: Long): Int? {
            val c = card.game.clock ?: return null
            if (!c.running) return c.secondsRemaining.coerceAtLeast(0)
            val elapsed = ((nowMono - card.anchorMono) / 1000L).coerceIn(0L, SportsConstants.EXTRAPOLATE_CAP_S.toLong()).toInt()
            return (c.secondsRemaining - elapsed).coerceAtLeast(0)
        }

        /** Whole minutes since [updatedMono]; 0 reads "Updated just now". */
        fun minutesSince(updatedMono: Long, nowMono: Long): Int = ((nowMono - updatedMono) / 60_000L).coerceAtLeast(0L).toInt()
    }
}
