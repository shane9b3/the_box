package app.thebox.player.engine

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener

/**
 * Frame rate of the playing video (docs/design/m2-playback-core.md §3.9.3), for AFR and the stats overlay.
 *
 * `ExoPlayer.setVideoFrameMetadataListener(estimator)` calls [onVideoFrameAboutToBeRendered] on the playback thread.
 * A declared `Format.frameRate` (HLS `FRAME-RATE`, MP4) is used as is; otherwise (live TS never declares one) the
 * estimate is the median of [FPS_ESTIMATE_FRAMES] consecutive presentation-time deltas, ignoring deltas above
 * [MAX_DELTA_US] (gaps, discontinuities) and non-positive ones (reordering). The value is snapped with [snap]
 * (`DisplayModePicker.snapFps`, WP-A) and delivered once per [reset] to [onFps] on [callbackLooper]'s thread. Attach
 * it only while AFR is ALWAYS or the stats overlay is on, and detach it after the estimate (§3.9.3); [reset] at every
 * tune.
 *
 * No allocation per frame: the deltas live in a fixed `LongArray` and the median sorts a scratch copy in place.
 */
@OptIn(UnstableApi::class)
internal class FpsEstimator(
    private val snap: (Float) -> Float?,
    callbackLooper: Looper,
    private val onFps: (Float) -> Unit,
) : VideoFrameMetadataListener {

    private val handler = Handler(callbackLooper)
    private val deltas = LongArray(FPS_ESTIMATE_FRAMES)
    private val scratch = LongArray(FPS_ESTIMATE_FRAMES)
    private var count = 0
    private var lastPtsUs = UNSET

    @Volatile private var generation = 0
    @Volatile private var done = false
    @Volatile private var resetRequested = false

    /** Last raw (unsnapped) estimate, for tests and diagnostics. */
    @Volatile var lastRawFps: Float = 0f
        private set

    /** Starts over (new tune or new video format). Main thread. */
    fun reset() {
        generation++
        resetRequested = true // the playback thread clears its own state at the next frame
        done = false
    }

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
        if (resetRequested) {
            resetRequested = false
            count = 0
            lastPtsUs = UNSET
        }
        if (done) return
        if (format.frameRate > 0f) {
            deliver(format.frameRate)
            return
        }
        val last = lastPtsUs
        lastPtsUs = presentationTimeUs
        if (last == UNSET) return
        val delta = presentationTimeUs - last
        if (delta <= 0 || delta > MAX_DELTA_US) return
        deltas[count++] = delta
        if (count < FPS_ESTIMATE_FRAMES) return
        System.arraycopy(deltas, 0, scratch, 0, FPS_ESTIMATE_FRAMES)
        scratch.sort()
        val median = (scratch[FPS_ESTIMATE_FRAMES / 2 - 1] + scratch[FPS_ESTIMATE_FRAMES / 2]) / 2.0
        count = 0
        deliver((1_000_000.0 / median).toFloat())
    }

    private fun deliver(rawFps: Float) {
        lastRawFps = rawFps
        val snapped = snap(rawFps) ?: return // keep measuring: no broadcast rate within tolerance
        done = true
        val gen = generation
        handler.post { if (gen == generation) onFps(snapped) }
    }

    companion object {
        /** `FPS_ESTIMATE_FRAMES` (App. B). */
        const val FPS_ESTIMATE_FRAMES = 30

        /** Deltas above 200 ms are gaps, not frame periods (App. B "max delta"). */
        const val MAX_DELTA_US = 200_000L

        private const val UNSET = Long.MIN_VALUE
    }
}
