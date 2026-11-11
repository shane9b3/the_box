package app.thebox.core.guide.fakes

import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideImage
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle

/** Records every [GuideCanvas] call in order (§5.1 "recording canvas"). */
class RecordingGuideCanvas : GuideCanvas {
    sealed interface Op {
        data class Save(val depth: Int) : Op
        data class Restore(val count: Int) : Op
        data class Clip(val l: Float, val t: Float, val r: Float, val b: Float) : Op
        data class Rect(val l: Float, val t: Float, val r: Float, val b: Float, val argb: Int) : Op
        data class RoundRect(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float, val argb: Int) : Op
        data class Stroke(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float, val width: Float, val argb: Int) : Op
        data class Circle(val cx: Float, val cy: Float, val radius: Float, val argb: Int) : Op
        data class Text(val text: String, val x: Float, val baseline: Float, val style: GuideTextStyle, val argb: Int) : Op
        data class Image(val image: GuideImage, val l: Float, val t: Float, val r: Float, val b: Float, val alpha: Float) : Op
        data class Asset(val asset: GuideAsset, val l: Float, val t: Float, val r: Float, val b: Float, val argb: Int) : Op
    }

    val ops = ArrayList<Op>()
    private var depth = 0

    override fun save(): Int { ops += Op.Save(depth); return depth++ }
    override fun restoreToCount(count: Int) { ops += Op.Restore(count); depth = count }
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) { ops += Op.Clip(l, t, r, b) }
    override fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int) { ops += Op.Rect(l, t, r, b, argb) }
    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, argb: Int) { ops += Op.RoundRect(l, t, r, b, radius, argb) }
    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, argb: Int) { ops += Op.Stroke(l, t, r, b, radius, width, argb) }
    override fun fillCircle(cx: Float, cy: Float, radius: Float, argb: Int) { ops += Op.Circle(cx, cy, radius, argb) }
    override fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int) {
        ops += Op.Text(text.substring(start, end), x, baseline, style, argb)
    }
    override fun drawImage(image: GuideImage, l: Float, t: Float, r: Float, b: Float, alpha: Float) { ops += Op.Image(image, l, t, r, b, alpha) }
    override fun drawAsset(asset: GuideAsset, l: Float, t: Float, r: Float, b: Float, argb: Int) { ops += Op.Asset(asset, l, t, r, b, argb) }

    fun texts(): List<String> = ops.filterIsInstance<Op.Text>().map { it.text }
    fun clear() { ops.clear(); depth = 0 }
}

/** §5.1 fake measurer: width = chars × size × 0.5 × scale; ascent 0.8 × size, descent 0.2 × size. */
class FakeTextMeasurer(override val scale: Float = 1f) : GuideTextMeasurer {
    private fun charW(style: GuideTextStyle) = style.sizePx * 0.5f * scale
    override fun width(text: String, start: Int, end: Int, style: GuideTextStyle): Float = (end - start) * charW(style)
    override fun fit(text: String, start: Int, end: Int, style: GuideTextStyle, maxWidth: Float): Int =
        minOf(end - start, (maxWidth / charW(style)).toInt().coerceAtLeast(0))
    override fun ascent(style: GuideTextStyle): Float = style.sizePx * 0.8f * scale
    override fun descent(style: GuideTextStyle): Float = style.sizePx * 0.2f * scale
}

data class FakeGuideImage(override val widthPx: Int, override val heightPx: Int, val url: String) : GuideImage

/** URLs in [loaded] resolve immediately; URLs in [failing] fail; anything else is "loading" (null). */
class FakeGuideImages(
    val loaded: MutableMap<String, GuideImage> = HashMap(),
    val failing: MutableSet<String> = HashSet(),
) : GuideImages {
    val requested = ArrayList<String>()
    /** URLs of rows replayed from a recording (review 2026-11-10, S3). */
    val touched = ArrayList<String>()
    var trims = 0
    override fun get(url: String): GuideImage? { requested += url; return loaded[url] }
    override fun touch(url: String) { touched += url }
    override fun failed(url: String): Boolean = url in failing
    override var onLoaded: (() -> Unit)? = null
    override fun trim() { trims++ }

    /** Simulates a load finishing on main. */
    fun complete(url: String, image: GuideImage = FakeGuideImage(64, 36, url)) { loaded[url] = image; onLoaded?.invoke() }
}

/** List-backed [GuideRows]; a null entry is a not-yet-loaded block (skeleton). */
class FakeGuideRows(channels: List<GuideChannel?> = emptyList(), version: Long = 1) : GuideRows {
    var rows: List<GuideChannel?> = channels
        set(value) { field = value; version++ }
    override var version: Long = version
        private set
    override val size: Int get() = rows.size
    override fun channel(index: Int): GuideChannel? = rows.getOrNull(index)
    override fun indexOf(rowId: Long): Int = rows.indexOfFirst { it?.rowId == rowId }
}

/** Counts draws and returns fixed bounds; for host/state tests that do not test drawing. */
class FakeGuideRenderer : GuideRenderer {
    var draws = 0
    var lastWindowStartMin = 0
    var lastScrollOffsetMin = 0f
    var lastScrollRow = 0f
    var bounds: FloatArray? = floatArrayOf(0f, 0f, 100f, 60f)
    override fun draw(canvas: GuideCanvas, frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float) {
        draws++; lastWindowStartMin = windowStartMin; lastScrollOffsetMin = scrollOffsetMin; lastScrollRow = scrollRow
    }
    override fun focusedCellBounds(frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float, out: FloatArray): Boolean {
        val b = bounds ?: return false
        b.copyInto(out); return true
    }
    override fun describeFocus(frame: GuideFrame): String = frame.focus?.let { "row ${it.row} at ${it.cellStart}" } ?: ""
}
