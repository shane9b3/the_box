// Contract (NFL commit 0, docs/design/m6-nfl.md §4.3, §4.5, §5.3, §11): the signatures are fixed for WP-3 (the body)
// and WP-4 (the caller, `NflTabRun`); changes go through the lead.
package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflShapes
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TabLead
import app.thebox.core.sports.TabTail
import app.thebox.core.sports.TeamColours
import app.thebox.ui.R
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * (NFL §4.3, §5.4) What the possession tab says, owned by the run (as [app.thebox.core.sports.PpContent]): captured when a
 * transition starts, so a CLOSE slides back the words it opened with and a pin holds its own. Mutable and preallocated,
 * so the runner and draw never allocate.
 *
 * Plain: "[side] BALL | OWN 35" ([lead], [tail]; a null [tail] draws the lead alone, centred). Pinned ([score] non-null):
 * the score label "TOUCHDOWN | KC +6" ([score], [points]) in [side]'s (the scorer's) colours.
 */
internal class NflTabContent(
    var side: Side = Side.AWAY,
    var lead: TabLead = TabLead.BALL,
    var tail: TabTail? = null,
    var score: NflScoreKind? = null,
    var points: Int = 0,
) {
    fun copyFrom(o: NflTabContent) {
        side = o.side
        lead = o.lead
        tail = o.tail
        score = o.score
        points = o.points
    }

    override fun toString(): String = "NflTabContent($side, $lead, $tail, $score, $points)"
}

/**
 * (NFL §4.3, §4.5, §5.3; WP-3) The possession tab in plain `android.graphics`: PP's geometry and track with the NFL inset
 * (37 / 46), the team's base gradient (`mixBlack .70 / .28 / .10`), the RED ZONE tail half, and the pinned pulse as a
 * second `PorterDuff.Mode.ADD` pass of the same gradient. [s] is device px per design px with Large folded in, [d]
 * without it (as [PowerPlayPainter]).
 *
 * - [bakeSize] once per size (off the main thread): the shadow, the ball sprite (the digit table is measured here, in
 *   the constructor).
 * - [buildLabels] off the main thread, [publishLabels] on the drawing thread; [labelsFor] says whether they match.
 * - [begin] when a side's colours are needed (a side change, the pin's scorer).
 * - [draw] per frame, allocation-free; `clipRect` only (no `clipPath`, no `saveLayer`).
 *
 * The block (ball icon, lead, divider, tail) is centred in the tab; the lead is Instrument Sans Bold, the tail SemiBold,
 * 20 px with .05 em tracking, white over a hard black .35 shadow 1 px down. Every content fits the 410 px room at 20 px
 * (§4.3 "Fit"), so there is no step-down.
 */
internal class NflTabPainter(
    val s: Float,
    val d: Float,
    val pillW: Float,
    val pillH: Float,
    private val sansBold: Typeface?,
    private val sansSemi: Typeface?,
) {
    /** Large folds 1.25 into [s] but not [d]. */
    private val large = s > d * 1.1f

    // ---- geometry (§4.3), device px; tab-local = the tab's top-left at its rest offset
    /** The NFL side inset (37 / 46 design px, `NFL_TAB_INSET*`), the same constants WP-1's `tabInset(size, NFL)` reads. */
    private val tabL = (if (large) K.NFL_TAB_INSET_LARGE else K.NFL_TAB_INSET) * d
    private val tabW = pillW - 2 * tabL
    private val hidden = K.PP_TAB_HIDDEN * s
    private val depth = K.PP_TAB_DEPTH * s
    private val tabH = hidden + depth
    private val tabR = K.PP_TAB_RADIUS * s
    private val travel = K.PP_TRAVEL * s
    private val layerM = (if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN) * d
    private val edge = pillH.roundToInt().toFloat()

    private val shadowDy = (if (large) K.PP_SHADOW_DY_LARGE else K.PP_SHADOW_DY) * d
    private val shadowSigma = K.PP_SHADOW_SIGMA * d
    private val shadowMargin = ceil(3f * shadowSigma + 1f)

    private val gap = K.PP_GAP * s
    private val iconGap = K.NFL_TAB_ICON_GAP * s
    private val icon = K.NFL_TAB_ICON * s
    private val dividerW = (K.PP_DIVIDER_W * s).coerceAtLeast(1f)
    private val dividerH = K.PP_DIVIDER_H * s
    private val textShadowDy = K.PP_TEXT_SHADOW_DY * s
    private val textCentre = K.PP_TEXT_CENTRE * s

    // ---- paints (one each; draw re-colours them, never allocates)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** §5.3: the pulse is the same gradient added once more at `NFL_TAB_PULSE_GAIN` × p, = `brightness(1 + .45 p)`. */
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sansSemi; textSize = K.PP_TEXT_PX * s; color = Color.WHITE; textAlign = Paint.Align.LEFT
        letterSpacing = K.NFL_TAB_TRACK_EM
    }
    private val dst = RectF()

    // ---- the yard digits (tabular: a new yard line never moves a digit)
    private val digitAdvance = FloatArray(10)
    private val cell: Float
    private val digitCap: Float
    private val digitBuf = CharArray(2)

    init {
        var c = 0f
        for (i in 0..9) {
            digitAdvance[i] = digits.measureText(DIGITS, i, i + 1)
            c = maxOf(c, digitAdvance[i])
        }
        cell = c
        val b = Rect()
        digits.getTextBounds("0", 0, 1, b)
        digitCap = -(b.top + b.bottom) / 2f
    }

    // ------------------------------------------------------------------ bakes

    @Volatile private var shadowMask: Bitmap? = null
    @Volatile private var ballSprite: Bitmap? = null

    /** The per-size bakes, off the main thread: the tab shadow (not in low-end mode) and the ball icon sprite. */
    fun bakeSize(withShadow: Boolean = true) {
        ballSprite = bakeBall()
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

    /** The mock's football icon (20 px, stroke 1.4), `ALPHA_8` at `PP_LABEL_ATLAS_SCALE`. */
    private fun bakeBall(): Bitmap {
        val a = K.PP_LABEL_ATLAS_SCALE
        val px = ceil(icon * a).toInt().coerceAtLeast(1)
        val bmp = createBitmap(px, px, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val unit = px / NflShapes.BALL_VIEWBOX
        c.scale(unit, unit)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = K.NFL_TAB_ICON_STROKE * s * a / unit
        }
        c.drawPath(ballPath(), p)
        return bmp
    }

    // ------------------------------------------------------------------ labels

    /** The baked label masks for one pair of teams (§4.3: per side "{ABBR} BALL" / "{ABBR} KICKOFF" / "{ABBR} +N", shared words). */
    class Labels(val away: String, val home: String) {
        internal val masks = arrayOfNulls<Bitmap>(LABELS)
        /** The label's advance (its layout width), device px. */
        internal val w = FloatArray(LABELS)
        /** The mask's destination relative to (pen x, the text's centre line), device px. */
        internal val left = FloatArray(LABELS)
        internal val top = FloatArray(LABELS)
        internal val dw = FloatArray(LABELS)
        internal val dh = FloatArray(LABELS)
        /** The gap between "OWN" / "OPP" and the yard digits (the format's own space), device px. */
        internal var zoneGap = 0f
    }

    private var labels: Labels? = null

    /** Builds and publishes on the calling thread (tests and single-threaded callers). */
    fun bakeLabels(away: String, home: String, res: Resources) = publishLabels(buildLabels(away, home, res))

    fun buildLabels(away: String, home: String, res: Resources): Labels {
        val texts = arrayOfNulls<String>(LABELS)
        texts[BALL] = res.getString(R.string.sports_tab_ball, away)
        texts[BALL + 1] = res.getString(R.string.sports_tab_ball, home)
        texts[KICK] = res.getString(R.string.sports_tab_kickoff, away)
        texts[KICK + 1] = res.getString(R.string.sports_tab_kickoff, home)
        for (k in NflScoreKind.values()) texts[WORD + k.ordinal] = res.getString(wordRes(k))
        texts[XP] = res.getString(R.string.sports_tab_xp)
        // "OWN %1$d": the words before the number are baked; the number is drawn from the digit table.
        val own = res.getString(R.string.sports_tab_own, 0)
        val opp = res.getString(R.string.sports_tab_opp, 0)
        texts[OWN] = own.substringBefore('0').trimEnd()
        texts[OPP] = opp.substringBefore('0').trimEnd()
        texts[MID] = res.getString(R.string.sports_tab_mid)
        texts[RZ] = res.getString(R.string.sports_tab_red_zone)
        for (side in 0..1) for (n in POINTS.indices) {
            texts[PTS + side * POINTS.size + n] = res.getString(R.string.sports_nfl_points, if (side == 0) away else home, POINTS[n])
        }
        val l = Labels(away, home)
        val a = K.PP_LABEL_ATLAS_SCALE
        val fm = Paint.FontMetrics()
        val cap = Rect()
        for (i in 0 until LABELS) {
            val text = texts[i] ?: continue
            val bold = i < XP
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (bold) sansBold else sansSemi; textSize = K.PP_TEXT_PX * s * a
                letterSpacing = K.NFL_TAB_TRACK_EM; color = Color.WHITE
            }
            l.w[i] = paint.measureText(text) / a
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
        val semi = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansSemi; textSize = K.PP_TEXT_PX * s; letterSpacing = K.NFL_TAB_TRACK_EM }
        val between = own.substringBefore('0').length - (texts[OWN]?.length ?: 0)
        l.zoneGap = if (between > 0) semi.measureText(" ") * between else 0f
        return l
    }

    fun publishLabels(l: Labels) { labels = l }

    fun labelsFor(away: String, home: String): Boolean = labels.let { it != null && it.away == away && it.home == home }

    // ------------------------------------------------------------------ colours

    private val tabDown = arrayOfNulls<Shader>(2)
    private val tabUp = arrayOfNulls<Shader>(2)
    private val redDown: Shader = gradient(K.NFL_RED_ZONE, false)
    private val redUp: Shader = gradient(K.NFL_RED_ZONE, true)

    /** The gradient for [side] from [colours] (`TeamColours.tab`, S1). */
    fun begin(side: Side, colours: TeamColours) {
        val i = index(side)
        tabDown[i] = gradient(colours.tab, false)
        tabUp[i] = gradient(colours.tab, true)
    }

    /** PP §6.1's vertical gradient, dark at the pill side: `mixBlack(.70 / .28 / .10)`; [up] reverses it. */
    private fun gradient(rgb: Int, up: Boolean): Shader {
        val n = K.PP_GRADIENT_STOPS.size
        val stops = FloatArray(n) { K.PP_GRADIENT_STOPS[it] }
        val dark = IntArray(n) { mixBlack(rgb, K.PP_GRADIENT_BLACK[it]) }
        if (!up) return LinearGradient(0f, 0f, 0f, tabH, dark, stops, Shader.TileMode.CLAMP)
        val rev = IntArray(n) { dark[n - 1 - it] }
        val revStops = FloatArray(n) { 1f - stops[n - 1 - it] }
        return LinearGradient(0f, 0f, 0f, tabH, rev, revStops, Shader.TileMode.CLAMP)
    }

    // ------------------------------------------------------------------ draw

    /**
     * One frame of the tab under its outside clip: [p] is the PP TAB track ([PpFrame.shineOn] and [PpFrame.labelScale]
     * are ignored), [content] the words ([NflTabContent.side]'s colours), [up] mirrors the tab above the pill (bottom
     * corners), [redZone] the RED ZONE layer's alpha 0..1 (`rzDriver`), [pulse] the pinned pulse 0..1
     * ([app.thebox.core.sports.NflScoreFrame.tabPulse]).
     *
     * RED ZONE (§4.5): [redZone] alone sets the red half's alpha and the words "RED ZONE"'s; the content's own tail is
     * drawn at 1 − [redZone] (a [TabTail.RedZone] tail draws no words of its own), so a cross-fade passes the tail it
     * fades from or to: in, the old field ("OPP 12") or the new `RedZone`; out, the new field ("OPP 27"). The block's
     * width follows the cross-fade, so the lead slides smoothly. Red is never drawn while pinned ([NflTabContent.score]),
     * and [pulse] is drawn only while pinned.
     */
    fun draw(c: Canvas, p: PpFrame, content: NflTabContent?, up: Boolean, redZone: Float, pulse: Float) {
        if (p.tabClosed || content == null) return
        val grad = (if (up) tabUp else tabDown)[index(content.side)] ?: return
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
        val pinned = content.score != null
        val pa = alpha255(K.NFL_TAB_PULSE_GAIN * pulse)
        if (pinned && pa > 0) {
            pulsePaint.shader = grad
            pulsePaint.alpha = pa
            c.drawRoundRect(0f, 0f, tabW, tabH, tabR, tabR, pulsePaint)
        }
        val l = labels
        if (l != null) drawBlock(c, l, content, up, p, if (pinned || content.tail == null) 0f else redZone.coerceIn(0f, 1f))
        c.restore()
    }

    /** The text block, tab-local: icon, lead, and (with a tail) the divider, the tail and the red half. */
    private fun drawBlock(c: Canvas, l: Labels, content: NflTabContent, up: Boolean, p: PpFrame, rz: Float) {
        val si = index(content.side)
        val score = content.score
        val lead = if (score != null) WORD + score.ordinal else (if (content.lead == TabLead.BALL) BALL else KICK) + si
        val tail = content.tail
        // The tail's own label (−1 = none) and its width.
        var tailMask = -1
        var yard = -1
        if (score != null) {
            val n = POINTS.indexOf(content.points)
            if (n >= 0) tailMask = PTS + si * POINTS.size + n
        } else {
            when (tail) {
                is TabTail.Field -> when (tail.zone) {
                    FieldZone.MIDFIELD -> tailMask = MID
                    FieldZone.OWN -> { tailMask = OWN; yard = tail.yard.coerceIn(0, 99) }
                    FieldZone.OPP -> { tailMask = OPP; yard = tail.yard.coerceIn(0, 99) }
                }
                TabTail.ExtraPoint -> tailMask = XP
                TabTail.RedZone -> tailMask = RZ
                null -> Unit
            }
        }
        val ownTail = tailMask >= 0 && tailMask != RZ
        val hasTail = tailMask >= 0
        var ownW = if (tailMask >= 0) l.w[tailMask] else 0f
        if (yard >= 0) ownW += l.zoneGap + (if (yard >= 10) 2 else 1) * cell
        val tailW = if (score == null && hasTail) ownW + (l.w[RZ] - ownW) * rz else ownW
        val leadW = icon + iconGap + l.w[lead]
        val blockW = if (hasTail) leadW + 2 * gap + dividerW + tailW else leadW
        val x0 = (tabW - blockW) / 2f
        val dividerX = x0 + leadW + gap

        // §4.5: the red half, the tab's own round rect under one clipRect from the divider.
        if (rz > 0f && hasTail) {
            c.save()
            c.clipRect(dividerX, 0f, tabW, tabH)
            redPaint.shader = if (up) redUp else redDown
            redPaint.alpha = alpha255(rz)
            c.drawRoundRect(0f, 0f, tabW, tabH, tabR, tabR, redPaint)
            c.restore()
        }

        val ta = p.textAlpha.coerceIn(0f, 1f)
        if (ta <= 0f) return
        val cy = if (up) depth - textCentre - p.textDy * s else hidden + textCentre + p.textDy * s
        val ball = ballSprite
        if (ball != null) {
            dst.set(x0, cy - icon / 2f, x0 + icon, cy + icon / 2f)
            drawShadowed(c, ball, ta)
        }
        mask(c, l, lead, x0 + icon + iconGap, cy, ta)
        if (!hasTail) return
        fill.color = argb(ta * K.PP_DIVIDER_ALPHA, 0xFFFFFF)
        c.drawRect(dividerX, cy - dividerH / 2f, dividerX + dividerW, cy + dividerH / 2f, fill)
        val tx = dividerX + dividerW + gap
        val own = ta * (1f - rz)
        if (ownTail && own > 0f) {
            mask(c, l, tailMask, tx, cy, own)
            if (yard >= 0) drawYard(c, yard, tx + l.w[tailMask] + l.zoneGap, cy, own)
        }
        if (rz > 0f) mask(c, l, RZ, tx, cy, ta * rz)
    }

    private fun mask(c: Canvas, l: Labels, i: Int, x: Float, cy: Float, alpha: Float) {
        val m = l.masks[i] ?: return
        dst.set(x + l.left[i], cy + l.top[i], x + l.left[i] + l.dw[i], cy + l.top[i] + l.dh[i])
        drawShadowed(c, m, alpha)
    }

    /** [bmp] into [dst]: black .35 at the hard offset, then white. */
    private fun drawShadowed(c: Canvas, bmp: Bitmap, alpha: Float) {
        maskPaint.color = argb(alpha * K.PP_TEXT_SHADOW_ALPHA, 0x000000)
        dst.offset(0f, textShadowDy)
        c.drawBitmap(bmp, null, dst, maskPaint)
        dst.offset(0f, -textShadowDy)
        maskPaint.color = argb(alpha, 0xFFFFFF)
        c.drawBitmap(bmp, null, dst, maskPaint)
    }

    private fun drawYard(c: Canvas, yard: Int, left: Float, cy: Float, alpha: Float) {
        var n = 0
        if (yard >= 10) digitBuf[n++] = '0' + yard / 10
        digitBuf[n++] = '0' + yard % 10
        for (pass in 0..1) {
            digits.color = if (pass == 0) argb(alpha * K.PP_TEXT_SHADOW_ALPHA, 0x000000) else argb(alpha, 0xFFFFFF)
            val y = cy + digitCap + if (pass == 0) textShadowDy else 0f
            var x = left
            for (i in 0 until n) {
                c.drawText(digitBuf, i, 1, x + (cell - digitAdvance[digitBuf[i] - '0']) / 2f, y, digits)
                x += cell
            }
        }
    }

    // ------------------------------------------------------------------ test seams

    internal val labelsReady: Boolean get() = labels != null
    internal val shadowBaked: Boolean get() = shadowMask != null
    /** The tab's rest rect in pill coordinates, below ([up] false) or above the pill. */
    internal fun tabRest(up: Boolean, out: RectF): RectF =
        if (up) out.apply { set(tabL, -depth, tabL + tabW, hidden) } else out.apply { set(tabL, pillH - hidden, tabL + tabW, pillH + depth) }
    internal val clipEdge: Float get() = edge
    /** The text block's content width for a [content] at rest (no cross-fade), device px; null before the labels. */
    internal fun blockWidth(content: NflTabContent): Float? {
        val l = labels ?: return null
        val score = content.score
        val si = index(content.side)
        val lead = if (score != null) WORD + score.ordinal else (if (content.lead == TabLead.BALL) BALL else KICK) + si
        val tw = when {
            score != null -> POINTS.indexOf(content.points).let { if (it >= 0) l.w[PTS + si * POINTS.size + it] else -1f }
            else -> when (val t = content.tail) {
                is TabTail.Field -> if (t.zone == FieldZone.MIDFIELD) l.w[MID] else l.w[if (t.zone == FieldZone.OWN) OWN else OPP] + l.zoneGap + (if (t.yard >= 10) 2 else 1) * cell
                TabTail.ExtraPoint -> l.w[XP]
                TabTail.RedZone -> l.w[RZ]
                null -> -1f
            }
        }
        val leadW = icon + iconGap + l.w[lead]
        return if (tw < 0f) leadW else leadW + 2 * gap + dividerW + tw
    }
    /** The room for the block (the tab less a far radius each side), device px. */
    internal val room: Float get() = tabW - 2 * tabR

    companion object {
        // Label indices: Bold leads, then SemiBold tails.
        private const val BALL = 0 // + side
        private const val KICK = 2 // + side
        private const val WORD = 4 // + NflScoreKind.ordinal (5)
        private const val XP = 9
        private const val OWN = 10
        private const val OPP = 11
        private const val MID = 12
        private const val RZ = 13
        private const val PTS = 14 // + side × 4 + the index in POINTS
        private val POINTS = intArrayOf(1, 2, 3, 6)
        private const val LABELS = 22
        private const val DIGITS = "0123456789"

        fun wordRes(k: NflScoreKind): Int = when (k) {
            NflScoreKind.TOUCHDOWN -> R.string.sports_nfl_td
            NflScoreKind.EXTRA_POINT -> R.string.sports_nfl_xp
            NflScoreKind.FIELD_GOAL -> R.string.sports_nfl_fg
            NflScoreKind.SAFETY -> R.string.sports_nfl_safety
            NflScoreKind.TWO_POINT -> R.string.sports_nfl_2pt
        }

        /** The mock's football outline and laces as a [Path] in viewBox units (bake only). */
        fun ballPath(): Path = Path().apply {
            moveTo(NflShapes.BALL_START[0], NflShapes.BALL_START[1])
            val q = NflShapes.BALL_CUBICS
            var i = 0
            while (i < q.size) { cubicTo(q[i], q[i + 1], q[i + 2], q[i + 3], q[i + 4], q[i + 5]); i += 6 }
            close()
            val ln = NflShapes.BALL_LINES
            i = 0
            while (i < ln.size) { moveTo(ln[i], ln[i + 1]); lineTo(ln[i + 2], ln[i + 3]); i += 4 }
        }

        private fun index(side: Side) = if (side == Side.AWAY) 0 else 1
        private fun blurRadius(sigma: Float) = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
        private fun alpha255(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        private fun argb(a: Float, rgb: Int): Int = (alpha255(a) shl 24) or (rgb and 0xFFFFFF)

        /** [rgb] toward black by [k], opaque, each channel rounded half to even (as `NhlTeamColours`). */
        internal fun mixBlack(rgb: Int, k: Float): Int {
            fun ch(shift: Int) = round(((rgb shr shift) and 0xFF) * (1.0 - k)).toInt() shl shift
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}
