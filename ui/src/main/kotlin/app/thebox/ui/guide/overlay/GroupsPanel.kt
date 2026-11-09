package app.thebox.ui.guide.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideGeometry
import app.thebox.core.guide.GroupMenuItem
import app.thebox.core.guide.GroupsShortcut
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Overlay
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val GROUPS_PANEL_TAG = "groups_panel"

/** Groups panel width, design px (it spans the grid panel's height, [GuideGeometry.PANEL_H]). */
const val GROUPS_PANEL_W = 360f

/** A row of the panel: a shortcut, a playlist section header, or an entry. [index] is the `Overlay.Groups.focused` index. */
internal sealed interface GroupsRow {
    data class Shortcut(val shortcut: GroupsShortcut, val index: Int) : GroupsRow
    data class Header(val title: String) : GroupsRow
    data class Entry(val entry: GuideSourceEntry, val index: Int, val entryIndex: Int) : GroupsRow
}

/** Shortcuts first, then entries, with a header whenever the playlist name changes (only set with > 1 playlist, §3.8.1). */
internal fun groupsRows(o: Overlay.Groups): List<GroupsRow> {
    val out = ArrayList<GroupsRow>(o.shortcuts.size + o.entries.size + 4)
    o.shortcuts.forEachIndexed { i, s -> out += GroupsRow.Shortcut(s, i) }
    var lastPlaylist: String? = null
    o.entries.forEachIndexed { i, e ->
        val p = e.playlistName
        if (p != null && p != lastPlaylist) out += GroupsRow.Header(p)
        lastPlaylist = p
        out += GroupsRow.Entry(e, o.shortcuts.size + i, i)
    }
    return out
}

/**
 * The Groups panel over the label column, (64, 500) – (424, 1024), the grid panel's height (docs/design/m3-guide.md §3.15, §3.8; S7a, E22): the
 * Search and Settings shortcut rows, then every source with its count (a locked group with the padlock, M5 §3.9.1); the
 * current source carries an accent dot. The
 * list is lazy (a playlist can have hundreds of groups) and follows the virtual focus. The group menu (Hide, Lock or
 * Unlock, Move up, Move down) opens beside the focused entry, still inside the grid area (never over the hole).
 */
@Composable
fun GroupsPanel(state: GuideUiState, modifier: Modifier = Modifier) {
    val o = state.overlay as? Overlay.Groups ?: return
    val rows = remember(o.shortcuts, o.entries) { groupsRows(o) }
    val focusedRow = rows.indexOfFirst { (it is GroupsRow.Shortcut && it.index == o.focused) || (it is GroupsRow.Entry && it.index == o.focused) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (focusedRow - 4).coerceAtLeast(0))
    LaunchedEffect(focusedRow) { if (focusedRow >= 0) keepVisible(list, focusedRow) }
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Box(modifier.offset(GuideGeometry.PANEL_L.dp, GuideGeometry.PANEL_T.dp).width(GROUPS_PANEL_W.dp).height(GuideGeometry.PANEL_H.dp).testTag(GROUPS_PANEL_TAG)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(GuideUi.OverlayFill, shape)
                .border(1.dp, GuideUi.OverlayBorder, shape)
                .padding(vertical = 14.dp),
        ) {
            Line(stringResource(R.string.guide_groups_title), GuideUi.sans(24, FontWeight.SemiBold, GuideUi.Fg50, 0.1f), Modifier.padding(start = 24.dp, bottom = 8.dp))
            LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows.size) { i ->
                    when (val r = rows[i]) {
                        is GroupsRow.Header -> Line(r.title.uppercase(), GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg50, 0.1f), Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
                        is GroupsRow.Shortcut -> GroupRow(
                            label = stringResource(if (r.shortcut == GroupsShortcut.SEARCH) R.string.guide_groups_search else R.string.guide_groups_settings),
                            count = null, focused = o.focused == r.index, current = false,
                        )
                        is GroupsRow.Entry -> GroupRow(r.entry.label, r.entry.count, o.focused == r.index, o.current == r.entryIndex, r.entry.locked, r.entry.notProtected)
                    }
                }
            }
        }
        o.menu?.let { menu ->
            GroupMenu(menu, o.menuFocused, Modifier.align(Alignment.BottomEnd).offset(x = 230.dp, y = (-16).dp))
        }
    }
}

/** Scrolls the least needed to show [index] fully (virtual focus never leaves the visible rows). */
internal suspend fun keepVisible(list: LazyListState, index: Int) {
    val info = list.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val full = visible.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
    val first = full.firstOrNull()?.index ?: visible.first().index
    val last = full.lastOrNull()?.index ?: visible.last().index
    when {
        index < first -> list.scrollToItem(index)
        index > last -> list.scrollToItem((index - (last - first)).coerceAtLeast(0))
    }
}

@Composable
private fun GroupRow(label: String, count: Int?, focused: Boolean, current: Boolean, locked: Boolean = false, notProtected: Boolean = false) {
    Chip(
        focused = focused,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        fill = Color.Transparent,
        shape = RoundedCornerShape(DesignTokens.Radii.Row),
        padding = PaddingValues(horizontal = 12.dp),
    ) { color ->
        if (current) Dot(GuideUi.Accent, 7.dp) else Box(Modifier.width(7.dp))
        Line(label, GuideUi.sans(28, FontWeight.SemiBold, color), Modifier.weight(1f))
        // M5 (§3.9.1 "Show with a lock"): a locked group keeps its count; OK asks for the PIN first.
        // U-S11 (code review 2026-10-16): no PIN protects it (FILTERED_NO_PIN): the amber 18+ chip, not the padlock.
        if (notProtected) app.thebox.ui.parental.NotProtectedChip(Modifier.padding(end = 8.dp), if (focused) color else null)
        else if (locked) app.thebox.ui.parental.LockGlyph(if (focused) color else GuideUi.Fg50, Modifier.padding(end = 8.dp))
        count?.let { Line(it.toString(), GuideUi.mono(24, color = if (focused) color else GuideUi.Fg50)) }
    }
}

@Composable
private fun GroupMenu(items: List<GroupMenuItem>, focused: Int, modifier: Modifier) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Button)
    Column(
        modifier
            .width(240.dp)
            .background(GuideUi.OverlayFill, shape)
            .border(1.dp, GuideUi.OverlayBorder, shape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { i, item ->
            Chip(i == focused, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(DesignTokens.Radii.Row), padding = PaddingValues(horizontal = 14.dp)) { color ->
                val label = when (item) {
                    GroupMenuItem.HIDE -> R.string.guide_group_hide
                    GroupMenuItem.MOVE_UP -> R.string.guide_group_up
                    GroupMenuItem.MOVE_DOWN -> R.string.guide_group_down
                    GroupMenuItem.LOCK -> R.string.guide_group_lock // M5
                    GroupMenuItem.UNLOCK -> R.string.guide_group_unlock
                    GroupMenuItem.EDIT -> R.string.guide_group_edit
                }
                Line(stringResource(label), GuideUi.sans(26, FontWeight.Medium, color))
            }
        }
    }
}
