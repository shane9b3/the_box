package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import app.thebox.core.playback.BufferProfile
import app.thebox.player.internal.AdaptiveLoadControlHandle

/**
 * A `LoadControl` whose thresholds come from a `@Volatile` [profile] (docs/design/m2-playback-core.md §3.6.3), so the
 * buffer level can change at runtime without rebuilding the player.
 *
 * The allocator and the per-player bookkeeping are delegated to a [DefaultLoadControl] whose byte target is the
 * device-class cap [maxTargetBytes] (so pooled free segments never exceed it and `onStopped` frees the media buffer).
 * The two decisions ([shouldContinueLoading], [shouldStartPlayback]) are ours and never call the delegate's.
 *
 * Only the `PlayerId`/`Parameters` overloads are overridden: in 1.11.1 the deprecated overloads' default bodies throw
 * (review S4). The back buffer is always 0 because `ExoPlayerImplInternal` reads it once per player.
 */
@OptIn(UnstableApi::class)
internal class AdaptiveLoadControl(
    private val maxTargetBytes: Int = NORMAL_TARGET_BYTES,
    initialProfile: BufferProfile = defaultProfile(maxTargetBytes),
) : LoadControl, AdaptiveLoadControlHandle {

    /** Written on main, read on the playback thread. A new value applies at the next evaluation. */
    @Volatile override var profile: BufferProfile = initialProfile

    private val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setTargetBufferBytes(maxTargetBytes)
        // Irrelevant to our decisions; must merely satisfy the builder's argument checks.
        .setBufferDurationsMs(40_000, 60_000, 1_000, 2_000)
        .setBackBuffer(0, false)
        .build()

    /** Hysteresis state of [shouldContinueLoading] (one player per instance; playback thread only). */
    @Volatile private var isLoading = false

    /** Number of [shouldStartPlayback] evaluations (diagnostics/tests; a plain counter, no allocation). */
    @Volatile var startEvaluations: Long = 0L
        private set

    /** Bytes currently held by the media buffer (stats, §3.15). */
    val bytesAllocated: Int get() = allocator.totalBytesAllocated

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun onPrepared(playerId: PlayerId) {
        isLoading = false
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) {
        isLoading = false
        delegate.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        isLoading = false
        delegate.onReleased(playerId)
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = 0L

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val p = profile
        val targetReached = allocator.totalBytesAllocated >= p.targetBytes
        val minUs = maxOf(p.minBufferMs * 1_000L, MIN_BUFFER_FLOOR_US)
        val maxUs = maxOf(p.maxBufferMs * 1_000L, minUs)
        val buffered = parameters.bufferedDurationUs
        if (buffered < minUs) {
            isLoading = !targetReached
        } else if (buffered >= maxUs || targetReached) {
            isLoading = false
        } // else: keep the previous decision (hysteresis between min and max)
        return isLoading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        startEvaluations++
        val p = profile
        var thresholdUs = (if (parameters.rebuffering) p.afterRebufferMs else p.startMs) * 1_000L
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            // HLS live: never wait longer than half the target live offset (§3.6.1).
            thresholdUs = minOf(thresholdUs, parameters.targetLiveOffsetUs / 2)
        }
        val playoutUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return thresholdUs <= 0 || playoutUs >= thresholdUs || allocator.totalBytesAllocated >= p.targetBytes
    }

    companion object {
        /** `LOW_RAM_TARGET_BYTES` / `NORMAL_TARGET_BYTES` (§3.6.1, App. B; values owned by WP-A's FSM). */
        const val LOW_RAM_TARGET_BYTES = 24 * 1024 * 1024
        const val NORMAL_TARGET_BYTES = 48 * 1024 * 1024

        /** Same floor as `DefaultLoadControl`: never let the low-water mark drop below 0.5 s. */
        private const val MIN_BUFFER_FLOOR_US = 500_000L

        /** Auto L0 (§3.6.1): used until the controller sets the profile of the first tune. */
        fun defaultProfile(targetBytes: Int): BufferProfile =
            BufferProfile(startMs = 1_000, afterRebufferMs = 3_000, minBufferMs = 10_000, maxBufferMs = 20_000, targetBytes = targetBytes, level = 0)
    }
}
