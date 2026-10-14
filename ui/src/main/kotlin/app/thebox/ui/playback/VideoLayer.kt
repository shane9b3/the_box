package app.thebox.ui.playback

/**
 * The one video surface of the app (plan §3 "Video surface", architecture review B1.2). Put it first in the root Box,
 * behind all other content, with **no** ancestor that applies graphicsLayer, alpha, clip, offscreen compositing or a
 * RenderEffect. [videoRect] is in window pixels (VideoRectCalculator output); null keeps the surface attached at 1×1 px
 * in the top-left corner (viewport HIDDEN/NONE), so the decoder keeps its surface and no reconfiguration happens.
 */
@androidx.compose.runtime.Composable
fun VideoLayer(
    surfacePlayer: androidx.media3.common.Player,
    videoRect: app.thebox.core.playback.RectPx?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    // Body (WP-D, §2.6): PlayerSurface(surfacePlayer, modifier.layout { … videoRect … }, SURFACE_TYPE_SURFACE_VIEW).
    TODO("WP-D")
}
