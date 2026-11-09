package app.thebox.ui.guide.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideGeometry
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.Zone
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
 *   focused-programme panel (top-aligned under the tabs, owner request 2026-11-09);
 * - the grid slot (64, 500) 1792 × 524, down to the bottom safe margin (y 1024): [grid] gets the modifier (WP-A's
 *   `GuideGridHost` carries its own layer);
 * - header chips and the overlays. No overlay intersects the hole (I-3). There is no footer hint row: the owner asked
 *   for it to go and for the grid to fill the space (decision 2026-11-09).
 *
 * This composable is a child of the root key sink and never an ancestor of `VideoLayer`.
 *
 * (code review 2026-10-14, H-S9) `GuideUiState` is a new instance on every change, so the children take narrow
 * slices (the machine keeps unchanged slices identical), and strong skipping skips every child whose slice did not
 * change: a grid move recomposes the panel only, a Tuned the mini overlay only.
 */
/** Trace section around the header/panel column draw (I-18, §2.13 item 20). */
const val TRACE_PANEL = "guide-panel"

/** Tabs → panel gap, design px (§3.15 "gap 16"). */
private const val PANEL_GAP = 16

@Composable
fun GuideScreen(
    state: GuideUiState,
    clock24: Boolean,
    poster: Boolean,
    anchor: Rect?,
    onHoleMeasured: (RectPx) -> Unit,
    modifier: Modifier = Modifier,
    debugHint: Boolean = false,
    scrollDescriptions: Boolean = true,
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
        ) { MiniPlayerOverlay(state.tuned, state.tunedChannel, poster) } // owner ruling 2026-11-01: video only

        Column(
            Modifier
                .offset(GuideGeometry.HEAD_L.dp, GuideGeometry.HEAD_T.dp)
                .size((GuideGeometry.CANVAS_W - GuideGeometry.HEAD_L - GuideGeometry.HEAD_R).dp, GuideGeometry.HEAD_H.dp)
                .testTag(HEADER_COLUMN_TAG)
                .graphicsLayer()
                .drawWithContent {
                    // I-18 (B8): this section must not fire on grid-only frames.
                    android.os.Trace.beginSection(TRACE_PANEL)
                    try { drawContent() } finally { android.os.Trace.endSection() }
                },
        ) {
            HeaderTabs(state.zone, state.tabFocused, state.sync, clock24)
            // (owner request 2026-11-09) The panel sits at the top, under the tabs (gap 16, §3.15), and its description
            // fills the rest of the column down to y 461; a scrolling description re-records only this layer (I-18).
            Spacer(Modifier.height(PANEL_GAP.dp))
            FocusedPanel(state.panel, Modifier.weight(1f), scrollDescription = scrollDescriptions)
        }

        grid(
            Modifier
                .offset(GuideGeometry.PANEL_L.dp, GuideGeometry.PANEL_T.dp)
                .size(GuideGeometry.PANEL_W.dp, GuideGeometry.PANEL_H.dp)
                .testTag(GRID_SLOT_TAG),
        )
        GridHeaderChips(
            chips = state.headerChips,
            focusedChip = if (state.zone == Zone.HEADER) state.headerChips.getOrNull(state.headerFocused) else null,
            sourceLabel = state.sourceLabel,
            nowDirection = state.nowDirection,
            dayLabel = state.dayLabel,
        )
        EmptyState(state.empty, debugHint = debugHint)

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
