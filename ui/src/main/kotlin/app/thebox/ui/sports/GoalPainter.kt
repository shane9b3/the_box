package app.thebox.ui.sports

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
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamColours
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * the badge fallback and the tally. [large] picks the Large glow table (§2.4: its peak σ is capped, so it is not
 * Standard × 1.25); by default it is read from the pill (506 / 1.25 = 404.8 against 404).
 *
 * - **Built here, once per size:** the paints, the pill rect and the GOAL letter atlas (S2: the four letters with their
 *   hard shadows, white, at [K.GOAL_ATLAS_SCALE] × the rest size, so the animated scale never re-rasterises a glyph).
 * - **[bakeGlow]**, off the main thread, never in low-end mode: the two glow masks.
 * - **[begin]**, once per takeover: the colours, the streak shaders, the tally chars, the padded badge copy.
 * - **[draw]** ([drawGlow] + [drawTakeover]): only `save` / `restore` / `clipRect` / `translate` / `rotate` / `scale` /
 *   `draw*`. Wipes are `clipRect`s over anti-aliased `drawRoundRect`s; there is no `clipPath`, no `saveLayer` and no
 *   `Shader.setLocalMatrix` (the streaks move by `translate` and a counter-offset rect, §2.2).
 *
 * [ScoreBugPainter.drawFrame] calls [drawGlow] before the glass and [drawTakeover] after the body (§2.1's order).
 */
internal class GoalPainter(
    val s: Float,
    val pillW: Float,
    val pillH: Float,
    private val goalFont: Typeface?,
    private val sansBold: Typeface?,
    val large: Boolean = pillW / s > K.SCORE_W + 0.4f,
) {
    /** Device px per design px without the Large factor (the glow table is given per size, §2.4). */
    private val d = if (large) s / K.SCORE_LARGE_SCALE else s
    private val radius = pillH / 2f
    private val cy = pillH / 2f
    private val pill = RectF(0f, 0f, pillW, pillH)
    private val scratch = RectF()

    private val leadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val streak1Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak2Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** N4: the badge's own paint (the body's bitmap paint is never re-alpha'd). */
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint()
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.GOAL_BADGE_TEXT_PX * s; color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val tallyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.GOAL_TALLY_PX * s; color = Color.WHITE; textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "'tnum'"
    }
    private val badgeTextOffset: Float
    private val tallyBaseline: Float

    // ---- the badge (§2.3, §4): centre of the bug's logo slot
    private val badgeCx = (K.GOAL_PAD + K.SCORE_LOGO_W / 2f) * s

    // ---- the GOAL atlas (S2)
    private val atlas: Bitmap
    private val letterSrc = Array(GoalFrame.LETTERS) { Rect() }
    /** Each letter's rest-size destination, relative to its own transform origin (the CSS span's centre). */
    private val letterDst = Array(GoalFrame.LETTERS) { RectF() }
    private val letterOx = FloatArray(GoalFrame.LETTERS)
    /** The word's centre (the pulse's origin); the letters' origins share its y. */
    val wordCx: Float
    private val tallyRight = pillW - K.GOAL_PAD * s

    init {
        val b = Rect()
        badgeText.getTextBounds("M", 0, 1, b)
        badgeTextOffset = -(b.top + b.bottom) / 2f
        tallyPaint.getTextBounds("0", 0, 1, b)
        tallyBaseline = cy - (b.top + b.bottom) / 2f

        // GOAL sits centred between the badge (+ gap) and the tally's fixed "00 – 00" slot (+ gap), §6.1.
        val slot = tallyPaint.measureText(TALLY_SLOT)
        val areaL = (K.GOAL_PAD + K.SCORE_LOGO_W + K.GOAL_GAP) * s
        val areaR = pillW - K.GOAL_PAD * s - slot - K.GOAL_GAP * s
        wordCx = (areaL + areaR) / 2f

        val a = K.GOAL_ATLAS_SCALE
        val word = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = goalFont; textSize = K.GOAL_WORD_PX * s * a; color = Color.WHITE }
        val shadow = Paint(word).apply { color = argb(K.GOAL_SHADOW_ALPHA, 0x000000) }
        val track = K.GOAL_TRACK * s * a
        val dy = K.GOAL_SHADOW_DY * s * a
        val pad = ceil(2f * a).toInt()
        val bounds = Array(GoalFrame.LETTERS) { Rect() }
        val adv = FloatArray(GoalFrame.LETTERS)
        var top = 0
        var bottom = 0
        for (i in 0 until GoalFrame.LETTERS) {
            word.getTextBounds(WORD, i, i + 1, bounds[i])
            adv[i] = word.measureText(WORD, i, i + 1)
            top = minOf(top, bounds[i].top)
            bottom = maxOf(bottom, bounds[i].bottom)
        }
        val cellTop = top - pad
        val cellH = (bottom + ceil(dy).toInt() + pad) - cellTop
        val cellW = IntArray(GoalFrame.LETTERS) { bounds[it].width() + 2 * pad }
        atlas = createBitmap(cellW.sum().coerceAtLeast(1), cellH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ac = Canvas(atlas)
        // Rest layout (device px): CSS letter-spacing follows every letter (the last too), and the word is centred.
        val wordW = (adv.sum() + GoalFrame.LETTERS * track) / a
        var pen = wordCx - wordW / 2f
        val baseline = cy - (top + bottom) / 2f / a
        var x0 = 0
        for (i in 0 until GoalFrame.LETTERS) {
            val bx = x0 - (bounds[i].left - pad).toFloat()
            val by = -cellTop.toFloat()
            ac.drawText(WORD, i, i + 1, bx, by + dy, shadow)
            ac.drawText(WORD, i, i + 1, bx, by, word)
            letterSrc[i].set(x0, 0, x0 + cellW[i], cellH)
            val box = (adv[i] + track) / a
            letterOx[i] = pen + box / 2f
            val l = pen + (bounds[i].left - pad) / a - letterOx[i]
            val t = baseline + cellTop / a - cy
            letterDst[i].set(l, t, l + cellW[i] / a, t + cellH / a)
            pen += box
            x0 += cellW[i]
        }
    }

    // ---- per takeover (begin)
    var side: Side = Side.AWAY
        private set
    var colours: TeamColours? = null
        private set
    private var badgeSource: Bitmap? = null
    private var badge: Bitmap? = null
    private var abbrev: String = ""
    private var tally = CharArray(0)
    private var tallyLen = 0

    /** Bakes the low and peak glow masks (off the main thread; never in low-end mode). */
    fun bakeGlow(): GlowMasks {
        val lowSpread = K.GOAL_GLOW_LOW_SPREAD * (if (large) K.SCORE_LARGE_SCALE else 1f)
        val lowSigma = K.GOAL_GLOW_LOW_SIGMA * (if (large) K.SCORE_LARGE_SCALE else 1f)
        val peakSpread = if (large) K.GOAL_GLOW_LARGE_PEAK_SPREAD else K.GOAL_GLOW_PEAK_SPREAD
        val peakSigma = if (large) K.GOAL_GLOW_LARGE_PEAK_SIGMA else K.GOAL_GLOW_PEAK_SIGMA
        val lowMargin = (lowSpread + 3f * lowSigma) * d
        val peakMargin = (peakSpread + 3f * peakSigma) * d
        return GlowMasks(
            bakeMask(lowSpread * d, lowSigma * d, lowMargin), bakeMask(peakSpread * d, peakSigma * d, peakMargin),
            lowMargin, peakMargin,
        )
    }

    /** One mask: the pill inflated by [spread], blurred to [sigma] (device px), with the pill cut out (anti-aliased). */
    private fun bakeMask(spread: Float, sigma: Float, margin: Float): Bitmap {
        val bmp = createBitmap(ceil(pillW + 2 * margin).toInt(), ceil(pillH + 2 * margin).toInt(), Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.translate(margin, margin)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            // Skia's sigma = 0.57735 × radius + 0.5.
            maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(-spread, -spread, pillW + spread, pillH + spread, radius + spread, radius + spread, p)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        c.drawRoundRect(pill, radius, radius, cut)
        return bmp
    }

    /**
     * Fixes one takeover: the scoring [side], its [colours], the [badge] (the bug's held `_dark` logo, or null for the
     * abbreviation fallback in white), [abbrev], and the tally [away] – [home]. Builds the streak shaders, the tally
     * chars and the padded badge copy. Called from the start effect, never in draw.
     */
    fun begin(side: Side, colours: TeamColours, badge: Bitmap?, abbrev: String, away: Int, home: Int) {
        this.side = side
        this.colours = colours
        this.abbrev = abbrev
        leadPaint.color = colours.accent or OPAQUE
        panelPaint.color = colours.base or OPAQUE
        glowPaint.color = colours.glow or OPAQUE
        streak1Paint.shader = streakShader(K.GOAL_STREAK1_W * s, colours.accent, K.GOAL_STREAK1_ALPHA)
        streak2Paint.shader = streakShader(K.GOAL_STREAK2_W * s, Color.WHITE, K.GOAL_STREAK2_ALPHA)
        val t = "$away – $home"
        if (tally.size < t.length) tally = CharArray(t.length)
        t.toCharArray(tally, 0, 0, t.length)
        tallyLen = t.length
        // N3: a copy with 1 px of transparent padding, made once per logo bitmap identity.
        if (badge == null) {
            this.badge = null
            badgeSource = null
        } else if (badge !== badgeSource) {
            val copy = createBitmap(badge.width + 2, badge.height + 2, Bitmap.Config.ARGB_8888)
            Canvas(copy).drawBitmap(badge, 1f, 1f, null)
            this.badge = copy
            badgeSource = badge
        }
    }

    /**
     * The streak band (S4): CSS `skewX(−24deg)` about the element's centre leans it "/", so its edges run along
     * (sin 24°, −cos 24°) and the gradient runs perpendicular, along (cos 24°, sin 24°). The left edge passes through
     * (0, pill centre); [w] is the CSS (horizontal) width. Soft 1 device px stops at each edge, `CLAMP` to transparent.
     */
    private fun streakShader(w: Float, rgb: Int, alpha: Float): Shader {
        val rad = Math.toRadians(-K.GOAL_STREAK_SKEW_DEG.toDouble())
        val nx = cos(rad).toFloat()
        val ny = sin(rad).toFloat()
        val len = w * nx // perpendicular width
        val eps = (1f / len).coerceAtMost(0.49f)
        val c = argb(alpha, rgb)
        val t = rgb and 0xFFFFFF
        return LinearGradient(
            0f, cy, len * nx, cy + len * ny,
            intArrayOf(t, c, c, t), floatArrayOf(0f, eps, 1f - eps, 1f), Shader.TileMode.CLAMP,
        )
    }

    /** The takeover at frame [g] in pill coordinates: the glow (if [glowOn] and [glow] is ready), lead, panel and contents. */
    fun draw(c: Canvas, g: GoalFrame, glow: GlowMasks?, glowOn: Boolean) {
        if (glowOn && glow != null) drawGlow(c, g, glow)
        drawTakeover(c, g)
    }

    /** The glow only (§2.4): drawn behind the glass. Low mask α = aLow·(1−m), peak mask α = aPeak·m, from [g]. */
    fun drawGlow(c: Canvas, g: GoalFrame, glow: GlowMasks) {
        if (colours == null) return
        val lo = alpha255(g.glowLowAlpha)
        if (lo > 0) { glowPaint.alpha = lo; c.drawBitmap(glow.low, -glow.lowMargin, -glow.lowMargin, glowPaint) }
        val hi = alpha255(g.glowPeakAlpha)
        if (hi > 0) { glowPaint.alpha = hi; c.drawBitmap(glow.peak, -glow.peakMargin, -glow.peakMargin, glowPaint) }
    }

    /** The lead, then under the panel's wipe clip: panel, streak 1, streak 2, flash, badge, GOAL, tally (§2.1). */
    fun drawTakeover(c: Canvas, g: GoalFrame) {
        if (colours == null) return
        if (g.leadTo > g.leadFrom) {
            c.withClip(g.leadFrom * pillW, 0f, g.leadTo * pillW, pillH) { drawRoundRect(pill, radius, radius, leadPaint) }
        }
        if (g.panelTo <= g.panelFrom) return
        c.withClip(g.panelFrom * pillW, 0f, g.panelTo * pillW, pillH) {
            drawRoundRect(pill, radius, radius, panelPaint)
            if (g.streak1On) streak(this, g.streak1X * s, streak1Paint)
            if (g.streak2On) streak(this, g.streak2X * s, streak2Paint)
            val fa = alpha255(g.flashAlpha)
            if (fa > 0) { flashPaint.alpha = fa; drawRoundRect(pill, radius, radius, flashPaint) }
            drawBadge(this, g)
            drawWord(this, g)
            val ta = alpha255(g.tallyAlpha)
            if (ta > 0 && tallyLen > 0) {
                tallyPaint.alpha = ta
                drawText(tally, 0, tallyLen, tallyRight + g.tallyDx * s, tallyBaseline, tallyPaint)
            }
        }
    }

    /** The band at left edge [dx]: translate, then the pill offset back by −dx, so the shader moves without a matrix. */
    private fun streak(c: Canvas, dx: Float, p: Paint) {
        scratch.set(-dx, 0f, pillW - dx, pillH)
        c.withTranslation(dx, 0f) { drawRoundRect(scratch, radius, radius, p) }
    }

    private fun drawBadge(c: Canvas, g: GoalFrame) {
        val a = alpha255(g.badgeAlpha)
        if (a <= 0) return
        c.withTranslation(badgeCx, cy) {
            rotate(g.badgeRotDeg)
            scale(g.badgeScale, g.badgeScale)
            val b = badge
            if (b != null) {
                badgePaint.alpha = a
                drawBitmap(b, -b.width / 2f, -b.height / 2f, badgePaint)
            } else {
                badgeText.alpha = a
                drawText(abbrev, 0f, badgeTextOffset, badgeText)
            }
        }
    }

    private fun drawWord(c: Canvas, g: GoalFrame) {
        c.withScale(g.wordPulse, g.wordPulse, wordCx, cy) {
            for (i in 0 until GoalFrame.LETTERS) {
                val a = alpha255(g.letterAlpha[i])
                if (a <= 0) continue
                withTranslation(letterOx[i], cy + g.letterDy[i] * s) {
                    scale(g.letterScale[i], g.letterScale[i])
                    letterPaint.alpha = a
                    drawBitmap(atlas, letterSrc[i], letterDst[i], letterPaint)
                }
            }
        }
    }

    /** Test seam: the atlas (S2), for its size check. */
    internal val atlasBitmap: Bitmap get() = atlas

    private companion object {
        const val WORD = "GOAL"
        /** The tally's fixed slot (§6.1): the widest two-digit tally. */
        const val TALLY_SLOT = "00 – 00"
        const val OPAQUE = 0xFF shl 24

        fun alpha255(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        fun argb(a: Float, rgb: Int): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (rgb and 0xFFFFFF)
    }
}
