package app.thebox.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.editor.ChannelEditorUiState
import app.thebox.core.editor.ChoiceKind
import app.thebox.core.editor.EditorChannelRow
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorGroupRow
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorMenus
import app.thebox.core.editor.EditorMoves
import app.thebox.core.editor.EditorPane
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorTexts
import app.thebox.core.editor.Lifted
import app.thebox.core.editor.Selection
import app.thebox.core.settings.SettingsRoute
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.FooterHints
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.settings.SettingsUi

const val EDITOR_SCREEN_TAG = "channel_editor"
const val EDITOR_GROUPS_PANE_TAG = "editor_groups_pane"
const val EDITOR_CHANNELS_PANE_TAG = "editor_channels_pane"
const val EDITOR_GROUPS_TITLE_TAG = "editor_groups_title"
const val EDITOR_CHANNELS_TITLE_TAG = "editor_channels_title"
const val EDITOR_CHIP_TAG = "editor_chip:"
const val EDITOR_MESSAGE_TAG = "editor_message"
const val EDITOR_GROUPS_MESSAGE_TAG = "editor_groups_message"
const val EDITOR_NOTICE_TAG = "editor_notice"
const val EDITOR_DIGITS_TAG = "editor_digits"
const val EDITOR_FOOTER_TAG = "editor_footer"

/**
 * The channel editor page (docs/design/m6-channel-editor.md §2) for [route]: opens [vm]'s visit while composed (the
 * settings screen composes it only while the route is on top) and draws [ChannelEditorContent] from the machine.
 */
@Composable
fun ChannelEditorHost(vm: ChannelEditorViewModel, route: SettingsRoute.ChannelEditor, modifier: Modifier = Modifier) {
    DisposableEffect(vm, route) {
        vm.open(route)
        onDispose { vm.close() }
    }
    val ui by vm.machine.ui.collectAsState()
    val logos by vm.showLogos.collectAsState()
    ChannelEditorContent(ui, vm.texts, logos, modifier)
}

/**
 * The editor's full canvas below the settings header (§2.2): the Groups pane (64, 144, 560 × 800), the channels pane
 * (648, 144, 1208 × 800), their layers (menu, picker, choice list, confirm card), the number jump, the notice line at
 * y 956 and the footer hints. A pure renderer of [ui]; every key goes to the machine. In-tree only (no Popup/Dialog), no
 * graphicsLayer or blur.
 */
@Composable
fun ChannelEditorContent(ui: ChannelEditorUiState, texts: EditorTexts, showLogos: Boolean, modifier: Modifier = Modifier) {
    val chipX = remember { mutableStateMapOf<String, Int>() }
    Box(modifier.fillMaxSize().testTag(EDITOR_SCREEN_TAG)) {
        GroupsPaneView(ui, texts, chipX, Modifier.offset(EditorUi.GROUPS_X.dp, EditorUi.PANE_Y.dp).size(EditorUi.GROUPS_W.dp, EditorUi.PANE_H.dp))
        ChannelsPaneView(ui, texts, showLogos, chipX, Modifier.offset(EditorUi.CHANNELS_X.dp, EditorUi.PANE_Y.dp).size(EditorUi.CHANNELS_W.dp, EditorUi.PANE_H.dp))

        // ---- layers (the machine opens and closes them; §2.4 "Layers")
        ui.choice?.let { c ->
            val groups = c.kind == ChoiceKind.GROUP_SORT
            val paneX = if (groups) EditorUi.GROUPS_X else EditorUi.CHANNELS_X
            val paneW = if (groups) EditorUi.GROUPS_W else EditorUi.CHANNELS_W
            val chip = chipX[(if (groups) "g:" else "c:") + choiceChip(c.kind)] ?: 0
            val x = (paneX + EditorUi.PANE_PAD + chip).coerceAtMost(paneX + paneW - EditorUi.PANE_PAD - EditorUi.CHOICE_W)
            val y = EditorUi.PANE_Y + EditorUi.TOOLBAR_Y + EditorUi.TOOLBAR_H + 8
            EditorChoiceCard(c, ui.filter, texts, Modifier.offset(x.dp, y.dp))
        }
        ui.menu?.let { m ->
            val groups = m.pane == EditorPane.GROUPS
            val paneX = if (groups) EditorUi.GROUPS_X else EditorUi.CHANNELS_X
            val paneW = if (groups) EditorUi.GROUPS_W else EditorUi.CHANNELS_W
            val rel = if (groups) ui.groups.cursor - ui.groups.top else (ui.channels?.let { it.cursor - it.top } ?: 0)
            val rowY = EditorUi.PANE_Y + EditorUi.ROWS_Y + rel.coerceAtLeast(0) * EditorUi.ROW_STEP
            val x = paneX + paneW - EditorUi.PANE_PAD - EditorUi.MENU_W
            EditorMenuCard(m, texts, reasonArg = lockedByGroupArg(ui), modifier = Modifier.offset(x.dp, editorMenuTop(rowY, menuHeight(m)).dp))
        }
        ui.picker?.let { p ->
            GroupPickerCard(p, ui.groups.groups, texts, Modifier.offset(EditorUi.PICKER_X.dp, EditorUi.PICKER_Y.dp))
        }
        ui.confirm?.let { EditorConfirmCard(it, texts, Modifier.fillMaxSize()) }
        ui.digits?.let { d ->
            Box(
                Modifier
                    .offset((EditorUi.CHANNELS_X + EditorUi.CHANNELS_W - EditorUi.PANE_PAD - DIGITS_W).dp, (EditorUi.PANE_Y + EditorUi.PANE_PAD).dp)
                    .size(DIGITS_W.dp, DIGITS_H.dp)
                    .testTag(EDITOR_DIGITS_TAG)
                    .background(SettingsUi.CardFill, RoundedCornerShape(SettingsUi.ROW_RADIUS.dp))
                    .border(1.dp, SettingsUi.CardBorder, RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)),
                contentAlignment = Alignment.Center,
            ) { Line(d, GuideUi.mono(48, FontWeight.Medium)) }
        }
        ui.notice?.let { n ->
            Box(Modifier.offset(EditorUi.NOTICE_X.dp, EditorUi.NOTICE_Y.dp).height(EditorUi.NOTICE_H.dp), contentAlignment = Alignment.CenterStart) {
                Line(n, SettingsUi.Notice, Modifier.widthIn(max = NOTICE_W.dp).testTag(EDITOR_NOTICE_TAG))
            }
        }
        Box(Modifier.fillMaxSize().padding(start = EditorUi.FOOTER_X.dp, bottom = EditorUi.FOOTER_BOTTOM.dp), contentAlignment = Alignment.BottomStart) {
            FooterHints(ui.footer, Modifier.testTag(EDITOR_FOOTER_TAG))
        }
    }
}

/** The notice line spans the canvas under the panes (64 … 1856); a joined notice fits on one line there. */
private const val NOTICE_W = 1792
private const val DIGITS_W = 200
private const val DIGITS_H = 88

/** The chip a choice list opens under: Show (1) for the filter, Sort… (2) for the sorts. */
private fun choiceChip(kind: ChoiceKind): Int = if (kind == ChoiceKind.FILTER) 1 else 2

private fun layerOpen(ui: ChannelEditorUiState) = ui.menu != null || ui.picker != null || ui.choice != null || ui.confirm != null

/** Rows of [pane] show focus: that pane, no toolbar chip, no layer, not moving. */
private fun rowsFocused(ui: ChannelEditorUiState, pane: EditorPane) =
    ui.pane == pane && ui.toolbar == null && !layerOpen(ui) && ui.move == null && !ui.gone

private fun chipFocused(ui: ChannelEditorUiState, pane: EditorPane, i: Int) =
    ui.pane == pane && ui.toolbar == i && !layerOpen(ui)

/** The group's display name from the loaded pages (or the lifted block), else its key. */
fun groupName(ui: ChannelEditorUiState, key: String): String {
    ui.groups.groups?.pages?.values?.forEach { page -> page.firstOrNull { it.groupKey == key }?.let { return it.name } }
    (ui.move?.lifted as? Lifted.Groups)?.rows?.firstOrNull { it.groupKey == key }?.let { return it.name }
    return key
}

/** A list's title: "All channels", "Results for '…'", or the group's name. */
fun listTitle(list: EditorList, ui: ChannelEditorUiState, texts: EditorTexts): String = when (list) {
    EditorList.All -> texts.get(EditorText.ALL_CHANNELS)
    is EditorList.Find -> texts.get(EditorText.FIND_RESULTS, list.query)
    is EditorList.Group -> groupName(ui, list.groupKey)
}

/** The `%1$s` of "Locked by its group, {group}": the focused channel's group lock, else the listed group. */
private fun lockedByGroupArg(ui: ChannelEditorUiState): String {
    val ch = ui.channels
    ch?.let { rowOf(it, it.cursor) }?.lockedByGroup?.let { return it }
    return (ui.list as? EditorList.Group)?.let { groupName(ui, it.groupKey) } ?: ""
}

/** The channels pane's empty or error text, its `%1$s` filled (the list's name or the Find query). */
fun emptyText(ui: ChannelEditorUiState, texts: EditorTexts): String? {
    val key = ui.empty ?: if (ui.gone) EditorText.E_PLAYLIST_GONE else if (ui.lockedList) EditorText.E_LOCKED else return null
    return when (key) {
        EditorText.E_EMPTY_HIDDEN, EditorText.E_EMPTY_SHOWN, EditorText.E_EMPTY_GROUP, EditorText.E_LOCKED -> texts.get(key, listTitle(ui.list, ui, texts))
        EditorText.E_FIND_NONE -> texts.get(key, (ui.list as? EditorList.Find)?.query ?: "")
        else -> texts.get(key)
    }
}

@Composable
private fun Pane(modifier: Modifier, tag: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(SettingsUi.PANE_RADIUS.dp)
    Box(
        modifier
            .testTag(tag)
            .background(SettingsUi.GlassFill, shape)
            .border(1.dp, SettingsUi.GlassBorder, shape)
            .padding(EditorUi.PANE_PAD.dp),
    ) { content() }
}

/** Pane title line (pane top + 16, h 40): name 30/600, counts mono 22 `.55` after it. */
@Composable
private fun TitleLine(name: String, counts: String?, tag: String) {
    Row(Modifier.fillMaxWidth().height(EditorUi.TITLE_H.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Line(name, EditorUi.PaneTitle, Modifier.weight(1f, fill = false))
        if (counts != null) Line(counts, EditorUi.PaneCounts, Modifier.padding(start = 16.dp).testTag(tag))
    }
}

/** Toolbar (pane top + 64, h 48): `Chip`s, sans 22, padding 10 × 18, gap 12. Disabled chips draw at `.4`. */
@Composable
private fun Toolbar(labels: List<String>, enabled: List<Boolean>, focused: (Int) -> Boolean, pane: String, chipX: SnapshotStateMap<String, Int>) {
    val density = LocalDensity.current
    Row(
        Modifier.offset(y = (EditorUi.TOOLBAR_Y - EditorUi.PANE_PAD).dp).height(EditorUi.TOOLBAR_H.dp),
        horizontalArrangement = Arrangement.spacedBy(EditorUi.CHIP_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val f = focused(i)
            Chip(
                focused = f,
                modifier = Modifier
                    .testTag("$EDITOR_CHIP_TAG$pane:$i")
                    .semantics { selected = f; contentDescription = label }
                    .onPlaced { c -> chipX["$pane:$i"] = with(density) { c.positionInParent().x.toDp().value.toInt() } },
                fill = SettingsUi.White08,
                border = SettingsUi.White12,
                padding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) { c ->
                val alpha = if (enabled[i] || f) 1f else SettingsUi.DISABLED_ALPHA
                Line(label, EditorUi.ChipText.copy(color = c.copy(alpha = alpha)))
            }
        }
    }
}

/** The rows area of a pane (from pane top + 128, 656 high): [count] rows of 72 with gaps of 8, first visible [top]. */
@Composable
private fun RowsArea(count: Int, top: Int, key: Any, item: @Composable (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = top.coerceIn(0, (count - 1).coerceAtLeast(0)))
    LaunchedEffect(key, top, count) {
        if (count > 0) state.scrollToItem(top.coerceIn(0, count - 1))
    }
    LazyColumn(
        Modifier.offset(y = (EditorUi.ROWS_Y - EditorUi.PANE_PAD).dp).fillMaxWidth().height(EditorUi.ROWS_H.dp),
        state = state,
        verticalArrangement = Arrangement.spacedBy(EditorUi.ROW_GAP.dp),
        userScrollEnabled = false,
    ) {
        items(count) { i -> item(i) }
    }
}

// ---------------------------------------------------------------- Groups pane

@Composable
private fun GroupsPaneView(ui: ChannelEditorUiState, texts: EditorTexts, chipX: SnapshotStateMap<String, Int>, modifier: Modifier) {
    Pane(modifier, EDITOR_GROUPS_PANE_TAG) {
        val win = ui.groups.groups
        val head = ui.groups.head
        val move = ui.move?.takeIf { it.pane == EditorPane.GROUPS }
        val counts = when {
            move != null && win != null -> texts.get(EditorText.MOVE_HEADER, (move.lifted as Lifted.Groups).rows.size, move.index - head.size + 1, win.count)
            win != null -> buildString {
                append(if (win.hidden > 0) texts.count(EditorText.GROUPS_COUNT, win.count, win.hidden) else texts.count(EditorText.GROUPS_COUNT_NONE, win.count))
                val n = EditorMenus.selectedCount(ui.groupSelection, win.count)
                if (n > 0) append(SEP).append(texts.count(EditorText.SELECTED_COUNT, n))
            }
            else -> null
        }
        TitleLine(texts.get(EditorText.GROUPS_TITLE), counts, EDITOR_GROUPS_TITLE_TAG)
        Toolbar(
            labels = listOf(
                texts.get(EditorText.TB_ACTIONS),
                texts.get(if (ui.showHiddenGroups) EditorText.TB_HIDDEN_GROUPS_ON else EditorText.TB_HIDDEN_GROUPS_OFF),
                texts.get(EditorText.TB_SORT),
            ),
            enabled = listOf(ui.groupSelection != Selection.None, true, true),
            focused = { chipFocused(ui, EditorPane.GROUPS, it) },
            pane = "g",
            chipX = chipX,
        )
        // Review S1: a Groups load failure is told in this pane (the head rows stay; OK retries from either pane).
        val count = head.size + if (ui.groupsFailed) 0 else (win?.count ?: 0)
        val focused = rowsFocused(ui, EditorPane.GROUPS)
        if (ui.groupsFailed) {
            PaneMessage(
                texts.get(EditorText.E_LOAD), locked = false,
                Modifier.offset(y = (EditorUi.ROWS_Y - EditorUi.PANE_PAD + head.size * EditorUi.ROW_STEP).dp).testTag(EDITOR_GROUPS_MESSAGE_TAG),
            )
        }
        val sources = remember(ui.move, ui.groupSelection) { if (move != null) EditorMoves.liftedSources(ui) else emptyList() }
        RowsArea(count, ui.groups.top, key = win?.version ?: -1L) { i ->
            if (i < head.size) {
                val entry = head[i]
                val look = when {
                    focused && ui.groups.cursor == i -> RowLook.FOCUSED
                    ui.pane == EditorPane.CHANNELS && ui.list == entry -> RowLook.CURRENT
                    else -> RowLook.PLAIN
                }
                val n = if (ui.list == entry) ui.channels?.count else null
                HeadRow(i, listTitle(entry, ui, texts), n, look)
                return@RowsArea
            }
            val gi = i - head.size
            var row: EditorGroupRow? = null
            var lifted = false
            if (move != null) {
                val start = move.index - head.size
                val block = (move.lifted as Lifted.Groups).rows
                val si = EditorMoves.sourceIndex(gi, start, block.size, sources)
                if (si == -1) { row = block.getOrNull(gi - start); lifted = true } else row = win?.let { rowOf(it, si) }
            } else {
                row = win?.let { rowOf(it, gi) }
            }
            if (row == null) {
                EditorPlaceholderRow("g$gi")
            } else {
                val look = when {
                    lifted -> RowLook.LIFTED
                    focused && ui.groups.cursor == i -> RowLook.FOCUSED
                    ui.pane == EditorPane.CHANNELS && (ui.list as? EditorList.Group)?.groupKey == row.groupKey -> RowLook.CURRENT
                    else -> RowLook.PLAIN
                }
                GroupRowView(row, EditorMenus.isTicked(ui.groupSelection, row.groupKey), look, texts)
            }
        }
    }
}

// ---------------------------------------------------------------- Channels pane

@Composable
private fun ChannelsPaneView(ui: ChannelEditorUiState, texts: EditorTexts, showLogos: Boolean, chipX: SnapshotStateMap<String, Int>, modifier: Modifier) {
    Pane(modifier, EDITOR_CHANNELS_PANE_TAG) {
        val win = ui.channels
        val move = ui.move?.takeIf { it.pane == EditorPane.CHANNELS }
        val counts = buildString {
            when {
                move != null && win != null -> append(texts.get(EditorText.MOVE_HEADER, (move.lifted as Lifted.Channels).rows.size, move.index + 1, win.count))
                win != null && !ui.lockedList -> {
                    append(if (win.hidden > 0) texts.count(EditorText.CHANNELS_COUNT, win.count, win.hidden) else texts.count(EditorText.CHANNELS_COUNT_NONE, win.count))
                    val n = EditorMenus.selectedCount(ui.channelSelection, win.count)
                    if (n > 0) append(SEP).append(texts.count(EditorText.SELECTED_COUNT, n))
                }
            }
            if (ui.pendingWrites > 0) {
                if (isNotEmpty()) append(SEP)
                append(texts.get(EditorText.SAVING))
            }
        }.ifEmpty { null }
        TitleLine(listTitle(ui.list, ui, texts), counts, EDITOR_CHANNELS_TITLE_TAG)
        Toolbar(
            labels = listOf(
                texts.get(EditorText.TB_ACTIONS),
                texts.get(EditorText.TB_SHOW, texts.get(filterLabel(ui.filter))),
                texts.get(EditorText.TB_SORT),
                texts.get(EditorText.TB_FIND),
            ),
            enabled = listOf(true, true, ui.list is EditorList.Group, true),
            focused = { chipFocused(ui, EditorPane.CHANNELS, it) },
            pane = "c",
            chipX = chipX,
        )
        val message = emptyText(ui, texts)
        val focused = rowsFocused(ui, EditorPane.CHANNELS)
        if (message != null) {
            Column(Modifier.offset(y = (EditorUi.ROWS_Y - EditorUi.PANE_PAD).dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EditorUi.ROW_GAP.dp)) {
                if (ui.lockedOut > 0 && !ui.lockedList && !ui.gone) LockedNoteRow(texts, focused && (win?.cursor ?: 0) < 0)
                PaneMessage(message, locked = ui.lockedList, modifier = Modifier.testTag(EDITOR_MESSAGE_TAG))
            }
            return@Pane
        }
        if (win == null) {
            // Loading: the first page's placeholders.
            Column(Modifier.offset(y = (EditorUi.ROWS_Y - EditorUi.PANE_PAD).dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EditorUi.ROW_GAP.dp)) {
                repeat(LOADING_ROWS) { EditorPlaceholderRow("c$it") }
            }
            return@Pane
        }
        val lo = if (ui.lockedOut > 0) -1 else 0
        val count = win.count - lo
        val sources = remember(ui.move, ui.channelSelection) { if (move != null) EditorMoves.liftedSources(ui) else emptyList() }
        RowsArea(count, win.top - lo, key = win.version) { k ->
            val li = k + lo
            if (li < 0) {
                LockedNoteRow(texts, focused && win.cursor == -1)
                return@RowsArea
            }
            var row: EditorChannelRow? = null
            var lifted = false
            if (move != null) {
                val block = (move.lifted as Lifted.Channels).rows
                val si = EditorMoves.sourceIndex(li, move.index, block.size, sources)
                if (si == -1) { row = block.getOrNull(li - move.index); lifted = true } else row = rowOf(win, si)
            } else {
                row = rowOf(win, li)
            }
            if (row == null) {
                EditorPlaceholderRow("c$li")
            } else {
                val look = when {
                    lifted -> RowLook.LIFTED
                    focused && win.cursor == li -> RowLook.FOCUSED
                    else -> RowLook.PLAIN
                }
                ChannelRowView(row, EditorMenus.isTicked(ui.channelSelection, row.stableKey), look, showLogos, texts)
            }
        }
    }
}

private const val LOADING_ROWS = 8

fun filterLabel(f: EditorFilter): EditorText = when (f) {
    EditorFilter.ALL -> EditorText.FILTER_ALL
    EditorFilter.SHOWN -> EditorText.FILTER_SHOWN
    EditorFilter.HIDDEN -> EditorText.FILTER_HIDDEN
}
