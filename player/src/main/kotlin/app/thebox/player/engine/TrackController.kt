package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import app.thebox.player.api.TrackInfo
import app.thebox.player.internal.PlayerBundle

/** `TrackInfo` mapping, selection, preferences, HDR poster track disable (§3.12). */
internal class TrackController(private val bundle: PlayerBundle) {
    fun current(): TrackInfo = TODO("WP-C")
}
