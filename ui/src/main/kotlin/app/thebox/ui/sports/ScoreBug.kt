package app.thebox.ui.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugSource

const val SCORE_BUG_TAG = "score_bug"
/** Debug composition counter of [ScoreBug] (`GuideDebugCounters`; §4.7: zero per tick and per poll). */
const val COMPOSITIONS_SCORE_BUG = "ScoreBug"

/**
 * The score bug (docs/design/m6-score-bug.md §4; WP-3): a 404 × 70 fake-glass pill at the box
 * `ScoreBugPlacement.place(placement, geometry)` returns (null = not drawn), sliding 150 ms between boxes (snapped in
 * [lowEnd]). `FullScreenHost` calls it after the bars and the stats, before the channel list, in FULL only, while
 * Show Sports is on. [ScoreBugSource.bug] and [ScoreBugSource.clock] are collected **here**, never in the host; the
 * clock is read only in the clock node's own layer (A-S10). With no followed game and [PlacementInput.placing], it
 * draws the sample bug ("AWY 0 | 0:00 | HME 0").
 *
 * Commit 0: the call site and the signature; draws nothing.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ScoreBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    lowEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_SCORE_BUG) }
}
