// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.12 item 6 (S9). Verbatim; changes go through the lead.
package app.thebox.core.catchup.archive

/**
 * (reviewed 2026-10-16, S9) Applies REPREPARE-class settings once when the user leaves Settings by any route
 * (`SettingsEffects.onClosed`). Implemented in `:app` by `LiveSettingsApplierImpl` (WP-A): when
 * `ArchivePlayback.status.phase` is active it calls `archive.resume()` at the playhead (a re-request with the new
 * settings), else `PlaybackController.reapplySettings()`. Next to [ArchivePlayback] because the archive case cannot live
 * in `:player` (`ArchivePlaybackImpl` is in `:app`).
 */
fun interface LiveSettingsApplier {
    fun apply()
}
