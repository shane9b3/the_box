package app.thebox.ui.full

import app.thebox.core.playback.AspectMode
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.VideoInfo

/**
 * Sticky video size across a zap (docs/design/m4-player-ux.md §0.14, §3.6.2; review S2, N4). The controller keeps the
 * last frame on `stop()` but resets `VideoInfo` to size 0 on every tune, which would make the root recompute the video
 * rect and resize the `SurfaceView` under the frozen frame. So the root lays out from [resolve]:
 *
 * - **holding** starts when `target.tuneId` changes after a picture was shown; while it lasts, the last non-zero
 *   width, height, pixel ratio and aspect are used;
 * - it ends when `renderedFirstFrames` advances, the tune reaches first media (`Playing`, also for radio, N4), an
 *   `Error`, or playback stops/idles (a failed or audio-only tune never renders a frame);
 * - (S2) during an owned archive hand-off (the visible `TunedState.handoff`) the aspect mode is also held, so FILL never
 *   flickers to FIT while `ArchivePlaybackImpl` re-applies it.
 *
 * Pure apart from its own fields; one per root. Main thread.
 */
class StickyVideo {
    private var tuneId = NONE
    private var holding = false
    private var framesAtHold = 0L
    private var last: VideoInfo? = null
    private var stableAspect: AspectMode? = null

    /** True while the previous picture's geometry is being held. */
    val isHolding: Boolean get() = holding

    fun resolve(video: VideoInfo, state: PlaybackState, handoff: Boolean): VideoInfo {
        val id = state.target?.tuneId
        if (id != null && id != tuneId) {
            if (tuneId != NONE && last != null) {
                holding = true
                framesAtHold = video.renderedFirstFrames
            }
            tuneId = id
        }
        if (holding) {
            val sameTune = state.target?.tuneId == tuneId
            val released = video.renderedFirstFrames > framesAtHold ||
                (sameTune && (state is PlaybackState.Playing || state is PlaybackState.Error)) ||
                state is PlaybackState.Idle || state is PlaybackState.Stopped
            if (released) holding = false
        }
        var out = video
        if (holding) {
            val l = last!!
            out = video.copy(width = l.width, height = l.height, pixelRatio = l.pixelRatio, aspectMode = l.aspectMode)
        } else if (video.width > 0 && video.height > 0) {
            last = video
        }
        if (handoff) {
            stableAspect?.let { if (it != out.aspectMode) out = out.copy(aspectMode = it) }
        } else {
            stableAspect = out.aspectMode
        }
        return out
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
