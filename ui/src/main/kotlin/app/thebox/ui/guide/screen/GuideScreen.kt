package app.thebox.ui.guide.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideGeometry
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Overlay
import app.thebox.core.playback.RectPx
import app.thebox.ui.guide.overlay.ContextMenu
import app.thebox.ui.guide.overlay.DayStrip
import app.thebox.ui.guide.overlay.EmptyState
import app.thebox.ui.guide.overlay.ExitHint
import app.thebox.ui.guide.overlay.GroupsPanel
import app.thebox.ui.guide.overlay.NumberEntryOverlay
import app.thebox.ui.guide.overlay.ProgrammeSheet
import app.thebox.ui.root.HoleGeometry

const val GUIDE_SCREEN_TAG = "guide_screen"
const val HOLE_SLOT_TAG = "hole_slot"
const val HEADER_COLUMN_TAG = "header_column"
const val GRID_SLOT_TAG = "grid_slot"

/**
 * The GUIDE screen (docs/design/m3-guide.md §3.15), absolute design px on the 1920 × 1080 canvas:
 * - the hole slot (64, 56) 720 × 405: transparent; publishes its `boundsInWindow(clipBounds = false)` through
 *   [onHoleMeasured] (the root dedupes) and holds the mini-player overlay;
 * - the header/panel column (832, 56) – (1856, 461) with a default `graphicsLayer()` (B8): tabs, sync chip, clock,
 *   focused-programme panel;
 * - the grid slot (64, 500) 1792 × 460: [grid] gets the modifier (WP-A's `GuideGridHost` carries its own layer);
 * - header chips, footer hints, and the overlays. No overlay intersects the hole (I-3).
 *
 * This composable is a child of the root key sink and never an ancestor of `VideoLayer`.
 */
@Composable
fun GuideScreen(
    state: GuideUiState,
    clock24: Boolean,
    lowEnd: Boolean,
    poster: Boolean,
    anchor: Rect?,
    onHoleMeasured: (RectPx) -> Unit,
    modifier: Modifier = Modifier,
    debugHint: Boolean = false,
    grid: @Composable (Modifier) -> Unit,
) {
    Box(modifier.fillMaxSize().testTag(GUIDE_SCREEN_TAG)) {
        Box(
            Modifier
                .offset(GuideGeometry.HOLE_L.dp, GuideGeometry.HOLE_T.dp)
                .size(GuideGeometry.HOLE_W.dp, GuideGeometry.HOLE_H.dp)
                .testTag(HOLE_SLOT_TAG)
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow(clipBounds = false)
                    onHoleMeasured(HoleGeometry.round(b.left, b.top, b.right, b.bottom))
                },
        ) { MiniPlayerOverlay(state, clock24, lowEnd, poster) }

        Column(
            Modifier
                .offset(GuideGeometry.HEAD_L.dp, GuideGeometry.HEAD_T.dp)
                .size((GuideGeometry.CANVAS_W - GuideGeometry.HEAD_L - GuideGeometry.HEAD_R).dp, GuideGeometry.HEAD_H.dp)
                .testTag(HEADER_COLUMN_TAG)
                .graphicsLayer(),
        ) {
            HeaderTabs(state, clock24)
            Spacer(Modifier.weight(1f))
            FocusedPanel(state)
        }

        grid(
            Modifier
                .offset(GuideGeometry.PANEL_L.dp, GuideGeometry.PANEL_T.dp)
                .size(GuideGeometry.PANEL_W.dp, GuideGeometry.PANEL_H.dp)
                .testTag(GRID_SLOT_TAG),
        )
        GridHeaderChips(state)
        EmptyState(state, debugHint = debugHint)

        FooterHints(state, Modifier.align(Alignment.BottomStart).padding(start = GuideGeometry.FOOTER_L.dp, bottom = GuideGeometry.FOOTER_B.dp))

        when (val o = state.overlay) {
            is Overlay.Groups -> GroupsPanel(state)
            is Overlay.Days -> DayStrip(state)
            is Overlay.Menu -> ContextMenu(state, anchor)
            is Overlay.Sheet -> ProgrammeSheet(state, anchor)
            is Overlay.Number -> NumberEntryOverlay(o, Modifier.offset(1520.dp, 72.dp).size(320.dp, 160.dp))
            is Overlay.Exit -> ExitHint(state, if (o.dialog) Modifier else Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 96.dp))
            null -> Unit
        }
    }
}
