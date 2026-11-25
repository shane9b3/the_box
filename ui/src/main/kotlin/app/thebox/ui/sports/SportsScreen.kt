package app.thebox.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.thebox.core.guide.FooterHint
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.group
import app.thebox.ui.R
import app.thebox.ui.full.PlayerText
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.FooterHints
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens
import java.time.LocalDate
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SPORTS_SCREEN_TAG = "sports_screen"
const val SPORTS_RETRY_TAG = "sports_retry"
const val SPORTS_MESSAGE_TAG = "sports_message"
const val SPORTS_UPDATED_TAG = "sports_updated"
const val SPORTS_SKELETON_TAG = "sports_skeleton"
const val SPORTS_GRID_TAG = "sports_grid"
const val SPORTS_WEEK_TAG = "sports_week"
const val SPORTS_LEAGUE_PILL_TAG = "sports_league_pill"
const val SPORTS_DATE_TAG = "sports_date"
const val SPORTS_TIME_TAG = "sports_time"
const val SPORTS_HEADING_TAG = "sports_heading"
const val SPORTS_TOAST_TAG = "sports_toast"
const val SPORTS_SHEET_TAG = "sports_sheet"
internal fun sportsLeagueTag(league: League) = "sports_league:" + league.name
/** (CHL §6.3) The group chips `NHL · NFL · CHL`: the same tags as before for NHL and NFL, "sports_league:CHL" for the third. */
internal fun sportsGroupTag(group: LeagueGroup) = "sports_league:" + group.name
/** (CHL §6.3) The inline sub-switch's chips `OHL · WHL · QMJHL`, shown only while a CHL league is. */
internal fun sportsChlTag(league: League) = "sports_chl:" + league.name
const val SPORTS_CHL_DIVIDER_TAG = "sports_chl_divider"

/** Design px (1 dp = 1 design px under the root's scale). */
private const val SCROLL_MARGIN = 48
private const val CHIP_H = 52

/** The page's focus zones, for the footer's hints (§2.6). */
private enum class Zone { CARDS, CHIPS }

/**
 * The Sports Hub page (docs/design/m6-sports-hub.md §1, §2; WP-4). It is on Compose focus like Search: the router passes every
 * key but BACK, and one Back returns to the guide grid (the machine's `onSportsClosed`). While focus is in the panel or a sheet
 * is open the screen holds BACK ([onBackFreeChanged] false) and returns focus itself (Back from the panel goes to its card).
 *
 * Layout (design px, 1920 × 1080): the title row at y 56; the detail panel (96, 148, 720 × 812); on the right (x 856, 968 wide)
 * the heading, the day strip at y 196 and the two-column grid of 472 × 304 cards from y 280 to 960; the footer at y 976.
 *
 * Keys: OK or Menu on any card (and Left from the left column) moves focus to the panel's first enabled slot; slots never
 * reorder; Back, or Right from the last slot, returns to the same card. No long press anywhere (§2.2). Initial focus: the
 * followed game, else the first live card, else the Today chip.
 *
 * The page sits over the root's video surface: no `graphicsLayer`, clip or blur on its root. While a clip plays it keeps its
 * state, including an open Goals sheet, but is not drawn ([ClipPlayerLayer] draws the clip's controls).
 */
@Composable
fun SportsScreen(
    viewModel: SportsViewModel,
    clock24: Boolean,
    modifier: Modifier = Modifier,
    onBackFreeChanged: (Boolean) -> Unit = {},
    /** The title row's date and time (tests pass a fixed clock so the screenshot does not change with the time of day). */
    wallClockMs: () -> Long = System::currentTimeMillis,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val panel by viewModel.panel.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val panelFocused by viewModel.panelFocused.collectAsStateWithLifecycle()
    val clipInfo by viewModel.clipInfo.collectAsStateWithLifecycle()
    val videoRefused by viewModel.videoRefused.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val session = viewModel.clipSession
    val clipState by (session?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow<ClipSession.State>(ClipSession.State.Idle) }).collectAsStateWithLifecycle()
    val lastPlayed by (session?.lastPlayed ?: remember { kotlinx.coroutines.flow.MutableStateFlow<app.thebox.core.sports.hub.ClipRef?>(null) }).collectAsStateWithLifecycle()
    val clipActive = clipState !is ClipSession.State.Idle
    // The Goals sheet's focus target (the clip just played) is frozen while a clip plays: a change under the clip (the
    // next goal of the queue) would move focus from the clip layer into the hidden sheet. [clipEpoch] counts ended clips.
    var sheetFocusVideoId by remember { mutableStateOf<Long?>(null) }
    var clipEpoch by remember { mutableIntStateOf(0) }
    // (Review L5) The row the user pressed, and what had played last at that moment: a lookup that fails plays nothing, so
    // focus returns to the pressed row rather than to the clip before it.
    var pressedVideoId by remember { mutableStateOf<Long?>(null) }
    var lastAtPress by remember { mutableStateOf<app.thebox.core.sports.hub.ClipRef?>(null) }
    LaunchedEffect(clipActive, lastPlayed) {
        if (!clipActive) sheetFocusVideoId = if (pressedVideoId != null && lastPlayed == lastAtPress) pressedVideoId else lastPlayed?.videoId
    }
    // Back is free only when neither a sheet, the panel nor the clip layer holds it (the layer reports its own hold).
    val holdsNothing = androidx.compose.runtime.rememberUpdatedState(sheet == null && !panelFocused)
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val marginPx = with(LocalDensity.current) { SCROLL_MARGIN.dp.roundToPx() }

    DisposableEffect(viewModel) {
        viewModel.onEntered()
        onDispose {
            viewModel.onLeft()
            onBackFreeChanged(true)
        }
    }
    val league = ui.prefs.league
    val nfl = league.group == LeagueGroup.NFL
    // (CHL §6.3, L7) `NHL · NFL · CHL`, then, with CHL shown, an inline `OHL · WHL · QMJHL` on the same row.
    val groupChips = remember { LeagueGroup.entries.associateWith { FocusRequester() } }
    val subChips = remember { CHL_LEAGUES.associateWith { FocusRequester() } }
    val dayChips = remember { (-SportsHubDays..SportsHubDays).associateWith { FocusRequester() } }
    val slotRequesters = remember { List(3) { FocusRequester() } }
    val cardRequesters = remember { HashMap<String, FocusRequester>() }
    var focusSub by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    var focusedCardId by remember { mutableStateOf<String?>(null) }
    var lastCardId by remember { mutableStateOf<String?>(null) }
    var returnCardId by remember { mutableStateOf<String?>(null) }
    var zone by remember { mutableStateOf(Zone.CARDS) }
    var focusedSlot by remember { mutableIntStateOf(-1) }
    var sheetSlot by remember { mutableIntStateOf(0) }
    var lastSlot by remember { mutableIntStateOf(0) }
    // (Review M2) The panel's buttons take focus from the moment the page decides to enter (set before the request), not
    // when the view model's flag has come back through recomposition.
    var panelEntered by remember { mutableStateOf(false) }
    // (The view model's live value: a quick Back then OK must not drop the new entry.)
    LaunchedEffect(panelFocused) { if (!panelFocused && !viewModel.panelFocused.value) panelEntered = false }
    val selectedChip = dayChips.getValue(if (nfl) ui.offset.coerceIn(-1, 1) else ui.offset)
    val selectedChipNow by androidx.compose.runtime.rememberUpdatedState(selectedChip)

    LaunchedEffect(Unit) { runCatching { dayChips.getValue(0).requestFocus() } }
    // The router holds BACK while focus is in the panel or a sheet is open (the clip layer holds it for its own life).
    LaunchedEffect(sheet, panelFocused, clipActive) { if (!clipActive) onBackFreeChanged(sheet == null && !panelFocused) }
    // A league switch lists the new league from its top.
    LaunchedEffect(league) { gridState.requestScrollToItem(0) }
    // OK on the CHL chip moves focus to the sub-chip of the league now shown, once the sub-switch has been composed (§6.3).
    LaunchedEffect(focusSub, league.group) {
        if (focusSub && league.group == LeagueGroup.CHL) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { subChips.getValue(league).requestFocus() }
            focusSub = false
        }
    }

    val body = ui.body
    val cards = (body as? SportsBody.Games)?.cards.orEmpty()
    val cardsNow by androidx.compose.runtime.rememberUpdatedState(cards)
    /** Each game id's last index on this page (kept for ids that left), for "focus moves to its neighbour by index" (§1.2). */
    val cardIndex = remember { HashMap<String, Int>() }
    // The panel's initial game starts its dwell once a board shows (§1.3).
    LaunchedEffect(cards.firstOrNull()?.game?.id, league, ui.offset) { if (cards.isNotEmpty()) viewModel.onBoardShown() }
    // Initial focus (§2.1): once, when the first board arrives. A load that shows nothing leaves the Today chip focused.
    LaunchedEffect(body is SportsBody.Games, body is SportsBody.Loading) {
        if (body is SportsBody.Loading) return@LaunchedEffect
        if (settled) return@LaunchedEffect
        settled = true
        if (body is SportsBody.Games) {
            val idx = SportsViewModel.initialFocusIndex(body.cards) ?: return@LaunchedEffect
            gridState.scrollToItem(idx)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { cardRequesters[body.cards[idx].game.id.id]?.requestFocus() }
        }
    }

    /**
     * Focus to the card of [id] once the list has settled; (review M6) a card that left the board hands focus to the card now
     * at its old index (§1.2), and an empty board to the selected day chip, so focus is never lost.
     */
    fun focusCard(id: String?) {
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            val list = cardsNow
            if (list.isEmpty()) { runCatching { selectedChipNow.requestFocus() }; return@launch }
            val target = id?.takeIf { i -> list.any { it.game.id.id == i } }
                ?: list[((id?.let { cardIndex[it] }) ?: 0).coerceIn(0, list.lastIndex)].game.id.id
            val index = list.indexOfFirst { it.game.id.id == target }
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                gridState.scrollToItem(index)
                androidx.compose.runtime.withFrameNanos { }
            }
            runCatching { cardRequesters[target]?.requestFocus() }
        }
    }
    fun exitPanel() {
        panelEntered = false
        viewModel.onPanelExited()
        focusCard(returnCardId ?: lastCardId)
    }
    fun focusSlot(i: Int) {
        scope.launch {
            // (Review M2) The buttons are focusable once the page has recomposed with `panelEntered` (and, after a sheet or
            // a clip, with the page focusable again): try for up to three frames, then give up and return to the card, so
            // the page is never half in the panel (footer, Back hold and selected card without focus there).
            repeat(3) {
                androidx.compose.runtime.withFrameNanos { }
                if (runCatching { slotRequesters[i].requestFocus() }.getOrDefault(false)) return@launch
            }
            exitPanel()
        }
    }
    fun enter(id: String) {
        returnCardId = id
        val first = viewModel.enterPanel(id) ?: return
        panelEntered = true
        lastSlot = first
        focusSlot(first)
    }
    // After a clip: back to the slot that started it (the Goals sheet restores its own focus when it is re-keyed), or to the
    // card when the panel was left under the clip (its game left the board).
    var wasClipActive by remember { mutableStateOf(false) }
    LaunchedEffect(clipActive) {
        if (wasClipActive && !clipActive) {
            clipEpoch++
            if (sheet == null) { if (panelFocused) focusSlot(lastSlot) else focusCard(returnCardId ?: lastCardId) }
        }
        wasClipActive = clipActive
    }
    // (Review M1) The view model closed the sheet or left the panel by itself (the game left the board, the day rolled
    // over): focus goes to the card (or its neighbour), never nowhere with Back held.
    val held = sheet != null || panelFocused
    var wasHeld by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        if (wasHeld && !held && !clipActive && viewModel.sheet.value == null && !viewModel.panelFocused.value) focusCard(returnCardId ?: lastCardId)
        wasHeld = held
    }
    // (Review M6) The focused card left the board on a re-poll: its neighbour by index takes focus.
    val cardIds = cards.map { it.game.id.id }
    // Read in the composition that changes the list, before the card's item leaves (Compose may then move focus anywhere).
    val focusedBefore = remember(cardIds) { focusedCardId }
    LaunchedEffect(cardIds) {
        val gone = focusedBefore?.takeIf { it !in cardIds && cardIndex.containsKey(it) }
        if (gone != null && !viewModel.panelFocused.value && viewModel.sheet.value == null && !clipActive) focusCard(gone)
        cardIds.forEachIndexed { i, id -> cardIndex[id] = i }
    }
    // A slot that stops being enabled while focused hands focus to the next enabled one, else to the card (§2.3).
    LaunchedEffect(panel.slots, panelFocused, focusedSlot) {
        if (panelFocused && focusedSlot in 0..2 && !panel.slots[focusedSlot].usable && sheet == null && !clipActive) {
            val next = panel.firstEnabled
            if (next != null) focusSlot(next) else exitPanel()
        }
    }

    val off = body is SportsBody.Off
    val focusedCard = cards.firstOrNull { it.game.id.id == focusedCardId }
    val showHighlights = !videoRefused

    Box(
        modifier
            .fillMaxSize()
            .testTag(SPORTS_SCREEN_TAG)
            .onPreviewKeyEvent { e ->
                // (Review L6) Any move before the sub-switch has appeared cancels the pending focus pull to its chip.
                if (focusSub && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key != Key.DirectionCenter) focusSub = false
                false
            },
    ) {
        // The page: composed (state kept) but not drawn while a clip plays; no layer, clip or alpha above the surface.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { if (!clipActive) drawContent() }
                .drawBehind { if (!clipActive) drawPageBackground() },
        ) {
            // (Review H1, H2) Nothing on the page takes focus behind an open sheet or under a clip.
            androidx.compose.runtime.CompositionLocalProvider(LocalHubFocusable provides (sheet == null && !clipActive)) {
            Column(Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp, top = 56.dp, bottom = 56.dp)) {
                TitleRow(ui, groupChips, subChips, selectedChip, chips = !off, clock24 = clock24, wallClockMs = wallClockMs, onZone = { zone = Zone.CHIPS },
                    onGroup = { g ->
                        viewModel.onGroup(g)
                        if (g == LeagueGroup.CHL) focusSub = true
                    },
                    onLeague = viewModel::onLeague,
                )
                Spacer(Modifier.height(28.dp))
                if (off) {
                    Message(stringResource(R.string.sports_off), retry = false, onRetry = {})
                } else {
                    Row(Modifier.fillMaxWidth().height(HubPanelPlan.PANEL_H.dp)) {
                        HubPanel(
                            model = panel, clock24 = clock24, slotRequesters = slotRequesters,
                            onSlot = { i ->
                                sheetSlot = i
                                lastSlot = i
                                viewModel.onSlot(i)
                            },
                            onSlotFocus = { focusedSlot = it ?: -1 },
                            onExit = ::exitPanel,
                            entered = panelEntered,
                        )
                        Spacer(Modifier.width(40.dp))
                        Column(Modifier.width(968.dp)) {
                            Heading(ui)
                            Spacer(Modifier.height(16.dp))
                            // Up from the strip: the shown league's chip (NFL §7.3, fix pass U4; CHL §6.3: its sub-chip).
                            val upTarget = if (league.group == LeagueGroup.CHL) subChips.getValue(league) else groupChips.getValue(league.group)
                            DayStrip(
                                chips = ui.days, selectedOffset = ui.offset, nfl = nfl, requesters = dayChips, up = upTarget,
                                onDown = {
                                    // Down from the strip: the card focused last on this day, else the first card (§2.7).
                                    val id = lastCardId?.takeIf { l -> cards.any { it.game.id.id == l } } ?: cards.firstOrNull()?.game?.id?.id
                                    if (id == null) false else { focusCard(id); true }
                                },
                                onDay = { o ->
                                    settled = true
                                    viewModel.onDay(o)
                                },
                                modifier = Modifier.fillMaxWidth().height(60.dp).onFocusChanged { if (it.hasFocus) zone = Zone.CHIPS },
                            )
                            Spacer(Modifier.height(24.dp))
                            Box(Modifier.fillMaxWidth().height(680.dp)) {
                                when (val b = body) {
                                    SportsBody.Loading -> Skeletons()
                                    is SportsBody.Games -> GamesGrid(
                                        b.cards, gridState, clock24, cardRequesters, dayChips.getValue(selectedOffsetOf(ui, nfl)),
                                        panel = panel, panelFocused = panelFocused, showHighlights = showHighlights, focusedId = focusedCardId,
                                        onFocus = { id, on, index ->
                                            // A card focused in passing while the page turns unfocusable (a sheet opening, a
                                            // clip starting) is not the user's move.
                                            if (on && (viewModel.sheet.value != null || viewModel.panelFocused.value || clipActive)) Unit
                                            else if (on) {
                                                focusedCardId = id
                                                lastCardId = id
                                                zone = Zone.CARDS
                                                viewModel.onCardFocused(id)
                                                scope.launch { gridState.ensureVisible(index, marginPx) }
                                            } else if (focusedCardId == id) focusedCardId = null
                                        },
                                        onEnter = ::enter,
                                    )
                                    is SportsBody.Empty -> Message(emptyText(b, ui), retry = false, onRetry = {})
                                    is SportsBody.Failed -> Message(SportsText.failure(res, b.kind, league), retry = true, onRetry = {
                                        // U-S3: the Retry chip leaves with the Failed body, so focus moves to the selected day chip
                                        // first, and the initial-focus rule runs again for the board that answers.
                                        runCatching { selectedChip.requestFocus() }
                                        settled = false
                                        viewModel.onRetry()
                                    })
                                    SportsBody.Off -> Unit
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Footer(ui, hints = footerOf(res, zone, panelFocused, sheet, focusedCard, panel, focusedSlot), updatedMono = ui.updatedMono)
            }
            }
            // A sheet takes the right column (§6.4). The sheet draws its own frame at x 856, y 148 (HubSheetFrame), so the
            // host is the full page; the grid behind is not focusable while it is open. Re-keyed after each clip, so focus
            // lands again on the clip just played even when it was the same clip as before (U-S14).
            sheet?.let { sh ->
                androidx.compose.runtime.key(clipEpoch) {
                    // (Review H2) Under a clip the (hidden) sheet takes no focus either, whatever its rows do.
                    androidx.compose.runtime.CompositionLocalProvider(LocalHubFocusable provides !clipActive) {
                    Box(Modifier.fillMaxSize().testTag(SPORTS_SHEET_TAG)) {
                        SheetHost(
                            sh, viewModel, panel, clipInfo, sheetFocusVideoId, clock24,
                            onPressed = { ref ->
                                pressedVideoId = ref.videoId
                                lastAtPress = lastPlayed
                            },
                            onClose = {
                                viewModel.closeSheet()
                                val i = sheetSlot
                                if (panelFocused && panel.slots.getOrNull(i)?.usable == true) focusSlot(i) else exitPanel()
                            },
                        )
                    }
                    }
                }
            }
        }
        if (session != null && clipActive) {
            // The sub-line's teams and "Goal k of n" come from the panel's game when the clip belongs to it.
            val clipGameId = when (val s = clipState) {
                is ClipSession.State.Playing -> s.queue.getOrNull(s.index)?.gameId
                is ClipSession.State.Resolving -> s.ref.gameId
                is ClipSession.State.Failed -> s.queue.getOrNull(s.index)?.gameId
                ClipSession.State.Idle -> null
            }
            val clipGame = panel.game?.takeIf { it.id == clipGameId }
            ClipPlayerLayer(
                session = session,
                onBackFreeChanged = { free -> onBackFreeChanged(free && holdsNothing.value) },
                modifier = Modifier.fillMaxSize(),
                game = clipGame,
                goals = if (clipGame != null) panel.detail?.goals.orEmpty() else emptyList(),
            )
        }
        toast?.let { t -> ToastBar(t, clock24, onShown = { viewModel.toastShown(t.seq) }) }
    }
}

private const val SportsHubDays = app.thebox.core.sports.SportsConstants.HUB_DAY_RANGE

private fun selectedOffsetOf(ui: SportsUiState, nfl: Boolean): Int = if (nfl) ui.offset.coerceIn(-1, 1) else ui.offset

/** The page background (§1.1): `Canvas` and one static linear gradient from the top left (`#123D2C`, α 0.85) to clear at 60 % of the diagonal. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageBackground() {
    drawRect(DesignTokens.Colors.Canvas)
    drawRect(
        Brush.linearGradient(
            colors = listOf(Color(0xFF123D2C).copy(alpha = 0.85f), Color.Transparent),
            start = Offset.Zero, end = Offset(size.width * 0.6f, size.height * 0.6f),
        ),
    )
}

@Composable
private fun emptyText(b: SportsBody.Empty, ui: SportsUiState): String {
    val res = LocalResources.current
    val league = ui.prefs.league
    // A day other than yesterday, today or tomorrow: "No NHL games on Saturday, November 21. Pick another day above."
    if (league.group != LeagueGroup.NFL && b.offset !in -1..1) {
        val date = runCatching { LocalDate.parse(b.dayKey) }.getOrNull()
        if (date != null) return res.getString(R.string.sports_empty_day_league, PlayerText.leagueName(res, league), SportsText.spokenDate(date))
    }
    // NFL §7.2: the off-season has no neighbour either way ("No NFL games this week."); a quiet week points at the chips.
    // CHL §6.5: "No OHL games today. Pick another day above." (the `_league` strings).
    return SportsText.emptyDay(res, b.day, league, weekOff = !ui.canPrev && !ui.canNext)
}

/** The footer's hints (§2.6). */
private fun footerOf(
    res: android.content.res.Resources, zone: Zone, panelFocused: Boolean, sheet: HubSheet?, focused: GameCardModel?,
    panel: HubPanelModel, focusedSlot: Int,
): List<FooterHint> = buildList {
    val ok = res.getString(R.string.sports_footer_keys_ok)
    val back = res.getString(R.string.sports_footer_keys_back)
    when {
        sheet != null -> {
            add(FooterHint("↑ ↓", res.getString(R.string.sports_hub_hint_choose)))
            when (sheet) {
                is HubSheet.Goals -> add(FooterHint(ok, res.getString(R.string.sports_hub_hint_play)))
                is HubSheet.Channels -> add(FooterHint(ok, res.getString(R.string.sports_hub_hint_watch)))
                is HubSheet.BoxScoreSheet -> add(FooterHint("← →", res.getString(R.string.sports_hub_hint_away_home)))
                is HubSheet.Matchup -> Unit
            }
            add(FooterHint(back, res.getString(R.string.sports_hub_hint_close)))
        }
        panelFocused -> {
            add(FooterHint("← →", res.getString(R.string.sports_hub_hint_choose)))
            panel.slots.getOrNull(focusedSlot)?.takeIf { it.usable }?.let { add(FooterHint(ok, slotText(res, it))) }
            add(FooterHint(back, res.getString(R.string.sports_hub_hint_games)))
        }
        zone == Zone.CHIPS || focused == null -> {
            add(FooterHint(res.getString(R.string.sports_footer_keys_browse), res.getString(R.string.sports_hub_hint_browse)))
            add(FooterHint(ok, res.getString(R.string.sports_hub_hint_show)))
            add(FooterHint(back, res.getString(R.string.sports_hub_hint_guide)))
        }
        else -> {
            add(FooterHint(res.getString(R.string.sports_footer_keys_browse), res.getString(R.string.sports_hub_hint_browse)))
            // "OK Options" is absent when the game has no enabled slot.
            if (panel.game?.id == focused.game.id && panel.firstEnabled != null) add(FooterHint(ok, res.getString(R.string.sports_hub_hint_options)))
            add(FooterHint(back, res.getString(R.string.sports_hub_hint_guide)))
        }
    }
}

// ------------------------------------------------------------------ title row

@Composable
private fun TitleRow(
    ui: SportsUiState,
    groupChips: Map<LeagueGroup, FocusRequester>,
    subChips: Map<League, FocusRequester>,
    down: FocusRequester,
    chips: Boolean,
    clock24: Boolean,
    wallClockMs: () -> Long,
    onZone: () -> Unit,
    onGroup: (LeagueGroup) -> Unit,
    onLeague: (League) -> Unit,
) {
    val res = LocalResources.current
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Line(stringResource(R.string.sports_hub_title), GuideUi.sans(44, FontWeight.SemiBold))
        Spacer(Modifier.width(32.dp))
        if (!chips) {
            // Show Sports off: nothing to pick, so the shown league reads as a static pill (fix pass U4: not always NHL).
            Box(Modifier.background(GuideUi.White08, RoundedCornerShape(DesignTokens.Radii.Chip)).padding(horizontal = 14.dp, vertical = 5.dp)) {
                Line(PlayerText.leagueName(res, ui.prefs.league), GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg70), Modifier.testTag(SPORTS_LEAGUE_PILL_TAG))
            }
        } else {
            // NFL §7.3 (ruling 24), CHL §6.3 (L7): NHL · NFL · CHL, the shown group filled, 22/600, height 52; with CHL shown, a
            // 1 px divider (16 px of air each side) and OHL · WHL · QMJHL in the same style on the same row, so nothing moves
            // vertically. Up is consumed; Down reaches the selected day chip; Left / Right walk the whole row.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LeagueGroup.entries.forEach { group ->
                    FocusChip(
                        text = PlayerText.groupName(res, group),
                        requester = groupChips.getValue(group),
                        tag = sportsGroupTag(group),
                        down = down,
                        selected = group == ui.prefs.league.group,
                        last = group == LeagueGroup.CHL && ui.prefs.league.group != LeagueGroup.CHL,
                        onFocus = onZone,
                        onClick = { onGroup(group) },
                    )
                }
                if (ui.prefs.league.group == LeagueGroup.CHL) {
                    // 10 px row gap + 6 px padding = the 16 px of air each side.
                    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(GuideUi.White12).testTag(SPORTS_CHL_DIVIDER_TAG))
                    CHL_LEAGUES.forEach { league ->
                        FocusChip(
                            text = PlayerText.leagueName(res, league),
                            requester = subChips.getValue(league),
                            tag = sportsChlTag(league),
                            down = down,
                            selected = league == ui.prefs.league,
                            last = league == League.QMJHL,
                            onFocus = onZone,
                            onClick = { onLeague(league) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        DateAndTime(clock24, wallClockMs)
    }
}

/** The date (26/500 `Fg70`) and the time (36/600), right-aligned; not focusable (§1.1). */
@Composable
private fun DateAndTime(clock24: Boolean, wallClockMs: () -> Long) {
    val now by produceState(wallClockMs()) {
        while (true) {
            delay(15_000L)
            value = wallClockMs()
        }
    }
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val date = app.thebox.ui.guide.screen.TimeFormats.format("EEE, MMM d", locale, Date(now))
    val time = SportsText.startTime(now, clock24, locale)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Line(date, GuideUi.sans(26, FontWeight.Medium, GuideUi.Fg70), Modifier.testTag(SPORTS_DATE_TAG))
        Line(time, GuideUi.sans(36, FontWeight.SemiBold), Modifier.testTag(SPORTS_TIME_TAG))
    }
}

/** A focusable league chip. Up is consumed: there is nothing above the title row (the top bar is not drawn here). */
@Composable
private fun FocusChip(
    text: String,
    requester: FocusRequester,
    tag: String,
    down: FocusRequester? = null,
    selected: Boolean = false,
    /** The last chip of the row: Right there changes nothing (the Corner and Delay chips left the page). */
    last: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusable = LocalHubFocusable.current
    Box(
        Modifier
            .testTag(tag)
            .focusRequester(requester)
            .focusProperties { canFocus = focusable; if (down != null) this.down = down }
            .onPreviewKeyEvent { e -> e.key == Key.DirectionUp || (last && e.key == Key.DirectionRight) }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Chip(
            focused = focused,
            modifier = Modifier.height(CHIP_H.dp),
            fill = if (selected) Color(0xFF2F6FD0) else GuideUi.White08,
            border = if (selected) GuideUi.White16 else null,
        ) { color -> Line(text, GuideUi.sans(22, FontWeight.SemiBold, if (selected && !focused) Color.White else color)) }
    }
}

/** "NHL SCORES & SCHEDULE" (24/700, 0.04 em, `Fg70`) and, for the NFL, the board's week (§1.1). */
@Composable
private fun Heading(ui: SportsUiState) {
    val res = LocalResources.current
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Line(
            res.getString(R.string.sports_hub_heading, PlayerText.leagueName(res, ui.prefs.league)),
            GuideUi.sans(24, FontWeight.Bold, GuideUi.Fg70, letterSpacingEm = 0.04f), Modifier.testTag(SPORTS_HEADING_TAG),
        )
        // NFL §7.2: the board's week ("Week 9"), once a board is shown.
        ui.boardKey?.let { key -> Line(SportsText.weekLabel(res, key), GuideUi.sans(22, color = GuideUi.Fg55), Modifier.testTag(SPORTS_WEEK_TAG)) }
    }
}

/** The footer (§1.1): the hints (up to the credit's width), then the credit and, while a re-poll fails, "Updated 2 min ago". */
@Composable
private fun Footer(ui: SportsUiState, hints: List<FooterHint>, updatedMono: Long?) {
    val res = LocalResources.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val credit = if (ui.prefs.league.group == LeagueGroup.NHL) res.getString(R.string.sports_hub_credit_nhl) else SportsText.credit(res, ui.prefs.league)
    val creditW = with(density) { measurer.measure(credit, GuideUi.sans(22, color = GuideUi.Fg55), maxLines = 1, softWrap = false).size.width.toDp() }
    // (U-S9) 1728 minus the credit and a 32 px gap, so the hints never run into it.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        FooterHints(hints, textPx = 24, maxWidth = (1728.dp - creditW - 32.dp).coerceAtLeast(0.dp))
        Spacer(Modifier.weight(1f))
        if (updatedMono != null) {
            UpdatedText(updatedMono)
            Spacer(Modifier.width(24.dp))
        }
        Line(credit, GuideUi.sans(22, color = GuideUi.Fg55))
    }
}

/**
 * "Updated 2 min ago" while a board is shown and its re-poll fails (§5.4). Its own minute-grained tick runs only while it
 * shows (the cards no longer tick: owner ruling 6).
 */
@Composable
private fun UpdatedText(updatedMono: Long) {
    val now = produceState(android.os.SystemClock.elapsedRealtime()) {
        while (true) {
            delay(15_000L)
            value = android.os.SystemClock.elapsedRealtime()
        }
    }
    val minutes = SportsViewModel.minutesSince(updatedMono, now.value)
    val text = if (minutes <= 0) stringResource(R.string.sports_updated_now) else pluralStringResource(R.plurals.sports_updated_min_ago, minutes, minutes)
    Line(text, GuideUi.sans(22, color = GuideUi.Warn), Modifier.testTag(SPORTS_UPDATED_TAG))
}

// ------------------------------------------------------------------ games

@Composable
private fun GamesGrid(
    cards: List<GameCardModel>,
    state: LazyGridState,
    clock24: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    dayChip: FocusRequester,
    panel: HubPanelModel,
    panelFocused: Boolean,
    showHighlights: Boolean,
    focusedId: String?,
    onFocus: (id: String, focused: Boolean, index: Int) -> Unit,
    onEnter: (String) -> Unit,
) {
    val res = LocalResources.current
    // (Review L9) One measurer for the whole grid (was two per card).
    val measure = rememberHubMeasure()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().testTag(SPORTS_GRID_TAG),
    ) {
        itemsIndexed(cards, key = { _, c -> c.game.id.id }) { index, card ->
            val id = card.game.id.id
            // Shared map (focus is restored by game id), but created under remember so it is not rebuilt per composition.
            val requester = remember(id) { requesters.getOrPut(id) { FocusRequester() } }
            val options = if (focusedId == id && panel.game?.id == card.game.id) {
                val labels = panel.slots.filter { it.usable }.joinToString(", ") { slotText(res, it) }
                if (labels.isEmpty()) null else res.getString(R.string.sports_hub_desc_press_ok, labels)
            } else null
            HubCard(
                card = card,
                clock24 = clock24,
                selected = panelFocused && panel.game?.id == card.game.id,
                leftColumn = index % 2 == 0,
                showHighlights = showHighlights,
                optionsText = options,
                onEnter = { onEnter(id) },
                onFocusChange = { onFocus(id, it, index) },
                focusRequester = requester,
                measure = measure,
                // Up from the first row goes to the selected day chip, whichever column it is in (§2.2).
                modifier = Modifier.focusProperties { if (index < 2) up = dayChip },
            )
        }
    }
}

/** Keeps the focused row at least [margin] px inside the viewport while it scrolls (§1.1). */
private suspend fun LazyGridState.ensureVisible(index: Int, margin: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val m = margin.toFloat()
    val top = item.offset.y
    val bottom = top + item.size.height
    val vTop = info.viewportStartOffset
    val vBottom = info.viewportEndOffset
    val delta = when {
        top < vTop + m -> top - (vTop + m)
        bottom > vBottom - m -> bottom - (vBottom - m)
        else -> 0f
    }
    if (delta != 0f) animateScrollBy(delta)
}

@Composable
private fun Skeletons() {
    val res = LocalResources.current
    val loading = res.getString(R.string.sports_loading)
    Column(
        Modifier.fillMaxSize().testTag(SPORTS_SKELETON_TAG).semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { repeat(2) { SkeletonCard() } }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { repeat(2) { SkeletonCard() } }
    }
}

@Composable
private fun SkeletonCard() {
    Box(Modifier.width(HubCardMetrics.CARD_W.dp).height(HubCardMetrics.CARD_H.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp)))
}

/** An empty or failed day: the reason and the next step (§4.5). Retry shows only for a failure. */
@Composable
private fun Message(text: String, retry: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(text, style = GuideUi.sans(28, FontWeight.Medium, GuideUi.Fg70), modifier = Modifier.width(900.dp).testTag(SPORTS_MESSAGE_TAG))
        if (retry) {
            var focused by remember { mutableStateOf(false) }
            val focusable = LocalHubFocusable.current
            Box(
                Modifier
                    .testTag(SPORTS_RETRY_TAG)
                    .focusProperties { canFocus = focusable }
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRetry),
            ) {
                Chip(focused = focused, modifier = Modifier.height(52.dp), fill = GuideUi.White12, border = GuideUi.White16) { color ->
                    Line(stringResource(R.string.sports_retry), GuideUi.sans(24, FontWeight.SemiBold, color))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sheets and the toast

/** Composes the sheet for [sheet] through WP-5's frozen signatures (§2.5, §6.4). */
@Composable
private fun SheetHost(
    sheet: HubSheet,
    vm: SportsViewModel,
    panel: HubPanelModel,
    clipInfo: Map<Long, app.thebox.core.sports.hub.ClipInfo>,
    focusVideoId: Long?,
    clock24: Boolean,
    onPressed: (app.thebox.core.sports.hub.ClipRef) -> Unit,
    onClose: () -> Unit,
) {
    val game = panel.game?.takeIf { it.id == sheet.gameId } ?: return
    // (Review M1) The sheet keeps the last detail of its game while the panel's detail is briefly absent (the detail loop
    // restarting): an open sheet never stops drawing with focus inside it.
    val kept = remember { arrayOfNulls<app.thebox.core.sports.hub.GameDetail>(1) }
    val d = (panel.detail?.also { kept[0] = it } ?: kept[0])?.takeIf { it.id == sheet.gameId }
    when (sheet) {
        is HubSheet.Goals -> if (d != null) GoalsSheet(
            game = game, detail = d, clipInfo = clipInfo, catchUp = panel.candidates.filter { it.airing != null }, focusVideoId = focusVideoId,
            onPlay = { q, i -> q.getOrNull(i)?.let(onPressed); vm.playClips(q, i) }, onPlayArchive = vm::playArchive, onMissingClip = vm::missingClip, onClose = onClose, modifier = Modifier.fillMaxSize(),
        )
        is HubSheet.Channels -> ChannelSheet(
            game = game, candidates = if (sheet.archiveOnly) panel.candidates.filter { it.airing != null } else panel.candidates,
            tunable = game.phase == GamePhase.LIVE || game.phase == GamePhase.INTERMISSION ||
                game.startUtcMs - System.currentTimeMillis() <= app.thebox.core.sports.SportsConstants.HUB_IMMINENT_MIN * 60_000L,
            archiveOnly = sheet.archiveOnly, clock24 = clock24, onTune = vm::tune, onPlayArchive = vm::playArchive, onNotYet = vm::channelNotYet,
            onClose = onClose, modifier = Modifier.fillMaxSize(),
        )
        is HubSheet.BoxScoreSheet -> BoxScoreSheet(game = game, box = sheet.box, onRetry = vm::retryBoxScore, onClose = onClose, modifier = Modifier.fillMaxSize())
        is HubSheet.Matchup -> if (d != null) MatchupSheet(game = game, detail = d, onClose = onClose, modifier = Modifier.fillMaxSize())
    }
}

/** The page's toast (§6.8, §5.5): a reason and a next step, for four seconds, over the footer. */
@Composable
private fun ToastBar(t: HubToast, clock24: Boolean, onShown: () -> Unit) {
    val res = LocalResources.current
    val text = when (t.kind) {
        HubToastKind.VIDEO_UNAVAILABLE -> res.getString(R.string.sports_hub_error_video_unavailable)
        HubToastKind.VIDEO_OFFLINE -> res.getString(R.string.sports_hub_error_video_offline)
        HubToastKind.VIDEO_REFUSED -> res.getString(R.string.sports_hub_error_video_refused)
        HubToastKind.VIDEO_GEO -> res.getString(R.string.sports_hub_error_video_geo)
        HubToastKind.VIDEO_REMOVED -> res.getString(R.string.sports_hub_error_video_removed)
        HubToastKind.VIDEO_NOT_PLAYABLE -> res.getString(R.string.sports_hub_error_video_not_playable)
        HubToastKind.GOAL_NO_VIDEO -> res.getString(R.string.sports_hub_goal_no_video_toast)
        HubToastKind.STARTS_AT -> res.getString(R.string.sports_hub_starts_at_toast, SportsText.startTime(t.startUtcMs ?: 0L, clock24))
    }
    LaunchedEffect(t.seq) {
        delay(4_000L)
        onShown()
    }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.background(GuideUi.OverlayFill, RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 16.dp).testTag(SPORTS_TOAST_TAG),
        ) { Text(text, style = GuideUi.sans(24, FontWeight.Medium), modifier = Modifier.widthIn(max = 1100.dp)) }
    }
}

/** (CHL §1.1) The sub-switch's leagues. */
private val CHL_LEAGUES: List<League> = app.thebox.core.sports.CHL_LEAGUES
