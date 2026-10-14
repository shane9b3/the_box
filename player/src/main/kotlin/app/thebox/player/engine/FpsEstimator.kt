package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener

/** `VideoFrameMetadataListener` → fps from presentation-time deltas (§3.9.3). */
@OptIn(UnstableApi::class)
internal class FpsEstimator : VideoFrameMetadataListener {
    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?): Unit =
        TODO("WP-C")
}
