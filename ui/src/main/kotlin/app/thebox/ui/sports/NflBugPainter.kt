// Contract (NFL commit 0, docs/design/m6-nfl.md §4.1, §4.2, §5.5, §11): the signatures are fixed for WP-3 (the body)
// and WP-4 (the caller, `NflBug`); changes go through the lead.
package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.SportsConstants as K

/**
 * (NFL §4.1, §4.2) What the NFL bug shows once its words are resolved: built by [NflBugPainter.frame] when the view
 * changes (never per frame). Sizes are design px, already fitted.
 *
 * Two-line states put the quarter on top ([topText]) and the clock below ([clockOnBottom], from the digit table), or a
 * dimmed "No update" / "Delayed" over the clock. One-line states ([oneLine]: Final, Final/OT, Halftime, End 1st…) draw
 * [topText] across the centre and down columns with the middle divider hidden. [down] is the down column ("1st & 10",
 * "PAT", "KICKOFF"), null = blank.
 */
internal class NflBugFrame(
    val awayAbbrev: String,
    val homeAbbrev: String,
    val awayScore: String,
    val homeScore: String,
    val topText: String?,
    val topPx: Float,
    val clockOnBottom: Boolean,
    /** The clock as the shown snapshot reports it (owner ruling 6); read only when [clockOnBottom]. */
    val clockSecs: Int,
    val oneLine: Boolean,
    val down: String?,
    val downPx: Float,
    /** The centre column is dimmed (stale, delayed). */
    val dim: Boolean,
)

/**
 * (NFL §4.1, §4.2, §5.5; WP-3) The NFL bug's whole drawing: the 520 × 70 pill (Large 650 × 88) with the NHL's glass,
 * type families and dividers, plus the 111 px down column. [drawFrame] is the one composite the app and the screenshot
 * tests both call, in §5.5's order: glow, glass, tab, body (with the scorer's pop), takeover. [s] is device px per design
 * px (the Large size folded in). Paints and digit tables are built once; a draw allocates nothing.
 *
 * Commit 0: the glass is the NHL's own bake at the NFL width (so `NflBug`'s stub can draw it); [frame] and [drawFrame] are
 * `TODO()` (WP-3).
 */
internal class NflBugPainter(
    val s: Float,
    sansSemi: Typeface?,
    sansBold: Typeface?,
    val pillW: Float = K.NFL_SCORE_W * s,
    val pillH: Float = K.SCORE_H * s,
) {
    /** The shared glass: the same layers, ring, fill and highlight as the NHL pill (§4.1 "from the NHL"). */
    private val glassPainter = ScoreBugPainter(s, sansSemi, sansBold, pillW, pillH)

    /** The glass bitmap's origin relative to the pill (its shadow margin), device px. */
    val glassOriginX: Float get() = glassPainter.glassOriginX
    val glassOriginY: Float get() = glassPainter.glassOriginY

    fun bakeGlass(withShadow: Boolean): Bitmap = glassPainter.bakeGlass(withShadow)

    fun drawGlass(c: Canvas, glass: Bitmap) = glassPainter.drawGlass(c, glass)

    /** The resolved words of [v] (an NFL view: `league == NFL`, [ScoreBugView.nfl] read for the down column). */
    fun frame(v: ScoreBugView, res: Resources): NflBugFrame = TODO("NFL WP-3: §4.2")

    /**
     * The bug's whole frame in the pill's own coordinates (the canvas already translated by the layer margin `M`), in
     * §5.5's order. [score] / [g] null, a takeover never begun, or [NflScoreFrame.done] = no takeover: the plain bug.
     * [tab] / [p] null = no tab; [content] its words, [up] mirrors it above the pill, [redZone] and [pulse] as
     * [NflTabPainter.draw]. [popDigits] overrides the scorer's digits during a §5.7 pair (the intermediate tally, the
     * no-spoiler hold); null = the frame's own.
     */
    fun drawFrame(
        c: Canvas,
        glass: Bitmap,
        f: NflBugFrame,
        logos: FollowLogos?,
        logoWaited: Boolean,
        placing: Boolean,
        score: NflScorePainter?,
        g: NflScoreFrame?,
        glow: GlowMasks?,
        glowOn: Boolean,
        tab: NflTabPainter? = null,
        p: PpFrame? = null,
        content: NflTabContent? = null,
        up: Boolean = false,
        redZone: Float = 0f,
        pulse: Float = 0f,
        popDigits: String? = null,
    ): Unit = TODO("NFL WP-3: §5.5")
}
