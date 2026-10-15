package app.thebox.ui.root

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import app.thebox.core.guide.GuideGeometry
import app.thebox.core.playback.RectPx
import app.thebox.ui.ambient.AmbientState
import app.thebox.ui.ambient.drawAmbient

const val HOLE_CHROME_TAG = "hole_chrome"

/** Ring around the mini player: `0 0 0 1px rgba(255,255,255,.14)` (prototype). */
private val RING = Color.White.copy(alpha = 0.14f)

/** Darkening of the ambient field inside the hole while it shows a still. */
private val STILL_TINT = Color.Black.copy(alpha = 0.28f)

/**
 * Drawn after the `SurfaceView`, so it composites over the video (docs/design/m3-guide.md §3.13.3). A sibling after
 * `VideoLayer`, never an ancestor. (code review 2026-10-14, H-S8) It carries its own default layer, like the ambient
 * background (B8): its draw reads the ambient cross-fade and the scrim, and without a layer every such frame re-recorded
 * the root's content (mini overlay, chips, footer). A sibling's layer does not touch the surface rule. Clips happen only
 * inside the draw.
 *
 * Nothing is drawn inside the video rect except at its edges:
 * 1. black in `hole − video` (letterbox, B1.5b);
 * 2. the four corners `hole − roundRect(hole, 20)`: the ambient field (same cross-fade as the background, N9);
 * 3. the 1 px ring;
 * 4. the still background (ambient clipped to the rounded hole) when the mini shows a still, or the HDR poster
 *    ([poster]: viewport MINI with the video track off, code review 2026-10-14, H-B1);
 * 5. the transition scrim ([scrim], 0..1, read in the draw phase only).
 */
@Composable
fun HoleChrome(rects: HoleRects, mode: HoleMode, ambient: AmbientState, scrim: () -> Float, modifier: Modifier = Modifier, poster: Boolean = false) {
    val hole = rects.hole
    val corners = remember(hole) { hole?.let(::cornerPathFor) }
    val rounded = remember(hole) { hole?.let(::roundPathFor) }
    Spacer(
        modifier
            .fillMaxSize()
            .testTag(HOLE_CHROME_TAG)
            .graphicsLayer()
            .drawBehind {
                if (hole == null) return@drawBehind
                val video = rects.video
                val hl = hole.left.toFloat()
                val ht = hole.top.toFloat()
                val hs = Size(hole.width.toFloat(), hole.height.toFloat())
                val radius = GuideGeometry.HOLE_R * hole.width / GuideGeometry.HOLE_W
                when (mode) {
                    HoleMode.FULL -> {
                        if (video == null) {
                            drawRect(Color.Black)
                        } else if (video != hole) {
                            clipRect(video.left.toFloat(), video.top.toFloat(), video.right.toFloat(), video.bottom.toFloat(), ClipOp.Difference) {
                                drawRect(Color.Black, Offset(hl, ht), hs)
                            }
                        }
                        val a = scrim()
                        if (a > 0f) drawRect(Color.Black, Offset(hl, ht), hs, alpha = a)
                    }
                    HoleMode.MINI, HoleMode.STILL -> {
                        if (mode == HoleMode.MINI && video != null && video != hole) {
                            clipRect(video.left.toFloat(), video.top.toFloat(), video.right.toFloat(), video.bottom.toFloat(), ClipOp.Difference) {
                                drawRect(Color.Black, Offset(hl, ht), hs)
                            }
                        }
                        if ((mode == HoleMode.STILL || poster) && rounded != null) {
                            // Repaint the field inside the hole so the background's drop shadow does not show through.
                            clipPath(rounded) { drawAmbient(ambient) }
                            drawRoundRect(STILL_TINT, Offset(hl, ht), hs, CornerRadius(radius))
                        }
                        corners?.let { path -> clipPath(path) { drawAmbient(ambient) } }
                        val ring = hole.width / GuideGeometry.HOLE_W // 1 design px
                        drawRoundRect(
                            RING,
                            topLeft = Offset(hl - ring / 2f, ht - ring / 2f),
                            size = Size(hs.width + ring, hs.height + ring),
                            cornerRadius = CornerRadius(radius + ring / 2f),
                            style = Stroke(width = ring),
                        )
                        val a = scrim()
                        if (a > 0f) drawRoundRect(Color.Black, Offset(hl, ht), hs, CornerRadius(radius), alpha = a)
                    }
                    HoleMode.COVERED -> Unit
                }
            },
    )
}

internal fun roundPathFor(hole: RectPx): Path {
    val radius = GuideGeometry.HOLE_R * hole.width / GuideGeometry.HOLE_W
    return Path().apply {
        addRoundRect(RoundRect(hole.left.toFloat(), hole.top.toFloat(), hole.right.toFloat(), hole.bottom.toFloat(), CornerRadius(radius)))
    }
}

/** `rect(hole) − roundRect(hole, 20 design px)` as an even-odd path; rebuilt only when the hole changes. */
internal fun cornerPathFor(hole: RectPx): Path {
    val radius = GuideGeometry.HOLE_R * hole.width / GuideGeometry.HOLE_W
    val l = hole.left.toFloat()
    val t = hole.top.toFloat()
    val r = hole.right.toFloat()
    val b = hole.bottom.toFloat()
    return Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(l, t, r, b))
        addRoundRect(RoundRect(l, t, r, b, CornerRadius(radius)))
    }
}
