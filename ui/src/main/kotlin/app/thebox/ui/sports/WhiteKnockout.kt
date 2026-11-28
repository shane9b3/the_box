package app.thebox.ui.sports

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * (CHL hub, owner report 2026-11-27) Some CHL teams have no transparent logo on the feed's host, only a JPEG on white
 * (Brampton: `18.png` is a 404, `18.jpg` is the logo on a white square). This turns the white **around** the logo
 * transparent so it can sit on a dark card: every near-white pixel reachable from the picture's edge is cleared, and the
 * logo's own enclosed whites are kept. The one-pixel rim left beside the cleared area is faded by its lightness, so the
 * logo has no white fringe. It runs only on the JPEG retry in [coilLogoFetchDetailed], ahead of [ChlLogoCheck]; a picture
 * whose edge is not white is left opaque and that check then refuses it as before.
 */
class WhiteKnockout : Transformation() {
    override val cacheKey: String get() = "WhiteKnockout"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = knockOut(input)

    companion object {
        /** A pixel counts as background when its darkest channel is at least this. */
        const val WHITE_FLOOR = 238

        /** Rim pixels fade from opaque at this darkest-channel value to clear at 255. */
        const val RIM_FLOOR = 160

        fun knockOut(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            if (w < 3 || h < 3) return src
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val cleared = BooleanArray(w * h)
            val stack = IntArray(w * h)
            var top = 0
            fun low(c: Int): Int = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            fun push(i: Int) {
                if (!cleared[i] && low(px[i]) >= WHITE_FLOOR) { cleared[i] = true; stack[top++] = i }
            }
            for (x in 0 until w) { push(x); push((h - 1) * w + x) }
            for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                if (x > 0) push(i - 1)
                if (x < w - 1) push(i + 1)
                if (i >= w) push(i - w)
                if (i < w * (h - 1)) push(i + w)
            }
            val out = IntArray(w * h)
            for (i in px.indices) {
                if (cleared[i]) { out[i] = 0; continue }
                val x = i % w
                val rim = (x > 0 && cleared[i - 1]) || (x < w - 1 && cleared[i + 1]) || (i >= w && cleared[i - w]) || (i < w * (h - 1) && cleared[i + w])
                val l = low(px[i])
                out[i] = if (rim && l > RIM_FLOOR) {
                    val a = (255 - l) * 255 / (255 - RIM_FLOOR)
                    (a shl 24) or (px[i] and 0x00FFFFFF)
                } else px[i] or (0xFF shl 24)
            }
            val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            res.setHasAlpha(true)
            res.setPixels(out, 0, w, 0, 0, w, h)
            return res
        }
    }
}
