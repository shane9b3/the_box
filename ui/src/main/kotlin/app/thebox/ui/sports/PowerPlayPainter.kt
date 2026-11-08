package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.PpContent
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamColours

/**
 * (Power play, docs/design/m6-power-play.md §3.4, §4; WP-2) The power-play tab, the pill tint and the logo halo for one
 * size, drawn with no real-time blur: every soft edge is an `ALPHA_8` bake.
 *
 * Commit 0 stub: the signatures are fixed for WP-2 (the bodies) and WP-3 (the callers); every body is a no-op, so the
 * composite draws the plain bug.
 *
 * [s] is device px per design px with the Large factor folded in (as [ScoreBugPainter.s]); [d] is device px per design
 * px without it (σ is × [d], N1); [pillW] × [pillH] is the pill's device size; [sansBold] draws the labels and digits.
 *
 * - **[bakeSize]**, once per size, off the main thread: the tab shadow mask (never in low-end mode), the shine shader,
 *   the paints and the `m:ss` digit table.
 * - **[bakeLabels]**, per size and teams (keyed on `(s, shownLarge, away, home)`, S8), off the main thread: the five
 *   label masks.
 * - **[bakeHalo]**, per logo bitmap identity, never in low-end mode: the halo mask under [side]'s logo.
 * - **[begin]**, once per transition start, in the runner: the tab's and the tint's gradients for [side].
 * - **[draw]** per frame, with no allocation: the tab (under the outside `clipRect`), the tint, the halo.
 */
internal class PowerPlayPainter(
    val s: Float,
    val d: Float,
    val pillW: Float,
    val pillH: Float,
    private val sansBold: Typeface?,
) {
    /** The per-size bakes (§3.4). Commit 0: no-op. */
    fun bakeSize() {
    }

    /**
     * The five label masks for [away] and [home] (abbreviations): `{away|home} POWER PLAY`, `{away|home} 5-ON-3` and
     * `PP GOAL`, from [res] (`R.string.sports_pp_label`, `sports_pp_two_man`, `sports_pp_goal`). Commit 0: no-op.
     */
    fun bakeLabels(away: String, home: String, res: Resources) {
    }

    /** The halo mask for [side]'s [logo] (the bug's held `_dark` logo bitmap). Commit 0: no-op. */
    fun bakeHalo(side: Side, logo: Bitmap) {
    }

    /** Fixes the tab's vertical gradient and the tint's horizontal gradient for [side] in [colours]. Commit 0: no-op. */
    fun begin(side: Side, colours: TeamColours) {
    }

    /**
     * Draws [p] in the pill's own coordinates, after the glass and before the body (§3.5 steps 3–5): the tab with
     * [content] (skipped when [PpFrame.tabClosed] or [content] is null), then the tint and the halo on [pillSide]
     * (skipped when [PpFrame.pillClosed] or [pillSide] is null). [up]: the tab opens above the pill (bottom corners);
     * it mirrors position, slide, text offset and gradient, never the text, the shine's lean or the shadow's dy (N7).
     * Commit 0: no-op.
     */
    fun draw(c: Canvas, p: PpFrame, content: PpContent?, pillSide: Side?, up: Boolean) {
    }
}
