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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
    phone: (@Composable (app.thebox.core.settings.PhonePurpose) -> Unit)? = null,
) {
    val ui by machine.ui.collectAsState()
    val link = machine as? SettingsShellLink
    val route = ui.route
    // (M5 integration) Full-screen pages other WPs draw: WP-D's phone page, WP-B's Welcome cards (first run).
    if (route is SettingsRoute.PhoneSetup && phone != null) {
        phone(route.purpose)
        return
    }
    val page = ui.page
    if (route == SettingsRoute.FirstRun && page != null && page.route == SettingsRoute.FirstRun && page.placeholder == null) {
        Box(modifier.fillMaxSize().testTag(SETTINGS_SCREEN_TAG)) {
            app.thebox.ui.setup.WelcomeScreen(page, ui.cursor)
            ui.notice?.let { Line(it, SettingsUi.Notice, Modifier.offset(64.dp, 960.dp).testTag(SETTINGS_NOTICE_TAG)) }
            ui.confirm?.let { ConfirmCard(it, Modifier.fillMaxSize()) }
        }
        return
    }
    SettingsContent(
        ui = ui,
        clock24 = clock24,
        modifier = modifier,
        editor = { e ->
            TextEditorOverlay(
                editor = e,
                onDone = { text -> machine.dispatch(SettingsEvent.EditorClosed(e.row, text, committed = true)) },
                onCancel = { machine.dispatch(SettingsEvent.EditorClosed(e.row, null, committed = false)) },
                editorBacks = remember(link) { link?.editorBacks ?: emptyFlow() },
                onPhoneSetup = { link?.push(SettingsRoute.PhoneSetup()) },
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
) {
    val solo = ui.sections.isEmpty()
    val section: SectionId? = SettingsTree.sectionOf(ui.route)
    val list = rememberLazyListState()
    Box(modifier.fillMaxSize().background(SettingsUi.Canvas).testTag(SETTINGS_SCREEN_TAG)) {
        Header(ui, section, clock24)
        if (!solo) SectionsPane(ui, section, Modifier.offset(SettingsUi.SECTIONS_X.dp, SettingsUi.PANE_Y.dp))
        val pageX = if (solo) SettingsUi.PAGE_SOLO_X else SettingsUi.PAGE_X
        val pageW = if (solo) SettingsUi.PAGE_SOLO_W else SettingsUi.PAGE_W
        PagePane(ui, list, Modifier.offset(pageX.dp, SettingsUi.PANE_Y.dp).size(pageW.dp, SettingsUi.PANE_H.dp))
        // ---- overlays (in-tree; the machine closes them; §3.2.4 back ladder)
        ui.menu?.let { m ->
            val y = focusedRowY(list, ui.cursor) ?: SettingsUi.ROWS_TOP
            SettingsMenu(m, Modifier.offset((pageX + pageW - SettingsUi.MENU_W - 24).dp, (SettingsUi.PANE_Y + y).dp))
        }
        ui.confirm?.let { ConfirmCard(it, Modifier.fillMaxSize()) }
        ui.numberPad?.let { NumberPad(it, Modifier.fillMaxSize()) }
        ui.editor?.let { editor(it) }
        // (M5 integration) WP-C's capture overlay (§3.8.3), driven by the machine's CaptureFlow.
        ui.capture?.let { CaptureOverlay(it, ui.captureButton, Modifier.fillMaxSize(), testReport = ui.captureReport, testMode = ui.captureTest) }
    }
}

/** The focused row's top inside the page pane (design px), when it is laid out; anchors the context menu. */
private fun focusedRowY(list: LazyListState, cursor: Int): Int? {
    val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == cursor } ?: return null
    return SettingsUi.ROWS_TOP + info.offset
}

@Composable
private fun Header(ui: SettingsUiState, section: SectionId?, clock24: Boolean) {
    Line(stringResource(R.string.settings_ui_title), SettingsUi.Title, Modifier.offset(SettingsUi.HEADER_X.dp, SettingsUi.HEADER_Y.dp))
    val parts = buildList {
        add(stringResource(R.string.settings_ui_title))
        section?.let { add(stringResource(SettingsUi.sectionLabel(it))) }
        val title = ui.page?.title
        if (title != null && (section == null || title != stringResource(SettingsUi.sectionLabel(section)))) add(title)
    }
    Line(
        parts.joinToString(stringResource(R.string.settings_ui_breadcrumb_sep)),
        SettingsUi.Breadcrumb,
        Modifier.offset(SettingsUi.HEADER_X.dp, SettingsUi.BREADCRUMB_Y.dp).testTag(SETTINGS_BREADCRUMB_TAG),
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
            Line(stringResource(R.string.settings_ui_show_advanced), SettingsUi.RowValue.copy(color = if (focused) SettingsUi.Canvas else SettingsUi.Fg85), Modifier.weight(1f))
            TogglePill(ui.advanced)
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
    Box(
        modifier
            .testTag(SETTINGS_PAGE_TAG)
            .background(SettingsUi.GlassFill, shape)
            .border(1.dp, SettingsUi.GlassBorder, shape)
            .padding(horizontal = SettingsUi.PAGE_PAD_H.dp, vertical = SettingsUi.PAGE_PAD_V.dp),
    ) {
        val page = ui.page ?: return@Box
        Column {
            Line(page.title, SettingsUi.PageTitle)
            page.subtitle?.let { Line(it, SettingsUi.PageSubtitle, Modifier.padding(top = 6.dp)) }
        }
        val rowsTop = (SettingsUi.ROWS_TOP - SettingsUi.PAGE_PAD_V).dp
        val bottom = SettingsUi.NOTICE_H.dp
        if (page.placeholder != null) {
            Line(page.placeholder!!, SettingsUi.RowValue.copy(color = SettingsUi.Fg60), Modifier.padding(top = rowsTop).testTag(SETTINGS_PAGE_PLACEHOLDER_TAG), maxLines = 3)
        } else {
            Rows(ui, page, list, Modifier.padding(top = rowsTop, bottom = bottom))
        }
        ui.notice?.let {
            Box(Modifier.align(Alignment.BottomStart).height(SettingsUi.NOTICE_H.dp), contentAlignment = Alignment.CenterStart) {
                Line(it, SettingsUi.Notice, Modifier.testTag(SETTINGS_NOTICE_TAG))
            }
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

@Composable
private fun Rows(ui: SettingsUiState, page: PageModel, list: LazyListState, modifier: Modifier) {
    val count = rowCount(page)
    // Keys: the RowId (rows rebuild without recomposing unchanged ones, §5 item 2); a duplicate id or an unloaded row
    // falls back to its index so a bad page can never crash the LazyColumn.
    val keys = remember(page) {
        val seen = HashSet<String>()
        Array(count) { i -> rowAt(page, i)?.id?.key?.takeIf { seen.add(it) }?.let { "r:$it" } ?: "i:$i" }
    }
    LaunchedEffect(page.route, ui.top, count) {
        if (count > 0) list.scrollToItem(ui.top.coerceIn(0, count - 1))
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
        items(count, key = { keys.getOrElse(shown(it)) { "i:$it" } }) { slot ->
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
