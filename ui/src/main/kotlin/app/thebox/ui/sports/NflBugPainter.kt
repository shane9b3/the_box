// Contract (NFL commit 0, docs/design/m6-nfl.md §4.1, §4.2, §5.5, §11): the signatures are fixed for WP-3 (the body)
// and WP-4 (the caller, `NflBug`); changes go through the lead.
package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.DownText
import app.thebox.core.sports.League
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.ui.R
import app.thebox.ui.theme.DesignTokens

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
 * ```
 * | 16 | logo 64×46 | 8 | score 50 | 12 | ┃1 | centre 106 | ┃1 | down 111 | ┃1 | 12 | score 50 | 8 | logo 64×46 | 16 |
 * ```
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

    private val fg = DesignTokens.Colors.Fg.toArgb()
    private val accent = DesignTokens.Colors.Accent.toArgb()
    private val radius = pillH / 2f

    private fun text(px: Float, tf: Typeface?, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * s; typeface = tf; color = fg; textAlign = align }

    private val score = text(K.SCORE_TEXT_PX.toFloat(), sansBold)
    private val clock = text(K.NFL_CLOCK_PX, sansBold, Paint.Align.LEFT).apply { fontFeatureSettings = "'tnum'" }
    private val top = text(K.NFL_QUARTER_PX, sansSemi)
    private val downPaint = text(K.NFL_DOWN_PX, sansBold)
    private val measureSemi = text(K.NFL_QUARTER_PX, sansSemi)
    private val measureBold = text(K.NFL_DOWN_PX, sansBold)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val bounds = Rect()
    private val fm = Paint.FontMetrics()
    private val disc = LogoDisc(s, sansBold)

    private val scoreBaselineOffset: Float
    private val digitAdvance = FloatArray(10)
    private val colonAdvance: Float
    private val cell: Float
    private val clockCap: Float
    private val clockBuf = CharArray(8)

    // ---- columns (§4.1), device px
    private val logoCx = (K.SCORE_PAD + K.SCORE_LOGO_W / 2f) * s
    private val scoreCx = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.NFL_SCORE_DIGITS_W / 2f) * s
    private val dw = (K.SCORE_DIVIDER_W * s).coerceAtLeast(1f)
    private val dh = K.SCORE_DIVIDER_H * s
    /** The three dividers' left edges: after the away score, after the centre, before the home score. */
    private val d1 = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.NFL_SCORE_DIGITS_W + K.SCORE_DIVIDER_GAP) * s
    private val d2 = d1 + dw + K.SCORE_CENTRE_W * s
    private val d3 = pillW - d1 - dw
    private val centreCx = (d1 + dw + d2) / 2f
    private val downCx = (d2 + dw + d3) / 2f
    private val oneLineCx = (d1 + dw + d3) / 2f
    private val quarterCy = QUARTER_CY * s
    private val clockCy = CLOCK_CY * s

    init {
        score.getTextBounds("0", 0, 1, bounds)
        scoreBaselineOffset = -(bounds.top + bounds.bottom) / 2f
        var c = 0f
        for (d in 0..9) { digitAdvance[d] = clock.measureText(DIGITS, d, d + 1); c = maxOf(c, digitAdvance[d]) }
        cell = c
        colonAdvance = clock.measureText(":")
        clock.getTextBounds("0", 0, 1, bounds)
        clockCap = -(bounds.top + bounds.bottom) / 2f
    }

    // ------------------------------------------------------------------ words and fit

    /** [startPx] stepped down by 2 while [text] is wider than [room] design px, never below 18. Design px. */
    private fun fit(text: String, startPx: Float, room: Float, p: Paint): Float {
        var px = startPx
        while (px > K.SCORE_TEXT_FLOOR_PX) {
            p.textSize = px * s
            if (p.measureText(text) <= room * s) break
            px -= K.SCORE_FIT_STEP_PX
        }
        return px.coerceAtLeast(K.SCORE_TEXT_FLOOR_PX.toFloat())
    }

    /** One centre line in the NFL's words ("4th" is `sports_period_4`; the NHL's own text for the rest). */
    private fun centre(res: Resources, c: CentreText, v: ScoreBugView): String = when (c) {
        is CentreText.Period -> SportsText.period(res, c.period, League.NFL)
        is CentreText.EndOf -> res.getString(R.string.sports_end_of, SportsText.period(res, c.period, League.NFL))
        else -> SportsText.centre(res, c, v, v.clockSecs ?: 0)
    }

    /** The down column's words (§4.2): "1st & 10", "4th & Goal", "3rd", "PAT", "KICKOFF". */
    private fun down(res: Resources, d: DownText): String = when (d) {
        is DownText.Down -> {
            val n = res.getString(
                when (d.down) { 1 -> R.string.sports_down_1; 2 -> R.string.sports_down_2; 3 -> R.string.sports_down_3; else -> R.string.sports_down_4 },
            )
            when {
                d.goal -> res.getString(R.string.sports_down_goal, n)
                d.distance != null -> res.getString(R.string.sports_down_and, n, d.distance)
                else -> n
            }
        }
        DownText.Pat -> res.getString(R.string.sports_down_pat)
        DownText.Kickoff -> res.getString(R.string.sports_down_kickoff)
    }

    /** The resolved words of [v] (an NFL view: `league == NFL`, [ScoreBugView.nfl] read for the down column). */
    fun frame(v: ScoreBugView, res: Resources): NflBugFrame {
        val oneLine = v.bottom == null
        val clockOnBottom = v.bottom == CentreText.Clock
        val topText = if (v.top == CentreText.Clock && oneLine) SportsText.clock(v.clockSecs ?: 0) else centre(res, v.top, v)
        val topPx = if (oneLine) {
            fit(topText, K.NFL_ONE_LINE_PX, (d3 - d1 - dw) / s - FIT_BREATHING, measureSemi)
        } else {
            fit(topText, K.NFL_QUARTER_PX, K.SCORE_CENTRE_W - FIT_BREATHING, measureSemi)
        }
        val downText = if (oneLine) null else v.nfl?.down?.let { down(res, it) }
        val downPx = downText?.let { fit(it, K.NFL_DOWN_PX, K.NFL_DOWN_W - K.NFL_DOWN_FIT_AIR, measureBold) } ?: K.NFL_DOWN_PX
        return NflBugFrame(
            awayAbbrev = v.away.abbrev,
            homeAbbrev = v.home.abbrev,
            awayScore = (v.away.score ?: 0).toString(),
            homeScore = (v.home.score ?: 0).toString(),
            topText = topText,
            topPx = topPx,
            clockOnBottom = clockOnBottom,
            clockSecs = v.clockSecs ?: 0,
            oneLine = oneLine,
            down = downText,
            downPx = downPx,
            dim = v.dim,
        )
    }

    // ------------------------------------------------------------------ the composite (§5.5)

    /**
     * The bug's whole frame in the pill's own coordinates (the canvas already translated by the layer margin `M`), in
     * §5.5's order. [score] / [g] null, a takeover never begun, or [NflScoreFrame.done] = no takeover: the plain bug.
     * [tab] / [p] null = no tab; [content] its words, [up] mirrors it above the pill, [redZone] and [pulse] as
     * [NflTabPainter.draw]. [popDigits] overrides the scorer's digits during a §5.7 pair (the intermediate tally, the
     * no-spoiler hold); null = the frame's own.
     *
     * The pop: while [NflScoreFrame.popOn], the scorer's digits ([NflScorePainter.side]) are drawn at the clamped
     * [NflScoreFrame.popScale] in the team's `pop` lerped to the body's white by [NflScoreFrame.popMix]. [popDigits]
     * applies whenever [score] has begun, popping or not.
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
    ) {
        val colours = score?.colours
        val override = if (score != null && colours != null && popDigits != null) score.side else null
        if (score == null || g == null || colours == null || g.done) {
            drawGlass(c, glass)
            if (tab != null && p != null) tab.draw(c, p, content, up, redZone, pulse)
            drawBody(c, f, logos, logoWaited, placing, override, popDigits)
            return
        }
        // 1. Glow, behind the glass (only while allowed and baked; never baked in low-end mode).
        if (glowOn && glow != null) score.drawGlow(c, g, glow)
        // 2. Glass. 3. The tab (its ADD pulse pass while pinned), outside the pill.
        drawGlass(c, glass)
        if (tab != null && p != null) tab.draw(c, p, content, up, redZone, pulse)
        // 4. Body, with the scorer's pop.
        if (g.popOn) {
            val side = score.side
            val text = popDigits ?: if (side == Side.AWAY) f.awayScore else f.homeScore
            drawBody(c, f, logos, logoWaited, placing, side, popDigits, clampPop(text, g.popScale), lerpColour(colours.pop, fg, g.popMix), true)
        } else {
            drawBody(c, f, logos, logoWaited, placing, override, popDigits)
        }
        // 5. Takeover.
        score.drawTakeover(c, g)
    }

    /** The popped [text] (40 px bold) stays within the 50 px column + 2 × 8; never below 1 from the clamp itself. */
    fun clampPop(text: String, scale: Float): Float {
        val w = score.measureText(text)
        if (w <= 0f) return scale
        return minOf(scale, maxOf(1f, (K.NFL_SCORE_DIGITS_W + 2 * 8f) * s / w))
    }

    // ------------------------------------------------------------------ body (§4.1, §4.2)

    /**
     * Logos (or discs), scores, the dividers, the centre (quarter over clock, or one line across centre and down), the
     * down column and the place-mode ring. [side] non-null: that side's digits are [digits] (null = the frame's own),
     * drawn at [popScale] in [popColour] when [pop].
     */
    fun drawBody(
        c: Canvas,
        f: NflBugFrame,
        logos: FollowLogos?,
        logoWaited: Boolean,
        placing: Boolean,
        side: Side? = null,
        digits: String? = null,
        popScale: Float = 1f,
        popColour: Int = fg,
        pop: Boolean = false,
    ) {
        val cy = pillH / 2f
        logo(c, logoCx, cy, f.awayAbbrev, logos?.away, logos != null, logoWaited)
        logo(c, pillW - logoCx, cy, f.homeAbbrev, logos?.home, logos != null, logoWaited)
        val away = if (side == Side.AWAY && digits != null) digits else f.awayScore
        val home = if (side == Side.HOME && digits != null) digits else f.homeScore
        scoreDigits(c, away, scoreCx, cy, pop && side == Side.AWAY, popScale, popColour)
        scoreDigits(c, home, pillW - scoreCx, cy, pop && side == Side.HOME, popScale, popColour)

        fill.color = argb(K.SCORE_DIVIDER_ALPHA, 0xFFFFFF)
        c.drawRect(d1, cy - dh / 2f, d1 + dw, cy + dh / 2f, fill)
        if (!f.oneLine) c.drawRect(d2, cy - dh / 2f, d2 + dw, cy + dh / 2f, fill)
        c.drawRect(d3, cy - dh / 2f, d3 + dw, cy + dh / 2f, fill)

        val dim = if (f.dim) K.SCORE_DIM_ALPHA else 1f
        val t = f.topText
        if (t != null) {
            top.textSize = f.topPx * s
            // The quarter at Fg .70; "No update" / "Delayed" and the one-line states at Fg; all × the dim.
            val quarter = !f.oneLine && !f.dim
            top.color = argb(dim * (if (quarter) K.NFL_QUARTER_ALPHA else 1f), fg)
            top.getFontMetrics(fm)
            val y = (if (f.oneLine) cy else quarterCy) - (fm.ascent + fm.descent) / 2f
            c.drawText(t, if (f.oneLine) oneLineCx else centreCx, y, top)
        }
        if (f.clockOnBottom) drawClock(c, f.clockSecs, dim)
        val dn = f.down
        if (dn != null) {
            downPaint.textSize = f.downPx * s
            downPaint.getFontMetrics(fm)
            c.drawText(dn, downCx, cy - (fm.ascent + fm.descent) / 2f, downPaint)
        }

        if (placing) {
            val w = K.PLACE_RING_W * s
            ring.strokeWidth = w
            ring.color = accent
            rect.set(-w / 2f, -w / 2f, pillW + w / 2f, pillH + w / 2f)
            c.drawRoundRect(rect, radius + w / 2f, radius + w / 2f, ring)
        }
    }

    private fun scoreDigits(c: Canvas, text: String, cx: Float, cy: Float, pop: Boolean, popScale: Float, popColour: Int) {
        if (!pop) {
            c.drawText(text, cx, cy + scoreBaselineOffset, score)
            return
        }
        score.color = popColour
        c.save()
        c.scale(popScale, popScale, cx, cy)
        c.drawText(text, cx, cy + scoreBaselineOffset, score)
        c.restore()
        score.color = fg
    }

    private fun logo(c: Canvas, cx: Float, cy: Float, abbrev: String, slot: FollowLogos.Slot?, hasHolder: Boolean, waited: Boolean) {
        val img = slot?.image
        if (img != null) {
            val b = img.asAndroidBitmap()
            c.drawBitmap(b, cx - b.width / 2f, cy - b.height / 2f, bmpPaint)
        } else if (!hasHolder || slot!!.failed || waited) {
            disc.draw(c, cx, cy, abbrev)
        }
    }

    /** `m:ss` / `mm:ss` (26/700, tabular from the digit table), centred in the centre column on the second line. */
    private fun drawClock(c: Canvas, secs: Int, dim: Float) {
        val v = secs.coerceIn(0, 99 * 60 + 59)
        val m = v / 60
        val r = v % 60
        var n = 0
        if (m >= 10) clockBuf[n++] = '0' + m / 10
        clockBuf[n++] = '0' + m % 10
        clockBuf[n++] = ':'
        clockBuf[n++] = '0' + r / 10
        clockBuf[n++] = '0' + r % 10
        val width = (n - 1) * cell + colonAdvance
        var x = centreCx - width / 2f
        val y = clockCy + clockCap
        clock.color = argb(dim, fg)
        for (i in 0 until n) {
            val ch = clockBuf[i]
            if (ch == ':') {
                c.drawText(clockBuf, i, 1, x, y, clock)
                x += colonAdvance
            } else {
                c.drawText(clockBuf, i, 1, x + (cell - digitAdvance[ch - '0']) / 2f, y, clock)
                x += cell
            }
        }
    }

    // ------------------------------------------------------------------ test seams

    /** Column centres and divider edges, device px. */
    internal val columns: FloatArray get() = floatArrayOf(logoCx, scoreCx, d1, centreCx, d2, downCx, d3, pillW - scoreCx, pillW - logoCx)

    private companion object {
        const val DIGITS = "0123456789"
        /** 8 px each side of a text kept clear of the dividers (as the NHL). */
        const val FIT_BREATHING = 16f
        /** Two-line centre: the quarter box 9..31, the clock box 31..61 (§4.2). */
        const val QUARTER_CY = 20f
        const val CLOCK_CY = 46f

        fun argb(a: Float, rgb: Int): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (rgb and 0xFFFFFF)

        fun lerpColour(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return Math.round(x + (y - x) * f) shl shift
            }
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}
