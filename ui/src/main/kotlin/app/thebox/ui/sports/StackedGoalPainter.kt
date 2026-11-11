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
import app.thebox.core.sports.GoalSpec
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedMock as M
import app.thebox.core.sports.TeamColours
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * The panel follows the mock's `.s-wrap` (`design/nhl-stacked/Stacked.dc.html`): `display:flex; align-items:center;
 * gap:30px; padding:0 40px` = the badge (150 wide), the GOAL column (`flex:1`, the word centred in it) and the tally (its
 * own width), at × `STACKED_SCALE`. The team-name line above GOAL is dropped (lead ruling, §4.2).
 *
 * Wipes are `clipRect`s over anti-aliased `drawRoundRect`s of the **whole box** (the top part plus an open bar, set by
 * [StackedPainter.drawFrame] through [extent]), so the panel's corners are round exactly where the box's are. No
 * `clipPath`, no `saveLayer`, no `Shader.setLocalMatrix`.
 */
internal class StackedGoalPainter(
    val s: Float,
    val large: Boolean,
    private val goalFont: Typeface?,
    private val sansBold: Typeface?,
    private val word: String,
    val spec: GoalSpec = GoalSpec.STACKED,
) {
    /** The scoring team's true colours (ruling 12; never stripe-swapped), set by [begin]; null before the first. */
    var colours: TeamColours? = null
        private set

    /** The scoring side, set by [begin]. */
    var side: Side = Side.AWAY
        private set

    /** Device px per design px without the Large factor (GA's glow table is given per size). */
    private val d = if (large) s / K.SCORE_LARGE_SCALE else s
    private val h = K.STACKED_H * s
    private val cy = h / 2f
    private val radius = M.BOX_RADIUS * K.STACKED_SCALE * s
    private val badgeSlot = K.STACKED_GOAL_LOGO * s
    private val gap = M.GOAL_GAP * K.STACKED_SCALE * s

    private val leadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val streak1Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak2Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val badgeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Review U N6: filtered, like the shadow, so a fractional mask origin (720p) does not snap by up to half a pixel. */
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.GOAL_BADGE_TEXT_PX * s; color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val tallyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.STACKED_TALLY_PX * s; color = Color.WHITE; textAlign = Paint.Align.LEFT
        fontFeatureSettings = "'tnum'"
    }
    private val badgeTextOffset: Float
    private val tallyBaseline1: Float
    private val tallyBaseline2: Float

    /** The whole box's rect (the top part plus an open bar), device px; [extent] sets its top and bottom. */
    private val box = RectF(0f, 0f, 0f, h)
    private val scratch = RectF()

    // ---- the GOAL atlas (GA S2): the four letters with their hard shadows, white, at GOAL_ATLAS_SCALE × the rest size
    private val atlas: Bitmap
    private val letterSrc = Array(GoalFrame.LETTERS) { Rect() }
    /** Each letter's rest-size destination, relative to its own transform origin (the CSS span's centre). */
    private val letterDst = Array(GoalFrame.LETTERS) { RectF() }
    /** Each letter's origin x relative to the word's centre. */
    private val letterOx = FloatArray(GoalFrame.LETTERS)
    /** The word's rest width (CSS: letter-spacing follows every letter, the last too). */
    private val wordW: Float

    init {
        require(word.length == GoalFrame.LETTERS) { "the GOAL word must have ${GoalFrame.LETTERS} letters: \"$word\"" }
        val b = Rect()
        badgeText.getTextBounds("M", 0, 1, b)
        badgeTextOffset = -(b.top + b.bottom) / 2f
        // The tally: a column of two lines (line-height normal) with a 6-mock gap, centred on the panel (align-items).
        val fm = tallyPaint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val lineGap = M.TALLY_LINE_GAP * K.STACKED_SCALE * s
        val top = cy - (2 * lineH + lineGap) / 2f
        tallyBaseline1 = top - fm.ascent
        tallyBaseline2 = tallyBaseline1 + lineH + lineGap

        val a = K.GOAL_ATLAS_SCALE
        val px = M.GOAL_WORD_PX * K.STACKED_SCALE * s
        val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = goalFont; textSize = px * a; color = Color.WHITE }
        val shadow = Paint(wordPaint).apply { color = argb(K.GOAL_SHADOW_ALPHA, 0x000000) }
        val track = M.GOAL_TRACK_EM * px * a
        val dy = GOAL_SHADOW_DY_MOCK * K.STACKED_SCALE * s * a
        val pad = ceil(2f * a).toInt()
        val bounds = Array(GoalFrame.LETTERS) { Rect() }
        val adv = FloatArray(GoalFrame.LETTERS)
        var top0 = 0
        var bottom0 = 0
        for (i in 0 until GoalFrame.LETTERS) {
            wordPaint.getTextBounds(word, i, i + 1, bounds[i])
            adv[i] = wordPaint.measureText(word, i, i + 1)
            top0 = minOf(top0, bounds[i].top)
            bottom0 = maxOf(bottom0, bounds[i].bottom)
        }
        val cellTop = top0 - pad
        val cellH = (bottom0 + ceil(dy).toInt() + pad) - cellTop
        val cellW = IntArray(GoalFrame.LETTERS) { bounds[it].width() + 2 * pad }
        atlas = createBitmap(cellW.sum().coerceAtLeast(1), cellH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ac = Canvas(atlas)
        wordW = (adv.sum() + GoalFrame.LETTERS * track) / a
        var pen = -wordW / 2f
        // The glyphs' ink is centred on the panel's middle (cy), as GA does for the pill.
        val baselineRel = -(top0 + bottom0) / 2f / a
        var x0 = 0
        for (i in 0 until GoalFrame.LETTERS) {
            val bx = x0 - (bounds[i].left - pad).toFloat()
            val by = -cellTop.toFloat()
            ac.drawText(word, i, i + 1, bx, by + dy, shadow)
            ac.drawText(word, i, i + 1, bx, by, wordPaint)
            letterSrc[i].set(x0, 0, x0 + cellW[i], cellH)
            val cell = (adv[i] + track) / a
            letterOx[i] = pen + cell / 2f
            val l = pen + (bounds[i].left - pad) / a - letterOx[i]
            val t = baselineRel + cellTop / a
            letterDst[i].set(l, t, l + cellW[i] / a, t + cellH / a)
            pen += cell
            x0 += cellW[i]
        }
    }

    // ---- per takeover (begin)
    private var badgeSource: Bitmap? = null
    private var badge: Bitmap? = null
    private var badgeShadow: Bitmap? = null
    /** The badge's rest rect about its centre, and its shadow's (offset down by the hard shadow). */
    private val badgeDst = RectF()
    private val badgeShadowDst = RectF()
    private var abbrev: String = ""
    private var tally1 = CharArray(0)
    private var tally1Len = 0
    private var tally2 = CharArray(0)
    private var tally2Len = 0
    private var boxW = 0f

    /** The layout fixed by [begin] (device px, x from the box's left). */
    private var pad = 0f
    private var badgeCx = 0f
    private var wordCx = 0f
    private var tallyX = 0f
    private var tallyW = 0f
    private var showTally = true

    /**
     * Once per takeover, on the main thread before its first frame: the scorer's [side] and [colours], the badge bitmap
     * ([badge], the logo loaded at the takeover size `STACKED_GOAL_LOGO`, S6; null = the abbreviation), both teams'
     * abbreviations and the score after the goal ([away] / [home], the tally), and [boxW], the pinned top-part width in
     * Standard design px (`StackedFrame.boxW` at takeover start).
     */
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
        this.abbrev = if (side == Side.AWAY) awayAbbrev else homeAbbrev
        this.boxW = boxW * s
        leadPaint.color = colours.accent or OPAQUE
        panelPaint.color = colours.base or OPAQUE
        glowPaint.color = colours.glow or OPAQUE
        streak1Paint.shader = streakShader(M.STREAK1_W * K.STACKED_SCALE * s, colours.accent, spec.streak1Alpha)
        streak2Paint.shader = streakShader(M.STREAK2_W * K.STACKED_SCALE * s, Color.WHITE, spec.streak2Alpha)
        val l1 = "$awayAbbrev $away"
        val l2 = "$homeAbbrev $home"
        if (tally1.size < l1.length) tally1 = CharArray(l1.length)
        l1.toCharArray(tally1, 0, 0, l1.length)
        tally1Len = l1.length
        if (tally2.size < l2.length) tally2 = CharArray(l2.length)
        l2.toCharArray(tally2, 0, 0, l2.length)
        tally2Len = l2.length

        // The badge (GA N3): a copy with 1 px of transparent padding, so a rotated, filtered edge stays smooth, and its
        // hard drop shadow (`filter: drop-shadow(0 6px 0 rgba(0,0,0,.25))`) as an alpha copy. Once per logo identity.
        if (badge == null) {
            this.badge = null
            badgeShadow = null
            badgeSource = null
        } else if (badge !== badgeSource) {
            val copy = createBitmap(badge.width + 2, badge.height + 2, Bitmap.Config.ARGB_8888)
            Canvas(copy).drawBitmap(badge, 1f, 1f, null)
            this.badge = copy
            badgeShadow = copy.extractAlpha()
            badgeSource = badge
            // Review U N10: upload both now, not on the first frame the badge shows (450 ms into the takeover).
            copy.prepareToDraw()
            badgeShadow?.prepareToDraw()
        }
        this.badge?.let { b ->
            // `height: 150px; width: auto` in a 150-wide slot: the logo (already fitted to the slot) at its own size,
            // scaled down only if it exceeds the slot.
            val k = minOf(1f, badgeSlot / (b.width - 2).coerceAtLeast(1), badgeSlot / (b.height - 2).coerceAtLeast(1))
            val w = b.width * k
            val bh = b.height * k
            badgeDst.set(-w / 2f, -bh / 2f, w / 2f, bh / 2f)
            val dy = K.STACKED_BADGE_SHADOW_DY * s
            badgeShadowDst.set(badgeDst.left, badgeDst.top + dy, badgeDst.right, badgeDst.bottom + dy)
        }
        layout()
    }

    /**
     * §4.2's fit rule on the pinned width: the mock's padding (40 → 18) while the row has at least
     * `STACKED_TAKEOVER_SLACK_MIN` of slack; under that, padding `STACKED_TAKEOVER_PAD_TIGHT`; and if the content still
     * does not fit, the tally is dropped (the scores come back on the box at 3 600 ms). GOAL is centred in the space
     * between the badge and the tally (or the right padding), as the mock's `flex:1` column does.
     */
    private fun layout() {
        tallyW = maxOf(tallyPaint.measureText(tally1, 0, tally1Len), tallyPaint.measureText(tally2, 0, tally2Len))
        fun need(p: Float, tally: Boolean) = 2 * p + badgeSlot + gap + wordW + (if (tally) gap + tallyW else 0f)
        pad = M.GOAL_PAD * K.STACKED_SCALE * s
        showTally = true
        if (boxW - need(pad, true) < K.STACKED_TAKEOVER_SLACK_MIN * s) pad = K.STACKED_TAKEOVER_PAD_TIGHT * s
        if (boxW - need(pad, true) < 0f) showTally = false
        badgeCx = pad + badgeSlot / 2f
        val midL = pad + badgeSlot + gap
        val midR = if (showTally) boxW - pad - tallyW - gap else boxW - pad
        wordCx = (midL + midR) / 2f
        tallyX = boxW - pad - tallyW
    }

    /**
     * (WP-2; set by [StackedPainter.drawFrame] before each [drawTakeover]) The whole box's top and bottom in the top
     * part's coordinates (device px: −barH and [h] with the bar open above, 0 and [h] + barH with it below), so the panel's
     * rounded corners fall on the box's corners only. Allocation-free.
     */
    internal fun extent(top: Float, bottom: Float) {
        box.top = top
        box.bottom = bottom
    }

    /**
     * The streak band (GA S4): CSS `skewX(−24deg)` about the element's centre leans it "/", so its edges run along
     * (sin 24°, −cos 24°) and the gradient runs perpendicular, along (cos 24°, sin 24°). The left edge passes through
     * (0, the top part's centre); [w] is the CSS (horizontal) width. Soft 1 device px stops at each edge, `CLAMP` to
     * transparent.
     */
    private fun streakShader(w: Float, rgb: Int, alpha: Float): Shader {
        val rad = Math.toRadians(-K.GOAL_STREAK_SKEW_DEG.toDouble())
        val nx = cos(rad).toFloat()
        val ny = sin(rad).toFloat()
        val len = w * nx
        val eps = (1f / len).coerceAtMost(0.49f)
        val c = argb(alpha, rgb)
        val t = rgb and 0xFFFFFF
        return LinearGradient(0f, cy, len * nx, cy + len * ny, intArrayOf(t, c, c, t), floatArrayOf(0f, eps, 1f - eps, 1f), Shader.TileMode.CLAMP)
    }

    /**
     * (B4) GA's two glow masks (low and peak: `GOAL_GLOW_*` spreads and σ, radius `StackedMock.BOX_RADIUS` × scale) baked
     * for exactly the box [w] × [h] **device px** (the top part plus the open bar, as pinned), with the box cut out.
     * [GlowMasks.lowMargin] / [GlowMasks.peakMargin] place each bitmap's origin up and left of that box's top-left. Off the
     * main thread. Null only for an empty box.
     */
    fun bakeGlow(w: Float, h: Float): GlowMasks? {
        if (!(w > 0f) || !(h > 0f)) return null
        val lowSpread = K.GOAL_GLOW_LOW_SPREAD * (if (large) K.SCORE_LARGE_SCALE else 1f)
        val lowSigma = K.GOAL_GLOW_LOW_SIGMA * (if (large) K.SCORE_LARGE_SCALE else 1f)
        val peakSpread = if (large) K.GOAL_GLOW_LARGE_PEAK_SPREAD else K.GOAL_GLOW_PEAK_SPREAD
        val peakSigma = if (large) K.GOAL_GLOW_LARGE_PEAK_SIGMA else K.GOAL_GLOW_PEAK_SIGMA
        val lowMargin = (lowSpread + 3f * lowSigma) * d
        val peakMargin = (peakSpread + 3f * peakSigma) * d
        return GlowMasks(
            bakeMask(w, h, lowSpread * d, lowSigma * d, lowMargin),
            bakeMask(w, h, peakSpread * d, peakSigma * d, peakMargin),
            lowMargin, peakMargin,
        )
    }

    /** One mask: the box inflated by [spread], blurred to [sigma] (device px), with the box cut out (anti-aliased). */
    private fun bakeMask(w: Float, h: Float, spread: Float, sigma: Float, margin: Float): Bitmap {
        val bmp = createBitmap(ceil(w + 2 * margin).toInt(), ceil(h + 2 * margin).toInt(), Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.translate(margin, margin)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            // Skia's sigma = 0.57735 × radius + 0.5.
            maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(-spread, -spread, w + spread, h + spread, radius + spread, radius + spread, p)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        c.drawRoundRect(0f, 0f, w, h, radius, radius, cut)
        return bmp
    }

    /**
     * The glow for [g] around the glow box, whose top-left is at ([x], [y]) device px in [StackedPainter.drawFrame]'s
     * coordinates (y is −barH when the bar is open above the box).
     */
    fun drawGlow(c: Canvas, g: GoalFrame, glow: GlowMasks, x: Float, y: Float) {
        if (colours == null) return
        val lo = alpha255(g.glowLowAlpha)
        if (lo > 0) { glowPaint.alpha = lo; c.drawBitmap(glow.low, x - glow.lowMargin, y - glow.lowMargin, glowPaint) }
        val hi = alpha255(g.glowPeakAlpha)
        if (hi > 0) { glowPaint.alpha = hi; c.drawBitmap(glow.peak, x - glow.peakMargin, y - glow.peakMargin, glowPaint) }
    }

    /** The lead, the panel and its contents for [g], over the top part (origin = its top-left, device px). */
    fun drawTakeover(c: Canvas, g: GoalFrame) {
        if (colours == null || boxW <= 0f) return
        box.right = boxW
        if (g.leadTo > g.leadFrom) {
            c.withClip(g.leadFrom * boxW, 0f, g.leadTo * boxW, h) { drawRoundRect(box, radius, radius, leadPaint) }
        }
        if (g.panelTo <= g.panelFrom) return
        c.withClip(g.panelFrom * boxW, 0f, g.panelTo * boxW, h) {
            drawRoundRect(box, radius, radius, panelPaint)
            if (g.streak1On) streak(this, g.streak1X * s, streak1Paint)
            if (g.streak2On) streak(this, g.streak2X * s, streak2Paint)
            val fa = alpha255(g.flashAlpha)
            if (fa > 0) { flashPaint.alpha = fa; drawRoundRect(box, radius, radius, flashPaint) }
            drawBadge(this, g)
            drawWord(this, g)
            val ta = alpha255(g.tallyAlpha)
            if (showTally && ta > 0) {
                tallyPaint.alpha = ta
                val x = tallyX + g.tallyDx * s
                drawText(tally1, 0, tally1Len, x, tallyBaseline1, tallyPaint)
                drawText(tally2, 0, tally2Len, x, tallyBaseline2, tallyPaint)
            }
        }
    }

    /** The band at left edge [dx]: translate, then the box offset back by −dx, so the shader moves without a matrix. */
    private fun streak(c: Canvas, dx: Float, p: Paint) {
        scratch.set(-dx, box.top, boxW - dx, box.bottom)
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
                val sh = badgeShadow
                if (sh != null) {
                    badgeShadowPaint.alpha = (a * K.STACKED_TEXT_SHADOW_ALPHA).roundToInt()
                    drawBitmap(sh, null, badgeShadowDst, badgeShadowPaint)
                }
                badgePaint.alpha = a
                drawBitmap(b, null, badgeDst, badgePaint)
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
                withTranslation(wordCx + letterOx[i], cy + g.letterDy[i] * s) {
                    scale(g.letterScale[i], g.letterScale[i])
                    letterPaint.alpha = a
                    drawBitmap(atlas, letterSrc[i], letterDst[i], letterPaint)
                }
            }
        }
    }

    /**
     * Hands the atlas (and [glow], if baked) to the GPU ahead of the first takeover (`Bitmap.prepareToDraw`), so the
     * first takeover frame does not pay for the upload. Main thread, once per painter or per new glow bake.
     */
    fun prewarm(glow: GlowMasks?) {
        atlas.prepareToDraw()
        glow?.low?.prepareToDraw()
        glow?.peak?.prepareToDraw()
    }

    // ---- test seams (read after [begin]; device px from the box's left)
    internal val atlasBitmap: Bitmap get() = atlas
    internal val tallyShown: Boolean get() = showTally
    internal val padUsed: Float get() = pad
    internal val badgeLeft: Float get() = badgeCx - badgeSlot / 2f
    internal val badgeRight: Float get() = badgeCx + badgeSlot / 2f
    internal val wordLeft: Float get() = wordCx - wordW / 2f
    internal val wordRight: Float get() = wordCx + wordW / 2f
    internal val tallyLeft: Float get() = tallyX
    internal val tallyRight: Float get() = tallyX + tallyW
    internal val wordCentre: Float get() = wordCx
    /** The two tally lines [begin] fixed. Allocates; never called in draw. */
    internal val tallyText: String get() = String(tally1, 0, tally1Len) + "\n" + String(tally2, 0, tally2Len)

    private companion object {
        /** The GOAL word's hard shadow, `text-shadow: 0 6px 0` in mock px (× `STACKED_SCALE` = 2.7). */
        const val GOAL_SHADOW_DY_MOCK = 6f
        const val OPAQUE = 0xFF shl 24

        fun alpha255(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        fun argb(a: Float, rgb: Int): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (rgb and 0xFFFFFF)
    }
}
