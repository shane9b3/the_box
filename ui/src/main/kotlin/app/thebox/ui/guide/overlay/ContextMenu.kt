package app.thebox.ui.guide.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideGeometry
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.Overlay
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val CONTEXT_MENU_TAG = "context_menu"

private const val MENU_W = 380f
private const val ITEM_H = 52f
private const val MENU_PAD = 8f
private const val ITEM_GAP = 4f

/**
 * Where an anchored overlay of [w] × [h] design px goes (docs/design/m3-guide.md §3.15): below the focused cell if it
 * fits above y 960, else above it but never above y 470; x starts at the cell and is clamped to the grid panel. No
 * anchor (cell off-screen): centred in the grid panel. Pure.
 */
fun anchoredOrigin(cell: Rect?, w: Float, h: Float): Offset {
    val panelL = GuideGeometry.PANEL_L
    val panelR = GuideGeometry.PANEL_L + GuideGeometry.PANEL_W
    val bottomLimit = GuideGeometry.PANEL_T + GuideGeometry.PANEL_H
    if (cell == null) return Offset(panelL + (GuideGeometry.PANEL_W - w) / 2f, GuideGeometry.PANEL_T + (GuideGeometry.PANEL_H - h) / 2f)
    val x = cell.left.coerceIn(panelL, (panelR - w).coerceAtLeast(panelL))
    val gap = 8f
    val below = cell.bottom + gap
    val y = if (below + h <= bottomLimit) below else (cell.top - gap - h).coerceAtLeast(MIN_TOP)
    return Offset(x, y)
}

/** Overlays never go above this y (the hole ends at 461, §4.5). */
const val MIN_TOP = 470f

/**
 * Context menu (long OK / Menu / More…, docs/design/m3-guide.md §3.9.3): only the items the machine offers, in its
 * order; anchored to the focused cell ([anchor], design px).
 */
@Composable
fun ContextMenu(state: GuideUiState, anchor: Rect?, modifier: Modifier = Modifier) {
    val o = state.overlay as? Overlay.Menu ?: return
    val h = MENU_PAD * 2 + o.items.size * ITEM_H + (o.items.size - 1).coerceAtLeast(0) * ITEM_GAP
    val at = anchoredOrigin(anchor, MENU_W, h)
    val shape = RoundedCornerShape(DesignTokens.Radii.Button)
    Column(
        modifier
            .offset(at.x.dp, at.y.dp)
            .width(MENU_W.dp)
            .testTag(CONTEXT_MENU_TAG)
            .background(GuideUi.OverlayFill, shape)
            .border(1.dp, GuideUi.OverlayBorder, shape)
            .padding(MENU_PAD.dp),
        verticalArrangement = Arrangement.spacedBy(ITEM_GAP.dp),
    ) {
        o.items.forEachIndexed { i, item ->
            Chip(i == o.focused, Modifier.fillMaxWidth().height(ITEM_H.dp), shape = RoundedCornerShape(DesignTokens.Radii.Row), padding = PaddingValues(horizontal = 16.dp)) { color ->
                Line(stringResource(menuLabel(item)), GuideUi.sans(24, FontWeight.Medium, color))
            }
        }
    }
}

fun menuLabel(item: MenuItem): Int = when (item) {
    MenuItem.WATCH -> R.string.guide_menu_watch
    MenuItem.WATCH_FROM_START -> R.string.guide_menu_watch_from_start
    MenuItem.REMIND -> R.string.guide_menu_remind
    MenuItem.CANCEL_REMINDER -> R.string.guide_menu_cancel_reminder
    MenuItem.ADD_FAVOURITE -> R.string.guide_menu_add_favourite
    MenuItem.REMOVE_FAVOURITE -> R.string.guide_menu_remove_favourite
    MenuItem.HIDE_CHANNEL -> R.string.guide_menu_hide_channel
    // M5 (§2.12 item 2)
    MenuItem.LOCK_CHANNEL -> R.string.guide_menu_lock_channel
    MenuItem.UNLOCK_CHANNEL -> R.string.guide_menu_unlock_channel
    MenuItem.FIX_GUIDE_DATA -> R.string.guide_menu_fix_guide_data
    MenuItem.CHANNEL_OPTIONS -> R.string.guide_menu_channel_options
}
