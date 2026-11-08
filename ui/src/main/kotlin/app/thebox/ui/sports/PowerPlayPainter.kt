package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import app.thebox.core.sports.PpContent
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.PpLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamColours
import app.thebox.ui.R
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * (Power play, docs/design/m6-power-play.md §3.4, §4; WP-2) The power-play tab, the pill tint and the logo halo for one
 * size, drawn with no real-time blur: every soft edge is an `ALPHA_8` bake.
 *
 * [s] is device px per design px with the Large factor folded in (as [ScoreBugPainter.s]); [d] is device px per design
 * px without it (σ is × [d], N1); [pillW] × [pillH] is the pill's device size; [sansBold] draws the labels and digits.
 *
 * - **Built here:** the paints, the tab geometry and the `m:ss` digit table (the label fit needs the slot width).
 * - **[bakeSize]**, once per size, off the main thread: the tab shadow mask (never in low-end mode) and the shine shader.
 * - **[bakeLabels]**, per size and teams (keyed on `(s, shownLarge, away, home)`, S8), off the main thread: the five
 *   label masks, `ALPHA_8` at [K.PP_LABEL_ATLAS_SCALE], each fitted (§4.1: 2 px steps to the 18 px floor, then no
 *   tracking). Drawn twice: black .35 at the hard offset, then white, so the pulse never re-rasterises a glyph.
 * - **[bakeHalo]**, per logo bitmap identity, never in low-end mode: the halo mask under [Side]'s logo, cut to the pill.
 * - **[begin]**, once per transition start, in the runner: the tab's two vertical gradients (below / above) and the
 *   tint's horizontal gradient, for one side. Each side keeps its own, so a closing tab keeps its colour while the pill
 *   track has already begun the other side.
 * - **[draw]** per frame, with no allocation, using only `save` / `restore` / `clipRect` / `translate` / `scale` /
 *   `drawRoundRect` / `drawRect` / `drawBitmap` / `drawText(char[])`: no `clipPath`, no `saveLayer`, no
 *   `Shader.setLocalMatrix` (the shine moves by `translate` and a counter-offset rect, N10).
 *
 * The bakes publish through `@Volatile` fields; [draw] reads each once and skips what is not ready.
 */
internal class PowerPlayPainter(
    val s: Float,
    val d: Float,
    val pillW: Float,
    val pillH: Float,
    private val sansBold: Typeface?,
) {
    /** Large folds 1.25 into [s] but not [d]. */
    private val large = s > d * 1.1f
    private val pillRadius = pillH / 2f

    // ---- the tab's geometry (§4.1), device px; tab-local = the tab's top-left at rest offset
    /**
     * The side inset, rounded to a whole design px per size (31 / 39, width 342 / 428, §4.1), as WP-1's
     * `ScoreBugPlacement.tabInset(size)` does, so the drawn tab matches `tabBox` exactly (integration may switch to it).
     */
    private val tabL = round(K.PP_TAB_INSET * (s / d)) * d
    private val tabW = pillW - 2 * tabL
    private val hidden = K.PP_TAB_HIDDEN * s
    private val depth = K.PP_TAB_DEPTH * s
    private val tabH = hidden + depth
    private val tabR = K.PP_TAB_RADIUS * s
    private val travel = K.PP_TRAVEL * s

    /** The layer margin `M` (device px) and the clip's edge, snapped to a device px (§4.3, N9). */
    private val layerM = (if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN) * d
    private val edge = pillH.roundToInt().toFloat()

    // ---- the shadow (§4.1: dy 5 / Large 6, σ 8, × d; never mirrored, N7)
    private val shadowDy = (if (large) K.PP_SHADOW_DY_LARGE else K.PP_SHADOW_DY) * d
    private val shadowSigma = K.PP_SHADOW_SIGMA * d
    private val shadowMargin = ceil(3f * shadowSigma + 1f)

    // ---- text (§4.1)
    private val gap = K.PP_GAP * s
    private val dividerW = (K.PP_DIVIDER_W * s).coerceAtLeast(1f)
    private val dividerH = K.PP_DIVIDER_H * s
    private val textShadowDy = K.PP_TEXT_SHADOW_DY * s
    private val textCentre = K.PP_TEXT_CENTRE * s
    /** The text block's room: the tab less a far-corner radius each side (306 Standard, 383 Large). */
    private val room = tabW - 2 * tabR

    // ---- paints (one each; draw re-colours them, never allocates)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.PP_TEXT_PX * s; color = Color.WHITE; textAlign = Paint.Align.LEFT
    }
    private val dst = RectF()

    // ---- the digit table (tabular `m:ss`: a new second never moves a digit)
    private val digitAdvance = FloatArray(10)
    private val colonAdvance: Float
    private val cell: Float
    /** The fixed "0:00" slot (`PP_SECS_MAX` 599 is never five glyphs, N2). */
    private val slotW: Float
    /** Baseline offset from the text's centre line: the digits' cap middle sits on it. */
    private val digitCap: Float
    private val digitBuf = CharArray(4)

    init {
        val b = Rect()
        var c = 0f
        for (i in 0..9) {
            digitAdvance[i] = digits.measureText(DIGITS, i, i + 1)
            c = maxOf(c, digitAdvance[i])
        }
        cell = c
        colonAdvance = digits.measureText(":")
        slotW = 3 * cell + colonAdvance
        digits.getTextBounds("0", 0, 1, b)
        digitCap = -(b.top + b.bottom) / 2f
    }

    // ------------------------------------------------------------------ bakes

    @Volatile private var shadowMask: Bitmap? = null
    @Volatile private var shine: Shader? = null

    /**
     * The per-size bakes (§3.4), off the main thread: the tab shadow mask (skipped when not [withShadow]: low-end mode
     * never bakes it, §3.6) and the shine band shader.
     */
    fun bakeSize(withShadow: Boolean = true) {
        shine = shineShader()
        if (!withShadow) {
            shadowMask = null
            return
        }
        val m = shadowMargin
        val bmp = createBitmap(ceil(tabW + 2 * m).toInt(), ceil(tabH + 2 * m).toInt(), Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(blurRadius(shadowSigma), BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bmp).drawRoundRect(m, m, m + tabW, m + tabH, tabR, tabR, p)
        shadowMask = bmp
    }

    /**
     * The shine band (GA S4's soft stops): CSS `skewX(−24deg)` about the band's centre (the tab's middle) leans it "/",
     * so its edges run along (sin 24°, −cos 24°) and the gradient runs perpendicular, along (cos 24°, sin 24°). Its left
     * edge passes through (0, the tab's middle) in tab-local px; [K.PP_SHINE_W] is the CSS (horizontal) width.
     */
    private fun shineShader(): Shader {
        val rad = Math.toRadians(SHINE_SKEW_DEG)
        val nx = cos(rad).toFloat()
        val ny = sin(rad).toFloat()
        val len = K.PP_SHINE_W * s * nx
        val eps = (1f / len).coerceAtMost(0.49f)
        val on = argb(K.PP_SHINE_ALPHA, 0xFFFFFF)
        val off = 0x00FFFFFF
        val cy = tabH / 2f
        return LinearGradient(
            0f, cy, len * nx, cy + len * ny,
            intArrayOf(off, on, on, off), floatArrayOf(0f, eps, 1f - eps, 1f), Shader.TileMode.CLAMP,
        )
    }

    /** One size's five label masks for one pair of teams, with their fitted placement (device px, rest scale). */
    private class Labels {
        val masks = arrayOfNulls<Bitmap>(LABELS)
        /** The label's advance (its layout width). */
        val w = FloatArray(LABELS)
        /** The mask's destination relative to (pen x, the text's centre line). */
        val left = FloatArray(LABELS)
        val top = FloatArray(LABELS)
        val dw = FloatArray(LABELS)
        val dh = FloatArray(LABELS)
        /** The fitted size, design px, and whether tracking was kept (test seam). */
        val px = FloatArray(LABELS)
        val tracked = BooleanArray(LABELS)
    }

    @Volatile private var labels: Labels? = null

    /**
     * The five label masks for [away] and [home] (abbreviations): `{away|home} POWER PLAY`, `{away|home} 5-ON-3` and
     * `PP GOAL`, from [res] (`R.string.sports_pp_label`, `sports_pp_two_man`, `sports_pp_goal`). Off the main thread.
     */
    fun bakeLabels(away: String, home: String, res: Resources) {
        val texts = arrayOf(
            res.getString(R.string.sports_pp_label, away),
            res.getString(R.string.sports_pp_label, home),
            res.getString(R.string.sports_pp_two_man, away),
            res.getString(R.string.sports_pp_two_man, home),
            res.getString(R.string.sports_pp_goal),
        )
        val l = Labels()
        val a = K.PP_LABEL_ATLAS_SCALE
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold }
        val fm = Paint.FontMetrics()
        val cap = Rect()
        for (i in 0 until LABELS) {
            val text = texts[i]
            // The block: the label, then (except PP GOAL) the gaps, the divider and the fixed seconds slot.
            val extra = if (i == PP_GOAL_INDEX) 0f else 2 * gap + dividerW + slotW
            var px = K.PP_TEXT_PX
            var track = K.PP_TRACK_EM
            while (px > K.SCORE_TEXT_FLOOR_PX && labelWidth(measure, text, px, track) + extra > room) px -= K.SCORE_FIT_STEP_PX
            px = px.coerceAtLeast(K.SCORE_TEXT_FLOOR_PX.toFloat())
            if (labelWidth(measure, text, px, track) + extra > room) track = 0f
            val w = labelWidth(measure, text, px, track)
            l.w[i] = w
            l.px[i] = px
            l.tracked[i] = track > 0f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = sansBold; textSize = px * s * a; letterSpacing = track; color = Color.WHITE
            }
            paint.getFontMetrics(fm)
            paint.getTextBounds("H", 0, 1, cap)
            val pad = ceil(2f * a)
            val bw = ceil(paint.measureText(text) + 2 * pad).toInt().coerceAtLeast(1)
            val bh = ceil(fm.descent - fm.ascent + 2 * pad).toInt().coerceAtLeast(1)
            val baseline = pad - fm.ascent
            val bmp = createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
            Canvas(bmp).drawText(text, pad, baseline, paint)
            val capMid = baseline + (cap.top + cap.bottom) / 2f
            l.masks[i] = bmp
            l.left[i] = -pad / a
            l.top[i] = -capMid / a
            l.dw[i] = bw / a
            l.dh[i] = bh / a
        }
        labels = l
    }

    /** [text]'s advance at [px] design px and [track] em (bake only). */
    private fun labelWidth(p: Paint, text: String, px: Float, track: Float): Float {
        p.textSize = px * s
        p.letterSpacing = track
        return p.measureText(text)
    }

    // ---- per side (index 0 = away, 1 = home)
    private val haloMask = arrayOfNulls<Bitmap>(2)
    private val haloSource = arrayOfNulls<Bitmap>(2)

    /** The halo mask for [side]'s [logo] (the bug's held `_dark` logo bitmap): alpha → blur → cut to the pill. */
    fun bakeHalo(side: Side, logo: Bitmap) {
        val i = index(side)
        if (haloSource[i] === logo && haloMask[i] != null) return
        val sigma = (if (large) K.PP_HALO_SIGMA_LARGE else K.PP_HALO_SIGMA) * d
        val blur = Paint().apply { maskFilter = BlurMaskFilter(blurRadius(sigma), BlurMaskFilter.Blur.NORMAL) }
        val offset = IntArray(2)
        val alpha = logo.extractAlpha(blur, offset)
        val w = ceil(pillW).toInt()
        val h = ceil(pillH).toInt()
        val bmp = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        // Where [ScoreBugPainter.drawBody] draws the logo: centred in its slot.
        val slotCx = (K.SCORE_PAD + K.SCORE_LOGO_W / 2f) * s
        val cx = if (side == Side.AWAY) slotCx else pillW - slotCx
        c.drawBitmap(alpha, cx - logo.width / 2f + offset[0], pillH / 2f - logo.height / 2f + offset[1], Paint())
        alpha.recycle()
        // Cut to the pill: DST_IN with a full-size pill mask (drawBitmap applies the mode over its whole bounds).
        val pill = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(pill).drawRoundRect(0f, 0f, pillW, pillH, pillRadius, pillRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        c.drawBitmap(pill, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        pill.recycle()
        haloMask[i] = bmp
        haloSource[i] = logo
    }

    private val tabDown = arrayOfNulls<Shader>(2)
    private val tabUp = arrayOfNulls<Shader>(2)
    private val tint = arrayOfNulls<Shader>(2)
    private val glow = IntArray(2)
    private val tintScale = FloatArray(2)

    /** Fixes the tab's vertical gradients and the tint's horizontal gradient for [side] in [colours] (§6.1). */
    fun begin(side: Side, colours: TeamColours) {
        val i = index(side)
        val stops = FloatArray(GRADIENT_STOPS.size) { GRADIENT_STOPS[it] }
        val dark = IntArray(GRADIENT_STOPS.size) { mixBlack(colours.tab, GRADIENT_BLACK[it]) }
        tabDown[i] = LinearGradient(0f, 0f, 0f, tabH, dark, stops, Shader.TileMode.CLAMP)
        // Above the pill the dark end is the tab's bottom (the pill side): the same stops, reversed.
        val rev = IntArray(dark.size) { dark[dark.size - 1 - it] }
        val revStops = FloatArray(stops.size) { 1f - stops[stops.size - 1 - it] }
        tabUp[i] = LinearGradient(0f, 0f, 0f, tabH, rev, revStops, Shader.TileMode.CLAMP)
        val tStops = FloatArray(TINT_STOPS.size) { TINT_STOPS[it] }
        val tColours = IntArray(TINT_STOPS.size) { argb(TINT_ALPHAS[it], colours.glow) }
        // From the power-play end: the left edge for the away side, the right for home.
        val x0 = if (side == Side.AWAY) 0f else pillW
        tint[i] = LinearGradient(x0, 0f, pillW - x0, 0f, tColours, tStops, Shader.TileMode.CLAMP)
        glow[i] = colours.glow
        tintScale[i] = colours.tintScale
    }

    // ------------------------------------------------------------------ draw

    /**
     * Draws [p] in the pill's own coordinates, after the glass and before the body (§3.5 steps 3–5): the tab with
     * [content] (skipped when [PpFrame.tabClosed] or [content] is null, or its side was never begun), then the tint and
     * the halo on [pillSide] (skipped when [PpFrame.pillClosed] or [pillSide] is null). [up]: the tab opens above the
     * pill (bottom corners); it mirrors position, slide, text offset and gradient, never the text, the shine's lean or
     * the shadow's dy (N7).
     */
    fun draw(c: Canvas, p: PpFrame, content: PpContent?, pillSide: Side?, up: Boolean) {
        if (!p.tabClosed && content != null) drawTab(c, p, content, up)
        if (!p.pillClosed && pillSide != null) drawPill(c, p, pillSide)
    }

    private fun drawTab(c: Canvas, p: PpFrame, content: PpContent, up: Boolean) {
        val i = index(content.side)
        val grad = (if (up) tabUp else tabDown)[i] ?: return
        val off = (1f - p.slide.coerceIn(0f, 1f)) * travel
        val top = if (up) -depth + off else pillH - hidden - off
        c.save()
        // §4.3: one clipRect just outside the pill, at a device px; nothing of the tab ever lands on the glass.
        if (up) c.clipRect(0f, -layerM, pillW, 0f) else c.clipRect(0f, edge, pillW, edge + layerM)
        val sh = shadowMask
        val sa = alpha255(p.shadowAlpha)
        if (sh != null && sa > 0) {
            shadowPaint.alpha = sa
            c.drawBitmap(sh, tabL - shadowMargin, top + shadowDy - shadowMargin, shadowPaint)
        }
        c.translate(tabL, top)
        tabPaint.shader = grad
        c.drawRoundRect(0f, 0f, tabW, tabH, tabR, tabR, tabPaint)
        val band = shine
        if (p.shineOn && band != null) {
            val dx = p.shineX * s
            shinePaint.shader = band
            c.translate(dx, 0f)
            c.drawRoundRect(-dx, 0f, tabW - dx, tabH, tabR, tabR, shinePaint)
            c.translate(-dx, 0f)
        }
        val ta = p.textAlpha.coerceIn(0f, 1f)
        val l = labels
        if (ta > 0f && l != null) {
            val cy = if (up) depth - textCentre - p.textDy * s else hidden + textCentre + p.textDy * s
            drawText(c, l, content, cy, ta, p.labelScale)
        }
        c.restore()
    }

    /** The text block centred in the tab (tab-local): the label at [scale], then the divider and `m:ss`. */
    private fun drawText(c: Canvas, l: Labels, content: PpContent, cy: Float, alpha: Float, scale: Float) {
        val li = when (content.label) {
            PpLabel.PP_GOAL -> PP_GOAL_INDEX
            PpLabel.ONE_MAN -> index(content.side)
            PpLabel.TWO_MAN -> 2 + index(content.side)
        }
        val mask = l.masks[li] ?: return
        val alone = li == PP_GOAL_INDEX || content.secs < 0
        val lw = l.w[li]
        val blockW = if (alone) lw else lw + 2 * gap + dividerW + slotW
        val x0 = (tabW - blockW) / 2f
        val shadowA = alpha * K.PP_TEXT_SHADOW_ALPHA

        c.save()
        if (scale != 1f) c.scale(scale, scale, x0 + lw / 2f, cy)
        dst.set(x0 + l.left[li], cy + l.top[li], x0 + l.left[li] + l.dw[li], cy + l.top[li] + l.dh[li])
        maskPaint.color = argb(shadowA, 0x000000)
        dst.offset(0f, textShadowDy)
        c.drawBitmap(mask, null, dst, maskPaint)
        dst.offset(0f, -textShadowDy)
        maskPaint.color = argb(alpha, 0xFFFFFF)
        c.drawBitmap(mask, null, dst, maskPaint)
        c.restore()
        if (alone) return

        val dx = x0 + lw + gap
        fill.color = argb(alpha * K.PP_DIVIDER_ALPHA, 0xFFFFFF)
        c.drawRect(dx, cy - dividerH / 2f, dx + dividerW, cy + dividerH / 2f, fill)
        val v = content.secs.coerceIn(0, K.PP_SECS_MAX)
        digitBuf[0] = '0' + v / 60
        digitBuf[1] = ':'
        digitBuf[2] = '0' + (v % 60) / 10
        digitBuf[3] = '0' + v % 10
        val sx = dx + dividerW + gap
        digits.color = argb(shadowA, 0x000000)
        drawDigits(c, sx, cy + digitCap + textShadowDy)
        digits.color = argb(alpha, 0xFFFFFF)
        drawDigits(c, sx, cy + digitCap)
    }

    private fun drawDigits(c: Canvas, left: Float, baseline: Float) {
        var x = left
        for (i in 0 until 4) {
            val ch = digitBuf[i]
            if (ch == ':') {
                c.drawText(digitBuf, i, 1, x, baseline, digits)
                x += colonAdvance
            } else {
                c.drawText(digitBuf, i, 1, x + (cell - digitAdvance[ch - '0']) / 2f, baseline, digits)
                x += cell
            }
        }
    }

    /** §3.5 steps 4–5: the tint (× `tintScale`), then the halo under [side]'s logo (× .95 × `tintScale`). */
    private fun drawPill(c: Canvas, p: PpFrame, side: Side) {
        val i = index(side)
        val shader = tint[i] ?: return
        val ta = alpha255(p.tint * tintScale[i])
        if (ta > 0) {
            tintPaint.shader = shader
            tintPaint.alpha = ta
            c.drawRoundRect(0f, 0f, pillW, pillH, pillRadius, pillRadius, tintPaint)
        }
        val halo = haloMask[i] ?: return
        val ha = alpha255(K.PP_HALO_ALPHA * p.halo * tintScale[i])
        if (ha > 0) {
            haloPaint.color = (glow[i] and 0xFFFFFF) or (ha shl 24)
            c.drawBitmap(halo, 0f, 0f, haloPaint)
        }
    }

    // ------------------------------------------------------------------ test seams

    /** The fitted size (design px) of label [i] (0 away PP, 1 home PP, 2 away 5-on-3, 3 home 5-on-3, 4 PP GOAL). */
    internal fun labelPx(i: Int): Float = labels?.px?.get(i) ?: 0f
    internal fun labelTracked(i: Int): Boolean = labels?.tracked?.get(i) ?: false
    internal val labelsReady: Boolean get() = labels != null
    internal val shadowBaked: Boolean get() = shadowMask != null
    internal fun haloBaked(side: Side): Boolean = haloMask[index(side)] != null
    /** The tab's rest rect in pill coordinates, below ([up] false) or above the pill. */
    internal fun tabRest(up: Boolean, out: RectF): RectF =
        if (up) out.apply { set(tabL, -depth, tabL + tabW, hidden) } else out.apply { set(tabL, pillH - hidden, tabL + tabW, pillH + depth) }
    internal val clipEdge: Float get() = edge

    private companion object {
        const val LABELS = 5
        const val PP_GOAL_INDEX = 4
        const val DIGITS = "0123456789"
        /** CSS `skewX(−24deg)` leans the band "/"; its gradient runs along (cos 24°, sin 24°). */
        const val SHINE_SKEW_DEG = 24.0
        val GRADIENT_STOPS = K.PP_GRADIENT_STOPS
        val GRADIENT_BLACK = K.PP_GRADIENT_BLACK
        val TINT_STOPS = K.PP_TINT_STOPS
        val TINT_ALPHAS = K.PP_TINT_ALPHAS

        fun index(side: Side) = if (side == Side.AWAY) 0 else 1

        /** Skia's sigma = 0.57735 × radius + 0.5 (N1): the radius for [sigma] device px. */
        fun blurRadius(sigma: Float) = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f)

        fun alpha255(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        fun argb(a: Float, rgb: Int): Int = (alpha255(a) shl 24) or (rgb and 0xFFFFFF)

        /**
         * [rgb] mixed toward black by [k] (0 = itself, 1 = black), opaque; each channel rounded half to even
         * (`kotlin.math.round`, as `NhlTeamColours` mixes), so the pixels match the §6.4 table.
         */
        fun mixBlack(rgb: Int, k: Float): Int {
            fun ch(shift: Int) = round(((rgb shr shift) and 0xFF) * (1.0 - k)).toInt() shl shift
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}
