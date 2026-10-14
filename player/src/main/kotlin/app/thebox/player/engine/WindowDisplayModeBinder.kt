package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import android.app.Activity
import app.thebox.player.api.PlaybackController

/**
 * Applies `PlaybackController.displayModeRequest` as the Activity window's `preferredDisplayModeId` (API < 31 AFR,
 * §3.9.2). Used by `MainActivity` and the debug harness.
 */
class WindowDisplayModeBinder(private val controller: PlaybackController) {
    /** Binds to [activity]'s window until it is destroyed. */
    fun bind(activity: Activity): Unit = TODO("WP-C")
}
