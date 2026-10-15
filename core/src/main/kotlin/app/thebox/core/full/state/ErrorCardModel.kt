package app.thebox.core.full.state

import app.thebox.core.full.ErrorCardState
import app.thebox.core.guide.GuideChannel
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.VideoDecoderMode

/** WP-A stub (docs/design/m4-player-ux.md §1.2, §3.7): `PlaybackError` → buttons + the first text step. */
object ErrorCardModel {
    /** [variantLeft]: ArchivePlayback has another catch-up variant; [decoder]: the channel's decoder override. */
    fun card(
        error: PlaybackError,
        channel: GuideChannel?,
        decoder: VideoDecoderMode?,
        variantLeft: Boolean,
        archiveFailed: Boolean,
    ): ErrorCardState = TODO("WP-A")
}
