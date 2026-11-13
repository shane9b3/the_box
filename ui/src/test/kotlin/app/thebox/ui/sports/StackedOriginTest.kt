package app.thebox.ui.sports

import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsConstants as K
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * (Owner request 2026-11-13; fix pass, review L1) [stackedOriginDx], pure, at 720p, 1080p and 4K (device px per design px
 * 1280 / 1920, 1, 2), both sizes, all four corners, at the three box widths (333 play, 391.5 power play, 301.5 break):
 * - left corners: the origin is 0, exactly as before the mirror;
 * - right corners: the rows' outer (right) edge lands on a whole device pixel, the same one at every box width (the
 *   rows never move), and on screen it is the right margin (1920 − 96 design px) rounded to the pixel grid.
 */
class StackedOriginTest {
    private val densities = listOf(1280f / 1920f, 1f, 2f)
    private val widths = listOf(
        K.STACKED_ROWS_W + K.STACKED_CLOCK_W,
        K.STACKED_ROWS_W + K.STACKED_SOG_W + K.STACKED_CLOCK_W,
        K.STACKED_ROWS_W + K.STACKED_SOG_W,
    )

    @Test
    fun theMirroredRowsSnapToAWholePixelAndNeverMove() {
        for (d in densities) for (size in ScoreBugSize.entries) for (corner in ScoreCorner.entries) {
            val k = ScoreBugPlacement.stackedScale(size)
            val m = if (size == ScoreBugSize.LARGE) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
            val foot = ScoreBugPlacement.cornerBox(corner, size, League.NHL, NhlBugStyle.STACKED)
            val footW = ScoreBugPlacement.width(size, League.NHL, NhlBugStyle.STACKED)
            val mirrored = ScoreBugPlacement.stackedMirrored(foot)
            val rowsW = K.STACKED_ROWS_W * k
            // The layer's left edge on screen, as `stackedPlaceAt` places it.
            val layerL = ((foot.l - m) * d).roundToInt()
            val lefts = HashSet<Float>()
            for (w in widths) {
                val top = ScoreBugPlacement.stackedTopBox(foot, size, w)
                val tagX = top.l - foot.l
                val origin = stackedOriginDx(mirrored, m, footW, rowsW, tagX, d)
                val msg = "${d}x $size $corner $w"
                if (!mirrored) {
                    assertEquals("$msg: a left corner's origin is 0", 0f, origin, 0f)
                    assertEquals("$msg: a left corner's box starts at the footprint", 0f, tagX, 0f)
                    continue
                }
                val rowsLeft = ((m + tagX) * d).roundToInt() + origin // in the layer, device px
                val outer = rowsLeft + rowsW * d
                assertEquals("$msg: the outer edge is a whole px ($outer)", 0f, abs(outer - Math.round(outer)), 1e-3f)
                assertEquals("$msg: the outer edge", ((m + footW) * d).roundToInt().toFloat(), outer, 1e-3f)
                val screen = layerL + outer
                assertEquals("$msg: on screen, the right margin on the pixel grid", (1920f - K.SCORE_INSET_X) * d, screen, 0.5f + 1e-3f)
                lefts += Math.round(rowsLeft * 1000f) / 1000f
            }
            if (mirrored) assertEquals("${d}x $size $corner: the rows never move between box widths ($lefts)", 1, lefts.size)
        }
    }
}
