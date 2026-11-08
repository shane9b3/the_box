// Contract (NFL commit 0, docs/design/m6-nfl.md §5.2, §5.5, §11): the signatures are fixed for WP-3 (the body) and WP-4
// (the caller, `NflRun`); changes go through the lead.
package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamColours

/**
 * (NFL §5.2, §5.5; WP-3) The NFL scoring takeover in plain `android.graphics`, on the GA rules (§2): one de-duplicated
 * glyph atlas per size for the five words (20 glyphs, `ARGB_8888` at `NFL_ATLAS_SCALE`, each with its hard shadow), the
 * ball and goalpost sprites (`ALPHA_8`), the GA glow masks for the NFL pill. [s] is device px per design px with Large
 * folded in; [large] picks the Large glow table.
 *
 * - **Built here, once per size:** the paints, the atlas, the sprites, the digit tables.
 * - **[bakeGlow]**, off the main thread, never in low-end mode.
 * - **[begin]**, once per takeover (or per segment of a §5.7 pair): the colours, the streak shaders, the tally chars,
 *   the padded badge.
 * - **[drawGlow]** before the glass, **[drawTakeover]** after the body (§5.5's order), allocation-free: only `save` /
 *   `restore` / `clipRect` / `translate` / `rotate` / `scale` / `drawRoundRect` / `drawBitmap` / `drawText(char[])`.
 *
 * Commit 0: every body is `TODO()`; nothing calls it until WP-4's `NflRun`.
 */
internal class NflScorePainter(
    val s: Float,
    val pillW: Float,
    val pillH: Float,
    private val wordFont: Typeface?,
    private val sansBold: Typeface?,
    val large: Boolean,
) {
    /** The scorer of the current takeover ([begin]). */
    var side: Side = Side.AWAY
        private set

    /** The current takeover's colours; null before the first [begin]. */
    var colours: TeamColours? = null
        private set

    fun bakeGlow(): GlowMasks = TODO("NFL WP-3: §5.5")

    /** One takeover (or one segment of a pair): [score]'s word, "+N" and its tally, the [badge] (the held logo, or null). */
    fun begin(side: Side, colours: TeamColours, badge: Bitmap?, abbrev: String, score: NflScore): Unit = TODO("NFL WP-3: §5.2")

    fun drawGlow(c: Canvas, g: NflScoreFrame, glow: GlowMasks): Unit = TODO("NFL WP-3: §5.5")

    fun drawTakeover(c: Canvas, g: NflScoreFrame): Unit = TODO("NFL WP-3: §5.1, §5.5")
}
