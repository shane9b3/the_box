// Contract (NFL commit 0, docs/design/m6-nfl.md §5.2, §5.5, §11): the signatures are fixed for WP-3 (the body) and WP-4
// (the caller, `NflRun`); changes go through the lead.
package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflShapes
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamColours
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * (NFL §5.2, §5.5; WP-3) The NFL scoring takeover in plain `android.graphics`, on the GA rules (§2): one de-duplicated
 * glyph atlas per size for the five words (20 glyphs, `ARGB_8888` at `NFL_ATLAS_SCALE`, each with its hard shadow), the
 * ball and goalpost sprites (`ALPHA_8`), the GA glow masks for the NFL pill. [s] is device px per design px with Large
 * folded in; [large] picks the Large glow table.
 *
 * - **Built here, once per size:** the paints, the atlas, the sprites, the word layouts.
 * - **[bakeGlow]**, off the main thread, never in low-end mode.
 * - **[begin]**, once per takeover (or per segment of a §5.7 pair): the colours, the streak shaders, the tally chars,
 *   the padded badge.
 * - **[drawGlow]** before the glass, **[drawTakeover]** after the body (§5.5's order), allocation-free: only `save` /
 *   `restore` / `clipRect` / `translate` / `rotate` / `scale` / `drawRoundRect` / `drawBitmap` / `drawText(char[])`.
 *
 * The five words are the `sports_nfl_*` strings (not translatable, so fixed here: the constructor has no resources;
 * `NflScreenshotTest` pins them against the strings). The ball runs under the inner band `NFL_RUN_BAND`, the arc and
 * the posts under the straight-section band `NFL_ARC_BAND` (B5): no `clipPath`, `saveLayer` or `setLocalMatrix`.
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

    /** Device px per design px without the Large factor (the glow table is per size, GA §2.4). */
    private val d = if (large) s / K.SCORE_LARGE_SCALE else s
    private val radius = pillH / 2f
    private val cy = pillH / 2f
    private val pill = RectF(0f, 0f, pillW, pillH)
    private val scratch = RectF()
    private val dst = RectF()

    private val leadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val streak1Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak2Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** The badge's hard shadow (`drop-shadow(0 2 0 black .25)`): the logo's own alpha, tinted black once here. */
    private val badgeShadow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint()
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.GOAL_BADGE_TEXT_PX * s; color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val pointsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.NFL_POINTS_PX * s; color = Color.WHITE; textAlign = Paint.Align.RIGHT
    }
    private val tallyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.NFL_TALLY_PX * s; color = Color.WHITE; textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "'tnum'"
    }
    private val badgeTextOffset: Float
    private val pointsBaseline: Float
    private val tallyBaseline: Float

    // ---- layout (§5.2), device px
    private val badgeCx = (K.NFL_TAKEOVER_PAD + K.SCORE_LOGO_W / 2f) * s
    private val blockRight = pillW - K.NFL_TAKEOVER_PAD * s
    private val blockCx = blockRight - K.NFL_BLOCK_W * s / 2f
    /** The word's centre (the pulse's origin): between the badge (+ gap) and the score block's slot (+ gap). */
    val wordCx: Float = (
        (K.NFL_TAKEOVER_PAD + K.SCORE_LOGO_W + K.NFL_TAKEOVER_GAP) * s +
            pillW - (K.NFL_TAKEOVER_PAD + K.NFL_BLOCK_W + K.NFL_TAKEOVER_GAP) * s
        ) / 2f
    private val runBand = K.NFL_RUN_BAND * s
    private val arcBand = K.NFL_ARC_BAND * s

    // ---- the glyph atlas (N2) and the five word layouts
    private val atlas: Bitmap
    private val glyphSrc: Array<Rect>
    /** Per word (by kind ordinal), per character: the glyph index (−1 = a space), its origin x and rest destination. */
    private val glyphOf = Array(WORDS.size) { IntArray(NflScoreFrame.LETTERS) { -1 } }
    private val letterOx = Array(WORDS.size) { FloatArray(NflScoreFrame.LETTERS) }
    private val letterDst = Array(WORDS.size) { Array(NflScoreFrame.LETTERS) { RectF() } }
    /** Each word's rest width (design px × s), a test seam. */
    internal val wordWidth = FloatArray(WORDS.size)

    // ---- the sprites (ALPHA_8 at NFL_ATLAS_SCALE)
    private val ball: Bitmap
    private val posts: Bitmap
    private val ballHalf = K.NFL_BALL_PX * s / 2f

    init {
        val b = Rect()
        badgeText.getTextBounds("M", 0, 1, b)
        badgeTextOffset = -(b.top + b.bottom) / 2f
        // The block: "+6" (line box 32) over the tally (line box 20), 2 apart, centred in the pill; caps centred per box.
        val blockH = (K.NFL_POINTS_PX + K.NFL_BLOCK_GAP + K.NFL_TALLY_PX) * s
        val blockTop = cy - blockH / 2f
        pointsPaint.getTextBounds("+0", 0, 2, b)
        pointsBaseline = blockTop + K.NFL_POINTS_PX * s / 2f - (b.top + b.bottom) / 2f
        tallyPaint.getTextBounds("0", 0, 1, b)
        tallyBaseline = blockTop + (K.NFL_POINTS_PX + K.NFL_BLOCK_GAP + K.NFL_TALLY_PX / 2f) * s - (b.top + b.bottom) / 2f

        // The distinct glyphs of the five words, baked once with their hard shadows.
        val chars = WORDS.joinToString("").filter { it != ' ' }.toSet().sorted()
        val a = K.NFL_ATLAS_SCALE
        val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = wordFont; textSize = K.NFL_WORD_PX * s * a; color = Color.WHITE }
        val shadow = Paint(wordPaint).apply { color = argb(K.NFL_WORD_SHADOW_ALPHA, 0x000000) }
        val dy = K.NFL_WORD_SHADOW_DY * s * a
        val pad = ceil(2f * a).toInt()
        val bounds = Array(chars.size) { Rect() }
        var top = 0
        var bottom = 0
        for ((i, ch) in chars.withIndex()) {
            wordPaint.getTextBounds(ch.toString(), 0, 1, bounds[i])
            top = minOf(top, bounds[i].top)
            bottom = maxOf(bottom, bounds[i].bottom)
        }
        val cellTop = top - pad
        val cellH = (bottom + ceil(dy).toInt() + pad) - cellTop
        val cellW = IntArray(chars.size) { bounds[it].width() + 2 * pad }
        atlas = createBitmap(cellW.sum().coerceAtLeast(1), cellH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ac = Canvas(atlas)
        glyphSrc = Array(chars.size) { Rect() }
        val cellLeft = FloatArray(chars.size)
        var x0 = 0
        for ((i, ch) in chars.withIndex()) {
            val str = ch.toString()
            val bx = x0 - (bounds[i].left - pad).toFloat()
            val by = -cellTop.toFloat()
            ac.drawText(str, bx, by + dy, shadow)
            ac.drawText(str, bx, by, wordPaint)
            glyphSrc[i].set(x0, 0, x0 + cellW[i], cellH)
            cellLeft[i] = (bounds[i].left - pad).toFloat()
            x0 += cellW[i]
        }
        // Rest layouts: CSS letter-spacing follows every letter (the last too); each word is centred on [wordCx].
        val track = K.NFL_WORD_TRACK_EM * K.NFL_WORD_PX * s * a
        val baseline = cy - (top + bottom) / 2f / a
        for ((w, word) in WORDS.withIndex()) {
            val n = minOf(word.length, NflScoreFrame.LETTERS)
            val adv = FloatArray(n) { (wordPaint.measureText(word, it, it + 1) + track) / a }
            val width = adv.sum()
            wordWidth[w] = width
            var pen = wordCx - width / 2f
            for (i in 0 until n) {
                val ox = pen + adv[i] / 2f
                letterOx[w][i] = ox
                val gi = chars.indexOf(word[i])
                glyphOf[w][i] = gi
                if (gi >= 0) {
                    val l = pen + cellLeft[gi] / a - ox
                    val t = baseline + cellTop / a - cy
                    letterDst[w][i].set(l, t, l + cellW[gi] / a, t + cellH / a)
                }
                pen += adv[i]
            }
        }

        ball = bakeBall()
        posts = bakePosts()
    }

    /** The football, centred in its 34 px box at `BALL_UNIT_PX` (B5: its ink within `NFL_BALL_INK_R`). */
    private fun bakeBall(): Bitmap {
        val a = K.NFL_ATLAS_SCALE
        val px = ceil(K.NFL_BALL_PX * s * a).toInt()
        val bmp = createBitmap(px, px, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val unit = NflShapes.BALL_UNIT_PX * s * a
        c.translate(px / 2f, px / 2f)
        c.scale(unit, unit)
        c.translate(-NflShapes.BALL_VIEWBOX / 2f, -NflShapes.BALL_VIEWBOX / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = NflShapes.BALL_STROKE
        }
        c.drawPath(NflTabPainter.ballPath(), p)
        return bmp
    }

    /** The goalposts, 43 × 52 (the mock's 81 × 108 at .531 / .486), stroke 3 of the 60 × 80 viewBox, round caps. */
    private fun bakePosts(): Bitmap {
        val a = K.NFL_ATLAS_SCALE
        val w = ceil(K.NFL_POSTS_W * s * a).toInt()
        val h = ceil(K.NFL_POSTS_H * s * a).toInt()
        val bmp = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.scale(w / NflShapes.POSTS_VIEW_W, h / NflShapes.POSTS_VIEW_H)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeWidth = NflShapes.POSTS_STROKE
        }
        val l = NflShapes.POSTS_LINES
        var i = 0
        while (i < l.size) { c.drawLine(l[i], l[i + 1], l[i + 2], l[i + 3], p); i += 4 }
        return bmp
    }

    // ---- per takeover (begin)
    private var kind = NflScoreKind.TOUCHDOWN
    private var badgeSource: Bitmap? = null
    private var badge: Bitmap? = null
    private var abbrev: String = ""
    private var points = CharArray(4)
    private var pointsLen = 0
    private var tally = CharArray(0)
    private var tallyLen = 0

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

    /** GA §2.4: the pill inflated by [spread], blurred to [sigma] (device px), with the pill cut out. */
    private fun bakeMask(spread: Float, sigma: Float, margin: Float): Bitmap {
        val bmp = createBitmap(ceil(pillW + 2 * margin).toInt(), ceil(pillH + 2 * margin).toInt(), Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.translate(margin, margin)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(-spread, -spread, pillW + spread, pillH + spread, radius + spread, radius + spread, p)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        c.drawRoundRect(pill, radius, radius, cut)
        return bmp
    }

    /** One takeover (or one segment of a pair): [score]'s word, "+N" and its tally, the [badge] (the held logo, or null). */
    fun begin(side: Side, colours: TeamColours, badge: Bitmap?, abbrev: String, score: NflScore) {
        this.side = side
        this.colours = colours
        this.abbrev = abbrev
        kind = score.kind
        leadPaint.color = colours.accent or OPAQUE
        panelPaint.color = colours.base or OPAQUE
        glowPaint.color = colours.glow or OPAQUE
        streak1Paint.shader = streakShader(K.NFL_STREAK1_W * s, colours.accent, K.GOAL_STREAK1_ALPHA)
        streak2Paint.shader = streakShader(K.NFL_STREAK2_W * s, Color.WHITE, K.GOAL_STREAK2_ALPHA)
        val pts = "+${score.points}"
        if (points.size < pts.length) points = CharArray(pts.length)
        pts.toCharArray(points, 0, 0, pts.length)
        pointsLen = pts.length
        val t = "${score.away} – ${score.home}"
        if (tally.size < t.length) tally = CharArray(t.length)
        t.toCharArray(tally, 0, 0, t.length)
        tallyLen = t.length
        if (badge == null) {
            this.badge = null
            badgeSource = null
        } else if (badge !== badgeSource) {
            // GA N3: a copy with 1 px of transparent padding, once per logo bitmap identity.
            val copy = createBitmap(badge.width + 2, badge.height + 2, Bitmap.Config.ARGB_8888)
            Canvas(copy).drawBitmap(badge, 1f, 1f, null)
            this.badge = copy
            badgeSource = badge
        }
    }

    /** The streak band (GA S4): `skewX(−24deg)`, the gradient perpendicular to its edges, soft 1 px stops. */
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

    fun drawGlow(c: Canvas, g: NflScoreFrame, glow: GlowMasks) {
        if (colours == null) return
        val lo = alpha255(g.glowLowAlpha)
        if (lo > 0) { glowPaint.alpha = lo; c.drawBitmap(glow.low, -glow.lowMargin, -glow.lowMargin, glowPaint) }
        val hi = alpha255(g.glowPeakAlpha)
        if (hi > 0) { glowPaint.alpha = hi; c.drawBitmap(glow.peak, -glow.peakMargin, -glow.peakMargin, glowPaint) }
    }

    /**
     * The lead, then under the panel's wipe clip: the panel, streak 1, streak 2, the posts and the arc (under the
     * straight-section band) or the run (under the inner band), the flash, the badge, the word and the score block.
     */
    fun drawTakeover(c: Canvas, g: NflScoreFrame) {
        if (colours == null) return
        if (g.leadTo > g.leadFrom) {
            c.save()
            c.clipRect(g.leadFrom * pillW, 0f, g.leadTo * pillW, pillH)
            c.drawRoundRect(pill, radius, radius, leadPaint)
            c.restore()
        }
        if (g.panelTo <= g.panelFrom) return
        c.save()
        c.clipRect(g.panelFrom * pillW, 0f, g.panelTo * pillW, pillH)
        c.drawRoundRect(pill, radius, radius, panelPaint)
        if (g.streak1On) streak(c, g.streak1X * s, streak1Paint)
        if (g.streak2On) streak(c, g.streak2X * s, streak2Paint)
        if (g.postsOn || g.arcOn) {
            c.save()
            c.clipRect(arcBand, 0f, pillW - arcBand, pillH)
            val pa = alpha255(g.postsAlpha)
            if (g.postsOn && pa > 0) {
                spritePaint.color = (pa shl 24) or 0xFFFFFF
                val x = K.NFL_POSTS_X * s
                val y = (K.NFL_POSTS_Y + g.postsDy) * s
                dst.set(x, y, x + K.NFL_POSTS_W * s, y + K.NFL_POSTS_H * s)
                c.drawBitmap(posts, null, dst, spritePaint)
            }
            if (g.arcOn) drawBall(c, g)
            c.restore()
        } else if (g.runOn) {
            c.save()
            c.clipRect(runBand, 0f, pillW - runBand, pillH)
            drawBall(c, g)
            c.restore()
        }
        val fa = alpha255(g.flashAlpha)
        if (fa > 0) { flashPaint.alpha = fa; c.drawRoundRect(pill, radius, radius, flashPaint) }
        drawBadge(c, g)
        drawWord(c, g)
        drawBlock(c, g)
        c.restore()
    }

    private fun drawBall(c: Canvas, g: NflScoreFrame) {
        val a = alpha255(g.ballAlpha)
        if (a <= 0) return
        spritePaint.color = (a shl 24) or 0xFFFFFF
        c.save()
        c.translate(g.ballX * s + ballHalf, g.ballY * s + ballHalf)
        c.rotate(g.ballRotDeg)
        c.scale(g.ballScale, g.ballScale)
        dst.set(-ballHalf, -ballHalf, ballHalf, ballHalf)
        c.drawBitmap(ball, null, dst, spritePaint)
        c.restore()
    }

    /** The band at left edge [dx]: translate, then the pill offset back by −dx, so the shader moves without a matrix. */
    private fun streak(c: Canvas, dx: Float, p: Paint) {
        scratch.set(-dx, 0f, pillW - dx, pillH)
        c.save()
        c.translate(dx, 0f)
        c.drawRoundRect(scratch, radius, radius, p)
        c.restore()
    }

    private fun drawBadge(c: Canvas, g: NflScoreFrame) {
        val a = alpha255(g.badgeAlpha)
        if (a <= 0) return
        c.save()
        c.translate(badgeCx + g.badgeDx * s, cy)
        c.rotate(g.badgeRotDeg)
        c.scale(g.badgeScale, g.badgeScale)
        val b = badge
        if (b != null) {
            badgeShadow.alpha = alpha255(g.badgeAlpha * K.NFL_LOGO_SHADOW_ALPHA)
            c.drawBitmap(b, -b.width / 2f, -b.height / 2f + K.NFL_LOGO_SHADOW_DY * s, badgeShadow)
            badgePaint.alpha = a
            c.drawBitmap(b, -b.width / 2f, -b.height / 2f, badgePaint)
        } else {
            badgeText.alpha = a
            c.drawText(abbrev, 0f, badgeTextOffset, badgeText)
        }
        c.restore()
    }

    private fun drawWord(c: Canvas, g: NflScoreFrame) {
        val w = kind.ordinal
        val n = minOf(g.letterCount, WORDS[w].length, NflScoreFrame.LETTERS)
        c.save()
        c.scale(g.wordPulse, g.wordPulse, wordCx, cy)
        for (i in 0 until n) {
            val gi = glyphOf[w][i]
            if (gi < 0) continue
            val a = alpha255(g.letterAlpha[i])
            if (a <= 0) continue
            c.save()
            c.translate(letterOx[w][i], cy + g.letterDy[i] * s)
            c.scale(g.letterScale[i], g.letterScale[i])
            letterPaint.alpha = a
            c.drawBitmap(atlas, glyphSrc[gi], letterDst[w][i], letterPaint)
            c.restore()
        }
        c.restore()
    }

    private fun drawBlock(c: Canvas, g: NflScoreFrame) {
        val a = alpha255(g.blockAlpha)
        if (a <= 0) return
        c.save()
        c.translate(g.blockDx * s, 0f)
        c.scale(g.blockScale, g.blockScale, blockCx, cy)
        pointsPaint.alpha = a
        tallyPaint.alpha = a
        if (pointsLen > 0) c.drawText(points, 0, pointsLen, blockRight, pointsBaseline, pointsPaint)
        if (tallyLen > 0) c.drawText(tally, 0, tallyLen, blockRight, tallyBaseline, tallyPaint)
        c.restore()
    }

    // ------------------------------------------------------------------ test seams

    internal val atlasBitmap: Bitmap get() = atlas
    internal val ballSprite: Bitmap get() = ball
    internal val postsSprite: Bitmap get() = posts
    internal val tallyText: String get() = String(tally, 0, tallyLen)
    internal val pointsText: String get() = String(points, 0, pointsLen)

    /** Hands the atlas and sprites to the GPU ahead of the first takeover (`Bitmap.prepareToDraw`); main thread, once. */
    fun prewarm(glow: GlowMasks?) {
        atlas.prepareToDraw()
        ball.prepareToDraw()
        posts.prepareToDraw()
        glow?.low?.prepareToDraw()
        glow?.peak?.prepareToDraw()
    }

    companion object {
        /** The five words by [NflScoreKind] ordinal: `sports_nfl_td` / `_xp` / `_fg` / `_safety` / `_2pt` (§5.2). */
        val WORDS = arrayOf("TOUCHDOWN", "EXTRA POINT", "FIELD GOAL", "SAFETY", "TWO-POINT")
        private const val OPAQUE = 0xFF shl 24

        private fun alpha255(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        private fun argb(a: Float, rgb: Int): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (rgb and 0xFFFFFF)
    }
}
