package app.thebox.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thebox.core.guide.FooterHint
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsConstants
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.FooterHints
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.full.PlayerText
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SPORTS_SCREEN_TAG = "sports_screen"
const val SPORTS_CORNER_TAG = "sports_corner"
const val SPORTS_DELAY_TAG = "sports_delay"
const val SPORTS_MENU_TAG = "sports_menu"
const val SPORTS_RETRY_TAG = "sports_retry"
const val SPORTS_MESSAGE_TAG = "sports_message"
const val SPORTS_UPDATED_TAG = "sports_updated"
const val SPORTS_SKELETON_TAG = "sports_skeleton"
const val SPORTS_GRID_TAG = "sports_grid"
const val SPORTS_WEEK_TAG = "sports_week"
const val SPORTS_LEAGUE_PILL_TAG = "sports_league_pill"
internal fun sportsLeagueTag(league: League) = "sports_league:" + league.name
internal fun sportsDayTag(day: SportsDay) = "sports_day:" + day.name
internal fun sportsMenuItemTag(i: Int) = "sports_menu_item:$i"

private enum class ChipMenu { CORNER, DELAY }

/** Design px (1 dp = 1 design px under the root's scale). */
private const val GRID_BOTTOM_GAP = 16
/** Corner / Delay chip height and the day chips' height (design px; owner request 2026-11-06). */
private const val CHIP_H = 52
private const val DAY_CHIP_H = 60
private const val SCROLL_MARGIN = 64

/**
 * The Sports tab (docs/design/m6-score-bug.md §5; WP-4a). It is on Compose focus like Search: the router passes every key
 * but BACK, and one Back returns to the guide grid (the machine's `onSportsClosed`). While a choice list is open the
 * screen holds BACK ([onBackFreeChanged] false) and closes the list itself.
 *
 * Layout: title row (96, 56) with the Corner and Delay chips right-aligned to 1824; the credit under the title; the day
 * chips at y 156; the games grid from y 232 (three 564 px columns, 128 px cards, 24 px row gap) down to 16 px above the
 * footer; the footer with a dynamic OK hint from the focused card.
 *
 * Initial focus: the followed game, else the first live card, else the Today chip.
 *
 * (NFL WP-5, docs/design/m6-nfl.md §7.3) The title row carries the NHL · NFL league chips (the selected one filled); Corner
 * and Delay stay right-aligned, and Up from the day chips still lands on Corner, from where Left reaches the league chips.
 * For the NFL the day chips read Last week · This week · Next week and a neighbour the board did not name is disabled.
 */
@Composable
fun SportsScreen(
    viewModel: SportsViewModel,
    clock24: Boolean,
    modifier: Modifier = Modifier,
    onBackFreeChanged: (Boolean) -> Unit = {},
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val marginPx = with(androidx.compose.ui.platform.LocalDensity.current) { SCROLL_MARGIN.dp.roundToPx() }

    DisposableEffect(viewModel) {
        viewModel.onEntered()
        onDispose {
            viewModel.onLeft()
            onBackFreeChanged(true)
        }
    }
    val corner = remember { FocusRequester() }
    val delayChip = remember { FocusRequester() }
    val dayChips = remember { SportsDay.entries.associateWith { FocusRequester() } }
    val leagueChips = remember { League.entries.associateWith { FocusRequester() } }
    val cardRequesters = remember { HashMap<String, FocusRequester>() }
    var menu by remember { mutableStateOf<ChipMenu?>(null) }
    var settled by remember { mutableStateOf(false) }
    var focusedCardId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { runCatching { dayChips.getValue(SportsDay.TODAY).requestFocus() } }
    LaunchedEffect(menu) { onBackFreeChanged(menu == null) }
    // A league switch lists the new league from its top.
    LaunchedEffect(ui.prefs.league) { gridState.requestScrollToItem(0) }

    // Initial focus (§5.1): once, when the first board arrives. A load that shows nothing leaves the Today chip focused.
    val body = ui.body
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

    val selectedChip = dayChips.getValue(ui.day)
    val off = body is SportsBody.Off
    val cardsNow = (body as? SportsBody.Games)?.cards.orEmpty()
    val focusedCard = cardsNow.firstOrNull { it.game.id.id == focusedCardId }

    Box(
        modifier
            .fillMaxSize()
            .testTag(SPORTS_SCREEN_TAG)
            .onPreviewKeyEvent { e ->
                if (menu != null && (e.key == Key.Back || e.key == Key.Escape || e.key == Key.ButtonB)) {
                    if (e.type == KeyEventType.KeyUp) {
                        val m = menu
                        menu = null
                        scope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            runCatching { (if (m == ChipMenu.CORNER) corner else delayChip).requestFocus() }
                        }
                    }
                    true
                } else false
            },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp, top = 56.dp, bottom = 40.dp)) {
            TitleRow(ui, corner, delayChip, leagueChips, selectedChip, chips = !off, onOpen = { menu = it }, onLeague = viewModel::onLeague)
            Spacer(Modifier.height(4.dp))
            CreditRow(ui)
            Spacer(Modifier.height(24.dp))
            // U-N4: with Show Sports off there is nothing to pick: no day chips (and no Corner / Delay chips).
            // NFL §7.3 (fix pass U4): Up from the day / week chips lands on the shown league's chip (both leagues).
            if (off) Spacer(Modifier.height(DAY_CHIP_H.dp)) else DayChips(ui, dayChips, leagueChips.getValue(ui.prefs.league)) { day ->
                settled = true
                viewModel.onDay(day)
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val b = body) {
                    SportsBody.Loading -> Skeletons()
                    is SportsBody.Games -> GamesGrid(
                        b.cards, gridState, clock24, cardRequesters, selectedChip,
                        onFocus = { id, on, index ->
                            if (on) {
                                focusedCardId = id
                                scope.launch { gridState.ensureVisible(index, marginPx) }
                            } else if (focusedCardId == id) focusedCardId = null
                        },
                        onCard = viewModel::onCard,
                    )
                    is SportsBody.Empty -> Message(emptyText(b, ui), retry = false, onRetry = {})
                    is SportsBody.Failed -> Message(SportsText.failure(res, b.kind, ui.prefs.league), retry = true, onRetry = {
                        // U-S3: the Retry chip leaves with the Failed body, so focus moves to the selected day chip
                        // first, and the initial-focus rule runs again for the board that answers.
                        runCatching { selectedChip.requestFocus() }
                        settled = false
                        viewModel.onRetry()
                    })
                    SportsBody.Off -> Message(stringResource(R.string.sports_off), retry = false, onRetry = {})
                }
            }
            Spacer(Modifier.height(GRID_BOTTOM_GAP.dp))
            FooterHints(footerOf(res, focusedCard), textPx = 24)
        }
        menu?.let { m ->
            ChoiceList(
                kind = m,
                prefsCorner = ui.prefs.corner,
                prefsDelay = ui.prefs.delaySec,
                onPick = { picked ->
                    when (picked) {
                        is Pick.Corner -> viewModel.setCorner(picked.corner)
                        is Pick.Delay -> viewModel.setDelay(picked.seconds)
                    }
                    menu = null
                    scope.launch {
                        androidx.compose.runtime.withFrameNanos { }
                        runCatching { (if (m == ChipMenu.CORNER) corner else delayChip).requestFocus() }
                    }
                },
            )
        }
    }
}

@Composable
private fun emptyText(b: SportsBody.Empty, ui: SportsUiState): String = when {
    // NFL §7.2: the off-season has no neighbour either way ("No NFL games this week."); a quiet week points at the chips.
    ui.prefs.league == League.NFL -> stringResource(if (!ui.canPrev && !ui.canNext) R.string.sports_week_off else R.string.sports_empty_week)
    else -> when (b.day) {
        SportsDay.YESTERDAY -> stringResource(R.string.sports_empty_yesterday)
        SportsDay.TODAY -> stringResource(R.string.sports_empty_today)
        SportsDay.TOMORROW -> stringResource(R.string.sports_empty_tomorrow)
    }
}

/** `← → ↑ ↓ Browse · OK Follow score / OK Stop following (from the focused card) · Back Guide` (U-S9). */
private fun footerOf(res: android.content.res.Resources, focused: GameCardModel?): List<FooterHint> = buildList {
    add(FooterHint(res.getString(R.string.sports_footer_keys_browse), res.getString(R.string.sports_footer_browse)))
    if (focused != null) {
        add(FooterHint(res.getString(R.string.sports_footer_keys_ok), res.getString(if (focused.followed) R.string.sports_footer_stop else R.string.sports_footer_follow)))
    }
    add(FooterHint(res.getString(R.string.sports_footer_keys_back), res.getString(R.string.sports_footer_back)))
}

// ------------------------------------------------------------------ title row

@Composable
private fun TitleRow(
    ui: SportsUiState,
    corner: FocusRequester,
    delayChip: FocusRequester,
    leagueChips: Map<League, FocusRequester>,
    down: FocusRequester,
    chips: Boolean,
    onOpen: (ChipMenu) -> Unit,
    onLeague: (League) -> Unit,
) {
    val res = LocalResources.current
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Line(stringResource(R.string.sports_title), GuideUi.sans(44, FontWeight.SemiBold))
        Spacer(Modifier.width(16.dp))
        if (!chips) {
            // Show Sports off: nothing to pick, so the shown league reads as a static pill (fix pass U4: not always NHL).
            Box(Modifier.background(GuideUi.White08, RoundedCornerShape(DesignTokens.Radii.Chip)).padding(horizontal = 14.dp, vertical = 5.dp)) {
                Line(PlayerText.leagueName(res, ui.prefs.league), GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg70), Modifier.testTag(SPORTS_LEAGUE_PILL_TAG))
            }
        } else {
            // NFL §7.3 (ruling 24): NHL · NFL, the shown one filled, 22/600, height 52. Up is consumed; Down reaches the day chips.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                League.entries.forEach { league ->
                    FocusChip(
                        text = PlayerText.leagueName(res, league),
                        requester = leagueChips.getValue(league),
                        tag = sportsLeagueTag(league),
                        down = down,
                        selected = league == ui.prefs.league,
                        textPx = 22,
                        weight = FontWeight.SemiBold,
                        onClick = { onLeague(league) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!chips) return@Row
        FocusChip(
            text = stringResource(R.string.sports_chip_corner, SportsText.corner(res, ui.prefs.corner)),
            requester = corner,
            tag = SPORTS_CORNER_TAG,
            down = down,
            onClick = { onOpen(ChipMenu.CORNER) },
        )
        Spacer(Modifier.width(12.dp))
        FocusChip(
            text = stringResource(R.string.sports_chip_delay, SportsText.delay(res, ui.prefs.delaySec)),
            requester = delayChip,
            tag = SPORTS_DELAY_TAG,
            down = down,
            onClick = { onOpen(ChipMenu.DELAY) },
        )
    }
}

/** A focusable chip ([height] design px, text [textPx]/500). Up is consumed: there is nothing above the title row (the top bar is not drawn here). */
@Composable
private fun FocusChip(
    text: String,
    requester: FocusRequester,
    tag: String,
    down: FocusRequester? = null,
    up: FocusRequester? = null,
    selected: Boolean = false,
    height: Int = CHIP_H,
    textPx: Int = 24,
    weight: FontWeight = FontWeight.Medium,
    /** (NFL §7.2) False = a week the board did not name: dimmed, not focusable, OK does nothing. */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .testTag(tag)
            .focusRequester(requester)
            .focusProperties {
                canFocus = enabled
                if (down != null) this.down = down
                if (up != null) this.up = up
            }
            .onPreviewKeyEvent { e -> up == null && e.key == Key.DirectionUp }
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Chip(
            focused = focused,
            modifier = Modifier.height(height.dp),
            fill = if (selected) GuideUi.White12 else GuideUi.White08,
            border = if (selected) GuideUi.White16 else null,
        ) { color -> Line(text, GuideUi.sans(textPx, weight, if (enabled) color else color.copy(alpha = 0.35f))) }
    }
}

@Composable
private fun CreditRow(ui: SportsUiState) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val nfl = ui.prefs.league == League.NFL
        Line(stringResource(if (nfl) R.string.sports_credit_espn else R.string.sports_credit), GuideUi.sans(20, color = GuideUi.Fg55))
        // NFL §7.2: the board's week ("Week 9"), once a board is shown.
        ui.boardKey?.let { key -> Line(SportsText.weekLabel(LocalResources.current, key), GuideUi.sans(20, color = GuideUi.Fg55), Modifier.testTag(SPORTS_WEEK_TAG)) }
        val updated = ui.updatedMono
        if (updated != null) UpdatedText(updated)
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
    Line(text, GuideUi.sans(20, color = GuideUi.Warn), Modifier.testTag(SPORTS_UPDATED_TAG))
}

// ------------------------------------------------------------------ day chips

@Composable
private fun DayChips(ui: SportsUiState, requesters: Map<SportsDay, FocusRequester>, up: FocusRequester, onDay: (SportsDay) -> Unit) {
    val nfl = ui.prefs.league == League.NFL
    Row(Modifier.height(DAY_CHIP_H.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SportsDay.entries.forEach { day ->
            FocusChip(
                text = stringResource(
                    when (day) {
                        SportsDay.YESTERDAY -> if (nfl) R.string.sports_week_last else R.string.sports_day_yesterday
                        SportsDay.TODAY -> if (nfl) R.string.sports_week_this else R.string.sports_day_today
                        SportsDay.TOMORROW -> if (nfl) R.string.sports_week_next else R.string.sports_day_tomorrow
                    },
                ),
                requester = requesters.getValue(day),
                tag = sportsDayTag(day),
                up = up,
                selected = day == ui.day,
                height = DAY_CHIP_H,
                textPx = 26,
                enabled = when (day) {
                    SportsDay.YESTERDAY -> ui.canPrev
                    SportsDay.TODAY -> true
                    SportsDay.TOMORROW -> ui.canNext
                },
                onClick = { onDay(day) },
            )
        }
    }
}

// ------------------------------------------------------------------ games

@Composable
private fun GamesGrid(
    cards: List<GameCardModel>,
    state: LazyGridState,
    clock24: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    dayChip: FocusRequester,
    onFocus: (id: String, focused: Boolean, index: Int) -> Unit,
    onCard: (GameCardModel) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().testTag(SPORTS_GRID_TAG),
    ) {
        itemsIndexed(cards, key = { _, c -> c.game.id.id }) { index, card ->
            val id = card.game.id.id
            // Shared map (focus is restored by game id), but created under remember so it is not rebuilt per composition.
            val requester = remember(id) { requesters.getOrPut(id) { FocusRequester() } }
            GameCard(
                card = card,
                clock24 = clock24,
                onClick = { onCard(card) },
                onFocusChange = { onFocus(id, it, index) },
                focusRequester = requester,
                // Up from the first row goes to the day chips, whichever column it is in (§5.3).
                modifier = Modifier.focusProperties { if (index < 3) up = dayChip },
            )
        }
    }
}

/** Keeps the focused row at least [margin] px inside the viewport while it scrolls (§5.2). */
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
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { repeat(3) { SkeletonCard() } }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { repeat(2) { SkeletonCard() } }
    }
}

@Composable
private fun SkeletonCard() {
    Box(Modifier.width(GameCardMetrics.CARD_W.dp).height(GameCardMetrics.CARD_H.dp).background(Color05, RoundedCornerShape(DesignTokens.Radii.Button)))
}

private val Color05 = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f)

/** An empty or failed day: the reason and the next step (§5.4). Retry shows only for a failure. */
@Composable
private fun Message(text: String, retry: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(text, style = GuideUi.sans(28, FontWeight.Medium, GuideUi.Fg70), modifier = Modifier.width(1200.dp).testTag(SPORTS_MESSAGE_TAG))
        if (retry) {
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .testTag(SPORTS_RETRY_TAG)
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

// ------------------------------------------------------------------ the Corner / Delay choice list

private sealed interface Pick {
    data class Corner(val corner: ScoreCorner) : Pick
    data class Delay(val seconds: Int) : Pick
}

/**
 * The small choice list the Corner and Delay chips open (the context-menu pattern): an opaque panel under the chips,
 * focus on the current value, Up/Down within, OK picks and closes, Back closes (handled by the screen). Left and Right
 * are consumed, and Up/Down stop at the ends, so focus never reaches the screen behind.
 */
@Composable
private fun ChoiceList(kind: ChipMenu, prefsCorner: ScoreCorner, prefsDelay: Int, onPick: (Pick) -> Unit) {
    val res = LocalResources.current
    val items: List<Pair<String, Pick>> = when (kind) {
        ChipMenu.CORNER -> ScoreCorner.entries.map { SportsText.corner(res, it) to (Pick.Corner(it) as Pick) }
        ChipMenu.DELAY -> SportsConstants.DELAY_STEPS_S.map { SportsText.delay(res, it) to (Pick.Delay(it) as Pick) }
    }
    val current = when (kind) {
        ChipMenu.CORNER -> items.indexOfFirst { (it.second as Pick.Corner).corner == prefsCorner }
        ChipMenu.DELAY -> items.indexOfFirst { (it.second as Pick.Delay).seconds == prefsDelay }
    }.coerceAtLeast(0)
    val requesters = remember(kind) { List(items.size) { FocusRequester() } }
    LaunchedEffect(kind) { runCatching { requesters[current].requestFocus() } }
    val shape = RoundedCornerShape(DesignTokens.Radii.Button)
    Box(Modifier.fillMaxSize().padding(top = 128.dp, end = 96.dp), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier
                .testTag(SPORTS_MENU_TAG)
                .width(320.dp)
                .background(GuideUi.OverlayFill, shape)
                .border(1.dp, GuideUi.OverlayBorder, shape)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { i, (label, pick) ->
                var focused by remember { mutableStateOf(false) }
                val isCurrent = i == current
                Box(
                    Modifier
                        .testTag(sportsMenuItemTag(i))
                        .fillMaxWidth()
                        .focusRequester(requesters[i])
                        .onPreviewKeyEvent { e ->
                            when (e.key) {
                                Key.DirectionLeft, Key.DirectionRight -> true
                                Key.DirectionUp -> i == 0
                                Key.DirectionDown -> i == items.lastIndex
                                else -> false
                            }
                        }
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(pick) },
                ) {
                    Chip(
                        focused = focused,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(DesignTokens.Radii.Cell),
                        fill = if (isCurrent) GuideUi.White12 else androidx.compose.ui.graphics.Color.Transparent,
                    ) { color ->
                        Line(label, GuideUi.sans(24, if (isCurrent) FontWeight.SemiBold else FontWeight.Medium, color), Modifier.weight(1f))
                        if (isCurrent) Line("✓", GuideUi.sans(24, FontWeight.SemiBold, color))
                    }
                }
            }
        }
    }
}
