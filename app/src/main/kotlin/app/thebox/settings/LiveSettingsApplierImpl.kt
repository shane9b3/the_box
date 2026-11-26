package app.thebox.settings

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.catchup.archive.PanelApplyResult
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.common.BoxLog
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies REPREPARE-class settings once when Settings closes (docs/design/m5-setup-settings.md §2.12 item 6, S9;
 * `SettingsEffects.onClosed` calls it at most once per visit, only when such a row changed):
 * - an **archive** session that is playing is re-requested at the playhead through [ArchivePlayback.seekTo] (the
 *   archive layer re-requests, or seeks natively within the current request; a native seek keeps the old pipeline
 *   until the next re-request, see §1.4a). A paused archive session is left paused: the change applies when it resumes
 *   (a resume after a long pause re-requests anyway, and leaving Settings must not start playback). A session paused
 *   in the **local** rewind buffer is re-prepared now through [ArchivePlayback.reapply] and stays paused (its resume
 *   would only continue the reader);
 * - otherwise [PlaybackController.reapplySettings] re-prepares the live tune at the live edge (a no-op when nothing
 *   live plays). Never a new engine.
 * [nowMs] is the device wall clock the archive status's `atMs` uses.
 */
@Singleton
class LiveSettingsApplierImpl internal constructor(
    private val controller: PlaybackController,
    private val archive: ArchivePlayback,
    private val nowMs: () -> Long,
) : LiveSettingsApplier {
    @Inject constructor(controller: PlaybackController, archive: ArchivePlayback) : this(controller, archive, System::currentTimeMillis)

    override fun apply() {
        val s = archive.status.value
        when (s.phase) {
            ArchivePhase.PLAYING, ArchivePhase.CONTINUING, ArchivePhase.SEEKING, ArchivePhase.STARTING, ArchivePhase.PROBING -> {
                val playhead = if (s.advancing) s.wallMs + (nowMs() - s.atMs).coerceAtLeast(0) else s.wallMs
                BoxLog.i(TAG) { "reapply: archive at the playhead" }
                // (M5 integration) An exact re-request at the playhead (ArchivePlayback.reapply amendment).
                archive.reapply(playhead)
            }
            // (Review 2026-11-09) Paused in the local rewind buffer: a resume only continues the reader (no re-request), so
            // the change would wait for the next zap or seek. `reapply` routes a LOCAL session to the controller, which
            // re-prepares the buffered reader at the pin and stays paused (M6 §7.6).
            ArchivePhase.PAUSED -> if (s.source == TimelineSource.LOCAL) {
                BoxLog.i(TAG) { "reapply: paused in the local buffer; re-preparing the reader, still paused" }
                archive.reapply(s.wallMs)
            } else {
                BoxLog.i(TAG) { "reapply: archive paused; applies on resume" }
            }
            ArchivePhase.PAUSED_LIVE -> BoxLog.i(TAG) { "reapply: archive paused; applies on resume" }
            ArchivePhase.IDLE, ArchivePhase.FAILED -> controller.reapplySettings()
        }
    }

    /**
     * (docs/design/m6-player-settings-panel.md §4.2, stage A) A REPREPARE row changed in the in-player panel. Reads the
     * controller and archive state through their public API only (no controller change) and answers what was done:
     * 1. idle or stopped → NOTHING_PLAYING, no call;
     * 2. the error card ([PlaybackState.Error]) → CARD_SHOWN, no call (`reapplySettings` is a no-op there and `retry()`
     *    would reset the re-resolve limiter, S7);
     * 3. archive phases as [apply]: playing (or starting, seeking, …) → `archive.reapply(playhead)` → REPREPARING; paused in
     *    the local rewind buffer → reader re-prepared, still paused → WAITS_FOR_RESUME; other paused → WAITS_FOR_RESUME;
     * 4. a clip or an archive target the archive layer does not own: `reapplySettings` is a no-op for those
     *    (`cycle.isVod`), so nothing is claimed → NOTHING_PLAYING, no call;
     * 5. live paused → `reapplySettings()` (it re-prepares a buffered reader and stays paused, or does nothing) →
     *    WAITS_FOR_RESUME;
     * 6. otherwise → `reapplySettings()` → REPREPARING.
     * REPREPARING means a re-prepare was asked for, not that the new value is in effect: `reapplySettings()` returns
     * `Unit`, and a per-channel choice (quick bar decoder, learned buffer, tunneling off) still wins (F13, B1; stage B).
     */
    override fun applyFromPanel(): PanelApplyResult {
        val st = controller.state.value
        if (st is PlaybackState.Idle || st is PlaybackState.Stopped) return PanelApplyResult.NOTHING_PLAYING
        if (st is PlaybackState.Error) return PanelApplyResult.CARD_SHOWN
        val s = archive.status.value
        return when (s.phase) {
            ArchivePhase.PLAYING, ArchivePhase.CONTINUING, ArchivePhase.SEEKING, ArchivePhase.STARTING, ArchivePhase.PROBING -> {
                val playhead = if (s.advancing) s.wallMs + (nowMs() - s.atMs).coerceAtLeast(0) else s.wallMs
                archive.reapply(playhead)
                PanelApplyResult.REPREPARING
            }
            ArchivePhase.PAUSED -> {
                if (s.source == TimelineSource.LOCAL) archive.reapply(s.wallMs)
                PanelApplyResult.WAITS_FOR_RESUME
            }
            ArchivePhase.PAUSED_LIVE -> PanelApplyResult.WAITS_FOR_RESUME
            ArchivePhase.IDLE, ArchivePhase.FAILED -> {
                val target = st.target
                when {
                    target == null || target.isClip || target.isArchive -> PanelApplyResult.NOTHING_PLAYING
                    st is PlaybackState.Playing && st.paused -> { controller.reapplySettings(); PanelApplyResult.WAITS_FOR_RESUME }
                    else -> { controller.reapplySettings(); PanelApplyResult.REPREPARING }
                }
            }
        }
    }

    private companion object {
        const val TAG = "LiveSettingsApplier"
    }
}
