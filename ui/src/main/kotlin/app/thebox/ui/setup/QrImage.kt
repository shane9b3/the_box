package app.thebox.ui.setup

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val PHONE_QR_TAG = "phone_qr"

/**
 * QR modules (qrcodegen, error correction M, docs/design/m5-setup-settings.md §3.5.1) as an ARGB bitmap of
 * [sizePx] × [sizePx] device pixels: black modules on white with the 4-module quiet zone, each module a whole number
 * of pixels (centred), so scanners see sharp edges. Pure; runs on `Dispatchers.Default` (§5: once per server start).
 */
fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
    val modules = qr.size + 2 * QUIET
    val scale = (sizePx / modules).coerceAtLeast(1)
    val offset = (sizePx - modules * scale) / 2
    val pixels = IntArray(sizePx * sizePx) { WHITE }
    for (y in 0 until qr.size) for (x in 0 until qr.size) {
        if (!qr.getModule(x, y)) continue
        val px = offset + (x + QUIET) * scale
        val py = offset + (y + QUIET) * scale
        for (dy in 0 until scale) {
            val row = (py + dy) * sizePx
            for (dx in 0 until scale) pixels[row + px + dx] = BLACK
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

private const val QUIET = 4
private const val WHITE = -0x1 // 0xFFFFFFFF
private const val BLACK = -0x1000000 // 0xFF000000

/** The QR for [text], [size] in design px (1 dp == 1 design px under `BoxDesignScale`); blank until encoded. */
@Composable
fun QrImage(text: String, size: Dp, description: String, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val bitmap by produceState<ImageBitmap?>(null, text, px) {
        value = withContext(Dispatchers.Default) { qrBitmap(text, px).asImageBitmap() }
    }
    val m = modifier.size(size).testTag(PHONE_QR_TAG).semantics { contentDescription = description }
    bitmap?.let { Image(it, contentDescription = null, modifier = m, filterQuality = FilterQuality.None) } ?: androidx.compose.foundation.layout.Box(m)
}
