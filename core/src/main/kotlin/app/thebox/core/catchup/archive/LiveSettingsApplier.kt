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

    /**
     * (Settings panel commit 0, docs/design/m6-player-settings-panel.md §4.2; additive) A REPREPARE row changed in the
     * in-player panel (`SettingsEffectsImpl.scheduleApply`, 400 ms after the change). Reads the controller and archive
     * state (no controller change) and answers what happened, for the panel's notice:
     * 1. idle or stopped → [PanelApplyResult.NOTHING_PLAYING], no call;
     * 2. the error card (`PlaybackState.Error`) → [PanelApplyResult.CARD_SHOWN], no call (no `retry()`, S7);
     * 3. archive phases as [apply]: playing → `archive.reapply(playhead)` → REPREPARING; paused → WAITS_FOR_RESUME;
     * 4. live paused → `reapplySettings()` → WAITS_FOR_RESUME;
     * 5. otherwise `reapplySettings()` → REPREPARING.
     * Stage A never claims the change took effect (B1). Default (test lambdas): [apply], then REPREPARING.
     */
    fun applyFromPanel(): PanelApplyResult { apply(); return PanelApplyResult.REPREPARING }
}

/**
 * (Settings panel commit 0, docs/design/m6-player-settings-panel.md §4.2; append-only: stage B adds APPLIED,
 * CHANNEL_OVERRIDE, RESTARTED, WAITS_FOR_LIVE, OTHER_PLAYLIST) What [LiveSettingsApplier.applyFromPanel] did. Panel notices
 * (band, 3 s): REPREPARING "Re-preparing the stream. A choice made for this channel on the quick bar still wins.";
 * WAITS_FOR_RESUME "Applies when playback resumes."; CARD_SHOWN "Saved. Close Settings and choose Retry to try it.";
 * NOTHING_PLAYING none. CARD_SHOWN does not count as an applied change (leaving Settings still applies, §4.2 "Close").
 */
enum class PanelApplyResult { REPREPARING, WAITS_FOR_RESUME, NOTHING_PLAYING, CARD_SHOWN }
