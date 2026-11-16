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
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreSpec
import app.thebox.core.sports.NflShapes
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedMock
import app.thebox.core.sports.StackedNflGeometry as G
import app.thebox.core.sports.TeamColours
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * The row flash and the scorer's pop are drawn by [StackedNflPainter] (they are the rows' content); this painter draws
 * what covers the top part. The structure is `StackedGoalPainter`'s (one glyph atlas per size, `clipRect` wipes over
 * anti-aliased round rects of the whole box, no `clipPath`, `saveLayer` or `setLocalMatrix`) with `NflScorePainter`'s
 * sprites (the ball and the goalposts, `ALPHA_8`) and de-duplicated five-word atlas.
 *
 * **Coordinates** (as `StackedNflDrawArgs`): the origin is the rows' left edge at the top part's top; mirrored, the top
 * part spans `[rowsW − boxW, rowsW]`, so [drawTakeover] shifts itself there. The timeline's x's are on the 333 box: the
 * arc and the posts scale by k = boxW / 333 (§4.3 "×k"), the run and the streaks cross whatever box there is.
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

    /** Device px per design px without the Large factor (GA's glow table is given per size). */
    private val d = if (large) s / K.SCORE_LARGE_SCALE else s
    private val h = K.STACKED_H * s
    private val cy = h / 2f
    private val rowsW = K.STACKED_ROWS_W * s
    private val radius = StackedMock.BOX_RADIUS * K.STACKED_SCALE * s
    private val badgeSlot = K.STACKED_NFL_BADGE * s
    private val streak1W = K.STACKED_NFL_STREAK1_W * s
    private val streak2W = K.STACKED_NFL_STREAK2_W * s
    /** `StackedNflGeometry.takeover`'s output (begin only). */
    private val items = FloatArray(G.T_SIZE)

    private val leadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val streak1Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak2Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** The badge's hard shadow (`drop-shadow(0 5px 0 rgba(0,0,0,.25))` → dy 2.25): the logo's alpha tinted black. */
    private val badgeShadow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val discFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val discStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s.coerceAtLeast(1f) }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.GOAL_BADGE_TEXT_PX * s; color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val pointsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.STACKED_NFL_POINTS_PX * s; color = Color.WHITE; textAlign = Paint.Align.RIGHT
    }
    private val tallyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansBold; textSize = K.STACKED_TALLY_PX * s; color = Color.WHITE; textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "'tnum'"
    }
    private val pointsCap: Float
    private val tallyCap: Float
    private var badgeTextOffset = 0f

    /** The whole box's rect (the top part plus an open bar), device px; [extent] sets its top and bottom. */
    private val box = RectF(0f, 0f, 0f, h)
    private val scratch = RectF()
    private val dst = RectF()

    // ---- the five words' de-duplicated glyph atlas, at the largest word size × NFL_ATLAS_SCALE
    private val atlas: Bitmap
    private val glyphSrc: Array<Rect>
    /** Per kind (ordinal), per character: the glyph index (−1 = space), its advance and ink box at [ATLAS_WORD_PX]. */
    private val glyphOf = Array(WORDS.size) { IntArray(NflScoreFrame.LETTERS) { -1 } }
    private val glyphAdv = Array(WORDS.size) { FloatArray(NflScoreFrame.LETTERS) }
    private val cellLeft: FloatArray
    private val cellW: IntArray
    private val cellTop: Int
    private val cellH: Int
    private val inkMid: Float
    /** Each word's width at [ATLAS_WORD_PX] (device px, tracking included). */
    private val baseWidth = FloatArray(WORDS.size)

    // ---- the sprites (ALPHA_8 at NFL_ATLAS_SCALE, tinted white per draw)
    private val ballPx = spec.runPx * s
    private val ball: Bitmap
    private val posts: Bitmap

    init {
        val b = Rect()
        pointsPaint.getTextBounds("+0", 0, 2, b)
        pointsCap = -(b.top + b.bottom) / 2f
        tallyPaint.getTextBounds("0", 0, 1, b)
        tallyCap = -(b.top + b.bottom) / 2f

        val chars = WORDS.joinToString("").filter { it != ' ' }.toSet().sorted()
        val a = K.NFL_ATLAS_SCALE
        val px = ATLAS_WORD_PX * s
        val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = wordFont; textSize = px * a; color = Color.WHITE }
        val shadow = Paint(wordPaint).apply { color = argb(K.NFL_WORD_SHADOW_ALPHA, 0x000000) }
        val dy = K.STACKED_NFL_WORD_SHADOW_DY * s * a
        val pad = ceil(2f * a).toInt()
        val bounds = Array(chars.size) { Rect() }
        var top = 0
        var bottom = 0
        for ((i, ch) in chars.withIndex()) {
            wordPaint.getTextBounds(ch.toString(), 0, 1, bounds[i])
            top = minOf(top, bounds[i].top)
            bottom = maxOf(bottom, bounds[i].bottom)
        }
        cellTop = top - pad
        cellH = (bottom + ceil(dy).toInt() + pad) - cellTop
        cellW = IntArray(chars.size) { bounds[it].width() + 2 * pad }
        atlas = createBitmap(cellW.sum().coerceAtLeast(1), cellH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ac = Canvas(atlas)
        glyphSrc = Array(chars.size) { Rect() }
        cellLeft = FloatArray(chars.size)
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
        inkMid = (top + bottom) / 2f / a
        val track = K.STACKED_NFL_WORD_TRACK_EM * px * a
        for ((w, word) in WORDS.withIndex()) {
            val n = minOf(word.length, NflScoreFrame.LETTERS)
            var width = 0f
            for (i in 0 until n) {
                glyphOf[w][i] = chars.indexOf(word[i])
                glyphAdv[w][i] = (wordPaint.measureText(word, i, i + 1) + track) / a
                width += glyphAdv[w][i]
            }
            baseWidth[w] = width
        }
        ball = bakeBall()
        posts = bakePosts()
    }

    /** The takeover ball: the mock's 24-unit football outline (stroke 1.6 / 24, no fill), [spec]'s run size. */
    private fun bakeBall(): Bitmap {
        val a = K.NFL_ATLAS_SCALE
        val px = ceil(ballPx * a).toInt().coerceAtLeast(1)
        val bmp = createBitmap(px, px, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.scale(px / NflShapes.BALL_VIEWBOX, px / NflShapes.BALL_VIEWBOX)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = NflShapes.BALL_STROKE
        }
        c.drawPath(NflTabPainter.ballPath(), p)
        return bmp
    }

    /** The goalposts at [spec]'s size (viewBox 60 × 80, stroke 3, round caps). */
    private fun bakePosts(): Bitmap {
        val a = K.NFL_ATLAS_SCALE
        val w = ceil(spec.postsW * s * a).toInt().coerceAtLeast(1)
        val ph = ceil(spec.postsH * s * a).toInt().coerceAtLeast(1)
        val bmp = createBitmap(w, ph, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.scale(w / NflShapes.POSTS_VIEW_W, ph / NflShapes.POSTS_VIEW_H)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeWidth = NflShapes.POSTS_STROKE
        }
        val l = NflShapes.POSTS_LINES
        var i = 0
        while (i < l.size) { c.drawLine(l[i], l[i + 1], l[i + 2], l[i + 3], p); i += 4 }
        return bmp
    }

    // ---- per takeover segment (begin)
    private var kind = NflScoreKind.TOUCHDOWN
    private var badgeSource: Bitmap? = null
    private var badge: Bitmap? = null
    private val badgeDst = RectF()
    private var abbrev = ""
    private val points = CharArray(4)
    private var pointsLen = 0
    private val tally = CharArray(16)
    private var tallyLen = 0
    private var boxW = 0f

    /** The layout fixed by [begin] (device px, x from the top part's left edge, unmirrored). */
    private var pad = 0f
    private var wordPx = 0f
    private var showTally = true
    private var showBadge = true
    private var badgeCx = 0f
    private var wordCx = 0f
    private var blockRight = 0f
    private var blockW = 0f
    private var pointsBaseline = 0f
    private var tallyBaseline = 0f
    /** (U-B1) The posts' placed left edge (`postsX × k`, device px, unmirrored) and their shift off the block (≤ 0). */
    private var postsX0 = 0f
    private var postsShift = 0f
    private val letterOx = FloatArray(NflScoreFrame.LETTERS)
    private val letterDst = Array(NflScoreFrame.LETTERS) { RectF() }

    /**
     * Once per takeover segment (a §5.7 pair begins twice), on the main thread before its first frame: the scorer's
     * [side] and true [colours], the segment's [scores] (one score; the runner passes the pair's segment alone), the
     * [badge] (the logo at the takeover size; null = the disc), both abbreviations, and [boxW], the pinned top part's
     * width in Standard design px (`StackedNflFrame.boxW` at takeover start: 333 in play, 243 in a break), which the
     * §2.5 fit runs against.
     */
    fun begin(side: Side, scores: List<NflScore>, colours: TeamColours, badge: Bitmap?, awayAbbrev: String, homeAbbrev: String, boxW: Float) {
        this.side = side
        this.colours = colours
        this.abbrev = if (side == Side.AWAY) awayAbbrev else homeAbbrev
        this.boxW = boxW * s
        val score = scores.firstOrNull()
        kind = score?.kind ?: NflScoreKind.TOUCHDOWN
        leadPaint.color = colours.accent or OPAQUE
        panelPaint.color = colours.base or OPAQUE
        glowPaint.color = colours.glow or OPAQUE
        streak1Paint.shader = streakShader(streak1W, colours.accent, spec.streak1Alpha)
        streak2Paint.shader = streakShader(streak2W, Color.WHITE, spec.streak2Alpha)
        val pts = if (score == null) "" else "+${score.points}"
        pointsLen = minOf(pts.length, points.size)
        pts.toCharArray(points, 0, 0, pointsLen)
        val t = if (score == null) "" else "${score.away} – ${score.home}"
        tallyLen = minOf(t.length, tally.size)
        t.toCharArray(tally, 0, 0, tallyLen)
        badgeText.textSize = goalBadgeFallbackPx(badgeText.typeface, abbrev, K.STACKED_NFL_BADGE) * s
        val tb = Rect()
        badgeText.getTextBounds("M", 0, 1, tb)
        badgeTextOffset = -(tb.top + tb.bottom) / 2f
        if (badge == null) {
            this.badge = null
            badgeSource = null
        } else if (badge !== badgeSource) {
            // GA N3: a copy with 1 px of transparent padding, so a rotated, filtered edge stays smooth.
            val copy = createBitmap(badge.width + 2, badge.height + 2, Bitmap.Config.ARGB_8888)
            Canvas(copy).drawBitmap(badge, 1f, 1f, null)
            copy.prepareToDraw()
            this.badge = copy
            badgeSource = badge
        }
        this.badge?.let { b ->
            val k = minOf(badgeSlot / (b.width - 2).coerceAtLeast(1), badgeSlot / (b.height - 2).coerceAtLeast(1))
            val w = b.width * k
            val bh = b.height * k
            badgeDst.set(-w / 2f, -bh / 2f, w / 2f, bh / 2f)
        }
        layout()
    }

    /**
     * Section 2.5's fit on the pinned width, by `StackedNflGeometry.fitTakeover` (padding 16.2 while the word fits at its
     * own size; then 8; then the word scaled to fit, floor 28; then the tally dropped; then the badge) and the items'
     * places by `StackedNflGeometry.takeover` (the badge at the padding, the block right-aligned at the far padding, the
     * word centred between: the mock's `flex: 1` column). The block (`"+N"` over the tally) is as wide as "88 – 88" or
     * the actual tally when wider. Unmirrored here; [drawTakeover] reflects about the box.
     */
    private fun layout() {
        val pointsW = pointsPaint.measureText(points, 0, pointsLen)
        val tallyW = maxOf(tallyPaint.measureText(TALLY_SLOT), tallyPaint.measureText(tally, 0, tallyLen))
        val nat = G.wordPx(kind) * s
        val atlasW = baseWidth[kind.ordinal]
        val withTally = maxOf(tallyW, pointsW)
        val fit = G.fitTakeover(boxW, s, nat, { px -> atlasW * (px / s) / ATLAS_WORD_PX }, if (tallyLen > 0) withTally else pointsW, pointsW)
        pad = fit.pad
        wordPx = fit.wordPx / s
        showTally = fit.tally && tallyLen > 0
        showBadge = fit.badge
        blockW = if (showTally) withTally else pointsW
        val wordW = atlasW * wordPx / ATLAS_WORD_PX
        G.takeover(boxW, s, fit, wordW, blockW, false, items)
        badgeCx = items[G.T_BADGE].let { if (it.isNaN()) pad else it } + badgeSlot / 2f
        wordCx = items[G.T_WORD] + wordW / 2f
        blockRight = items[G.T_BLOCK] + blockW
        // U-B1: the posts end one gap before the block (the mock keeps them behind the end of the word); the arc bends with them.
        postsX0 = spec.postsX * s * G.arcK(boxW / s)
        postsShift = G.postsShift(blockRight - blockW, postsX0, spec.postsW * s, s)
        // The block: "+N" (line box 26.1) over the tally (20), 2.7 apart, centred on the panel; caps centred per line.
        if (showTally) {
            val blockH = (K.STACKED_NFL_POINTS_PX + K.STACKED_NFL_BLOCK_GAP + K.STACKED_TALLY_PX) * s
            val top = cy - blockH / 2f
            pointsBaseline = top + K.STACKED_NFL_POINTS_PX * s / 2f + pointsCap
            tallyBaseline = top + (K.STACKED_NFL_POINTS_PX + K.STACKED_NFL_BLOCK_GAP + K.STACKED_TALLY_PX / 2f) * s + tallyCap
        } else {
            pointsBaseline = cy + pointsCap
        }
        // The word's letters at [wordPx]: CSS letter-spacing after every letter; each letter's origin is its cell's centre.
        val w = kind.ordinal
        val q = wordPx / ATLAS_WORD_PX
        val a = K.NFL_ATLAS_SCALE
        val n = minOf(WORDS[w].length, NflScoreFrame.LETTERS)
        var pen = -baseWidth[w] * q / 2f
        for (i in 0 until n) {
            val adv = glyphAdv[w][i] * q
            val ox = pen + adv / 2f
            letterOx[i] = ox
            val gi = glyphOf[w][i]
            if (gi >= 0) {
                val l = pen + cellLeft[gi] / a * q - ox
                val t = (cellTop / a - inkMid) * q
                letterDst[i].set(l, t, l + cellW[gi] / a * q, t + cellH / a * q)
            }
            pen += adv
        }
    }

    /**
     * (Set by [StackedNflPainter.drawFrame] before each [drawTakeover]) The whole box's top and bottom in the top part's
     * coordinates (device px), so the panel's rounded corners fall on the box's corners only. Allocation-free.
     */
    internal fun extent(top: Float, bottom: Float) {
        box.top = top
        box.bottom = bottom
    }

    /** The streak band (GA S4): `skewX(−24deg)`, the gradient perpendicular to its edges, soft 1 px stops. */
    private fun streakShader(w: Float, rgb: Int, alpha: Float): Shader {
        val rad = Math.toRadians(-K.STACKED_NFL_STREAK_SKEW_DEG.toDouble())
        val nx = cos(rad).toFloat()
        val ny = sin(rad).toFloat()
        val len = w * nx
        val eps = (1f / len).coerceAtMost(0.49f)
        val c = argb(alpha, rgb)
        val t = rgb and 0xFFFFFF
        return LinearGradient(0f, cy, len * nx, cy + len * ny, intArrayOf(t, c, c, t), floatArrayOf(0f, eps, 1f - eps, 1f), Shader.TileMode.CLAMP)
    }

    /**
     * The GA glow masks (low and peak) for a [w] × [h] (Standard design px) pinned box — the top part plus the open bar —
     * with the box cut out, off the main thread; null for an empty box.
     */
    fun bakeGlow(w: Float, h: Float): GlowMasks? {
        if (!(w > 0f) || !(h > 0f)) return null
        val bw = w * s
        val bh = h * s
        val lowSpread = K.GOAL_GLOW_LOW_SPREAD * (if (large) K.SCORE_LARGE_SCALE else 1f)
        val lowSigma = K.GOAL_GLOW_LOW_SIGMA * (if (large) K.SCORE_LARGE_SCALE else 1f)
        val peakSpread = if (large) K.GOAL_GLOW_LARGE_PEAK_SPREAD else K.GOAL_GLOW_PEAK_SPREAD
        val peakSigma = if (large) K.GOAL_GLOW_LARGE_PEAK_SIGMA else K.GOAL_GLOW_PEAK_SIGMA
        val lowMargin = (lowSpread + 3f * lowSigma) * d
        val peakMargin = (peakSpread + 3f * peakSigma) * d
        return GlowMasks(
            bakeMask(bw, bh, lowSpread * d, lowSigma * d, lowMargin),
            bakeMask(bw, bh, peakSpread * d, peakSigma * d, peakMargin),
            lowMargin, peakMargin,
        )
    }

    private fun bakeMask(w: Float, h: Float, spread: Float, sigma: Float, margin: Float): Bitmap {
        val bmp = createBitmap(ceil(w + 2 * margin).toInt(), ceil(h + 2 * margin).toInt(), Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.translate(margin, margin)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(-spread, -spread, w + spread, h + spread, radius + spread, radius + spread, p)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        c.drawRoundRect(0f, 0f, w, h, radius, radius, cut)
        return bmp
    }

    /** The glow of frame [g] with the baked [glow] masks, the box's top-left at ([x], [y]) device px. */
    fun drawGlow(c: Canvas, g: NflScoreFrame, glow: GlowMasks?, x: Float, y: Float) {
        if (colours == null || glow == null) return
        val lo = alpha255(g.glowLowAlpha)
        if (lo > 0) { glowPaint.alpha = lo; c.drawBitmap(glow.low, x - glow.lowMargin, y - glow.lowMargin, glowPaint) }
        val hi = alpha255(g.glowPeakAlpha)
        if (hi > 0) { glowPaint.alpha = hi; c.drawBitmap(glow.peak, x - glow.peakMargin, y - glow.peakMargin, glowPaint) }
    }

    /** The takeover of frame [g] over the top part (origin as `StackedNflDrawArgs`), [mirrored] for a right-hand corner. */
    fun drawTakeover(c: Canvas, g: NflScoreFrame, mirrored: Boolean) {
        if (colours == null || boxW <= 0f || g.done) return
        val ox = if (mirrored) rowsW - boxW else 0f
        c.withTranslation(ox, 0f) { panel(this, g, mirrored) }
    }

    private fun panel(c: Canvas, g: NflScoreFrame, mirrored: Boolean) {
        box.left = 0f
        box.right = boxW
        if (g.leadTo > g.leadFrom) {
            val l = if (mirrored) (1f - g.leadTo) * boxW else g.leadFrom * boxW
            val r = if (mirrored) (1f - g.leadFrom) * boxW else g.leadTo * boxW
            c.withClip(l, 0f, r, h) { drawRoundRect(box, radius, radius, leadPaint) }
        }
        if (g.panelTo <= g.panelFrom) return
        val pl = if (mirrored) (1f - g.panelTo) * boxW else g.panelFrom * boxW
        val pr = if (mirrored) (1f - g.panelFrom) * boxW else g.panelTo * boxW
        c.withClip(pl, 0f, pr, h) {
            drawRoundRect(box, radius, radius, panelPaint)
            if (g.streak1On) streak(this, if (mirrored) boxW - g.streak1X * s - streak1W else g.streak1X * s, streak1Paint)
            if (g.streak2On) streak(this, if (mirrored) boxW - g.streak2X * s - streak2W else g.streak2X * s, streak2Paint)
            // The arc and the posts are on the 333 box's x's (×k); the run crosses whatever box there is.
            val k = G.arcK(boxW / s)
            if (g.postsOn) {
                val pa = alpha255(g.postsAlpha)
                if (pa > 0) {
                    spritePaint.color = (pa shl 24) or 0xFFFFFF
                    postsRect(g, mirrored, dst)
                    drawBitmap(posts, null, dst, spritePaint)
                }
            }
            if (g.arcOn) drawBall(this, g, arcX(g, k), mirrored)
            else if (g.runOn) drawBall(this, g, g.ballX * s, mirrored)
            val fa = alpha255(g.flashAlpha)
            if (fa > 0) { flashPaint.alpha = fa; drawRoundRect(box, radius, radius, flashPaint) }
            if (showBadge) drawBadge(this, g, mirrored)
            drawWord(this, g, if (mirrored) boxW - wordCx else wordCx)
            drawBlock(this, g, mirrored)
        }
    }

    /** (U-B1) The posts' drawn rect for frame [g] (box-local device px, [mirrored] reflected), shifted off the block. */
    internal fun postsRect(g: NflScoreFrame, mirrored: Boolean, out: RectF): RectF {
        val pw = spec.postsW * s
        val x0 = postsX0 + postsShift
        val x = if (mirrored) boxW - x0 - pw else x0
        val y = (spec.postsY + g.postsDy) * s
        return out.apply { set(x, y, x + pw, y + spec.postsH * s) }
    }

    /** (U-B1) The arc ball's sprite left (device px, unmirrored): the frame's x × k, bent by the posts' shift. */
    internal fun arcX(g: NflScoreFrame, k: Float = G.arcK(boxW / s)): Float =
        G.bendArc(g.ballX * s * k, postsShift, spec.arcP0X * s * k, spec.arcP2X * s * k)

    /**
     * (U-B1) The "+N" / tally block's drawn extent for frame [g] across x (box-local device px, [mirrored] reflected),
     * with its slide and scale; [out]'s top / bottom are the panel's.
     */
    internal fun blockDrawnRect(g: NflScoreFrame, mirrored: Boolean, out: RectF): RectF {
        val right = if (mirrored) boxW - (blockRight - blockW) else blockRight
        val dx = (if (mirrored) -g.blockDx else g.blockDx) * s
        val bcx = right - blockW / 2f
        val half = blockW / 2f * g.blockScale
        return out.apply { set(bcx - half + dx, 0f, bcx + half + dx, h) }
    }

    /** The band at left edge [dx]: translate, then the box offset back by −dx, so the shader moves without a matrix. */
    private fun streak(c: Canvas, dx: Float, p: Paint) {
        scratch.set(-dx, box.top, boxW - dx, box.bottom)
        c.withTranslation(dx, 0f) { drawRoundRect(scratch, radius, radius, p) }
    }

    /** The ball sprite with its top-left at ([x0], ballY) unmirrored; mirrored x' = boxW − x − size, rotation negated. */
    private fun drawBall(c: Canvas, g: NflScoreFrame, x0: Float, mirrored: Boolean) {
        val a = alpha255(g.ballAlpha)
        if (a <= 0) return
        spritePaint.color = (a shl 24) or 0xFFFFFF
        val half = ballPx / 2f
        val x = if (mirrored) boxW - x0 - ballPx else x0
        c.withTranslation(x + half, g.ballY * s + half) {
            rotate(if (mirrored) -g.ballRotDeg else g.ballRotDeg)
            scale(g.ballScale, g.ballScale)
            dst.set(-half, -half, half, half)
            drawBitmap(ball, null, dst, spritePaint)
        }
    }

    private fun drawBadge(c: Canvas, g: NflScoreFrame, mirrored: Boolean) {
        val a = alpha255(g.badgeAlpha)
        if (a <= 0) return
        val cx = if (mirrored) boxW - badgeCx - g.badgeDx * s else badgeCx + g.badgeDx * s
        c.withTranslation(cx, cy) {
            rotate(if (mirrored) -g.badgeRotDeg else g.badgeRotDeg)
            scale(g.badgeScale, g.badgeScale)
            val b = badge
            if (b != null) {
                badgeShadow.alpha = alpha255(g.badgeAlpha * K.STACKED_TEXT_SHADOW_ALPHA)
                val dy = K.STACKED_NFL_BADGE_SHADOW_DY * s
                dst.set(badgeDst.left, badgeDst.top + dy, badgeDst.right, badgeDst.bottom + dy)
                drawBitmap(b, null, dst, badgeShadow)
                badgePaint.alpha = a
                drawBitmap(b, null, badgeDst, badgePaint)
            } else {
                // The `LogoDisc` look at the badge's size, with the code.
                val r = badgeSlot / 2f
                discFill.color = argb(DISC_FILL_ALPHA * g.badgeAlpha, 0xFFFFFF)
                drawCircle(0f, 0f, r, discFill)
                discStroke.color = argb(DISC_STROKE_ALPHA * g.badgeAlpha, 0xFFFFFF)
                drawCircle(0f, 0f, r - discStroke.strokeWidth / 2f, discStroke)
                badgeText.alpha = a
                drawText(abbrev, 0f, badgeTextOffset, badgeText)
            }
        }
    }

    /** The word centred at ([cx], the panel's middle): per-letter rise, the pulse about the word's centre. */
    private fun drawWord(c: Canvas, g: NflScoreFrame, cx: Float) {
        val w = kind.ordinal
        val n = minOf(g.letterCount, WORDS[w].length, NflScoreFrame.LETTERS)
        c.withScale(g.wordPulse, g.wordPulse, cx, cy) {
            for (i in 0 until n) {
                val gi = glyphOf[w][i]
                if (gi < 0) continue
                val a = alpha255(g.letterAlpha[i])
                if (a <= 0) continue
                withTranslation(cx + letterOx[i], cy + g.letterDy[i] * s) {
                    scale(g.letterScale[i], g.letterScale[i])
                    letterPaint.alpha = a
                    drawBitmap(atlas, glyphSrc[gi], letterDst[i], letterPaint)
                }
            }
        }
    }

    /** "+N" over the tally, right-aligned at the block's right (mirrored: the block's span reflected), sliding in. */
    private fun drawBlock(c: Canvas, g: NflScoreFrame, mirrored: Boolean) {
        val a = alpha255(g.blockAlpha)
        if (a <= 0) return
        val right = if (mirrored) boxW - (blockRight - blockW) else blockRight
        val dx = (if (mirrored) -g.blockDx else g.blockDx) * s
        val bcx = right - blockW / 2f
        c.withTranslation(dx, 0f) {
            withScale(g.blockScale, g.blockScale, bcx, cy) {
                pointsPaint.alpha = a
                if (pointsLen > 0) drawText(points, 0, pointsLen, right, pointsBaseline, pointsPaint)
                if (showTally && tallyLen > 0) {
                    tallyPaint.alpha = a
                    drawText(tally, 0, tallyLen, right, tallyBaseline, tallyPaint)
                }
            }
        }
    }

    /** Hands the atlas, sprites and [glow] to the GPU ahead of the first takeover; main thread. */
    fun prewarm(glow: GlowMasks?) {
        atlas.prepareToDraw()
        ball.prepareToDraw()
        posts.prepareToDraw()
        glow?.low?.prepareToDraw()
        glow?.peak?.prepareToDraw()
    }

    // ---- test seams (read after [begin]; device px from the top part's left, unmirrored)
    internal val wordPxUsed: Float get() = wordPx
    internal val padUsed: Float get() = pad
    internal val tallyShown: Boolean get() = showTally
    internal val badgeShown: Boolean get() = showBadge
    internal val badgeLeft: Float get() = badgeCx - badgeSlot / 2f
    internal val badgeRight: Float get() = badgeCx + badgeSlot / 2f
    internal val wordLeft: Float get() = wordCx - baseWidth[kind.ordinal] * wordPx / ATLAS_WORD_PX / 2f
    internal val wordRight: Float get() = wordCx + baseWidth[kind.ordinal] * wordPx / ATLAS_WORD_PX / 2f
    internal val blockLeft: Float get() = blockRight - blockW
    internal val blockRightEdge: Float get() = blockRight
    internal val tallyText: String get() = String(tally, 0, tallyLen)
    internal val pointsText: String get() = String(points, 0, pointsLen)
    internal val atlasBitmap: Bitmap get() = atlas
    internal val ballSprite: Bitmap get() = ball
    internal val postsSprite: Bitmap get() = posts
    internal val postsShiftUsed: Float get() = postsShift
    internal val boxWidth: Float get() = boxW

    companion object {
        /** The five words by [NflScoreKind] ordinal (`sports_nfl_*`, not translated). */
        val WORDS = NflScorePainter.WORDS
        /** The atlas is drawn at the largest word size (TOUCHDOWN / SAFETY, `StackedNflGeometry.wordPx`). */
        private const val ATLAS_WORD_PX = K.STACKED_NFL_WORD_TD_PX
        /** The tally's slot (§2.5: "88 – 88" 67.7 at 20). */
        private const val TALLY_SLOT = "88 – 88"
        /** The `LogoDisc` alphas (white .08 fill, .12 stroke). */
        private const val DISC_FILL_ALPHA = 0.08f
        private const val DISC_STROKE_ALPHA = 0.12f
        private const val OPAQUE = 0xFF shl 24

        private fun alpha255(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        private fun argb(a: Float, rgb: Int): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (rgb and 0xFFFFFF)
    }
}
