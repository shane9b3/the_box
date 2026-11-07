package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withScale
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.ui.theme.DesignTokens
import kotlin.math.ceil

/**
 * (docs/design/m6-score-bug.md §4.1, §4.2, §4.5) What the bug shows once its words are resolved: built by
 * [ScoreBugPainter.frame] when the view changes (never per frame). Sizes are design px, already fitted (U-N3).
 */
internal class ScoreBugFrame(
    val awayAbbrev: String,
    val homeAbbrev: String,
    val awayScore: String,
    val homeScore: String,
    /** The centre's top line is the clock (drawn by [ScoreBugPainter.drawClock] from the digit table). */
    val clockOnTop: Boolean,
    /** The clock as the shown snapshot reports it (owner ruling 6); read only when [clockOnTop]. */
    val clockSecs: Int,
    /** The static top line (one-line states such as "Final", "SO"); null when the clock is the top. */
    val topText: String?,
    val topPx: Float,
    val bottomText: String?,
    val bottomPx: Float,
    /** The whole centre column is dimmed (stale, delayed; U-S11). */
    val dim: Boolean,
)

/**
 * The bug's whole drawing, in plain `android.graphics` (the guide's toolkit-agnostic precedent): the glass baked once into
 * a bitmap ([bakeGlass]), the static body ([drawBody]) and the clock ([drawClock]); [drawFrame] is the one composite the
 * app and the screenshot tests both call (docs/design/m6-goal-animation.md §2.1). [s] is device px per design px (the
 * Large size folded in). Paints and digit tables are built once; a draw allocates nothing. Used by the composable and,
 * unchanged, by the screenshot tests.
 */
internal class ScoreBugPainter(
    val s: Float,
    sansSemi: Typeface?,
    sansBold: Typeface?,
    /** The pill's device size; Large is 506 × 88 (not exactly 1.25 × 404 × 70), the content scales by [s]. */
    val pillW: Float = K.SCORE_W * s,
    val pillH: Float = K.SCORE_H * s,
) {
    private val fg = DesignTokens.Colors.Fg.toArgb()
    private val accent = DesignTokens.Colors.Accent.toArgb()

    private val radius = pillH / 2f

    private fun text(px: Float, tf: Typeface?, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * s; typeface = tf; color = fg; textAlign = align }

    private val score = text(K.SCORE_TEXT_PX.toFloat(), sansBold)
    private val clock = text(K.SCORE_CLOCK_PX.toFloat(), sansSemi, Paint.Align.LEFT)
    private val line = text(K.SCORE_ONE_LINE_PX.toFloat(), sansSemi)
    private val period = text(K.SCORE_PERIOD_PX.toFloat(), sansSemi)
    private val measure = text(K.SCORE_PERIOD_PX.toFloat(), sansSemi)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val bounds = Rect()
    private val disc = LogoDisc(s, sansBold)

    private val scoreBaselineOffset: Float
    /** Digit advance table of the clock paint (a tick allocates nothing, §4.7). */
    private val digitAdvance = FloatArray(10)
    private val colonAdvance: Float
    private val clockCap: Float
    private val clockBuf = CharArray(8)

    /** The clock's size in design px: 26, stepped down only if "88:88" would not fit the 106 column. */
    val clockPx: Float

    init {
        score.getTextBounds("0", 0, 1, bounds)
        scoreBaselineOffset = -(bounds.top + bounds.bottom) / 2f
        var px = K.SCORE_CLOCK_PX.toFloat()
        fun widest(): Float {
            clock.textSize = px * s
            var w = 0f
            for (d in 0..9) w = maxOf(w, clock.measureText(('0' + d).toString()))
            return 4 * w + clock.measureText(":")
        }
        // U-N1: the same budget as [fit] (the 8 px clearance each side of the dividers).
        while (px > K.SCORE_TEXT_FLOOR_PX && widest() > (K.SCORE_CENTRE_W - FIT_BREATHING) * s) px -= K.SCORE_FIT_STEP_PX
        clockPx = px
        clock.textSize = px * s
        for (d in 0..9) digitAdvance[d] = clock.measureText(('0' + d).toString())
        colonAdvance = clock.measureText(":")
        clock.getTextBounds("0", 0, 1, bounds)
        clockCap = -(bounds.top + bounds.bottom) / 2f
    }

    // ------------------------------------------------------------------ words and fit (U-N3)

    /** [startPx] stepped down by 2 until [text] fits the 106 column (less [FIT_BREATHING], 8 a side, so no text touches a divider), never below 18; no ellipsis. Design px. */
    fun fit(text: String, startPx: Float): Float {
        var px = startPx
        while (px > K.SCORE_TEXT_FLOOR_PX) {
            measure.textSize = px * s
            if (measure.measureText(text) <= (K.SCORE_CENTRE_W - FIT_BREATHING) * s) break
            px -= K.SCORE_FIT_STEP_PX
        }
        return px.coerceAtLeast(K.SCORE_TEXT_FLOOR_PX.toFloat())
    }

    fun frame(v: ScoreBugView, res: Resources): ScoreBugFrame {
        val top = v.top
        val bottom = v.bottom
        val clockOnTop = top == CentreText.Clock
        val topText = if (clockOnTop) null else SportsText.centre(res, top, v, 0)
        val bottomText = bottom?.let { SportsText.centre(res, it, v, 0) }
        val topStart = if (bottomText == null) K.SCORE_ONE_LINE_PX else K.SCORE_CLOCK_PX
        return ScoreBugFrame(
            awayAbbrev = v.away.abbrev,
            homeAbbrev = v.home.abbrev,
            awayScore = (v.away.score ?: 0).toString(),
            homeScore = (v.home.score ?: 0).toString(),
            clockOnTop = clockOnTop,
            clockSecs = v.clockSecs ?: 0,
            topText = topText,
            topPx = if (topText != null) fit(topText, topStart.toFloat()) else clockPx,
            bottomText = bottomText,
            bottomPx = if (bottomText != null) fit(bottomText, K.SCORE_PERIOD_PX.toFloat()) else K.SCORE_PERIOD_PX.toFloat(),
            dim = v.dim,
        )
    }

    // ------------------------------------------------------------------ glass (§4.2)

    /** Margin of the baked bitmap around the pill, design px (the shadow's blur). */
    private val glassMargin = K.GLASS_SHADOW_BLUR * s
    private val glassDy = K.GLASS_SHADOW_DY * s

    /** Where the baked bitmap's origin sits relative to the pill's top-left. */
    val glassOriginX: Float get() = -glassMargin
    val glassOriginY: Float get() = -glassMargin

    /**
     * Shadow (skipped in low-end), 1 px outer ring, fill, top highlight and hairline, baked once into one bitmap that
     * covers the pill plus the shadow margin. The shadow is clipped out of the pill so the fill stays exactly `.86`
     * (the contrast numbers in §4.2 assume it).
     */
    fun bakeGlass(withShadow: Boolean): Bitmap {
        val w = ceil(pillW + 2 * glassMargin).toInt()
        val h = ceil(pillH + 2 * glassMargin + glassDy).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(glassMargin, glassMargin)
        val pill = RectF(0f, 0f, pillW, pillH)
        val path = Path().apply { addRoundRect(pill, radius, radius, Path.Direction.CW) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (withShadow) {
            c.save()
            @Suppress("DEPRECATION")
            c.clipPath(path, Region.Op.DIFFERENCE)
            p.style = Paint.Style.FILL
            p.color = argb(K.GLASS_SHADOW_ALPHA, 0x000000)
            p.maskFilter = BlurMaskFilter(K.GLASS_SHADOW_BLUR * s, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(0f, glassDy, pillW, pillH + glassDy), radius, radius, p)
            c.restore()
            p.maskFilter = null
        }
        val line = s.coerceAtLeast(1f)
        // Outer ring: 1 px black .30, just outside the pill, so the edge holds over white ice.
        p.style = Paint.Style.STROKE
        p.strokeWidth = line
        p.color = argb(K.GLASS_RING_ALPHA, 0x000000)
        c.drawRoundRect(RectF(-line / 2f, -line / 2f, pillW + line / 2f, pillH + line / 2f), radius + line / 2f, radius + line / 2f, p)
        // Fill: PanelFill (#0A0D13 at .86).
        p.style = Paint.Style.FILL
        p.color = (DesignTokens.Colors.Canvas.toArgb() and 0x00FFFFFF) or (Math.round(K.GLASS_FILL_ALPHA * 255f) shl 24)
        c.drawRoundRect(pill, radius, radius, p)
        // Top highlight: white .06 to 0 over the top half.
        c.save()
        c.clipPath(path)
        p.shader = LinearGradient(0f, 0f, 0f, pillH / 2f, argb(K.GLASS_HIGHLIGHT_ALPHA, 0xFFFFFF), argb(0f, 0xFFFFFF), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, pillW, pillH / 2f, p)
        p.shader = null
        c.restore()
        // Hairline: 1 px inside stroke, white .16 to .06.
        p.style = Paint.Style.STROKE
        p.strokeWidth = line
        p.shader = LinearGradient(
            0f, 0f, 0f, pillH,
            argb(K.GLASS_HAIRLINE_TOP_ALPHA, 0xFFFFFF), argb(K.GLASS_HAIRLINE_BOTTOM_ALPHA, 0xFFFFFF), Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(RectF(line / 2f, line / 2f, pillW - line / 2f, pillH - line / 2f), radius - line / 2f, radius - line / 2f, p)
        return bmp
    }

    fun drawGlass(c: Canvas, glass: Bitmap) {
        c.drawBitmap(glass, -glassMargin, -glassMargin, null)
    }

    // ------------------------------------------------------------------ the composite (goal animation §2.1)

    /**
     * The bug's whole frame in the pill's own coordinates (the canvas already translated by the layer margin `M`), in the
     * fixed order of the goal design §2.1: glow (only while [goal] is set and [glowOn], never in low-end mode), glass,
     * body (with the scorer's pop), then the takeover. [goal] / [g] null = no takeover: the plain bug.
     *
     * Commit 0: glass + body only; the glow, the pop and the takeover hooks are WP-2's.
     */
    @Suppress("UNUSED_PARAMETER")
    fun drawFrame(
        c: Canvas,
        glass: Bitmap,
        f: ScoreBugFrame,
        logos: FollowLogos?,
        logoWaited: Boolean,
        placing: Boolean,
        goal: GoalPainter?,
        g: GoalFrame?,
        glow: GlowMasks?,
        glowOn: Boolean,
    ) {
        // 1. glow: WP-2.
        drawGlass(c, glass)
        drawBody(c, f, logos, logoWaited, placing)
        // 4. takeover: WP-2.
    }

    // ------------------------------------------------------------------ body (§4.1, §4.5)

    /**
     * Logos (or discs), scores, dividers, the centre lines (the clock included) and the place-mode ring, in the pill's
     * own coordinates (0, 0 = its top-left). [popSide] non-null: that side's score is drawn at [popScale] about its
     * centre in [popColour] (goal animation §2.1, the score pop; [drawFrame] computes the colour, WP-2 the clamp).
     */
    fun drawBody(
        c: Canvas,
        f: ScoreBugFrame,
        logos: FollowLogos?,
        logoWaited: Boolean,
        placing: Boolean,
        popSide: Side? = null,
        popScale: Float = 1f,
        popColour: Int = fg,
    ) {
        val cy = pillH / 2f
        logo(c, (K.SCORE_PAD + K.SCORE_LOGO_W / 2f) * s, cy, f.awayAbbrev, logos?.away, logos != null, logoWaited)
        logo(c, pillW - (K.SCORE_PAD + K.SCORE_LOGO_W / 2f) * s, cy, f.homeAbbrev, logos?.home, logos != null, logoWaited)

        val awayCx = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f) * s
        val homeCx = pillW - (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f) * s
        scoreDigits(c, f.awayScore, awayCx, cy, popSide == Side.AWAY, popScale, popColour)
        scoreDigits(c, f.homeScore, homeCx, cy, popSide == Side.HOME, popScale, popColour)

        // Dividers 1 × 34, white .18.
        fill.color = argb(K.SCORE_DIVIDER_ALPHA, 0xFFFFFF)
        val dw = (K.SCORE_DIVIDER_W * s).coerceAtLeast(1f)
        val dh = K.SCORE_DIVIDER_H * s
        val d1 = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W + K.SCORE_DIVIDER_GAP) * s
        val d2 = pillW - (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W + K.SCORE_DIVIDER_GAP) * s - dw
        c.drawRect(d1, cy - dh / 2f, d1 + dw, cy + dh / 2f, fill)
        c.drawRect(d2, cy - dh / 2f, d2 + dw, cy + dh / 2f, fill)

        // Centre column (static lines).
        val centreX = (d1 + dw + d2) / 2f
        val dim = if (f.dim) K.SCORE_DIM_ALPHA else 1f
        if (f.topText != null) {
            val p = line
            p.textSize = f.topPx * s
            p.color = argb(dim, fg and 0xFFFFFF)
            val y = if (f.bottomText == null) cy - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f
            else TWO_LINE_TOP_CY * s - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f
            c.drawText(f.topText, centreX, y, p)
        }
        if (f.bottomText != null) {
            period.textSize = f.bottomPx * s
            period.color = argb(dim * K.SCORE_PERIOD_ALPHA, fg and 0xFFFFFF)
            c.drawText(f.bottomText, centreX, BOTTOM_CY * s - (period.fontMetrics.ascent + period.fontMetrics.descent) / 2f, period)
        }
        if (f.clockOnTop) drawClock(c, f.clockSecs, f)

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
        c.withScale(popScale, popScale, cx, cy) { drawText(text, cx, cy + scoreBaselineOffset, score) }
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

    // ------------------------------------------------------------------ clock (§4.5; the snapshot's value, owner ruling 6)

    /** The clock's box in the pill: the centre column (x from [clockLeft], [clockWidth] wide), the full pill height. */
    val clockLeft: Float = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W + K.SCORE_DIVIDER_GAP + K.SCORE_DIVIDER_W) * s
    val clockWidth: Float = pillW - 2 * clockLeft

    /**
     * `m:ss` / `mm:ss` from [secs], digit by digit from the pre-measured table (tabular: a new value never moves a digit),
     * centred in the centre column, in the pill's coordinates. [drawBody] calls it with the frame's [ScoreBugFrame.clockSecs].
     */
    fun drawClock(c: Canvas, secs: Int, f: ScoreBugFrame) {
        val v = secs.coerceIn(0, 99 * 60 + 59)
        val m = v / 60
        val r = v % 60
        var n = 0
        if (m >= 10) clockBuf[n++] = '0' + m / 10
        clockBuf[n++] = '0' + m % 10
        clockBuf[n++] = ':'
        clockBuf[n++] = '0' + r / 10
        clockBuf[n++] = '0' + r % 10
        var cell = 0f
        for (d in digitAdvance) cell = maxOf(cell, d)
        val width = (n - 1) * cell + colonAdvance
        var x = clockLeft + (clockWidth - width) / 2f
        val cy = if (f.bottomText == null) pillH / 2f else TWO_LINE_TOP_CY * s
        val y = cy + clockCap
        clock.color = argb(if (f.dim) K.SCORE_DIM_ALPHA else 1f, fg and 0xFFFFFF)
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

    private companion object {
        /** Two-line centre: the clock box is 9..39 and the period box 39..61 of the 70 (§4.1). */
        /**
         * Design px kept free in the centre column (8 each side) so a full-width string clears the dividers: at 4 a side,
         * "Final/OT" and "Final/SO" read as touching them (integration pass 2026-11-05).
         */
        const val FIT_BREATHING = 16f
        const val TWO_LINE_TOP_CY = 24f
        const val BOTTOM_CY = 50f

        fun argb(a: Float, rgb: Int): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (rgb and 0xFFFFFF)
    }
}
