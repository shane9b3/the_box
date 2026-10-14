package app.thebox.player.controller

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import androidx.media3.common.PlaybackException
import app.thebox.core.playback.FailureSignal

/** `PlaybackException` + last load error → [FailureSignal] (§4.1). */
internal class FailureClassifier {
    fun classify(error: PlaybackException, lastLoadError: Throwable?): FailureSignal = TODO("WP-D")
}
