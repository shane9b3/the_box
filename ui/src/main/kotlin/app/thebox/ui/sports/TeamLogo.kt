package app.thebox.ui.sports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import app.thebox.core.sports.CodeFit
import app.thebox.core.sports.SportsConstants
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import coil3.transform.Transformation

/** Fetches one team logo already trimmed and fitted to a [w] × [h] device-px slot; null = failed. */
typealias LogoFetch = suspend (url: String, w: Int, h: Int) -> Bitmap?

/**
 * (docs/design/m6-score-bug.md §3) The Coil path: the SVG is rasterised at 4x the slot with the memory cache off (the
 * [FollowLogos] holder is the cache), then [LogoTrim] crops and fits it. Coil's disk cache keeps the SVG source.
 */
fun coilLogoFetch(loader: ImageLoader, context: Context, chlLeague: Boolean = false): LogoFetch = coilLogoFetch(loader, context, chlLeague, null)

/** [overrideOrigin] fixes the debug override for tests (null = read it from the app, once); "" = none. */
internal fun coilLogoFetch(loader: ImageLoader, context: Context, chlLeague: Boolean, fixedOverride: String?): LogoFetch {
    var overrideOrigin: String? = fixedOverride
    return fetch@{ url, w, h ->
    // (CHL §3.5, S9; review L1, L2) A CHL logo (the caller knows the league; the bug's hook sees only the URL, so a URL on the
    // CHL logo host or the fake panel's `/leaguestat/` folder counts too) must be exactly a CHL logo URL, else no request and
    // no logo, and its request gets the no-alpha check ahead of the trim. NHL and NFL requests are built exactly as before.
    // The override (a debug build with the data source's override file) is read once, off the caller's thread; "" = none.
    val resolved = overrideOrigin ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ChlLogoCheck.overrideOrigin(context) ?: "" }.also { overrideOrigin = it }
    val override = resolved.ifEmpty { null }
    val chl = chlLeague || ChlLogoCheck.isChlHost(url, override)
    if (chl && !ChlLogoCheck.isChlLogoUrl(url, override)) return@fetch null
    (loader.execute(logoRequest(context, url, chl, w, h)) as? SuccessResult)?.image?.toBitmap()
    }
}

/** The logo request for [data] (a URL; a bitmap in tests): rasterised at 4x the slot, then (CHL only) checked, then trimmed. */
internal fun logoRequest(context: Context, data: Any, chl: Boolean, w: Int, h: Int): ImageRequest =
    ImageRequest.Builder(context.applicationContext ?: context)
        .data(data)
        .size(w * SportsConstants.LOGO_RASTER_SCALE, h * SportsConstants.LOGO_RASTER_SCALE)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .allowHardware(false)
        .transformations(*logoTransformations(chl, w, h))
        .build()

/** The request's transformations: the CHL-only [ChlLogoCheck] first (it must fail before the trim), then [LogoTrim]. */
internal fun logoTransformations(chl: Boolean, w: Int, h: Int): Array<Transformation> =
    if (chl) arrayOf(ChlLogoCheck(), LogoTrim(w, h)) else arrayOf(LogoTrim(w, h))

/**
 * The fallback (§3): a neutral disc with the team abbreviation (White08 fill, 1 px Border12, Instrument Sans 18/700 Fg),
 * drawn when the logo failed or has not arrived within `LOGO_WAIT_MS`. Paints are built once; [draw] allocates nothing for a
 * code seen before.
 *
 * (CHL §6.4, P6, lead ruling on O3) A code of more than 3 characters (BRAM) draws a capsule instead: height 44, width
 * `max(44, text + 2 × 10)`, the disc's fill, stroke and text, centred in the slot. Its text steps down from 18 in 2 px steps
 * ([CodeFit.stepDown]) to the 4-letter floor of 16 ([CodeFit.fallbackFloorPx]) until the capsule overhangs the 64 px slot by
 * at most 4 a side and keeps at least 6 from the ink of the widest score (0–19) in the Pill's geometry. Never a display
 * alias. Codes of 3 characters or fewer keep the disc, exactly as today.
 */
internal class LogoDisc(private val scale: Float, private val face: Typeface?) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = argb(0.08f, 0xFFFFFF) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = scale.coerceAtLeast(1f); color = argb(0.12f, 0xFFFFFF)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = SportsConstants.LOGO_DISC_TEXT_PX * scale; color = 0xFFEEF1F6.toInt(); textAlign = Paint.Align.CENTER
        this.typeface = face
    }
    private val centreOffset = -(text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
    private val rect = RectF()

    // ---- the capsule (4+ character codes); measured in design px, drawn at [scale]
    private val measure by lazy(LazyThreadSafetyMode.NONE) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face } }
    private val bounds = Rect()
    // (Review L5) Two entries, one per team of the bug: both rows may draw a capsule every frame (BRAM vs FLNT), and a miss
    // costs a step-down search; a hit costs two string compares and no allocation.
    private val capsuleCodes = arrayOfNulls<String>(2)
    private val capsulePxs = IntArray(2)
    /** Each capsule's width in design px. */
    private val capsuleWs = FloatArray(2)
    private val capsuleOffsets = FloatArray(2)
    private var capsuleNext = 0

    /** The score ink's left edge in the Pill (least over 0..19, design px from the bug's left edge); computed once. */
    private val scoreInkLeft: Float by lazy(LazyThreadSafetyMode.NONE) {
        val score = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = SportsConstants.SCORE_TEXT_PX.toFloat(); textAlign = Paint.Align.CENTER }
        val cx = SportsConstants.SCORE_PAD + SportsConstants.SCORE_LOGO_W + SportsConstants.SCORE_LOGO_GAP + SportsConstants.SCORE_DIGITS_W / 2f
        var least = Float.MAX_VALUE
        for (n in 0..19) {
            val t = n.toString()
            score.getTextBounds(t, 0, t.length, bounds)
            least = minOf(least, cx - score.measureText(t) / 2f + bounds.left)
        }
        least
    }

    private fun capsuleWidthAt(code: String, px: Int): Float {
        measure.textSize = px.toFloat()
        return maxOf(SportsConstants.LOGO_DISC_D, measure.measureText(code) + 2 * SportsConstants.LOGO_CAPSULE_PAD)
    }

    /** The capsule text size for [code] in design px (see the class comment); the 4-letter floor when nothing fits. */
    internal fun capsuleTextPx(code: String): Int {
        val slotCx = SportsConstants.SCORE_PAD + SportsConstants.SCORE_LOGO_W / 2f
        val floor = CodeFit.fallbackFloorPx(code)
        return CodeFit.stepDown(SportsConstants.LOGO_DISC_TEXT_PX, floor) { px ->
            val w = capsuleWidthAt(code, px)
            val overhang = ((w - SportsConstants.SCORE_LOGO_W) / 2f).coerceAtLeast(0f)
            overhang <= SportsConstants.LOGO_CAPSULE_OVERHANG_MAX && scoreInkLeft - (slotCx + w / 2f) >= SportsConstants.LOGO_CAPSULE_SCORE_GAP_MIN
        } ?: floor
    }

    /** True when [abbrev] draws as a capsule rather than a disc. */
    internal fun isCapsule(abbrev: String): Boolean = abbrev.length > 3

    fun draw(c: Canvas, cx: Float, cy: Float, abbrev: String, alpha: Float = 1f) {
        if (isCapsule(abbrev)) {
            drawCapsule(c, cx, cy, abbrev, alpha)
            return
        }
        val r = SportsConstants.LOGO_DISC_D * scale / 2f
        fill.alpha = (0x14 * alpha).toInt()
        c.drawCircle(cx, cy, r, fill)
        stroke.alpha = (0x1F * alpha).toInt()
        val h = stroke.strokeWidth / 2f
        rect.set(cx - r + h, cy - r + h, cx + r - h, cy + r - h)
        c.drawOval(rect, stroke)
        text.alpha = (255 * alpha).toInt()
        c.drawText(abbrev, cx, cy + centreOffset, text)
    }

    /**
     * (CHL §6.4, lead ruling on S1) The Stacked rows' fallback for a code of more than 3 characters: a plain 44 px disc
     * **without text**, filled with the team's Stacked stripe colour ([colour], opaque; contrast-safe against the row by the
     * Stacked table). It sits inside the 50.4 px logo slot with the disc's own gaps to the stripe and the code, which the
     * row already prints beside it.
     */
    fun drawStripeDisc(c: Canvas, cx: Float, cy: Float, colour: Int) {
        stripeFill.color = colour
        c.drawCircle(cx, cy, SportsConstants.LOGO_DISC_D * scale / 2f, stripeFill)
    }

    private val stripeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawCapsule(c: Canvas, cx: Float, cy: Float, abbrev: String, alpha: Float) {
        var i = if (capsuleCodes[0] == abbrev) 0 else if (capsuleCodes[1] == abbrev) 1 else -1
        if (i < 0) {
            i = capsuleNext
            capsuleNext = 1 - capsuleNext
            capsuleCodes[i] = abbrev
            capsulePxs[i] = capsuleTextPx(abbrev)
            capsuleWs[i] = capsuleWidthAt(abbrev, capsulePxs[i])
            text.textSize = capsulePxs[i] * scale
            capsuleOffsets[i] = -(text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        }
        text.textSize = capsulePxs[i] * scale
        val hw = capsuleWs[i] * scale / 2f
        val hh = SportsConstants.LOGO_DISC_D * scale / 2f
        fill.alpha = (0x14 * alpha).toInt()
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        c.drawRoundRect(rect, hh, hh, fill)
        stroke.alpha = (0x1F * alpha).toInt()
        val h = stroke.strokeWidth / 2f
        rect.set(cx - hw + h, cy - hh + h, cx + hw - h, cy + hh - h)
        c.drawRoundRect(rect, hh - h, hh - h, stroke)
        text.alpha = (255 * alpha).toInt()
        c.drawText(abbrev, cx, cy + capsuleOffsets[i], text)
        text.textSize = SportsConstants.LOGO_DISC_TEXT_PX * scale
    }

    private fun argb(a: Float, rgb: Int): Int = ((a * 255f + 0.5f).toInt() shl 24) or rgb
}

/**
 * (CHL §6.4, lead ruling on O3, review S1) The text size, in design px, of a goal badge's fallback code: [SportsConstants.GOAL_BADGE_TEXT_PX]
 * (24), and for a code of 4 or more characters stepped down in 2 px steps to [CodeFit.fallbackFloorPx] (16) until the text's
 * **ink** fits [slotPx] (the Pill's 64 px logo slot; the Stacked takeover's 67.5). A 3-letter code is always 24, exactly as
 * today. Never a display alias; at the floor it is drawn at the floor (a lead escalation, not a clip).
 */
internal fun goalBadgeFallbackPx(face: Typeface?, code: String, slotPx: Float): Int {
    val start = SportsConstants.GOAL_BADGE_TEXT_PX.toInt()
    if (code.length < 4) return start
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
    val b = Rect()
    val floor = CodeFit.fallbackFloorPx(code)
    return CodeFit.stepDown(start, floor) { px ->
        paint.textSize = px.toFloat()
        paint.getTextBounds(code, 0, code.length, b)
        b.width() <= slotPx
    } ?: floor
}
