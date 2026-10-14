package app.thebox.core.playback.surface

import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.RectPx
import app.thebox.core.playback.VideoPlacement
import app.thebox.core.playback.VideoRectCalculator
import kotlin.math.roundToInt

object SurfaceConstants {
    const val ASPECT_16_9 = 16f / 9f
    const val ASPECT_4_3 = 4f / 3f
}

/**
 * Aspect maths for M3/M4 (§3.11). FIT: the largest rect of the display aspect `w × pixelRatio : h` centred in the hole;
 * RATIO_16_9/RATIO_4_3: that aspect centred; FILL/ZOOM: the whole hole with `cropToFill = true` (the controller then
 * sets `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING`). Sizes are rounded to whole pixels, odd remainders go to the
 * right/bottom, and the result never leaves the hole. An unknown video size yields the hole.
 */
class VideoRectCalculatorImpl : VideoRectCalculator {
    override fun place(hole: RectPx, videoWidth: Int, videoHeight: Int, pixelRatio: Float, mode: AspectMode): VideoPlacement {
        if (mode == AspectMode.FILL || mode == AspectMode.ZOOM) return VideoPlacement(hole, cropToFill = true)
        val aspect = when (mode) {
            AspectMode.RATIO_16_9 -> SurfaceConstants.ASPECT_16_9
            AspectMode.RATIO_4_3 -> SurfaceConstants.ASPECT_4_3
            else -> {
                if (videoWidth <= 0 || videoHeight <= 0) return VideoPlacement(hole, cropToFill = false)
                val par = if (pixelRatio > 0f && pixelRatio.isFinite()) pixelRatio else 1f
                videoWidth * par / videoHeight
            }
        }
        val hw = hole.width
        val hh = hole.height
        if (hw <= 0 || hh <= 0) return VideoPlacement(hole, cropToFill = false)
        val w: Int
        val h: Int
        if (hw.toFloat() / hh > aspect) {
            h = hh
            w = (hh * aspect).roundToInt().coerceIn(1, hw)
        } else {
            w = hw
            h = (hw / aspect).roundToInt().coerceIn(1, hh)
        }
        val left = hole.left + (hw - w) / 2
        val top = hole.top + (hh - h) / 2
        return VideoPlacement(RectPx(left, top, left + w, top + h), cropToFill = false)
    }
}
