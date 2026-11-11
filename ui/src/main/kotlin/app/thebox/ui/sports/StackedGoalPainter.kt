package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalSpec
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamColours

/**
 * (Stacked §6, §7.2; Stacked WP-2) The Stacked takeover's drawing for one size: the lead and panel wipes over the box's
 * **top part** (the bar stays visible), the streaks, the flash, the badge, GOAL (Barlow Condensed Black Italic atlas, as
 * GA §2.3) and the two-line tally ("EDM 3" / "TOR 1", `STACKED_TALLY_PX`), plus the exact-box glow (B4).
 *
 * [s] and [large] as [StackedPainter]. [goalFont] is `R.font.barlow_condensed_black_italic`; [sansBold] draws the badge
 * fallback and the tally; [word] is `R.string.sports_goal_word` (exactly `GoalFrame.LETTERS` characters). [spec] is the
 * spec the runner samples with (its streak alphas are read here).
 *
 * - **Once per size** (constructor, off the main thread): paints, the GOAL atlas.
 * - **[bakeGlow]**, off the main thread, per pinned box size (the runner caches by `(w, h, size)`, LRU
 *   `STACKED_GLOW_CACHE`, and prewarms at layout rest).
 * - **[begin]**, once per takeover: colours, streak shaders, tally chars, the padded badge and its hard shadow; the content
 *   is measured against [begin]'s `boxW` (the pinned width, §4.2 fit rules: padding 18 → 8, then the tally dropped).
 * - **[drawGlow]** / **[drawTakeover]** per frame, allocation-free; called by [StackedPainter.drawFrame].
 *
 * **Commit-0 stub:** the signatures are final; [begin] records the side and colours, [bakeGlow] returns null and the draws
 * draw nothing. Stacked WP-2 fills them in.
 */
internal class StackedGoalPainter(
    val s: Float,
    val large: Boolean,
    @Suppress("unused") private val goalFont: Typeface?,
    @Suppress("unused") private val sansBold: Typeface?,
    @Suppress("unused") private val word: String,
    val spec: GoalSpec = GoalSpec.STACKED,
) {
    /** The scoring team's true colours (ruling 12; never stripe-swapped), set by [begin]; null before the first. */
    var colours: TeamColours? = null
        private set

    /** The scoring side, set by [begin]. */
    var side: Side = Side.AWAY
        private set

    /**
     * Once per takeover, on the main thread before its first frame: the scorer's [side] and [colours], the badge bitmap
     * ([badge], the logo loaded at the takeover size `STACKED_GOAL_LOGO`, S6; null = the abbreviation), both teams'
     * abbreviations and the score after the goal ([away] / [home], the tally), and [boxW], the pinned top-part width in
     * Standard design px (`StackedFrame.boxW` at takeover start).
     */
    @Suppress("UNUSED_PARAMETER")
    fun begin(
        side: Side,
        colours: TeamColours,
        badge: Bitmap?,
        awayAbbrev: String,
        homeAbbrev: String,
        away: Int,
        home: Int,
        boxW: Float,
    ) {
        this.side = side
        this.colours = colours
    }

    /**
     * (B4) GA's two glow masks (low and peak: `GOAL_GLOW_*` spreads and σ, radius `StackedMock.BOX_RADIUS` × scale) baked
     * for exactly the box [w] × [h] **device px** (the top part plus the open bar, as pinned), with the box cut out.
     * [GlowMasks.lowMargin] / [GlowMasks.peakMargin] place each bitmap's origin up and left of that box's top-left. Off the
     * main thread. Null only from the commit-0 stub or for an empty box.
     */
    @Suppress("UNUSED_PARAMETER")
    fun bakeGlow(w: Float, h: Float): GlowMasks? = null

    /**
     * The glow for [g] around the glow box, whose top-left is at ([x], [y]) device px in [StackedPainter.drawFrame]'s
     * coordinates (y is −barH when the bar is open above the box).
     */
    @Suppress("UNUSED_PARAMETER")
    fun drawGlow(c: Canvas, g: GoalFrame, glow: GlowMasks, x: Float, y: Float) = Unit

    /** The lead, the panel and its contents for [g], over the top part (origin = its top-left, device px). */
    @Suppress("UNUSED_PARAMETER")
    fun drawTakeover(c: Canvas, g: GoalFrame) = Unit
}
