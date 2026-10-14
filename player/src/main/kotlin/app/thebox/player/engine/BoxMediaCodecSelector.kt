package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** HW/SW ordering, per-channel override, per-MIME software-audio gate (§3.8.1). */
@OptIn(UnstableApi::class)
internal class BoxMediaCodecSelector : MediaCodecSelector {
    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> =
        TODO("WP-C")
}
