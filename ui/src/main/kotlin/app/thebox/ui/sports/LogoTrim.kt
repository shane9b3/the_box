package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Rect
import coil3.size.Size
import coil3.transform.Transformation

/**
 * (docs/design/m6-score-bug.md §3, U-S1) Crops a rasterised team logo to its alpha bounds (the art fills only about 70 % of
 * the 960 × 640 viewBox) and scales it to fit the [slotW] × [slotH] device-px slot. A Coil [Transformation], so the trimmed
 * bitmap is what the request returns; [trimAndFit] is the same work as a pure function for tests.
 */
class LogoTrim(private val slotW: Int, private val slotH: Int) : Transformation() {
    override val cacheKey: String get() = "LogoTrim($slotW,$slotH)"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = trimAndFit(input, slotW, slotH)

    companion object {
        /** Pixels with alpha at or below this count as empty. */
        const val ALPHA_FLOOR = 8

        /** The smallest rectangle holding every pixel with alpha above [ALPHA_FLOOR]; the whole bitmap if it has none. */
        fun alphaBounds(src: Bitmap): Rect {
            val w = src.width
            val h = src.height
            val row = IntArray(w)
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            for (y in 0 until h) {
                src.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if ((row[x] ushr 24) > ALPHA_FLOOR) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            return if (maxX < 0) Rect(0, 0, w, h) else Rect(minX, minY, maxX + 1, maxY + 1)
        }

        /** Crops to the alpha bounds, then scales (up or down, aspect kept) to fit [slotW] × [slotH]. Software bitmap. */
        fun trimAndFit(src: Bitmap, slotW: Int, slotH: Int): Bitmap {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val b = alphaBounds(soft)
            val cropped = if (b.left == 0 && b.top == 0 && b.width() == soft.width && b.height() == soft.height) soft
            else Bitmap.createBitmap(soft, b.left, b.top, b.width(), b.height())
            val k = minOf(slotW.toFloat() / cropped.width, slotH.toFloat() / cropped.height)
            val w = (cropped.width * k).toInt().coerceAtLeast(1)
            val h = (cropped.height * k).toInt().coerceAtLeast(1)
            return if (w == cropped.width && h == cropped.height) cropped else Bitmap.createScaledBitmap(cropped, w, h, true)
        }
    }
}
