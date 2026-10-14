package app.thebox.ui.playback

import androidx.annotation.StringRes
import app.thebox.core.playback.PlaybackErrorCode

/** WP-C stub (docs/design/m3-guide.md §1.4, §3.13.4): headline and next-step line for every `PlaybackErrorCode`. */
object ErrorText {
    @StringRes fun headline(code: PlaybackErrorCode): Int = TODO("WP-C")
    @StringRes fun step(code: PlaybackErrorCode): Int = TODO("WP-C")
}
