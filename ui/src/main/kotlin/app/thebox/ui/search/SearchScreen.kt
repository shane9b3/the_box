package app.thebox.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.SearchResults
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTextStyles
import app.thebox.ui.theme.DesignTokens
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The S9 Search screen (docs/design/m3-guide.md §3.10.3; review S15). It is the one guide screen on real Compose focus
 * (the IME needs it): the field takes focus on entry, DOWN moves into the result rows. OK on a channel tunes it; on a
 * programme it tunes (on now), plays catch-up (in archive) or toggles the reminder (future). Long OK opens a small menu
 * (Remind/Cancel, Add/Remove favourite). BACK goes results → field → guide; the screen leaves SEARCH only through
 * [SearchEffect.Close] on [SearchViewModel.effects], which the host forwards to the state machine (B7).
 *
 * Sizes are design px (`.dp` inside `BoxDesignScale`, 1 dp = 1 design px).
 */
const val SEARCH_FIELD_TAG = "search_field"
const val SEARCH_MENU_TAG = "search_menu"
internal const val SEARCH_CARD_TAG = "search_card:"

@Composable
fun SearchScreen(viewModel: SearchViewModel, modifier: Modifier = Modifier, onIdleChanged: (Boolean) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val field = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var menuResults by remember { mutableStateOf<SearchResults?>(null) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    fun dismissMenu() { menu = null; menuResults = null }
    fun openMenu(target: MenuTarget, results: SearchResults, focus: FocusRequester) {
        if (viewModel.isCurrent(results)) { returnFocus = focus; menuResults = results; menu = target }
    }
    DisposableEffect(viewModel) {
        viewModel.onEntered()
        onDispose { viewModel.onLeft() }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    LaunchedEffect(menu) {
        if (menu == null && returnFocus != null) {
            val restored = runCatching { returnFocus!!.requestFocus() }.getOrDefault(false)
            if (!restored) runCatching { field.requestFocus() }
            returnFocus = null
        }
    }
    LaunchedEffect(state.results) {
        if (menu != null && (menuResults == null || !viewModel.isCurrent(menuResults!!))) dismissMenu()
    }
    // U1 (code review 2026-11-09): new results can remove the focused card; Compose then moves focus to the root key sink,
    // where the screen never sees BACK. Give it to the field instead, a frame later (after the removal has settled).
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.results) {
        withFrameNanos { }
        if (!hasFocus && menu == null && viewModel.isVisiting) runCatching { field.requestFocus() }
    }
    // §2.13 item 7: the root key sink may take BACK only while the field is empty and no menu is open (B1). The IME
    // consumes BACK while it shows, so an empty field that still gets BACK means "leave Search".
    val idle = state.query.isEmpty() && menu == null
    LaunchedEffect(idle) { onIdleChanged(idle) }

    Box(
        modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.onPreviewKeyEvent { e ->
            if (e.key != Key.Back && e.key != Key.Escape && e.key != Key.ButtonB) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp && !e.nativeKeyEvent.isCanceled) {
                when {
                    menu != null -> dismissMenu()
                    !fieldFocused -> runCatching { field.requestFocus() }
                    else -> viewModel.onBack()
                }
            }
            true
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 96.dp, end = 96.dp, top = 56.dp, bottom = 56.dp),
        ) {
            Tabs()
            Spacer(Modifier.height(32.dp))
            SearchField(state.query, field, onFocus = { fieldFocused = it }, onChange = viewModel::onQuery)
            Spacer(Modifier.height(40.dp))
            val results = state.results
            when {
                results == null -> Hint(stringResource(if (state.searching) R.string.search_searching else R.string.search_min_chars))
                results.channels.isEmpty() && results.programmes.isEmpty() && !state.guideDataMissing -> {
                    Hint(stringResource(R.string.search_nothing_found, results.query))
                    Hint(stringResource(R.string.search_nothing_next))
                }
                else -> {
                    if (results.channels.isNotEmpty()) {
                        RowTitle(stringResource(R.string.search_channels))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(results.channels, key = { it.rowId }) { ch ->
                                ChannelCard(ch, onClick = { if (viewModel.isCurrent(results)) viewModel.onChannel(ch, results) }, onLongClick = { focus -> openMenu(MenuTarget.Channel(ch), results, focus) })
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                    RowTitle(stringResource(R.string.search_programmes))
                    when {
                        state.guideDataMissing -> Hint(stringResource(R.string.search_needs_guide))
                        results.programmes.isEmpty() -> Hint(stringResource(R.string.search_no_programmes))
                        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(results.programmes, key = { "${it.channel.identityHash}:${it.info.rawStartMin}" }) { hit ->
                                ProgrammeCard(
                                    hit,
                                    reminder = state.reminders.contains(hit.channel.identityHash, hit.info.rawStartMin),
                                    clock24 = state.clock24,
                                    onClick = { if (viewModel.isCurrent(results)) viewModel.onProgramme(hit, results) },
                                    onLongClick = { focus -> openMenu(MenuTarget.Programme(hit), results, focus) },
                                )
                            }
                        }
                    }
                }
            }
        }
        menu?.let { target ->
            ResultMenu(
                target = target,
                hasReminder = (target as? MenuTarget.Programme)?.let { viewModel.hasReminder(it.hit) } ?: false,
                isFavourite = { ch -> viewModel.isFavourite(ch) },
                onReminder = { hit -> menuResults?.let { if (viewModel.isCurrent(it)) viewModel.toggleReminder(hit, it) }; dismissMenu() },
                onFavourite = { ch -> menuResults?.let { if (viewModel.isCurrent(it)) viewModel.toggleFavourite(ch, it) }; dismissMenu() },
                onDismiss = ::dismissMenu,
            )
        }
    }
}

private sealed interface MenuTarget {
    val channel: GuideChannel
    data class Channel(override val channel: GuideChannel) : MenuTarget
    data class Programme(val hit: ProgrammeHit) : MenuTarget { override val channel get() = hit.channel }
}

private val Fg = DesignTokens.Colors.Fg
private val Dim = Fg.copy(alpha = 0.6f)
private val Body = BoxTextStyles.Body.copy(color = Fg)
private val Small = BoxTextStyles.Body.copy(color = Dim, fontSize = 24.sp)

@Composable
private fun Tabs() {
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(stringResource(R.string.search_tab_guide), style = Body.copy(color = Dim))
        BasicText(stringResource(R.string.search_tab_search), style = Body.copy(color = DesignTokens.Colors.AccentText))
        BasicText("⚙", style = Body.copy(color = Dim))
    }
}

@Composable
private fun SearchField(text: String, focus: FocusRequester, onFocus: (Boolean) -> Unit, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(DesignTokens.Colors.GlassFill, RoundedCornerShape(DesignTokens.Radii.Button))
            .border(if (focused) 3.dp else 1.dp, if (focused) DesignTokens.Colors.Accent else DesignTokens.Colors.GlassBorder, RoundedCornerShape(DesignTokens.Radii.Button))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) BasicText(stringResource(R.string.search_hint), style = Body.copy(color = Dim))
        BasicTextField(
            value = text,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Body,
            cursorBrush = SolidColor(DesignTokens.Colors.Accent),
            // Device bug G5 (§5.11): the single-line field consumed D-pad Down, so the result rows were unreachable
            // with a remote. Down (and the IME's Search action) leave the field for the first result row.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) {
                        keyboard?.hide()
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                    } else {
                        false
                    }
                }
                .testTag(SEARCH_FIELD_TAG),
        )
    }
}

@Composable
private fun RowTitle(text: String) {
    BasicText(text, style = Small.copy(color = Dim, fontSize = 26.sp, letterSpacing = 1.5.sp))
}

@Composable
private fun Hint(text: String) {
    BasicText(text, style = Body.copy(color = Dim), modifier = Modifier.padding(vertical = 8.dp))
}

/** A focusable card: OK = click, long OK (key repeat or long press) = [onLongClick]. Draws its own ring (no tv-material scaling). */
@Composable
private fun ResultCard(tag: String, width: Int, height: Int, onClick: () -> Unit, onLongClick: (FocusRequester) -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val gesture = remember(scope, focus) { SearchOkGesture(scope, { click() }, { longClick(focus) }) }
    DisposableEffect(gesture) { onDispose { gesture.cancel() } }
    Box(
        Modifier
            .size(width.dp, height.dp)
            .testTag(SEARCH_CARD_TAG + tag)
            .focusRequester(focus)
            .onFocusChanged { if (!it.hasFocus) gesture.cancel() }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.ButtonA
                if (!ok) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        gesture.down(e.nativeKeyEvent.repeatCount > 0, e.nativeKeyEvent.isCanceled)
                        true
                    }
                    KeyEventType.KeyUp -> {
                        gesture.up(e.nativeKeyEvent.isCanceled)
                        true
                    }
                    else -> false
                }
            }
            .background(if (focused) Fg else DesignTokens.Colors.GlassFill, RoundedCornerShape(DesignTokens.Radii.Cell))
            .border(if (focused) 3.dp else 1.dp, if (focused) DesignTokens.Colors.Accent else DesignTokens.Colors.GlassBorder, RoundedCornerShape(DesignTokens.Radii.Cell))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalCardFocused provides focused) { content() }
    }
}

private val LocalCardFocused = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun cardText(): Color = if (LocalCardFocused.current) DesignTokens.Colors.Canvas else Fg

@Composable
private fun ChannelCard(ch: GuideChannel, onClick: () -> Unit, onLongClick: (FocusRequester) -> Unit) {
    ResultCard(ch.rowId.toString(), 340, 170, onClick, onLongClick) {
        val color = cardText()
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ch.logoUrl != null) AsyncImage(model = ch.logoUrl, contentDescription = null, modifier = Modifier.size(84.dp, 60.dp))
                Spacer(Modifier.width(8.dp))
                ch.number?.let { BasicText(it.toString(), style = Small.copy(color = color.copy(alpha = 0.7f))) }
            }
            BasicText(ch.displayName, style = Body.copy(color = color, fontSize = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProgrammeCard(hit: ProgrammeHit, reminder: Boolean, clock24: Boolean, onClick: () -> Unit, onLongClick: (FocusRequester) -> Unit) {
    ResultCard("${hit.channel.identityHash}:${hit.info.rawStartMin}", 520, 190, onClick, onLongClick) {
        val color = cardText()
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(hit.info.title, style = Body.copy(color = color, fontSize = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (hit.inArchive) BasicText("  ↺", style = Body.copy(color = color, fontSize = 28.sp))
                if (reminder) BasicText("  ●", style = Body.copy(color = DesignTokens.Colors.Live, fontSize = 24.sp))
            }
            BasicText(hit.channel.displayName, style = Small.copy(color = color.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The configuration's locale (observable: a locale change recomposes); lint NonObservableLocale.
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            BasicText(timeText(hit, clock24, locale), style = Small.copy(color = color.copy(alpha = 0.7f)))
        }
    }
}

/**
 * "Tue 13 Oct · 19:30 – 20:00" (or "7:30 PM – 8:00 PM" with [clock24] off, as the header clock) in the device zone; the
 * day only when it is not today. U5 (code review 2026-11-09): follows the 12/24-hour setting and the current locale.
 */
internal fun timeText(
    hit: ProgrammeHit,
    clock24: Boolean,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: Long = System.currentTimeMillis(),
): String {
    val hm = DateTimeFormatter.ofPattern(if (clock24) "HH:mm" else "h:mm a", locale)
    val s = Instant.ofEpochSecond(hit.info.startMin * 60L).atZone(zone)
    val e = Instant.ofEpochSecond(hit.info.endMin * 60L).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val day = if (s.toLocalDate() == today) "" else DateTimeFormatter.ofPattern("EEE d MMM", locale).format(s) + " · "
    return day + hm.format(s) + " – " + hm.format(e)
}

@Composable
private fun ResultMenu(
    target: MenuTarget,
    hasReminder: Boolean,
    isFavourite: suspend (GuideChannel) -> Boolean,
    onReminder: (ProgrammeHit) -> Unit,
    onFavourite: (GuideChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    var favourite by remember(target) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(target) { favourite = isFavourite(target.channel) }
    val options = buildList<Pair<String, () -> Unit>> {
        if (target is MenuTarget.Programme) add(stringResource(if (hasReminder) R.string.search_menu_cancel_reminder else R.string.search_menu_remind) to { onReminder(target.hit) })
        favourite?.let { fav -> add(stringResource(if (fav) R.string.search_menu_remove_favourite else R.string.search_menu_add_favourite) to { onFavourite(target.channel) }) }
        add(stringResource(R.string.search_menu_close) to onDismiss)
    }
    val focusers = remember(target, options.size) { List(options.size) { FocusRequester() } }
    var cursor by remember(target, options.size) { mutableStateOf(0) }
    LaunchedEffect(focusers) { runCatching { focusers[0].requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).testTag(SEARCH_MENU_TAG), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(640.dp)
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (e.type == KeyEventType.KeyDown) {
                                cursor = (cursor + if (e.key == Key.DirectionDown) 1 else -1).coerceIn(0, options.lastIndex)
                                runCatching { focusers[cursor].requestFocus() }
                            }
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight, Key.Tab -> true
                        else -> false
                    }
                }
                .background(DesignTokens.Colors.Canvas, RoundedCornerShape(DesignTokens.Radii.Panel))
                .border(1.dp, DesignTokens.Colors.GlassBorder, RoundedCornerShape(DesignTokens.Radii.Panel))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            options.forEachIndexed { i, (label, action) ->
                MenuItem(label, Modifier.focusRequester(focusers[i]).onFocusChanged { if (it.hasFocus) cursor = i }, action)
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(if (focused) Fg else Color.Transparent, RoundedCornerShape(DesignTokens.Radii.Button))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(label, style = Body.copy(color = if (focused) DesignTokens.Colors.Canvas else Fg))
    }
}
