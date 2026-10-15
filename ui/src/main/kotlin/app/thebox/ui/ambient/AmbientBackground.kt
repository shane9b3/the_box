package app.thebox.ui.ambient

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.thebox.core.playback.RectPx
import app.thebox.ui.theme.DesignTokens
import kotlin.math.roundToInt

const val AMBIENT_TAG = "ambient"
/** Trace section around the ambient draw (I-18, §2.13 item 20). */
const val TRACE_AMBIENT = "guide-ambient"

/**
 * The window background (docs/design/m3-guide.md §3.14, §3.13.3): the ambient bitmap scaled to the window and
 * cross-faded on a hue change, the hole's pre-rendered drop shadow under [shadowHole], or flat #0A0D13 in low-end mode
 * or with Ambient off, black in FULL ([black]). Everything except [videoRect], where the `SurfaceView` shows through.
 *
 * Drawn **after** `VideoLayer` (WP-C finding, 2026-10-14, see `AppRoot`): on API ≤ 30 `SurfaceView` punches its hole
 * with an unclipped `drawColor(0, CLEAR)` and Compose's view-interop holders set `clipChildren = false`, so anything
 * drawn before the surface in the frame is erased to transparent across the whole window.
 * A sibling of `VideoLayer`, never its ancestor: it carries a default `graphicsLayer()` (B8) so a grid frame does not
 * re-record it.
 */
@Composable
fun AmbientBackground(state: AmbientState, videoRect: RectPx?, shadowHole: RectPx?, black: Boolean, modifier: Modifier = Modifier) {
    LaunchedEffect(state.targetHue, state.flat) {
        if (!state.flat) state.show(state.targetHue ?: AmbientState.DEFAULT_HUE)
    }
    Spacer(
        modifier
            .fillMaxSize()
            .testTag(AMBIENT_TAG)
            .graphicsLayer()
            .drawBehind {
                android.os.Trace.beginSection(TRACE_AMBIENT) // I-18: must not fire on grid-only frames (B8)
                try {
                    val v = videoRect
                    if (v == null) {
                        drawField(state, shadowHole, black)
                    } else {
                        // Never paint inside the video rect: the SurfaceView shows through it.
                        clipRect(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), ClipOp.Difference) {
                            drawField(state, shadowHole, black)
                        }
                    }
                } finally {
                    android.os.Trace.endSection()
                }
            },
    )
}

private fun DrawScope.drawField(state: AmbientState, shadowHole: RectPx?, black: Boolean) {
    if (black) {
        drawRect(Color.Black)
        return
    }
    drawAmbient(state)
    val shadow = state.shadow
    if (shadowHole != null && shadow != null) drawShadow(shadow, shadowHole)
}

/** Base + ambient bitmaps with the shared cross-fade; also used by `HoleChrome` for the hole corners (N9). */
fun DrawScope.drawAmbient(state: AmbientState) {
    val cur = state.current
    if (state.flat || cur == null) {
        drawRect(DesignTokens.Colors.Canvas)
        return
    }
    val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
    val prev = state.previous
    val fade = state.fade
    if (prev != null && fade < 1f) {
        drawImage(prev, dstSize = dst, filterQuality = FilterQuality.Low)
        drawImage(cur, dstSize = dst, alpha = fade, filterQuality = FilterQuality.Low)
    } else {
        drawImage(cur, dstSize = dst, filterQuality = FilterQuality.Low)
    }
}

private fun DrawScope.drawShadow(shadow: ImageBitmap, hole: RectPx) {
    // The bitmap covers the hole plus SHADOW_MARGIN design px per side at ¼ scale; dy = 30 design px (§3.13.3).
    val s = hole.width / app.thebox.core.guide.GuideGeometry.HOLE_W
    val m = AmbientRenderer.SHADOW_MARGIN * s
    val left = (hole.left - m).roundToInt()
    val top = (hole.top - m + AmbientRenderer.SHADOW_DY * s).roundToInt()
    val w = (hole.width + 2 * m).roundToInt()
    val h = (hole.height + 2 * m).roundToInt()
    drawImage(shadow, dstOffset = IntOffset(left, top), dstSize = IntSize(w, h), filterQuality = FilterQuality.Low)
}
