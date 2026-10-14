package app.thebox.ui.playback

import androidx.annotation.OptIn
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW

/**
 * The one video surface of the app (plan §3 "Video surface", architecture review B1.2). Put it first in the root Box,
 * behind all other content, with **no** ancestor that applies graphicsLayer, alpha, clip, offscreen compositing or a
 * RenderEffect. [videoRect] is in window pixels (VideoRectCalculator output); null keeps the surface attached at 1×1 px
 * in the top-left corner (viewport HIDDEN/NONE), so the decoder keeps its surface and no reconfiguration happens.
 */
@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
fun VideoLayer(
    surfacePlayer: androidx.media3.common.Player,
    videoRect: app.thebox.core.playback.RectPx?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    // §2.6 body: a constant-size placement per rect (no animation, no graphicsLayer, no clip). The surface itself is a
    // plain SurfaceView created by PlayerSurface; mini ↔ full is a rect change, the surface is never swapped.
    val rect = videoRect
    PlayerSurface(
        player = surfacePlayer,
        modifier = modifier.layout { measurable, constraints ->
            val w = rect?.width?.coerceAtLeast(1) ?: 1
            val h = rect?.height?.coerceAtLeast(1) ?: 1
            val placeable = measurable.measure(Constraints.fixed(w, h))
            val outerW = if (constraints.hasBoundedWidth) constraints.maxWidth else (rect?.right ?: w)
            val outerH = if (constraints.hasBoundedHeight) constraints.maxHeight else (rect?.bottom ?: h)
            layout(outerW, outerH) { placeable.place(rect?.left ?: 0, rect?.top ?: 0) }
        },
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
    )
}
