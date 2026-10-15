package app.thebox.ui.full

import androidx.compose.runtime.Composable
import androidx.media3.common.text.Cue
import app.thebox.core.playback.RectPx

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.6.3): `AndroidView(SubtitleView)` sized to [videoRect] (window px), a
 * direct child of the root `Box` after `HoleChrome`; never focusable, never an ancestor of `VideoLayer`, no
 * `graphicsLayer`/alpha/clip. [bottomPaddingFraction] lifts captions over a visible bar (N5).
 */
@Composable
fun SubtitleLayer(cues: List<Cue>, videoRect: RectPx, bottomPaddingFraction: Float) {
    TODO("WP-D")
}
