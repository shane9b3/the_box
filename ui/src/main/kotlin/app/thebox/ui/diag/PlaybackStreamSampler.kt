package app.thebox.ui.diag

import app.thebox.core.diag.StreamSample
import app.thebox.core.diag.StreamSampler
import app.thebox.core.playback.PlaybackEventType
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The connection test's Stream-speed step (docs/design/m5-setup-settings.md §3.11.2): when a live channel of the
 * tested playlist is playing, samples `PlaybackController.stats` every [STEP_MS] for the requested time. Delivered =
 * the mean data-source bitrate; needed = the channel's media bitrate = delivered × T / media received, where media
 * received = time actually played + Δbuffer (a stalled player plays nothing and only buffers); drops = rebuffers in
 * the window, plus one when the buffer drained by more than 2 s. No third-party speed test (§8 Q9).
 */
class PlaybackStreamSampler @Inject constructor(private val controller: PlaybackController) : StreamSampler {
    override suspend fun sample(playlistId: Long, durationMs: Long): StreamSample? = withContext(Dispatchers.Main) {
        val st = controller.state.value
        val target = st.target
        if (st !is PlaybackState.Playing || target == null || target.isArchive || target.identity?.playlistId != playlistId) return@withContext null
        controller.setStatsEnabled(true)
        try {
            val startEvents = controller.diagnostics.events().count { it.type == PlaybackEventType.REBUFFER }
            val first = controller.stats.value
            var sum = 0L
            var n = 0
            var elapsed = 0L
            var playedMs = 0L
            while (elapsed < durationMs) {
                delay(STEP_MS)
                elapsed += STEP_MS
                val now = controller.state.value
                if (now.target?.tuneId != target.tuneId) return@withContext null
                if (now is PlaybackState.Playing && !now.paused) playedMs += STEP_MS
                val s = controller.stats.value
                if (s.streamBitrateBps > 0) {
                    sum += s.streamBitrateBps
                    n++
                }
            }
            val last = controller.stats.value
            val delivered = if (n > 0) sum / n else 0L
            val deltaBufferMs = last.bufferMs - first.bufferMs
            // The channel's media bitrate: bits delivered per second of media received (played + newly buffered).
            val mediaMs = (playedMs + deltaBufferMs).coerceAtLeast(MIN_WINDOW_MS)
            val needed = if (delivered > 0) delivered * durationMs / mediaMs else 0L
            val drops = controller.diagnostics.events().count { it.type == PlaybackEventType.REBUFFER } - startEvents
            StreamSample(deliveredBps = delivered, neededBps = needed, bufferDrops = drops.coerceAtLeast(0) + if (deltaBufferMs < -DRAIN_AS_DROP_MS) 1 else 0)
        } finally {
            controller.setStatsEnabled(false)
        }
    }

    companion object {
        const val STEP_MS = 500L
        const val MIN_WINDOW_MS = 1_000L
        /** A buffer that shrank by more than 2 s in the window counts as one drop even without a rebuffer yet. */
        const val DRAIN_AS_DROP_MS = 2_000L
    }
}

/** WP-E's `:ui` bindings: the sampler `:data`'s connection test takes as an optional binding. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagUiModule {
    @Binds abstract fun streamSampler(impl: PlaybackStreamSampler): StreamSampler
}
