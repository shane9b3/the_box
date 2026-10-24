package app.thebox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.Pane
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.state.SettingsShellLink
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.TimeFormats
import app.thebox.ui.theme.BoxTextStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import java.util.Date
import java.util.Locale

/** The opaque settings layer (covers the hole while SETTINGS shows, §3.1). */
const val SETTINGS_SCREEN_TAG = "settings_screen"
const val SETTINGS_SECTIONS_TAG = "settings_sections"
const val SETTINGS_PAGE_TAG = "settings_page"
const val SETTINGS_BREADCRUMB_TAG = "settings_breadcrumb"
const val SETTINGS_NOTICE_TAG = "settings_notice"
const val SETTINGS_PAGE_PLACEHOLDER_TAG = "settings_page_placeholder"
/** U-S7: the page subtitle (its bounds are checked against the first row's). */
const val SETTINGS_SUBTITLE_TAG = "settings_subtitle"

/**
 * Mode SETTINGS (docs/design/m5-setup-settings.md §3.1, §3.2): the two-pane shell drawn from [SettingsMachine.ui]. The
 * machine owns every key (the one router → guide machine → settings machine path); this composable only renders, except
 * the text editor, which owns Compose focus while it is open (S3) and reports back through
 * [SettingsEvent.EditorClosed]. Every overlay is in-tree (no Popup/Dialog), inside the root key sink.
 */
@Composable
fun SettingsScreen(
    machine: SettingsMachine,
    clock24: Boolean,
    modifier: Modifier = Modifier,
    /** (M5 integration) WP-D's phone page for `SettingsRoute.PhoneSetup` (the activity supplies its view model). */
    /** (save-location integration, S6-Q1) The second argument is `SettingsRoute.PhoneSetup.browse` (NETWORK_FOLDER). */
    phone: (@Composable (app.thebox.core.settings.PhonePurpose, Long?) -> Unit)? = null,
    /**
     * (m6-channel-editor §2.0) The channel editor for `SettingsRoute.ChannelEditor` (the activity supplies its view
     * model). Drawn inside [SettingsContent], after the header and before the text editor and number pad overlays.
     */
    channelEditor: (@Composable (SettingsRoute.ChannelEditor) -> Unit)? = null,
) {
    val ui by machine.ui.collectAsState()
    val link = machine as? SettingsShellLink
    val route = ui.route
    // (M5 integration) Full-screen pages other WPs draw: WP-D's phone page, WP-B's Welcome cards (first run).
    if (route is SettingsRoute.PhoneSetup && phone != null) {
        phone(route.purpose, route.browse)
        return
    }
    val page = ui.page
    // U-S1 measurement item (code review 2026-10-16, §6.7): an async trace section `settings-open` from the screen's
    // first composition (the frame after the Settings key) to the first frame that draws a real (non-placeholder) page;
    // `SettingsOpenBenchmark` reads it with `TraceSectionMetric`. One section per visit; no layer, nothing allocated per draw.
    val openTrace = remember { OpenTrace().also { it.begin() } }
    DisposableEffect(openTrace) { onDispose { openTrace.end() } }
    val traced = if (openTrace.open && page != null && page.placeholder == null) {
        Modifier.drawWithContent { drawContent(); openTrace.end() }
    } else Modifier
    if (route == SettingsRoute.FirstRun && page != null && page.route == SettingsRoute.FirstRun && page.placeholder == null) {
        Box(modifier.then(traced).fillMaxSize().testTag(SETTINGS_SCREEN_TAG)) {
            app.thebox.ui.setup.WelcomeScreen(page, ui.cursor)
            ui.notice?.let { Line(it, SettingsUi.Notice, Modifier.offset(64.dp, 960.dp).testTag(SETTINGS_NOTICE_TAG)) }
            ui.confirm?.let { ConfirmCard(it, Modifier.fillMaxSize()) }
        }
        return
    }
    SettingsContent(
        ui = ui,
        clock24 = clock24,
        modifier = modifier.then(traced),
        channelEditor = channelEditor,
        editor = { e ->
            TextEditorOverlay(
                editor = e,
                onDone = { text -> machine.dispatch(SettingsEvent.EditorClosed(e.row, text, committed = true)) },
                onCancel = { machine.dispatch(SettingsEvent.EditorClosed(e.row, null, committed = false)) },
                editorBacks = remember(link) { link?.editorBacks ?: emptyFlow() },
                // U-L8 (code review 2026-10-16): inside the first-run forms the phone import keeps the first-run review.
                onPhoneSetup = { link?.push(SettingsRoute.PhoneSetup(firstRun = firstRunOf(machine.ui.value.route))) },
            )
        },
    )
}

/** [SettingsScreen] for a given state (tests and previews); [editor] draws the editor overlay. */
@Composable
fun SettingsContent(
    ui: SettingsUiState,
    clock24: Boolean,
    modifier: Modifier = Modifier,
    editor: @Composable (app.thebox.core.settings.EditorState) -> Unit = {},
    /** (m6-channel-editor §2.0) The full-canvas channel editor page; replaces the sections and page panes. */
    channelEditor: (@Composable (SettingsRoute.ChannelEditor) -> Unit)? = null,
) {
    val solo = ui.sections.isEmpty()
    val section: SectionId? = SettingsTree.sectionOf(ui.route)
    val list = rememberLazyListState()
    val route = ui.route
    // U-S10 (save-location review 2026-10-24): the titles of the pages this visit has shown, for the breadcrumb's middle.
    val titles = remember { androidx.compose.runtime.mutableStateMapOf<SettingsRoute, String>() }
    ui.page?.let { p -> androidx.compose.runtime.SideEffect { if (p.title.isNotBlank()) titles[p.route] = p.title } }
    Box(modifier.fillMaxSize().background(SettingsUi.Canvas).testTag(SETTINGS_SCREEN_TAG)) {
        Header(ui, section, clock24, titles)
        if (route is SettingsRoute.ChannelEditor && channelEditor != null) {
            // The editor draws its own panes, notice and footer below the header; the settings machine still owns the
            // text editor, the number pad and the PIN prompt it opens for the editor (SettingsShellLink seams, §3.2).
            channelEditor(route)
            ui.confirm?.let { ConfirmCard(it, Modifier.fillMaxSize()) }
            ui.numberPad?.let { NumberPad(it, Modifier.fillMaxSize()) }
            ui.editor?.let { editor(it) }
            return@Box
        }
        if (!solo) SectionsPane(ui, section, Modifier.offset(SettingsUi.SECTIONS_X.dp, SettingsUi.PANE_Y.dp))
        val pageX = if (solo) SettingsUi.PAGE_SOLO_X else SettingsUi.PAGE_X
        val pageW = if (solo) SettingsUi.PAGE_SOLO_W else SettingsUi.PAGE_W
        PagePane(ui, list, Modifier.offset(pageX.dp, SettingsUi.PANE_Y.dp).size(pageW.dp, SettingsUi.PANE_H.dp))
        // ---- overlays (in-tree; the machine closes them; §3.2.4 back ladder)
        ui.menu?.let { m ->
            val y = focusedRowY(list, ui.cursor, ui.page?.subtitle != null) ?: SettingsUi.rowsTop(ui.page?.subtitle != null)
            SettingsMenu(m, Modifier.offset((pageX + pageW - SettingsUi.MENU_W - 24).dp, menuTop(y, m.options.size).dp))
        }
        ui.confirm?.let { ConfirmCard(it, Modifier.fillMaxSize()) }
        ui.numberPad?.let { NumberPad(it, Modifier.fillMaxSize()) }
        ui.editor?.let { editor(it) }
        // (M5 integration) WP-C's capture overlay (§3.8.3), driven by the machine's CaptureFlow.
        ui.capture?.let { CaptureOverlay(it, ui.captureButton, Modifier.fillMaxSize(), testReport = ui.captureReport, testMode = ui.captureTest) }
    }
}

/** U-S10: the breadcrumb's width (1,920 − 2 × 64). */
private const val BREADCRUMB_W = 1792

/**
 * U-S10 (save-location review 2026-10-24): "Settings › <section> › <each page on the path> › <this page>". The middle comes
 * from the titles of the pages on [stack] that this visit has shown ([titles]); the root, section pages, the first-run
 * page and the full-screen phone page are not parts. With the stack two deep this is M5's three-part form.
 */
internal fun breadcrumbParts(settings: String, section: String?, stack: List<SettingsRoute>, titles: Map<SettingsRoute, String>, title: String?): List<String> {
    val parts = ArrayList<String>()
    parts += settings
    section?.let { parts += it }
    for (r in stack) {
        if (r == SettingsRoute.Root || r is SettingsRoute.Section || r == SettingsRoute.FirstRun || r is SettingsRoute.PhoneSetup) continue
        val t = titles[r]?.takeIf { it.isNotBlank() && it != section } ?: continue
        if (parts.last() != t) parts += t
    }
    if (title != null && title.isNotBlank() && title != section && parts.last() != title) parts += title
    return parts
}

/** The focused row's top inside the page pane (design px), when it is laid out; anchors the context menu. */
private fun focusedRowY(list: LazyListState, cursor: Int, subtitle: Boolean): Int? {
    val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == cursor } ?: return null
    return SettingsUi.rowsTop(subtitle) + info.offset // U-S7
}

@Composable
private fun Header(ui: SettingsUiState, section: SectionId?, clock24: Boolean, titles: Map<SettingsRoute, String>) {
    Line(stringResource(R.string.settings_ui_title), SettingsUi.Title, Modifier.offset(SettingsUi.HEADER_X.dp, SettingsUi.HEADER_Y.dp))
    val parts = breadcrumbParts(
        stringResource(R.string.settings_ui_title), section?.let { stringResource(SettingsUi.sectionLabel(it)) }, ui.stack, titles, ui.page?.title,
    )
    // U-S10: elided in the middle when it does not fit the 1,792 px header (the section and the current page stay).
    androidx.tv.material3.Text(
        parts.joinToString(stringResource(R.string.settings_ui_breadcrumb_sep)),
        style = SettingsUi.Breadcrumb,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
        modifier = Modifier.offset(SettingsUi.HEADER_X.dp, SettingsUi.BREADCRUMB_Y.dp).width(BREADCRUMB_W.dp).testTag(SETTINGS_BREADCRUMB_TAG),
    )
    Box(Modifier.fillMaxWidth().padding(end = SettingsUi.CLOCK_RIGHT.dp, top = SettingsUi.HEADER_Y.dp), contentAlignment = Alignment.TopEnd) {
        SettingsClock(clock24)
    }
}

@Composable
private fun SettingsClock(clock24: Boolean) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L)
        }
    }
    val locale: Locale = LocalConfiguration.current.locales[0]
    Line(TimeFormats.format(if (clock24) "HH:mm" else "h:mm a", locale, Date(now)), BoxTextStyles.Clock.copy(color = SettingsUi.Fg))
}

@Composable
private fun SectionsPane(ui: SettingsUiState, current: SectionId?, modifier: Modifier) {
    val shape = RoundedCornerShape(SettingsUi.PANE_RADIUS.dp)
    Column(
        modifier
            .size(SettingsUi.SECTIONS_W.dp, SettingsUi.PANE_H.dp)
            .testTag(SETTINGS_SECTIONS_TAG)
            .background(SettingsUi.GlassFill, shape)
            .border(1.dp, SettingsUi.GlassBorder, shape)
            .padding(SettingsUi.SECTIONS_PADDING.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsUi.SECTION_ROW_GAP.dp)) {
            ui.sections.forEachIndexed { i, id ->
                val focused = ui.pane == Pane.SECTIONS && ui.sectionCursor == i && !overlayOpen(ui)
                SectionRow(stringResource(SettingsUi.sectionLabel(id)), focused, current = !focused && id == current, Modifier.testTag(SETTINGS_SECTION_TAG + i))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SettingsUi.GlassBorder))
        Spacer(Modifier.height(SettingsUi.SECTION_ROW_GAP.dp))
        val focused = ui.pane == Pane.SECTIONS && ui.sectionCursor == ui.sections.size && !overlayOpen(ui)
        val rs = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
        var m = Modifier.fillMaxWidth().height(SettingsUi.SECTION_ROW_H.dp).testTag(SETTINGS_ADVANCED_TAG).semantics { selected = focused }
        if (focused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, rs).background(SettingsUi.Fg, rs)
        Row(m.padding(start = SettingsUi.SECTION_LABEL_X.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Line(stringResource(R.string.settings_ui_show_advanced), SettingsUi.AdvancedLabel.copy(color = if (focused) SettingsUi.Canvas else SettingsUi.Fg85), Modifier.weight(1f))
            TogglePill(ui.advanced, onLight = focused) // U-S9
        }
    }
}

@Composable
private fun SectionRow(label: String, focused: Boolean, current: Boolean, modifier: Modifier) {
    val rs = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
    var m = modifier.fillMaxWidth().height(SettingsUi.SECTION_ROW_H.dp).semantics { selected = focused }
    m = when {
        focused -> m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, rs).background(SettingsUi.Fg, rs)
        current -> m.background(SettingsUi.White08, rs)
        else -> m
    }
    Box(m, contentAlignment = Alignment.CenterStart) {
        if (current) Box(Modifier.offset(x = 8.dp).size(4.dp, 32.dp).background(SettingsUi.Accent, RoundedCornerShape(2.dp)))
        Line(
            label,
            SettingsUi.SectionLabel.copy(color = if (focused) SettingsUi.Canvas else if (current) SettingsUi.Fg else SettingsUi.Fg85),
            Modifier.padding(start = SettingsUi.SECTION_LABEL_X.dp),
        )
    }
}

private fun overlayOpen(ui: SettingsUiState) =
    ui.menu != null || ui.confirm != null || ui.editor != null || ui.numberPad != null || ui.capture != null

@Composable
private fun PagePane(ui: SettingsUiState, list: LazyListState, modifier: Modifier) {
    val shape = RoundedCornerShape(SettingsUi.PANE_RADIUS.dp)
    // Owner finding 2026-10-18: rows used to stop 64 px above the card's edge (notice band + padding), so a cut row
    // sat over empty space. The list now runs to the card's bottom edge (clipped by the card's shape) and the notice,
    // when there is one, overlays the bottom on the card's own fill.
    Box(
        modifier
            .testTag(SETTINGS_PAGE_TAG)
            .background(SettingsUi.GlassFill, shape)
            .border(1.dp, SettingsUi.GlassBorder, shape)
            .clip(shape)
            .padding(start = SettingsUi.PAGE_PAD_H.dp, end = SettingsUi.PAGE_PAD_H.dp, top = SettingsUi.PAGE_PAD_V.dp),
    ) {
        val page = ui.page ?: return@Box
        Column {
            Line(page.title, SettingsUi.PageTitle)
            page.subtitle?.let { Line(it, SettingsUi.PageSubtitle, Modifier.padding(top = 6.dp).testTag(SETTINGS_SUBTITLE_TAG)) }
        }
        // U-S7: a page with a subtitle starts its rows below it (every page, not only the storage ones).
        val rowsTop = (SettingsUi.rowsTop(page.subtitle != null) - SettingsUi.PAGE_PAD_V).dp
        if (page.placeholder != null) {
            Line(page.placeholder!!, SettingsUi.RowValue.copy(color = SettingsUi.Fg60), Modifier.padding(top = rowsTop).testTag(SETTINGS_PAGE_PLACEHOLDER_TAG), maxLines = 3)
        } else {
            Rows(ui, page, list, Modifier.padding(top = rowsTop))
        }
        ui.notice?.let {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(SettingsUi.NOTICE_H.dp).background(SettingsUi.CardFill),
                contentAlignment = Alignment.CenterStart,
            ) { Line(it, SettingsUi.Notice, Modifier.testTag(SETTINGS_NOTICE_TAG)) }
        }
    }
}

/** The combined index space of a page (§3.2.1): fixed [PageModel.rows] first, then the paged list. */
fun rowCount(page: PageModel): Int = page.rows.size + (page.list?.count ?: 0)

/** Row [i] of [page], or null when it is an unloaded list row (drawn as a placeholder). */
fun rowAt(page: PageModel, i: Int): SettingsRow? {
    if (i < page.rows.size) return page.rows.getOrNull(i)
    val l = page.list ?: return null
    val j = i - page.rows.size
    if (j < 0 || j >= l.count) return null
    return l.loaded[j / l.pageSize]?.getOrNull(j % l.pageSize)
}

/** Validates loaded identities only; unloaded lists can contain millions of rows. */
internal class SettingsRowKeys(private val page: PageModel) {
    private val duplicates = HashSet<Int>()

    init {
        val seen = HashSet<String>()
        fun accept(index: Int, row: SettingsRow) {
            if (!seen.add(row.id.key)) duplicates += index
        }
        page.rows.forEachIndexed(::accept)
        page.list?.let { list ->
            list.loaded.toSortedMap().forEach { (pageIndex, rows) ->
                if (pageIndex >= 0) rows.take(list.pageSize).forEachIndexed { offset, row ->
                    val index = pageIndex.toLong() * list.pageSize + offset
                    if (index in 0L until list.count.toLong()) accept(page.rows.size + index.toInt(), row)
                }
            }
        }
    }

    fun keyAt(index: Int): String = rowAt(page, index)?.takeUnless { index in duplicates }
        ?.let { "r:${it.id.key}" } ?: "i:$index"
}

@Composable
private fun Rows(ui: SettingsUiState, page: PageModel, list: LazyListState, modifier: Modifier) {
    val count = rowCount(page)
    // Keys: the RowId (rows rebuild without recomposing unchanged ones, §5 item 2); a duplicate id or an unloaded row
    // falls back to its index so a bad page can never crash the LazyColumn.
    val keys = remember(page) { SettingsRowKeys(page) }
    LaunchedEffect(page.route, ui.top, ui.cursor, ui.choice, count) {
        if (count > 0) revealCursor(list, ui.top.coerceIn(0, count - 1), ui.cursor.coerceIn(0, count - 1))
    }
    val pageFocus = ui.pane == Pane.PAGE && !overlayOpen(ui)
    // (M5 integration) Move mode shows the lifted row at its target index; the rows between shift by one (§3.2.2).
    val move = ui.move
    val shown: (Int) -> Int = if (move == null || move.index == move.fromIndex) { i -> i } else { i ->
        when {
            i == move.index -> move.fromIndex
            move.index > move.fromIndex && i in move.fromIndex until move.index -> i + 1
            move.index < move.fromIndex && i in (move.index + 1)..move.fromIndex -> i - 1
            else -> i
        }
    }
    LazyColumn(modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(SettingsUi.ROW_GAP.dp), userScrollEnabled = false) {
        items(count, key = { keys.keyAt(shown(it)) }) { slot ->
            val i = shown(slot)
            val row = rowAt(page, i)
            if (row == null) {
                PlaceholderRow(i)
            } else {
                SettingsRowView(
                    row = row,
                    focused = pageFocus && ui.cursor == slot,
                    choice = ui.choice?.takeIf { pageFocus && it.row == row.id },
                    moving = ui.move?.row == row.id,
                )
            }
        }
    }
}

/**
 * Scrolls the page so [top] is the first row and the [cursor] row is wholly inside the viewport (device finding
 * 2026-10-17). The machine keeps `top` by a fixed budget of 8 rows (`SettingsMachineImpl.VISIBLE_ROWS`) of 76 px; rows
 * with a second line are 104 px and an open option list adds its options, so the focused row could end below the pane,
 * cut off or not composed at all. When it does not fit under [top], the list scrolls on by whole rows until it does, so
 * the first row stays whole as the machine's model says. At most [cursor] − [top] remeasures, only on a cursor move.
 */
internal suspend fun revealCursor(list: LazyListState, top: Int, cursor: Int) {
    list.scrollToItem(top)
    var first = top
    while (first < cursor) {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == cursor }
        if (item != null && item.offset + item.size <= info.viewportEndOffset) return
        first++
        list.scrollToItem(first)
    }
}

/** U-L8 (code review 2026-10-16): whether [route] is part of the first-run flow (its phone setup keeps the review). */
internal fun firstRunOf(route: SettingsRoute): Boolean = when (route) {
    SettingsRoute.FirstRun -> true
    is SettingsRoute.AddSource -> route.firstRun
    is SettingsRoute.PhoneSetup -> route.firstRun
    is SettingsRoute.ImportProgress -> route.firstRun
    else -> false
}

/**
 * U-L9 (code review 2026-10-16): the context menu's top (design px) for a focused row at [rowY] within the pane,
 * clamped so a menu of [options] rows opened low on the page stays inside the pane (it ran past y 1080).
 */
internal fun menuTop(rowY: Int, options: Int): Int {
    val height = options * SettingsUi.MENU_ROW_H + 2 * MENU_PAD
    return minOf(SettingsUi.PANE_Y + rowY, SettingsUi.PANE_Y + SettingsUi.PANE_H - height).coerceAtLeast(SettingsUi.PANE_Y)
}

private const val MENU_PAD = 12

/** U-S1 measurement item: the trace section name `SettingsOpenBenchmark` measures. */
const val TRACE_SETTINGS_OPEN = "settings-open"

/** The open → first real page async trace of one Settings visit (main thread). */
private class OpenTrace {
    var open = false
        private set
    private val cookie = nextCookie++

    fun begin() {
        open = true
        if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.beginAsyncSection(TRACE_SETTINGS_OPEN, cookie)
    }

    fun end() {
        if (!open) return
        open = false
        if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.endAsyncSection(TRACE_SETTINGS_OPEN, cookie)
    }

    private companion object { var nextCookie = 1 }
}
