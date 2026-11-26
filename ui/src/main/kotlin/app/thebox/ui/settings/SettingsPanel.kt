package app.thebox.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.MoveState
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.Pane
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.UiTextScale
import app.thebox.core.settings.state.SettingsPanelRules
import app.thebox.core.settings.state.SettingsShellLink
import app.thebox.core.settings.state.StorageMarks
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.settings.vm.ResourceSettingsTexts
import app.thebox.ui.theme.DesignTokens
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.emptyFlow

/** Test tags of the in-player panel (docs/design/m6-player-settings-panel.md §5). */
const val SETTINGS_PANEL_TAG = "settings_panel"
const val SETTINGS_PANEL_HEADER_TAG = "settings_panel_header"
const val SETTINGS_PANEL_HAIRLINE_TAG = "settings_panel_hairline"
const val SETTINGS_PANEL_TITLE_TAG = "settings_panel_title"
const val SETTINGS_PANEL_ROWS_TAG = "settings_panel_rows"
const val SETTINGS_PANEL_ROOT_ROW_TAG = "settings_panel_root_row:"
const val SETTINGS_PANEL_BAND_TAG = "settings_panel_band"
const val SETTINGS_PANEL_BAND_TIMING_TAG = "settings_panel_band_timing"
const val SETTINGS_PANEL_BAND_TEXT_TAG = "settings_panel_band_text"
const val SETTINGS_PANEL_NOTICE_TAG = "settings_panel_notice"

/**
 * (Settings panel §5) The panel's geometry in design px and its type. The box is the picker sheet's (`PlayerUi.PICKER_*`,
 * F16): (1376, 48) 496 × 880, ending 24 above the quick bar. Text goes through `GuideUi.sans/mono` and so `UiTextScale`
 * (Default 1, Largest 1.2); every size here is at least 22.
 */
object SettingsPanelUi {
    // ---- box (§5.1)
    const val BOX_X = PlayerUi.PICKER_L.toInt()
    const val BOX_Y = PlayerUi.PICKER_T.toInt()
    const val BOX_W = PlayerUi.PICKER_W.toInt()
    const val BOX_H = PlayerUi.PICKER_H.toInt()
    const val PAD_H = 16; const val PAD_TOP = 20; const val PAD_BOTTOM = 16
    /** 496 − 2 × 16: the rows' width (x 1392 … 1856). */
    const val INNER_W = BOX_W - 2 * PAD_H
    val Fill = PlayerUi.PanelFill
    val Border = PlayerUi.Border09

    // ---- header (§5.2)
    const val HEADER_H = 40; const val ARROW_X = 12; const val PARENT_MAX_W = 160
    const val DIVIDER_W = 1; const val DIVIDER_H = 24; const val DIVIDER_GAP = 12; const val ARROW_GAP = 10
    const val HAIRLINE_GAP = 12
    val Divider = Color.White.copy(alpha = 0.22f)
    val Hairline = Color.White.copy(alpha = 0.12f)

    // ---- page title (§5.3)
    const val TITLE_GAP = 16; const val SUBTITLE_GAP = 4; const val ROWS_GAP = 12

    // ---- compact rows (§5.4) and option lists (§5.5)
    const val ROW_H = 64; const val ROW_H_TWO = 92; const val ROW_GAP = 6; const val ROW_PAD_H = 16; const val ROW_PAD_V = 10
    const val ROW_RADIUS = 12
    const val CHOICE_INDENT = 24; const val OPTION_H = 56; const val OPTION_H_DETAIL = 76; const val OPTION_GAP = 14
    /** Between two options of one list (the list itself starts [ROW_GAP] under its row). */
    const val OPTION_ROW_GAP = 2

    // ---- band (§5.6, S8)
    const val BAND_LINES = 4; const val BAND_LINE_H = 29; const val BAND_PAD_V = 12; const val BAND_GAP = 8
    const val BAND_BAR_W = 4; const val BAND_BAR_H = 22; const val BAND_TEXT_X = 16

    /** One band line at the running text size, in whole px: 29 at Default, 35 at Largest. */
    fun bandLineH(): Float = kotlin.math.ceil(BAND_LINE_H * UiTextScale.factor - 0.01f)
    /** 12 + 4 × 29 + 12 = 140 at Default; 12 + 4 × 35 + 12 = 164 at Largest. The band keeps it when empty. */
    fun bandH(): Float = 2 * BAND_PAD_V + BAND_LINES * bandLineH()

    /** Show fade of the panel's content (snapped when low-end). */
    const val FADE_MS = 150

    // ---- type
    val Arrow: TextStyle get() = GuideUi.sans(26, FontWeight.SemiBold)
    val Parent: TextStyle get() = GuideUi.sans(22, FontWeight.Medium, SettingsUi.Fg55)
    val Current: TextStyle get() = GuideUi.sans(24, FontWeight.SemiBold)
    val FullScreenHint: TextStyle get() = GuideUi.sans(22, color = SettingsUi.Fg55)
    val PageTitle: TextStyle get() = GuideUi.sans(28, FontWeight.SemiBold)
    val PageSubtitle: TextStyle get() = GuideUi.sans(22, color = SettingsUi.Fg60)
    val Placeholder: TextStyle get() = GuideUi.sans(22, color = SettingsUi.Fg60)
    val RowLabel: TextStyle get() = GuideUi.sans(26, FontWeight.Medium)
    val RowValue: TextStyle get() = GuideUi.sans(22)
    val RowValueMono: TextStyle get() = GuideUi.mono(22)
    val Chevron: TextStyle get() = GuideUi.sans(26)
    val OptionLabel: TextStyle get() = GuideUi.sans(24)
    val OptionDetail: TextStyle get() = GuideUi.sans(22)
    private val bandLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val BandTiming: TextStyle get() = GuideUi.sans(22, FontWeight.Medium, SettingsUi.Fg92).copy(lineHeight = bandLineH().sp, lineHeightStyle = bandLine)
    val BandText: TextStyle get() = GuideUi.sans(22, color = SettingsUi.Fg70).copy(lineHeight = bandLineH().sp, lineHeightStyle = bandLine)
}

/**
 * (Settings panel commit 0, docs/design/m6-player-settings-panel.md §5) The in-player settings panel: the one settings
 * machine's state drawn in one column over FULL, in the picker box (1376, 48) 496 × 880 design px. `AppRoot` draws it in
 * the key sink, after `FullScreenHost`, while `shown == FULL && GuideUiState.settingsPanel && settingsMachine != null`
 * (§5.13): above the player overlays, below the reminder card, the PIN pad and the toast. It collects [machine]'s `ui`
 * itself and draws the panel box (header, page title, compact rows, description band, context menu), then the centred
 * card overlays (`ConfirmCard`, `NumberPad`, `TextEditorOverlay` wired as in `SettingsScreen`, `CaptureOverlay`).
 * [lowEnd]: the 150 ms show fade is snapped. Never an ancestor of `VideoLayer`; no blur (§6). The only layer is the
 * fade's, on the panel box itself (a key-sink child, never an ancestor of the video).
 */
@Composable
fun SettingsPanel(machine: SettingsMachine, lowEnd: Boolean, modifier: Modifier = Modifier) {
    val ui by machine.ui.collectAsState()
    val link = machine as? SettingsShellLink
    SettingsPanelContent(
        ui = ui,
        lowEnd = lowEnd,
        modifier = modifier,
        editor = { e ->
            TextEditorOverlay(
                editor = e,
                onDone = { text -> machine.dispatch(SettingsEvent.EditorClosed(e.row, text, committed = true)) },
                onCancel = { machine.dispatch(SettingsEvent.EditorClosed(e.row, null, committed = false)) },
                editorBacks = remember(link) { link?.editorBacks ?: emptyFlow() },
                // The phone route expands to full-screen Settings (§2.6); first-run never reaches the panel.
                onPhoneSetup = { link?.push(SettingsRoute.PhoneSetup(firstRun = firstRunOf(machine.ui.value.route))) },
            )
        },
    )
}

/** [SettingsPanel] for a given state (tests); [editor] draws the editor overlay. */
@Composable
fun SettingsPanelContent(
    ui: SettingsUiState,
    lowEnd: Boolean,
    modifier: Modifier = Modifier,
    editor: @Composable (app.thebox.core.settings.EditorState) -> Unit = {},
) {
    // U-S10's title memory: the titles of the pages this visit has shown, for the breadcrumb's parent.
    val titles = remember { mutableStateMapOf<SettingsRoute, String>() }
    ui.page?.let { p -> SideEffect { if (p.title.isNotBlank()) titles[p.route] = p.title } }
    val fade = remember { Animatable(if (lowEnd) 1f else 0f) }
    LaunchedEffect(lowEnd) { if (lowEnd) fade.snapTo(1f) else fade.animateTo(1f, tween(SettingsPanelUi.FADE_MS)) }
    val settingsTitle = stringResource(R.string.settings_ui_title)
    Box(modifier.fillMaxSize()) {
        val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
        Column(
            Modifier
                .offset(SettingsPanelUi.BOX_X.dp, SettingsPanelUi.BOX_Y.dp)
                .size(SettingsPanelUi.BOX_W.dp, SettingsPanelUi.BOX_H.dp)
                .graphicsLayer { alpha = fade.value }
                .testTag(SETTINGS_PANEL_TAG)
                .semantics { paneTitle = settingsTitle }
                .background(SettingsPanelUi.Fill, shape)
                .border(1.dp, SettingsPanelUi.Border, shape)
                .padding(start = SettingsPanelUi.PAD_H.dp, end = SettingsPanelUi.PAD_H.dp, top = SettingsPanelUi.PAD_TOP.dp, bottom = SettingsPanelUi.PAD_BOTTOM.dp),
        ) {
            val root = ui.pane == Pane.SECTIONS
            val page = ui.page?.takeIf { !root }
            PanelHeader(
                parent = if (root) null else parentTitle(ui, titles),
                title = if (root) settingsTitle else page?.title?.takeIf { it.isNotBlank() } ?: sectionTitle(ui.route) ?: settingsTitle,
                root = root,
            )
            Spacer(Modifier.height(SettingsPanelUi.HAIRLINE_GAP.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(SettingsPanelUi.Hairline).testTag(SETTINGS_PANEL_HAIRLINE_TAG))
            if (!root && page != null) PageTitle(page.title, page.subtitle)
            Spacer(Modifier.height(SettingsPanelUi.ROWS_GAP.dp))
            val rowsModifier = Modifier.weight(1f).fillMaxWidth().testTag(SETTINGS_PANEL_ROWS_TAG)
            when {
                root -> RootList(ui, rowsModifier)
                page == null -> Spacer(rowsModifier)
                page.placeholder != null -> Box(rowsModifier) { Line(page.placeholder!!, SettingsPanelUi.Placeholder, Modifier.testTag(SETTINGS_PAGE_PLACEHOLDER_TAG), maxLines = 3) }
                else -> PageRows(ui, page, rowsModifier)
            }
            Spacer(Modifier.height(SettingsPanelUi.BAND_GAP.dp))
            val focusedRow = if (root || page == null || page.placeholder != null) null else rowAt(page, moveShown(ui.move, ui.cursor))
            Band(
                timing = focusedRow?.let { r -> SettingsPanelRules.timingText(SettingsPanelRules.timing(r))?.let { stringResource(ResourceSettingsTexts.idOf(it)) } },
                hint = focusedRow?.takeIf { SettingsPanelRules.quickBarHint(it) }?.let { stringResource(R.string.settings_panel_quick_bar_hint) },
                description = focusedRow?.let { bandDescription(it) },
                notice = ui.notice,
                move = if (ui.move != null) stringResource(R.string.backup_move_hint) else null,
            )
        }
        // ---- centred card overlays, over the video and the panel (§2.7, §5.13)
        ui.confirm?.let { ConfirmCard(it, Modifier.fillMaxSize()) }
        ui.numberPad?.let { NumberPad(it, Modifier.fillMaxSize()) }
        ui.editor?.let { editor(it) }
        ui.capture?.let { CaptureOverlay(it, ui.captureButton, Modifier.fillMaxSize(), testReport = ui.captureReport, testMode = ui.captureTest) }
    }
}

/** The name of [route]'s section, when it is one (the breadcrumb and title fallbacks while a page loads). */
@Composable
private fun sectionTitle(route: SettingsRoute): String? = SettingsTree.sectionOf(route)?.let { stringResource(SettingsUi.sectionLabel(it)) }

/**
 * (§5.2) Where BACK goes: the page under this one on the stack. Root is "Settings"; a section page is its section's name;
 * any other page is the title it showed this visit. A detached bottom page (nothing under it) has no parent.
 */
@Composable
private fun parentTitle(ui: SettingsUiState, titles: Map<SettingsRoute, String>): String? {
    val stack = ui.stack
    val i = stack.lastIndexOf(ui.route)
    val parent = (if (i >= 1) stack[i - 1] else if (i < 0) stack.lastOrNull() else null) ?: return null
    return when (parent) {
        SettingsRoute.Root -> stringResource(R.string.settings_ui_title)
        is SettingsRoute.Section -> stringResource(SettingsUi.sectionLabel(parent.id))
        else -> titles[parent] ?: sectionTitle(parent) ?: stringResource(R.string.settings_ui_title)
    }
}

/**
 * (§5.2) "← Settings | Playlists": the back arrow, the parent's name dim (at most 160, ellipsised), a 1 × 24 divider and
 * the current page bright (the rest, ellipsised). At Root: "Settings" alone and "☰ Full screen" at the right. Grows with
 * the text size.
 */
@Composable
private fun PanelHeader(parent: String?, title: String, root: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = SettingsPanelUi.HEADER_H.dp).padding(start = SettingsPanelUi.ARROW_X.dp).testTag(SETTINGS_PANEL_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (root) {
            Line(title, SettingsPanelUi.Current, Modifier.weight(1f))
            Line(stringResource(R.string.settings_panel_full_screen_hint), SettingsPanelUi.FullScreenHint, Modifier.padding(start = 12.dp))
            return@Row
        }
        if (parent != null) {
            Line("←", SettingsPanelUi.Arrow)
            Spacer(Modifier.width(SettingsPanelUi.ARROW_GAP.dp))
            Line(parent, SettingsPanelUi.Parent, Modifier.widthIn(max = SettingsPanelUi.PARENT_MAX_W.dp))
            Spacer(Modifier.width(SettingsPanelUi.DIVIDER_GAP.dp))
            Box(Modifier.size(SettingsPanelUi.DIVIDER_W.dp, SettingsPanelUi.DIVIDER_H.dp).background(SettingsPanelUi.Divider))
            Spacer(Modifier.width(SettingsPanelUi.DIVIDER_GAP.dp))
        }
        Line(title, SettingsPanelUi.Current, Modifier.weight(1f))
    }
}

/** (§5.3) The page title 28/600 on one line, its subtitle 22 .6 on up to 2 lines. Not at Root. */
@Composable
private fun PageTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(top = SettingsPanelUi.TITLE_GAP.dp)) {
        Line(title, SettingsPanelUi.PageTitle, Modifier.testTag(SETTINGS_PANEL_TITLE_TAG))
        subtitle?.let { Line(it, SettingsPanelUi.PageSubtitle, Modifier.padding(top = SettingsPanelUi.SUBTITLE_GAP.dp).testTag(SETTINGS_SUBTITLE_TAG), maxLines = 2) }
    }
}

/** (§0 item 1) The root list: `Pane.SECTIONS`, one compact row per section, a chevron at the right. */
@Composable
private fun RootList(ui: SettingsUiState, modifier: Modifier) {
    val focus = !overlayOpen(ui)
    val labels = ui.sections.map { stringResource(SettingsUi.sectionLabel(it)) }
    val memory = remember { WindowMemory(0) }
    RowsWindow(
        count = labels.size,
        cursorSlot = ui.sectionCursor,
        memory = memory,
        key = { "s:$it" },
        gapBefore = { SettingsPanelUi.ROW_GAP },
        menu = null,
        modifier = modifier,
    ) { i -> RootRow(labels[i], focus && i == ui.sectionCursor, Modifier.testTag(SETTINGS_PANEL_ROOT_ROW_TAG + i)) }
}

@Composable
private fun RootRow(label: String, focused: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(SettingsPanelUi.ROW_RADIUS.dp)
    var m = modifier.fillMaxWidth().heightIn(min = SettingsPanelUi.ROW_H.dp).semantics { selected = focused; contentDescription = label }
    if (focused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
    val text = if (focused) SettingsUi.Canvas else SettingsUi.Fg
    Row(m.padding(horizontal = SettingsPanelUi.ROW_PAD_H.dp, vertical = SettingsPanelUi.ROW_PAD_V.dp), verticalAlignment = Alignment.CenterVertically) {
        Line(label, SettingsPanelUi.RowLabel.copy(color = if (focused) text else SettingsUi.Fg92), Modifier.weight(1f).testTag(SETTINGS_PANEL_LABEL_TAG), maxLines = 2)
        CompactChevron(SettingsUi.secondary(text, focused, 0.45f))
    }
}

/**
 * The page's rows as window slots: each row, and while an inline option list is open, each of its options as its own
 * slot right under the row, so the window can follow the focused option of a long list (guide shift: 49 options).
 */
@Composable
private fun PageRows(ui: SettingsUiState, page: PageModel, modifier: Modifier) {
    val pageFocus = ui.pane == Pane.PAGE && !overlayOpen(ui)
    val rows = rowCount(page)
    val keys = remember(page) { SettingsRowKeys(page) }
    // The open option list: on the cursor row, while the page has the focus (as SettingsScreen draws it).
    val choice = ui.choice?.takeIf { pageFocus }
    val choiceRow = choice?.let { c -> rowAt(page, moveShown(ui.move, ui.cursor))?.takeIf { it.id == c.row } }
    val options = choiceRow?.let { compactOptions(it) }
    val c = if (options != null) ui.cursor else -1
    val n = options?.first?.size ?: 0
    val optFocus = choice?.focused ?: 0
    val cursorSlot = if (options != null) c + 1 + optFocus.coerceIn(0, maxOf(0, n - 1)) else ui.cursor
    // The window's first row: the machine's `top` when the page is entered, then kept around the focus.
    val memory = remember(page.route) { WindowMemory(ui.top) }
    val menu = ui.menu
    RowsWindow(
        count = rows + n,
        cursorSlot = cursorSlot,
        memory = memory,
        key = { s ->
            when {
                c < 0 || s <= c -> keys.keyAt(moveShown(ui.move, s))
                s <= c + n -> "o:${keys.keyAt(moveShown(ui.move, c))}:${s - c - 1}"
                else -> keys.keyAt(moveShown(ui.move, s - n))
            }
        },
        gapBefore = { s -> if (c >= 0 && s in (c + 2)..(c + n)) SettingsPanelUi.OPTION_ROW_GAP else SettingsPanelUi.ROW_GAP },
        menu = menu?.let { m -> { SettingsMenu(m) } },
        modifier = modifier,
    ) { s ->
        if (c >= 0 && s in (c + 1)..(c + n)) {
            val j = s - c - 1
            val (opts, selected, multi) = options!!
            CompactOptionRow(choiceRow!!, opts[j], j, selected = j in selected, focused = j == optFocus, multi = multi)
        } else {
            val slot = if (c >= 0 && s > c + n) s - n else s
            val i = moveShown(ui.move, slot)
            val row = rowAt(page, i)
            if (row == null) PlaceholderRow(i, height = SettingsPanelUi.ROW_H)
            else SettingsRowView(
                row = row,
                focused = pageFocus && ui.cursor == slot && options == null,
                choice = null,
                moving = ui.move?.row == row.id,
                compact = true,
            )
        }
    }
}

/** The window's first slot between frames (read and written in the measure pass only; not snapshot state). */
private class WindowMemory(var first: Int)

/**
 * (§5.7, "a window around the focus") A list that shows only whole slots: from the remembered first slot (moved up to
 * keep one slot above the focus, and on until the focused slot and the one after it fit), every slot that fits wholly in
 * the rows area. So no row is ever cut at the band, at any text size. [menu] is placed right-aligned at the focused
 * slot's top, clamped to the rows area (§2.7). Composes only the slots it measures (about a screenful).
 */
@Composable
private fun RowsWindow(
    count: Int,
    cursorSlot: Int,
    memory: WindowMemory,
    key: (Int) -> Any,
    gapBefore: (Int) -> Int,
    menu: (@Composable () -> Unit)?,
    modifier: Modifier,
    content: @Composable (Int) -> Unit,
) {
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val w = constraints.maxWidth
        val vh = constraints.maxHeight
        if (count <= 0) return@SubcomposeLayout layout(w, vh) {}
        val child = Constraints(minWidth = w, maxWidth = w)
        val measured = HashMap<Int, Placeable>()
        fun m(i: Int): Placeable = measured.getOrPut(i) { subcompose(key(i)) { content(i) }.first().measure(child) }
        fun gap(i: Int): Int = gapBefore(i).dp.roundToPx()
        fun bottom(first: Int, last: Int): Int {
            var y = 0
            for (i in first..last) { if (i > first) y += gap(i); y += m(i).height }
            return y
        }
        val cur = cursorSlot.coerceIn(0, count - 1)
        val ahead = minOf(cur + 1, count - 1)
        var first = minOf(memory.first, maxOf(0, cur - 1)).coerceIn(0, cur)
        while (first < cur && bottom(first, ahead) > vh) first++
        memory.first = first
        val placed = ArrayList<Pair<Placeable, Int>>()
        var y = 0
        var cursorY = 0
        var i = first
        while (i < count) {
            val g = if (i > first) gap(i) else 0
            val p = m(i)
            if (i > first && y + g + p.height > vh) break
            y += g
            if (i == cur) cursorY = y
            placed += p to y
            y += p.height
            i++
        }
        val menuPlaceable = menu?.let { subcompose("menu", it).first().measure(Constraints(maxWidth = w, maxHeight = vh)) }
        layout(w, vh) {
            placed.forEach { (p, py) -> p.place(0, py) }
            menuPlaceable?.let { mp -> mp.place(w - mp.width, cursorY.coerceAtMost(vh - mp.height).coerceAtLeast(0)) }
        }
    }
}

/**
 * (§5.6, S8) The description band: a hairline, then 4 lines (12 + 4 × 29 + 12 = 140 at Default, about 164 at Largest).
 * Line 1 is the timing line (22/500 with a 4 × 22 accent bar; absent for a LIVE row), then the quick-bar hint, then the
 * focused row's description or disabled reason (22 .7), ellipsised at what is left. A notice replaces the whole band (up to
 * 4 lines); in move mode the move hint replaces the description. It keeps its height when empty.
 */
@Composable
private fun Band(timing: String?, hint: String?, description: String?, notice: String?, move: String?) {
    val lineH = SettingsPanelUi.bandLineH()
    Column(Modifier.fillMaxWidth().height(SettingsPanelUi.bandH().dp).testTag(SETTINGS_PANEL_BAND_TAG)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(SettingsPanelUi.Hairline))
        Column(Modifier.padding(top = (SettingsPanelUi.BAND_PAD_V - 1).dp)) {
            if (notice != null) {
                BarLine(notice, SETTINGS_PANEL_NOTICE_TAG, lineH, SettingsPanelUi.BAND_LINES)
                return@Column
            }
            // S8: the timing is never cut. One line at Default; at a larger text size a long one takes a second line
            // from the description (its line count is known after its first layout).
            var timingLines by remember(timing) { mutableIntStateOf(1) }
            if (timing != null) BarLine(timing, SETTINGS_PANEL_BAND_TIMING_TAG, lineH, TIMING_MAX_LINES) { timingLines = it }
            val left = SettingsPanelUi.BAND_LINES - if (timing != null) timingLines else 0
            // The quick-bar hint on its own line, then the description; or the move hint (S9).
            val rest = if (move != null) move else listOfNotNull(hint, description).joinToString("\n").ifEmpty { null }
            if (rest != null && left > 0) {
                Line(rest, SettingsPanelUi.BandText, Modifier.padding(start = SettingsPanelUi.BAND_TEXT_X.dp).testTag(SETTINGS_PANEL_BAND_TEXT_TAG), maxLines = left)
            }
        }
    }
}

/** The timing line's cap: one line at Default, two at the larger text sizes when it is long (S8). */
private const val TIMING_MAX_LINES = 2

/** A band line with the accent bar at its left (the timing line, a notice). */
@Composable
private fun BarLine(text: String, tag: String, lineH: Float, maxLines: Int, onLines: (Int) -> Unit = {}) {
    Box {
        Box(
            Modifier.padding(top = ((lineH - SettingsPanelUi.BAND_BAR_H) / 2).coerceAtLeast(0f).dp)
                .size(SettingsPanelUi.BAND_BAR_W.dp, SettingsPanelUi.BAND_BAR_H.dp)
                .background(SettingsUi.Accent, RoundedCornerShape(2.dp)),
        )
        androidx.tv.material3.Text(
            text, style = SettingsPanelUi.BandTiming, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            onTextLayout = { onLines(it.lineCount) },
            modifier = Modifier.padding(start = SettingsPanelUi.BAND_TEXT_X.dp).testTag(tag),
        )
    }
}

/**
 * The band's description for [row]: `secondLine(row)` without a timing sentence (§5.6). WpaPages leaves it out in the
 * panel (A4); other builders may still end with it ("<text> · Takes effect …") or be only it (WP-B's playlist rows,
 * F14), so the band drops such a sentence: the timing line already says it.
 */
@Composable
private fun bandDescription(row: SettingsRow): String? {
    val d = secondLine(row)?.let { StorageMarks.plain(it) } ?: return null
    val sentences = EFFECT_SENTENCES.map { stringResource(it) }
    return bandDescriptionOf(d, sentences, stringResource(R.string.settings_sep))
}

/** The full-screen timing sentences (Settings and Setup texts) the band drops from a description. */
private val EFFECT_SENTENCES = listOf(
    R.string.settings_effect_next_tune, R.string.settings_effect_reprepare, R.string.settings_effect_next_refresh,
    R.string.settings_effect_next_epg_import, R.string.settings_effect_next_launch, R.string.settings_effect_restart,
    R.string.setup_effect_next_tune, R.string.setup_effect_next_launch, R.string.setup_effect_next_refresh, R.string.setup_effect_next_epg,
)

/** [d] without a trailing "[sep]<sentence>"; null when [d] is only a timing sentence. */
internal fun bandDescriptionOf(d: String, sentences: List<String>, sep: String): String? {
    for (s in sentences) {
        if (d == s) return null
        if (d.endsWith(sep + s)) return d.removeSuffix(sep + s).ifBlank { null }
    }
    return d
}

/** The row move mode shows in window slot [i]: the lifted row at its target index, the rows between shifted by one. */
internal fun moveShown(move: MoveState?, i: Int): Int {
    if (move == null || move.index == move.fromIndex) return i
    return when {
        i == move.index -> move.fromIndex
        move.index > move.fromIndex && i in move.fromIndex until move.index -> i + 1
        move.index < move.fromIndex && i in (move.index + 1)..move.fromIndex -> i - 1
        else -> i
    }
}

private fun overlayOpen(ui: SettingsUiState) =
    ui.menu != null || ui.confirm != null || ui.editor != null || ui.numberPad != null || ui.capture != null
