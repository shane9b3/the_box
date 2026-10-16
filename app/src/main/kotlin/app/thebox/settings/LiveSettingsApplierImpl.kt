package app.thebox.settings

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.common.BoxLog
import app.thebox.player.api.PlaybackController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies REPREPARE-class settings once when Settings closes (docs/design/m5-setup-settings.md §2.12 item 6, S9;
 * `SettingsEffects.onClosed` calls it at most once per visit, only when such a row changed):
 * - an **archive** session that is playing is re-requested at the playhead through [ArchivePlayback.seekTo] (the
 *   archive layer re-requests, or seeks natively within the current request; a native seek keeps the old pipeline
 *   until the next re-request, see §1.4a). A paused archive session is left paused: the change applies when it resumes
 *   (a resume after a long pause re-requests anyway, and leaving Settings must not start playback);
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
                // seq 0: never advances the player machine's scrub sequence (ArchivePlayback keeps the max it saw).
                archive.seekTo(playhead, 0)
            }
            ArchivePhase.PAUSED, ArchivePhase.PAUSED_LIVE -> BoxLog.i(TAG) { "reapply: archive paused; applies on resume" }
            ArchivePhase.IDLE, ArchivePhase.FAILED -> controller.reapplySettings()
        }
    }

    private companion object {
        const val TAG = "LiveSettingsApplier"
    }
}
