package app.thebox.core.playback.surface

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.RectPx
import app.thebox.core.playback.VideoPlacement
import app.thebox.core.playback.VideoRectCalculator

/** Aspect maths for M3/M4 (§3.11). */
class VideoRectCalculatorImpl : VideoRectCalculator {
    override fun place(hole: RectPx, videoWidth: Int, videoHeight: Int, pixelRatio: Float, mode: AspectMode): VideoPlacement = TODO("WP-A")
}
