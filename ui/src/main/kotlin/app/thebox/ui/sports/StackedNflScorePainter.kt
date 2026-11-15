package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreSpec
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamColours

/**
 * (NFL Stacked §2.5, §4.3, §5.3; WP-3) The NFL scoring takeover on the Stacked box's top part: the lead and panel wipes in
 * the scorer's true colours (ruling 12), the GA glow baked for the exact pinned box, the streaks, the ball run (TD,
 * two-point, safety) or the arc and goalposts (XP, FG), the badge (the logo at 58.5, or the `LogoDisc`), the word in
 * Barlow Condensed Black Italic fitted to ≥ `STACKED_NFL_WORD_FLOOR_PX` (§2.5 steps 1–4), the "+N" over the tally
 * "24 – 17", and the row flash. Mirrored for a right-hand corner (§5.3: wipes from the right, "/" kept, rotations
 * negated). Allocation-free in [drawGlow] / [drawTakeover].
 *
 * [s] is device px per Standard design px (× 1.25 for [large]); [wordFont] Barlow Condensed Black Italic, [sansBold]
 * Instrument Sans Bold; null = the system fallback. [spec] is the timeline's numbers the painter also reads (streak
 * alphas, ball and posts geometry): `NflScoreSpec.STACKED` in the app.
 *
 * **Commit-0 stub:** [begin] records the scorer, [bakeGlow] bakes nothing and the draws draw nothing. Commit 0 never
 * builds it (`StackedNflBug` draws the NFL Pill until WP-4).
 */
internal class StackedNflScorePainter(
    val s: Float,
    val large: Boolean,
    val wordFont: Typeface?,
    val sansBold: Typeface?,
    val spec: NflScoreSpec = NflScoreSpec.STACKED,
) {
    /** The scoring side, set by [begin]. */
    var side: Side = Side.AWAY
        private set

    /** The scorer's true colours, set by [begin]; null before the first. */
    var colours: TeamColours? = null
        private set

    /**
     * Once per takeover segment (a §5.7 pair begins twice), on the main thread before its first frame: the scorer's
     * [side] and true [colours], the segment's [scores] (one score; the runner passes the pair's segment alone), the
     * [badge] (the logo at the takeover size; null = the disc), both abbreviations, and [boxW], the pinned top part's
     * width in Standard design px (`StackedNflFrame.boxW` at takeover start: 333 in play, 243 in a break), which the
     * §2.5 fit runs against.
     */
    fun begin(side: Side, scores: List<NflScore>, colours: TeamColours, badge: Bitmap?, awayAbbrev: String, homeAbbrev: String, boxW: Float) {
        // WP-3: the §2.5 layout and fit.
        this.side = side
        this.colours = colours
    }

    /** The GA glow masks for a [w] × [h] (Standard design px) pinned box, off the main thread; null = no glow. */
    fun bakeGlow(w: Float, h: Float): GlowMasks? {
        // WP-3: the exact-box GA bake.
        return null
    }

    /** The glow of frame [g] with the baked [glow] masks, the box's top-left at ([x], [y]) device px. */
    fun drawGlow(c: Canvas, g: NflScoreFrame, glow: GlowMasks?, x: Float, y: Float) {
        // WP-3: the glow cross-fade.
    }

    /** The takeover of frame [g] over the top part (origin as `StackedNflDrawArgs`), [mirrored] for a right-hand corner. */
    fun drawTakeover(c: Canvas, g: NflScoreFrame, mirrored: Boolean) {
        // WP-3: wipes, streaks, ball, posts, flash, badge, word, block.
    }
}
