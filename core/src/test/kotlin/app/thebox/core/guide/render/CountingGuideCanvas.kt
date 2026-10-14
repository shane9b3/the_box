package app.thebox.core.guide.render

import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.GuideImage
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.GuideTextStyle

/**
 * Allocation-free canvas for the allocation and perf tests: counts ops by kind and, when [recordTexts] is on, keeps
 * the `String` instances passed to `drawText` in a pre-sized array (identity, not copies).
 */
class CountingGuideCanvas(capacity: Int = 4096) : GuideCanvas {
    var ops = 0
    var texts = 0
    var saves = 0
    var depth = 0
    var recordTexts = false
    val textRefs = arrayOfNulls<String>(capacity)

    fun reset() { ops = 0; texts = 0; saves = 0; depth = 0 }

    override fun save(): Int { ops++; saves++; return depth++ }
    override fun restoreToCount(count: Int) { ops++; depth = count }
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) { ops++ }
    override fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int) { ops++ }
    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, argb: Int) { ops++ }
    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, argb: Int) { ops++ }
    override fun fillCircle(cx: Float, cy: Float, radius: Float, argb: Int) { ops++ }
    override fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int) {
        require(start in 0..end && end <= text.length) { "range $start..$end of ${text.length}" }
        if (recordTexts && texts < textRefs.size) textRefs[texts] = text
        texts++; ops++
    }
    override fun drawImage(image: GuideImage, l: Float, t: Float, r: Float, b: Float, alpha: Float) { ops++ }
    override fun drawAsset(asset: GuideAsset, l: Float, t: Float, r: Float, b: Float, argb: Int) { ops++ }
}

/** Every URL resolves to the same image; lookup allocates nothing. */
class StaticGuideImages(private val image: GuideImage?) : GuideImages {
    override fun get(url: String): GuideImage? = image
    override fun failed(url: String): Boolean = false
    override var onLoaded: (() -> Unit)? = null
    override fun trim() {}
}
