package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamColours

/**
 * (Goal animation, docs/design/m6-goal-animation.md §2.4) The glow's two pre-baked `ALPHA_8` masks of one size, tinted at
 * draw time (no real-time blur). Each covers the pill inflated by its margin (spread + 3σ, device px), with the pill cut
 * out; [lowMargin] / [peakMargin] are where each bitmap's origin sits up and left of the pill's top-left.
 */
class GlowMasks(val low: Bitmap, val peak: Bitmap, val lowMargin: Float, val peakMargin: Float)

/**
 * (Goal animation §2.2–§2.5; WP-2) The takeover's drawing for one size: built once per size ([s] device px per design
 * px, the Large factor folded in; [pillW] × [pillH] device px), [begin] once per takeover, [draw] per frame with no
 * allocation. [goalFont] is Barlow Condensed Black Italic (`R.font.barlow_condensed_black_italic`); [sansBold] draws
 * the badge fallback and the tally.
 *
 * Commit 0 stub: the constructor holds its arguments; every body is WP-2's.
 */
internal class GoalPainter(
    val s: Float,
    val pillW: Float,
    val pillH: Float,
    private val goalFont: Typeface?,
    private val sansBold: Typeface?,
) {
    /** Bakes the low and peak glow masks (off the main thread; never in low-end mode). */
    fun bakeGlow(): GlowMasks = TODO("WP-2")

    /**
     * Fixes one takeover: the scoring [side], its [colours], the [badge] (the bug's held `_dark` logo, or null for the
     * abbreviation fallback in white), [abbrev], and the tally [away] – [home]. Builds the streak shaders, the tally
     * chars and the padded badge copy. Called from the start effect, never in draw.
     */
    @Suppress("UNUSED_PARAMETER")
    fun begin(side: Side, colours: TeamColours, badge: Bitmap?, abbrev: String, away: Int, home: Int): Unit = TODO("WP-2")

    /** The takeover at frame [g] in pill coordinates: the glow (if [glowOn] and [glow] is ready), lead, panel and contents. */
    @Suppress("UNUSED_PARAMETER")
    fun draw(c: Canvas, g: GoalFrame, glow: GlowMasks?, glowOn: Boolean): Unit = TODO("WP-2")
}
