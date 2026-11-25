package app.thebox.ui.sports

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalRules
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.legacyParentalAccess
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.ScoreRepository
import app.thebox.core.sports.Sport
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsDays
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.group
import app.thebox.core.sports.isChl
import app.thebox.core.sports.sport
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ChannelHorizon
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.GameChannelMatcher
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailRepository
import app.thebox.player.api.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The day chips' meanings for the texts that name a day ("No NHL games yesterday"): [of] maps a strip offset. For the NFL
 * (ruling 28) YESTERDAY / TODAY / TOMORROW stand for the previous, current and next ESPN week.
 */
enum class SportsDay {
    YESTERDAY, TODAY, TOMORROW;

    companion object {
        fun of(offset: Int): SportsDay = if (offset < 0) YESTERDAY else if (offset == 0) TODAY else TOMORROW
    }
}

/**
 * One card: the snapshot, whether it is the followed game, and the mono instant the board was fetched. [refused] (CHL
 * integration, WP-3 review L10): the followed game's feed refused the app (`ScoreRepository.followFailure` is
 * [FailKind.REFUSED]); the card then shows the reason and the next step. Always false for other cards.
 */
data class GameCardModel(val game: GameSnapshot, val followed: Boolean, val anchorMono: Long, val refused: Boolean = false)

/** What the games area shows (§5.4). */
sealed interface SportsBody {
    /** Skeleton cards. */
    data object Loading : SportsBody
    data class Games(val cards: List<GameCardModel>) : SportsBody
    /** A loaded day without games. [offset] is the strip's offset (0 = today); [day] is its yesterday / today / tomorrow reading. */
    data class Empty(val dayKey: String, val offset: Int) : SportsBody {
        val day: SportsDay get() = SportsDay.of(offset)
    }
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
    /** The selected chip of the day strip: 0 = today; −7..+7 for the NHL and CHL, −1 / 0 / +1 (weeks) for the NFL. */
    val offset: Int = 0,
    val body: SportsBody = SportsBody.Loading,
    val updatedMono: Long? = null,
    val prefs: SportsPrefs = SportsPrefs(),
    /** (Sports Hub §3.1) The strip's chips. */
    val days: List<DayChip> = defaultDays(),
    /** (NFL §7.2) The shown board's key ("w2026-2-9"): the screen turns it into the week label. Null before a board. */
    val boardKey: String? = null,
) {
    val day: SportsDay get() = SportsDay.of(offset)
    /** (NFL §7.2, S6) Whether the previous / next week chip can be pressed (the board named that neighbour). */
    val canPrev: Boolean get() = days.firstOrNull { it.offset == -1 }?.enabled ?: false
    val canNext: Boolean get() = days.firstOrNull { it.offset == 1 }?.enabled ?: false

    companion object {
        fun defaultDays(): List<DayChip> = (-SportsConstants.HUB_DAY_RANGE..SportsConstants.HUB_DAY_RANGE).map { DayChip(it, null) }
    }
}

/** A sheet over the right column (§2.5, §6.4). Restored by game id and action, never by index. */
sealed interface HubSheet {
    val gameId: GameId
    data class Goals(override val gameId: GameId) : HubSheet
    /** [archiveOnly]: the final NFL / CHL "Watch from start" rows. */
    data class Channels(override val gameId: GameId, val archiveOnly: Boolean) : HubSheet
    /** [box] is null while loading. */
    data class BoxScoreSheet(override val gameId: GameId, val box: Fetch<BoxScore>? = null) : HubSheet
    data class Matchup(override val gameId: GameId) : HubSheet
}

enum class HubToastKind { VIDEO_UNAVAILABLE, VIDEO_OFFLINE, VIDEO_REFUSED, VIDEO_GEO, VIDEO_REMOVED, VIDEO_NOT_PLAYABLE, GOAL_NO_VIDEO, STARTS_AT, VIDEO_LATER }

/** A short message with a reason and a next step (§6.8, §5.5). [seq] distinguishes two equal toasts in a row. */
@Immutable
data class HubToast(val kind: HubToastKind, val startUtcMs: Long? = null, val seq: Long = 0L)

/**
 * The Sports Hub page's view model (docs/design/m6-sports-hub.md; WP-4). It reads the optional [ScoreRepository] (absent =
 * the feature is not bound: the page shows the failure text and makes no request), asks for `SPORTS_TAB` demand while the
 * screen is on, and maps the day list to cards in a **frozen order** (U-S3): live, then pre-game, then scheduled by start,
 * then final, computed on board load, day change and page entry; re-polls update cards in place, a new game is appended and a
 * vanished one removed.
 *
 * The detail panel follows card focus ([onCardFocused]): its pill, headline and matchup art switch at once from the board; the
 * detail, the channel match and the clip lookups start after `HUB_FETCH_DWELL_MS` of rest, or at once when OK enters the panel
 * ([enterPanel]). The panel's three slots never reorder and are frozen while focus is in the panel (§2.3).
 *
 * Day strip: NHL and CHL 15 days (today −7 … +7, `showDay(key)` on OK); the NFL's three weeks come only from the board's
 * `prevDay` / `nextDay` (S6: no date fallback). Picking a channel tunes it and does not follow the game (owner ruling).
 */
@HiltViewModel
class SportsViewModel internal constructor(
    private val repo: ScoreRepository?,
    private val store: SettingsStore?,
    /** `SystemClock.elapsedRealtime()` in production: the time base of `DayState.Board.fetchedAtMono`. */
    private val nowMono: () -> Long,
    /** (CHL §2.3) The wall clock for [SportsDays.today], the CHL's reference-zone date with its 05:00 roll-over. */
    private val nowUtcMs: () -> Long = System::currentTimeMillis,
    /** The hub's seams; each is optional and the page works without it. */
    private val detail: GameDetailRepository? = null,
    private val matcher: GameChannelMatcher? = null,
    private val resolver: ClipResolver? = null,
    /** Builds the clip session on the view model's scope (null = no clips). */
    clipSessionFactory: (CoroutineScope) -> ClipSession? = { null },
    private val access: () -> ParentalAccess = { ParentalAccess.OPEN },
    /** The NHL's Eastern date, for its date fallback (unchanged; last, so a trailing lambda stays this one); the NFL has none (S6). */
    private val easternToday: () -> LocalDate,
) : ViewModel() {
    @Inject constructor(
        scores: Optional<ScoreRepository>,
        store: SettingsStore,
        detail: Optional<GameDetailRepository>,
        matcher: Optional<GameChannelMatcher>,
        resolver: Optional<ClipResolver>,
        controller: PlaybackController,
        archive: ArchivePlayback,
        parental: ParentalSession,
        settings: GuideSettingsSource,
        guideTrim: app.thebox.ui.guide.grid.GuideTrim,
    ) : this(
        scores.orElse(null), store, android.os.SystemClock::elapsedRealtime, System::currentTimeMillis,
        detail.orElse(null), matcher.orElse(null), resolver.orElse(null),
        { scope -> ClipSession(controller, archive, resolver.orElse(null), scope) },
        {
            val requested = legacyParentalAccess(settings.settings.value.debugUnlocked)
            if (requested == ParentalAccess.OPEN) requested else ParentalRules.accessFor(parental.state.value, null)
        },
        { LocalDate.now(EASTERN) },
    ) {
        // (Review L1) `MainActivity.onTrimMemory` reaches the guide's trim singleton only; the page's caches register there.
        trimHandle = guideTrim.register { level, uiHidden -> onTrimMemory(level, uiHidden) }
    }

    /** The registration with `GuideTrim` (the injected constructor only); closed in [onCleared]. */
    private var trimHandle: AutoCloseable? = null

    /** The clip session (null without a player); `SportsScreen` composes `ClipPlayerLayer` over it. */
    val clipSession: ClipSession? = clipSessionFactory(viewModelScope)

    /** For `AppRoot.sportsClip`: the session's own state, or "no clip". */
    val clipUi: StateFlow<ClipUi> = clipSession?.ui ?: MutableStateFlow(ClipUi()).asStateFlow()

    private val _effects = MutableSharedFlow<SportsEffect>(extraBufferCapacity = 8)
    /** Tune / archive / resume requests for `GuideViewModel.onSportsEffect` (collected by `MainActivity`). */
    val effects: SharedFlow<SportsEffect> = _effects.asSharedFlow()

    private val _toast = MutableStateFlow<HubToast?>(null)
    /** The page's toast (§6.8, §5.5); the screen shows it for a few seconds and calls [toastShown]. */
    val toast: StateFlow<HubToast?> = _toast.asStateFlow()
    private var toastSeq = 0L

    private val selected = MutableStateFlow(0)

    /** The card the panel shows (the last one focused on this board); null = the board's initial-focus game. */
    private val shownId = MutableStateFlow<String?>(null)

    /** The NFL's neighbouring week keys, learned from the This-week board (S6). */
    private var yesterdayKey: String? = null
    private var tomorrowKey: String? = null
    /** The key of this week's NFL board, or the date of today's board for the others (learned, reset on page entry). */
    private var todayKey: String? = null
    /**
     * (Owner finding 2026-11-25) The NHL's `/score/now` keeps naming yesterday for hours after midnight Eastern. Once that
     * day has nothing left to play, Today is the Eastern calendar date and is asked for by its date; true while that holds.
     */
    private var todayDated = false
    /** Day key → number of games, from the boards' `gameCounts` (§3.3), per page entry. */
    private val gameCounts = HashMap<String, Int>()
    /** Past-day boards, kept per page entry (≤ 15) so stepping back across final days does not refetch (§3.2). */
    private val pastBoards = object : LinkedHashMap<String, DayState.Board>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DayState.Board>?): Boolean = size > 15
    }
    private var orderKey: String? = null
    private var orderIds: List<String> = emptyList()
    private var boardId: String? = null

    val state: StateFlow<SportsUiState> = if (repo == null) {
        MutableStateFlow(SportsUiState(body = SportsBody.Failed(FailKind.IO)))
    } else {
        combine(repo.day, repo.followed, repo.prefs, selected, repo.followFailure) { day, followed, prefs, sel, failure ->
            if (!prefs.enabled) SportsUiState(sel, SportsBody.Off, null, prefs)
            else {
                val body = bodyOf(day, followed?.id, sel, prefs.league, failure == FailKind.REFUSED)
                val nfl = prefs.league.group == LeagueGroup.NFL
                SportsUiState(
                    sel, body, (day as? DayState.Board)?.takeIf { it.failing }?.fetchedAtMono, prefs,
                    days = daysFor(prefs.league),
                    boardKey = (day as? DayState.Board)?.board?.dayKey?.takeIf { nfl },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SportsUiState())
    }

    // ------------------------------------------------------------------ the panel

    private val panelFocus = MutableStateFlow(false)
    /** The slot actions held while focus is in the panel (§2.3); null = not frozen. */
    private val frozen = MutableStateFlow<List<SlotAction?>?>(null)
    private val matches = MutableStateFlow<Map<String, MatchState>>(emptyMap())
    private val clipInfos = MutableStateFlow<Map<Long, ClipInfo>>(emptyMap())
    /** True while the video service has refused the app (§6.8): the video slots and chips are off for `HUB_VIDEO_KEY_REREAD_MS`. */
    private val videoOff = MutableStateFlow(false)
    /** True while the video service has refused the app (§6.8): video slots and the card chip hide. */
    val videoRefused: StateFlow<Boolean> get() = videoOff.asStateFlow()
    private val detailFlow: StateFlow<DetailState> = detail?.detail ?: MutableStateFlow(DetailState.None)

    private val _panel = MutableStateFlow(HubPanelModel(game = null))
    /** What the detail panel shows now; always three slots. */
    val panel: StateFlow<HubPanelModel> = _panel.asStateFlow()

    private val _sheet = MutableStateFlow<HubSheet?>(null)
    val sheet: StateFlow<HubSheet?> = _sheet.asStateFlow()

    /** The lookups the page made, by video id (the sheets' durations and thumbnails). */
    val clipInfo: StateFlow<Map<Long, ClipInfo>> = clipInfos.asStateFlow()

    /** True while focus is in the panel. */
    val panelFocused: StateFlow<Boolean> = panelFocus.asStateFlow()

    private var fetchJob: Job? = null
    private var matchJob: Job? = null
    private var fetchedId: String? = null
    private var fetchedPhase: GamePhase? = null
    private val infoRequested = HashSet<Long>()
    private var videoOffJob: Job? = null
    private var entered = false
    /** The game whose panel focus is in (§2.3); null while focus is outside the panel. */
    private var enteredId: GameId? = null

    init {
        viewModelScope.launch {
            merge(
                state.map { }, shownId.map { }, detailFlow.map { }, matches.map { }, clipInfos.map { }, panelFocus.map { },
                frozen.map { }, videoOff.map { },
            ).collect {
                refreshPanel()
                ensureClipInfos()
            }
        }
        clipSession?.let { s ->
            viewModelScope.launch {
                s.state.collect { st ->
                    val playing = st !is ClipSession.State.Idle
                    detail?.pause(playing)
                    if (entered) repo?.reportDemand(DemandOwner.SPORTS_TAB, !playing)
                }
            }
            viewModelScope.launch { s.effects.collect { _effects.emit(it) } }
            viewModelScope.launch {
                s.failures.collect { f ->
                    if (f == ClipSession.Failure.REFUSED) markVideoOff()
                    toast(
                        when (f) {
                            ClipSession.Failure.UNAVAILABLE -> HubToastKind.VIDEO_UNAVAILABLE
                            ClipSession.Failure.OFFLINE -> HubToastKind.VIDEO_OFFLINE
                            ClipSession.Failure.REFUSED -> HubToastKind.VIDEO_REFUSED
                            ClipSession.Failure.GEO -> HubToastKind.VIDEO_GEO
                            ClipSession.Failure.NOT_FOUND -> HubToastKind.VIDEO_REMOVED
                            ClipSession.Failure.NOT_PLAYABLE -> HubToastKind.VIDEO_NOT_PLAYABLE
                            ClipSession.Failure.NO_CLIP -> HubToastKind.GOAL_NO_VIDEO
                            ClipSession.Failure.LATER -> HubToastKind.VIDEO_LATER
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** The screen came on: today, once per open, and the day list is wanted (`SEEN`). */
    fun onEntered() {
        entered = true
        selected.value = 0
        // The NHL relearns today from the next board. The NFL keeps the week keys it learned (S6: there is no date to fall back
        // to, and an unchanged board does not re-emit); a This-week board overwrites them.
        if (state.value.prefs.league.group != LeagueGroup.NFL) {
            yesterdayKey = null
            tomorrowKey = null
            todayKey = null
        }
        todayDated = false
        gameCounts.clear()
        pastBoards.clear()
        orderKey = null
        orderIds = emptyList()
        boardId = null
        shownId.value = null
        enteredId = null
        panelFocus.value = false
        frozen.value = null
        matches.value = emptyMap()
        _sheet.value = null
        fetchedId = null
        fetchedPhase = null
        infoRequested.clear()
        matcher?.clear()
        val r = repo ?: return
        r.reportDemand(DemandOwner.SPORTS_TAB, clipSession?.state?.value is ClipSession.State.Idle || clipSession == null)
        r.showDay(null)
    }

    fun onLeft() {
        entered = false
        repo?.reportDemand(DemandOwner.SPORTS_TAB, false)
        fetchJob?.cancel()
        matchJob?.cancel()
        detail?.show(null)
        fetchedId = null
        enteredId = null
        panelFocus.value = false
        frozen.value = null
        _sheet.value = null
        if (clipSession != null && clipSession.state.value !is ClipSession.State.Idle) clipSession.close()
    }

    override fun onCleared() {
        trimHandle?.close()
        trimHandle = null
        // (R-N1) Ends the clip session without a resume: an effect emitted here would not be delivered.
        clipSession?.close()
        detail?.show(null)
        detail?.trim()
        matcher?.clear()
        super.onCleared()
    }

    /**
     * `onTrimMemory` ≥ RUNNING_LOW (§8.3): the detail, clip-info and match caches go.
     *
     * (Verification review L-c) `TRIM_MEMORY_UI_HIDDEN` alone is not memory pressure (it comes every time the app goes to
     * the background): nothing is dropped, so the detail repository keeps its end-time facts. Under real pressure while
     * the UI is hidden nothing network-related starts: the clip-info cache (at most 40 small entries) and the record of
     * what was asked are kept, so no lookup is made again in the background; they go at the next trim on screen.
     */
    @Suppress("DEPRECATION") // the TRIM_MEMORY_* levels are still delivered on the API levels we ship to (25–35)
    fun onTrimMemory(level: Int = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, uiHidden: Boolean = false) {
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        detail?.trim()
        // The shown game keeps its match (its Channels slot would otherwise read "Finding…" until focus moves).
        matches.value = matches.value.filterKeys { it == fetchedId }
        matcher?.clear()
        if (uiHidden) return
        clipInfos.value = emptyMap()
        infoRequested.clear()
        ensureClipInfos()
    }

    // ------------------------------------------------------------------ league and day chips

    /**
     * OK on a league chip (NFL §7.3, ruling 24; CHL §6.3): writes the shown league, which the repository answers by loading
     * that league's today. The chip row restarts at Today with nothing learned. Choosing a CHL league also writes the
     * remembered sub-league (`chlLeague`); NHL and NFL leave it alone.
     */
    fun onLeague(league: League) {
        if (state.value.prefs.league == league) return
        selected.value = 0
        yesterdayKey = null
        tomorrowKey = null
        todayKey = null
        todayDated = false
        gameCounts.clear()
        pastBoards.clear()
        store?.update(SportsDomain) { it.copy(league = league, chlLeague = if (league.isChl) league else it.chlLeague) }
    }

    /**
     * OK on a group chip: NHL and NFL as the league; CHL as the remembered sub-league (`chlShown`, OHL until one was chosen).
     * Pressing the chip of the group already shown changes nothing.
     */
    fun onGroup(group: LeagueGroup) {
        val prefs = state.value.prefs
        if (prefs.league.group == group) return
        onLeague(
            when (group) {
                LeagueGroup.NHL -> League.NHL
                LeagueGroup.NFL -> League.NFL
                LeagueGroup.CHL -> prefs.chlShown
            },
        )
    }

    /** OK on a day chip ([offset] −7..+7; the NFL's −1 / 0 / +1 are weeks): one request, none on focus (§3.2). */
    fun onDay(offset: Int) {
        val r = repo ?: return
        val league = state.value.prefs.league
        // S6: an NFL neighbour that the board did not name is not requested (the chip is disabled; this is the guard).
        if (league.group == LeagueGroup.NFL && offset != 0 && dayKeyFor(offset, league) == null) return
        if (offset !in allowedOffsets(league)) return
        if (selected.value == offset) {
            // Pressing the chip you are on is a refresh request only when nothing is showing (a failure or a skeleton).
            if (state.value.body is SportsBody.Failed || state.value.body is SportsBody.Loading) r.retry()
            return
        }
        selected.value = offset
        val key = dayKeyFor(offset, league)
        // A past day already read this visit is shown from memory: no request.
        if (key != null && pastBoards[key] != null) return
        r.showDay(key)
    }

    private fun allowedOffsets(league: League): IntRange =
        if (league.group == LeagueGroup.NFL) -1..1 else -SportsConstants.HUB_DAY_RANGE..SportsConstants.HUB_DAY_RANGE

    /** The key to ask the repository for; null = today (`/score/now`). */
    private fun dayKeyFor(offset: Int, league: League): String? = when {
        offset == 0 -> if (todayDated && league.group == LeagueGroup.NHL) todayKey else null
        league.group == LeagueGroup.NFL -> if (offset < 0) yesterdayKey else tomorrowKey // S6: never a date
        else -> anchorDate(league).plusDays(offset.toLong()).toString()
    }

    /** The date today's chip stands for: today's board when one has loaded, else the league's own date (§3.2). */
    private fun anchorDate(league: League): LocalDate =
        todayKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: dateFallback(league)

    /** The league's date before a today board has loaded: the NHL's Eastern date; the CHL's own "today" (§2.3, [SportsDays.today]). */
    private fun dateFallback(league: League): LocalDate = when (league.group) {
        LeagueGroup.NHL -> easternToday()
        LeagueGroup.NFL -> easternToday() // never used: the NFL has no date chips (S6)
        LeagueGroup.CHL -> SportsDays.today(league, nowUtcMs())
    }

    private fun daysFor(league: League): List<DayChip> =
        if (league.group == LeagueGroup.NFL) {
            listOf(
                DayChip(-1, yesterdayKey, enabled = yesterdayKey != null),
                DayChip(0, todayKey),
                DayChip(1, tomorrowKey, enabled = tomorrowKey != null),
            )
        } else {
            val anchor = anchorDate(league)
            (-SportsConstants.HUB_DAY_RANGE..SportsConstants.HUB_DAY_RANGE).map { o ->
                val key = anchor.plusDays(o.toLong()).toString()
                DayChip(o, key, noGames = gameCounts[key] == 0)
            }
        }

    // ------------------------------------------------------------------ the day list

    private fun bodyOf(day: DayState, followedId: GameId?, sel: Int, league: League, refused: Boolean = false): SportsBody {
        // A past day read earlier this visit comes from memory (§3.2).
        val cached = if (sel != 0) dayKeyFor(sel, league)?.let { pastBoards[it] } else null
        val shown = cached ?: day
        return when (shown) {
            DayState.Loading -> SportsBody.Loading
            is DayState.Failed -> SportsBody.Failed(shown.kind)
            is DayState.Board -> {
                val b = shown.board
                // A board of the other league (the repository is still switching) never passes for the shown one.
                if (boardLeague(b) != league) SportsBody.Loading else {
                    // U-N5: only a board the repository says is today's teaches today, whatever the order of events.
                    if (sel == 0 && shown.isToday) learn(b, league)
                    b.gameCounts.forEach { (k, n) -> gameCounts[k] = n }
                    if (cached == null && !isFor(shown, sel, league)) SportsBody.Loading
                    else {
                        if (sel != 0 && cached == null && isPast(b, league)) pastBoards[b.dayKey] = shown
                        if (b.games.isEmpty()) SportsBody.Empty(b.dayKey, sel)
                        else {
                            val id = "${league.name}|$sel|${b.dayKey}"
                            if (boardId != id) { boardId = id; shownId.value = null }
                            SportsBody.Games(
                                frozenOrder(id, b.games, league).map { GameCardModel(it, it.id == followedId, shown.fetchedAtMono, refused && it.id == followedId) },
                            )
                        }
                    }
                }
            }
        }
    }

    /** A board worth keeping: a day before today whose games are all over. */
    private fun isPast(b: DayBoard, league: League): Boolean =
        league.group != LeagueGroup.NFL && b.games.isNotEmpty() && b.games.all { it.phase == GamePhase.FINAL || it.phase == GamePhase.POSTPONED } &&
            runCatching { LocalDate.parse(b.dayKey).isBefore(anchorDate(league)) }.getOrDefault(false)

    /** (U-S3) The order is computed once per board; re-polls keep it, a new game goes last and a vanished one drops out. */
    private fun frozenOrder(key: String, games: List<GameSnapshot>, league: League): List<GameSnapshot> {
        if (orderKey != key || orderIds.isEmpty()) {
            val sorted = order(games, league)
            orderKey = key
            orderIds = sorted.map { it.id.id }
            return sorted
        }
        val byId = games.associateBy { it.id.id }
        val kept = orderIds.mapNotNull { byId[it] }
        val fresh = order(games.filter { it.id.id !in orderIds }, league)
        val result = kept + fresh
        orderIds = result.map { it.id.id }
        return result
    }

    /** (CHL §1.2) A CHL board names its league, so an empty one is never read as an NHL board; older boards are inferred. */
    private fun boardLeague(b: DayBoard): League =
        b.league ?: b.games.firstOrNull()?.id?.league ?: if (SportsText.isWeekKey(b.dayKey)) League.NFL else League.NHL

    private fun learn(b: DayBoard, league: League) {
        if (league.group == LeagueGroup.NFL) {
            // S6: the week keys the board named, or none (an edge, the off-season or an empty board): never a date.
            // (Fix pass U2) Always from a This-week board: once ESPN's week rolls over, the new This week is the key
            // learned as Next week, and a "learn once" guard would skip it forever (the view model lives for days).
            yesterdayKey = b.prevDay.takeIf { b.games.isNotEmpty() }
            tomorrowKey = b.nextDay.takeIf { b.games.isNotEmpty() }
            todayKey = b.dayKey
            return
        }
        if (league.group == LeagueGroup.NHL) {
            // (Owner finding 2026-11-25) `/score/now` names the NHL's own day, which runs hours past midnight Eastern. While
            // that day still has a game to play (a late west-coast game) it is Today. Once it is all over, Today is the
            // Eastern date: that day is asked for by its date, instead of waiting behind a skeleton until the feed moves on.
            val wall = dateFallback(league).toString()
            val unfinished = b.games.any { it.phase != GamePhase.FINAL && it.phase != GamePhase.POSTPONED }
            // The feed lags the calendar, it never leads it: a later day here is another chip's board, not today's.
            if (b.dayKey > wall) return
            if (b.dayKey == wall || unfinished) {
                todayKey = b.dayKey
                return
            }
            todayKey = wall
            if (!todayDated) {
                todayDated = true
                if (selected.value == 0) repo?.showDay(wall)
            }
            return
        }
        if (b.dayKey != dayKeyForGuard(-1, league) && b.dayKey != dayKeyForGuard(1, league)) todayKey = b.dayKey
    }

    private fun dayKeyForGuard(offset: Int, league: League): String = anchorDate(league).plusDays(offset.toLong()).toString()

    /** A stale board of another chip's day must not pass for the one just chosen while its load is in flight. */
    private fun isFor(day: DayState.Board, sel: Int, league: League): Boolean {
        val b = day.board
        return when {
            // (Fix pass U2) The NFL trusts the repository's isToday: a week key is never a calendar date, and after a
            // week rollover the new This week equals the stale Next week key.
            // The NHL: the board `learn` settled on (the feed's own day, or the Eastern date asked for by its date).
            sel == 0 && league.group == LeagueGroup.NHL -> if (todayDated) b.dayKey == todayKey else day.isToday && b.dayKey == todayKey
            sel == 0 -> day.isToday && (league.group == LeagueGroup.NFL || (b.dayKey != dayKeyForGuard(-1, league) && b.dayKey != dayKeyForGuard(1, league)))
            else -> dayKeyFor(sel, league)?.let { b.dayKey == it } ?: false
        }
    }

    // ------------------------------------------------------------------ focus, panel and slots

    /** Compose focus moved to a card ([id] null = it left the grid for a chip or the title row: the panel keeps its game). */
    fun onCardFocused(id: String?) {
        if (id == null) return
        // While the panel or a sheet has focus the panel stays on its game (§2.3): a card can only be focused there in passing
        // (the page turning unfocusable behind a sheet or a clip), and that never changes the game.
        if (panelFocus.value || _sheet.value != null) return
        if (shownId.value == id) return
        shownId.value = id
        val game = currentCards().firstOrNull { it.game.id.id == id }?.game ?: return
        startFetch(game, immediate = false)
    }

    /** The screen's first board / a new board: the panel's initial game starts its dwell too. */
    fun onBoardShown() {
        val g = shownGame() ?: return
        startFetch(g, immediate = false)
    }

    /**
     * OK or Menu on a card, or Left from the left column (§2.2): focus goes to the panel's first enabled slot. Starts the detail,
     * the match and the clip lookups at once. Returns the slot index to focus, or null when no slot is enabled (nothing happens).
     */
    fun enterPanel(id: String): Int? {
        if (shownId.value != id) shownId.value = id
        val game = currentCards().firstOrNull { it.game.id.id == id }?.game ?: return null
        refreshPanel()
        // OK skips the rest. The repository rests HUB_FETCH_DWELL_MS on a new game and skips the rest on a second show() of the
        // game resting (GameDetailRepositoryImpl's contract), so the page shows twice; an already-fetched game is re-read by
        // the poll policy as usual.
        showNow(game)
        startFetch(game, immediate = true)
        refreshPanel()
        val first = _panel.value.firstEnabled ?: return null
        enteredId = game.id
        frozen.value = _panel.value.slots.map { it.action }
        panelFocus.value = true
        refreshPanel()
        return _panel.value.firstEnabled ?: first
    }

    /** Focus left the panel (Back, Right from the last slot, or a sheet closed back to a card): the slots thaw. */
    fun onPanelExited() {
        enteredId = null
        panelFocus.value = false
        frozen.value = null
    }

    /** A slot's action (§1.5). */
    fun onSlot(index: Int) {
        val p = _panel.value
        val game = p.game ?: return
        val slot = p.slots.getOrNull(index) ?: return
        if (!slot.usable) return
        when (slot.action) {
            SlotAction.FOLLOW -> repo?.follow(if (p.followed) null else game)
            SlotAction.RETRY -> detail?.retry()
            SlotAction.CHANNEL -> {
                val ctx = ctxOf(p)
                val watch = HubSlots.watchable(ctx)
                if (watch != null && slot.label == SlotLabel.WATCH) tune(watch)
                else _sheet.value = HubSheet.Channels(game.id, archiveOnly = false)
            }
            SlotAction.GOALS -> {
                _sheet.value = HubSheet.Goals(game.id)
                // (Owner bug 2026-11-25) The condensed game's row needs its lookup (thumbnail, duration): made when the
                // sheet opens, so browsing the grid makes no third lookup per game.
                ensureClipInfos()
            }
            SlotAction.WATCH_RECAP -> p.detail?.recapId.let { it ?: game.media?.recapId }?.let { playClips(listOf(ClipRef(it, ClipKind.RECAP, game.id)), 0) }
            SlotAction.BOX_SCORE -> openBoxScore(game)
            SlotAction.MATCHUP -> _sheet.value = HubSheet.Matchup(game.id)
            SlotAction.WATCH_FROM_START -> {
                val rows = p.candidates.filter { it.airing != null }
                if (rows.size == 1) playArchive(rows[0]) else if (rows.isNotEmpty()) _sheet.value = HubSheet.Channels(game.id, archiveOnly = true)
            }
            null -> Unit
        }
    }

    /** OK on Retry (or on a failure's text). */
    fun onRetry() {
        repo?.retry()
    }

    fun closeSheet() {
        _sheet.value = null
    }

    private fun openBoxScore(game: GameSnapshot) {
        val d = detail
        _sheet.value = HubSheet.BoxScoreSheet(game.id, null)
        if (d == null) { _sheet.value = HubSheet.BoxScoreSheet(game.id, Fetch.Failed(FailKind.IO, null, nowMono())); return }
        viewModelScope.launch {
            val box = d.boxScore(game.id)
            val cur = _sheet.value
            if (cur is HubSheet.BoxScoreSheet && cur.gameId == game.id) _sheet.value = cur.copy(box = box)
        }
    }

    /** Retry in the Box score sheet. */
    fun retryBoxScore() {
        val cur = _sheet.value as? HubSheet.BoxScoreSheet ?: return
        val game = currentCards().firstOrNull { it.game.id == cur.gameId }?.game ?: return
        openBoxScore(game)
    }

    // ------------------------------------------------------------------ the sheets' actions

    /** OK on a channel row or on Watch: tunes it, goes full screen, and does **not** follow the game (owner ruling). */
    fun tune(c: ChannelCandidate) {
        _effects.tryEmit(SportsEffect.Tune(c.channel))
    }

    /** OK on a catch-up row / "Watch from start": the existing archive path. */
    fun playArchive(c: ChannelCandidate) {
        val info = c.airing ?: return
        _effects.tryEmit(SportsEffect.PlayArchive(c.channel, info))
    }

    /** OK on a row of a game that is not on yet (§5.5). */
    fun channelNotYet() {
        val g = panel.value.game ?: return
        toast(HubToastKind.STARTS_AT, g.startUtcMs)
    }

    fun missingClip() = toast(HubToastKind.GOAL_NO_VIDEO)

    /** Plays [queue]`[`[index]`]` through the clip session (a goal and every later goal, or a recap of one). */
    fun playClips(queue: List<ClipRef>, index: Int) {
        val s = clipSession ?: run { toast(HubToastKind.VIDEO_NOT_PLAYABLE); return }
        s.play(queue, index)
    }

    fun toastShown(seq: Long) {
        if (_toast.value?.seq == seq) _toast.value = null
    }

    private fun toast(kind: HubToastKind, startUtcMs: Long? = null) {
        _toast.value = HubToast(kind, startUtcMs, ++toastSeq)
    }

    private fun markVideoOff() {
        videoOff.value = true
        videoOffJob?.cancel()
        videoOffJob = viewModelScope.launch {
            delay(SportsConstants.HUB_VIDEO_KEY_REREAD_MS)
            videoOff.value = false
        }
    }

    // ------------------------------------------------------------------ fetching for the shown game

    private fun currentCards(): List<GameCardModel> = (state.value.body as? SportsBody.Games)?.cards.orEmpty()

    private fun shownGame(): GameSnapshot? = shownCard()?.game

    private fun shownCard(): GameCardModel? {
        val cards = currentCards()
        if (cards.isEmpty()) return null
        val id = shownId.value
        return cards.firstOrNull { it.game.id.id == id } ?: cards[initialFocusIndex(cards) ?: 0]
    }

    /** The panel's game starts its detail, match and clip lookups after the dwell, or at once ([immediate]). */
    private fun startFetch(game: GameSnapshot, immediate: Boolean) {
        if (fetchedId == game.id.id && fetchedPhase == game.phase) return
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            if (!immediate) delay(SportsConstants.HUB_FETCH_DWELL_MS)
            fetchedId = game.id.id
            fetchedPhase = game.phase
            showNow(game)
            startMatch(game)
            ensureClipInfos()
        }
    }

    /**
     * The page has rested already (or OK skipped the rest): a first show() of a new game makes the repository start its own
     * rest, and a second show() of that game skips it, so the detail request starts now and the browse rest is 600 ms, not
     * twice that.
     */
    private fun showNow(game: GameSnapshot) {
        val d = detail ?: return
        d.show(game)
        d.show(game)
    }

    /**
     * (Review M3) Nothing is shown (an empty or failed board, a league or day being loaded): the previous game's detail and
     * match stop, so nothing is polled for a game no longer on screen (§0.1).
     */
    private fun stopFetching() {
        if (fetchedId == null && fetchJob == null && matchJob == null) return
        fetchJob?.cancel()
        fetchJob = null
        matchJob?.cancel()
        matchJob = null
        fetchedId = null
        fetchedPhase = null
        detail?.show(null)
    }

    /**
     * (Review M7) A re-poll changed the shown game's phase (pre-game → live, live → final): the repository gets the newer
     * snapshot once (no rest), so its poll cadence follows the phase, and the channel match runs again (upcoming and imminent
     * games match differently). The previous answer stays on screen until the new one arrives.
     */
    private fun followPhase(game: GameSnapshot) {
        if (fetchedId != game.id.id || fetchedPhase == null || fetchedPhase == game.phase) return
        fetchedPhase = game.phase
        detail?.show(game)
        val m = matcher ?: return
        if (game.phase == GamePhase.POSTPONED) return
        matchJob?.cancel()
        matchJob = viewModelScope.launch { runMatch(m, game) }
    }

    private fun startMatch(game: GameSnapshot) {
        val id = game.id.id
        if (game.phase == GamePhase.POSTPONED) return
        val m = matcher
        if (m == null) { matches.value = matches.value + (id to MatchState.Done(emptyList())); return }
        val old = matches.value[id]
        if (old is MatchState.Done) return
        // (Event channels §8.5) A game still beyond the guide keeps its state; one the guide now covers is matched.
        // (Code review 2026-11-25, UI m3) Only a finished answer: a placeholder left by a cancelled read is read again.
        if (old is MatchState.BeyondGuide && old.complete && beyondGuide(m, game)) return
        matchJob?.cancel()
        // Beyond the guide the slot is the grey Channels at once, never "Finding…" (only the team channels are read).
        matches.value = matches.value + (id to (if (beyondGuide(m, game)) MatchState.BeyondGuide(emptyList(), m.guideEndMs() == null, complete = false) else MatchState.Finding))
        matchJob = viewModelScope.launch { runMatch(m, game) }
    }

    /** (Event channels §8.5, owner ruling c) True when [game] starts beyond the guide: no channel search runs. */
    private fun beyondGuide(m: GameChannelMatcher, game: GameSnapshot): Boolean =
        game.phase != GamePhase.FINAL && game.phase != GamePhase.LIVE && game.phase != GamePhase.INTERMISSION &&
            ChannelHorizon.beyondGuide(game.startUtcMs, m.guideEndMs(), nowUtcMs())

    /**
     * The match of [game] (IO inside the matcher): beyond the guide only the two team channels (`teamChannels`, for the
     * line); otherwise the full match with the shown day's games as the board (the cities-only rule, §8.4).
     */
    private suspend fun runMatch(m: GameChannelMatcher, game: GameSnapshot) {
        val id = game.id.id
        val state: MatchState = if (beyondGuide(m, game)) {
            val teams = guarded { m.teamChannels(game, access()) }
            MatchState.BeyondGuide(teams, m.guideEndMs() == null)
        } else {
            val board = currentCards().map { it.game }.filter { it.id.league == game.id.league }
            MatchState.Done(guarded { m.match(game, nowUtcMs(), access(), board) })
        }
        matches.value = matches.value + (id to state)
    }

    private inline fun guarded(block: () -> List<ChannelCandidate>): List<ChannelCandidate> =
        runCatching(block).getOrElse { if (it is kotlin.coroutines.cancellation.CancellationException) throw it else emptyList() }

    /** The clip lookups of the shown NHL game (§6.3): the recap and the winning goal's clip; the latest goal's clip. */
    private fun ensureClipInfos() {
        val r = resolver ?: return
        val game = shownGame() ?: return
        if (game.id.league.group != LeagueGroup.NHL || fetchedId != game.id.id) return
        val ds = detailFlow.value
        val d = (ds as? DetailState.Ready)?.detail?.takeIf { it.id == game.id }
        val refs = ArrayList<ClipRef>()
        when (game.phase) {
            GamePhase.FINAL -> {
                (d?.recapId ?: game.media?.recapId)?.let { refs += ClipRef(it, ClipKind.RECAP, game.id) }
                if (d != null) HubSummary.winningGoal(game, d)?.clipId?.let { refs += ClipRef(it, ClipKind.GOAL, game.id) }
                // (Owner bug 2026-11-25) The condensed game, only while its Goals sheet is open (the row that shows it).
                val goalsOpen = (_sheet.value as? HubSheet.Goals)?.gameId == game.id
                if (goalsOpen) (d?.condensedId ?: game.media?.condensedId)?.let { refs += ClipRef(it, ClipKind.CONDENSED, game.id) }
            }
            GamePhase.LIVE, GamePhase.INTERMISSION ->
                d?.goals?.lastOrNull { it.clipId != null }?.clipId?.let { refs += ClipRef(it, ClipKind.GOAL, game.id) }
            else -> Unit
        }
        for (ref in refs) {
            if (!infoRequested.add(ref.videoId)) continue
            viewModelScope.launch {
                when (val res = runCatching { r.info(ref) }.getOrElse { if (it is kotlin.coroutines.cancellation.CancellationException) throw it else null }) {
                    is ClipResult.Ok -> clipInfos.value = (clipInfos.value + (ref.videoId to res.value)).let { m -> if (m.size > 40) m.entries.drop(m.size - 40).associate { it.toPair() } else m }
                    ClipResult.Refused -> { infoRequested.remove(ref.videoId); markVideoOff() }
                    else -> if (res !is ClipResult.NotFound) infoRequested.remove(ref.videoId)
                }
            }
        }
    }

    // ------------------------------------------------------------------ building the panel

    private fun ctxOf(p: HubPanelModel): HubSlots.Ctx = HubSlots.Ctx(
        game = p.game!!, followed = p.followed, detail = p.detail, failure = p.failure,
        match = p.match, videoOff = videoOff.value, nowMs = p.nowMs, matcherBound = matcher != null,
    )

    private fun refreshPanel() {
        val p = buildPanel()
        _panel.value = p
        val game = p.game
        // (Review M1, M6) The panel's game changed under an open sheet or a focused panel (a re-poll dropped it, the board's day
        // rolled over, a new board): the sheet closes and focus leaves the panel; the screen moves focus to a card.
        _sheet.value?.let { if (it.gameId != game?.id) _sheet.value = null }
        if (panelFocus.value && enteredId != null && enteredId != game?.id) onPanelExited()
        if (game == null) stopFetching() else followPhase(game)
    }

    private fun buildPanel(): HubPanelModel {
        val card = shownCard() ?: return HubPanelModel(game = null)
        val g = card.game
        val ds = detailFlow.value
        var d: GameDetail? = null
        var kind = if (g.id.league.group == LeagueGroup.NHL) DetailKind.NONE else DetailKind.BOARD_ONLY
        var failure: FailKind? = null
        var stale = false
        var failing = false
        var statsAt: Long? = null
        val now = nowUtcMs()
        when (ds) {
            is DetailState.Ready -> if (ds.detail.id == g.id) {
                d = ds.detail; kind = DetailKind.READY; stale = ds.stale; failing = ds.failing
                // (Review L3) To the minute ("Stats from 8:12 PM"), so two builds a millisecond apart are equal models.
                statsAt = (now - (nowMono() - ds.fetchedAtMono).coerceAtLeast(0L)) / 60_000L * 60_000L
            }
            is DetailState.Loading -> if (ds.id == g.id) kind = DetailKind.LOADING
            is DetailState.Failed -> if (ds.id == g.id) { kind = DetailKind.FAILED; failure = ds.kind }
            is DetailState.BoardOnly -> if (ds.id == g.id) kind = DetailKind.BOARD_ONLY
            DetailState.None -> Unit
        }
        // §4.3: the headline's score is the newer of the board's and the detail's answer; a tie takes the board.
        var away = g.away.score
        var home = g.home.score
        val ready = ds as? DetailState.Ready
        if (d != null && ready != null && (g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION) && d.awayScore != null && d.homeScore != null &&
            ready.fetchedAtMono > card.anchorMono
        ) {
            away = d.awayScore; home = d.homeScore
        }
        val match = matches.value[g.id.id] ?: MatchState.Idle
        val infos = clipInfos.value
        val recapId = d?.recapId ?: g.media?.recapId
        val latest = d?.goals?.lastOrNull { it.clipId != null }?.clipId
        val winning = if (d != null && g.phase == GamePhase.FINAL) HubSummary.winningGoal(g, d)?.clipId else null
        val base = HubPanelModel(
            game = g, followed = card.followed, detail = d, detailKind = kind, failure = failure, stale = stale, failing = failing,
            statsAtMs = statsAt, awayScore = away, homeScore = home, match = match,
            recap = recapId?.let { infos[it] }, latestGoal = latest?.let { infos[it] }, winningGoal = winning?.let { infos[it] }, nowMs = now,
            videoOff = videoOff.value,
            imminent = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION || g.startUtcMs - now <= SportsConstants.HUB_IMMINENT_MIN * 60_000L,
        )
        val ctx = ctxOf(base)
        val fr = frozen.value
        val slots = if (panelFocus.value && fr != null) fr.map { HubSlots.forAction(it, ctx) } else HubSlots.default(ctx)
        return base.copy(slots = slots)
    }

    // ------------------------------------------------------------------ the old page's helpers (kept)

    companion object {
        val EASTERN: ZoneId = ZoneId.of("America/New_York")

        /** Live (and intermission), then pre-game, then scheduled (and postponed) by start time, then final (§5.2). */
        fun order(games: List<GameSnapshot>, league: League = League.NHL): List<GameSnapshot> =
            games.sortedWith(compareBy<GameSnapshot> {
                when (league.sport) { Sport.FOOTBALL -> nflRank(it.phase); Sport.HOCKEY -> rank(it.phase) }
            }.thenBy { it.startUtcMs }.thenBy { it.id.id })

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
