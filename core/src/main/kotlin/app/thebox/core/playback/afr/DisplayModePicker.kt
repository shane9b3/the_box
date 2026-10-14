package app.thebox.core.playback.afr

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.DisplayModeInfo
import app.thebox.core.playback.DisplayModePicker

/** Mode choice and fps snapping (§3.9). */
class DisplayModePickerImpl : DisplayModePicker {
    override fun snapFps(fps: Float): Float? = TODO("WP-A")
    override fun pick(current: DisplayModeInfo, supported: List<DisplayModeInfo>, fps: Float): DisplayModeInfo? = TODO("WP-A")
}
