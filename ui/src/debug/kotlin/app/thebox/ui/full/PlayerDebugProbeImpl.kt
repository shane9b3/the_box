package app.thebox.ui.full

import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.full.PlayerStateMachine

/**
 * Debug builds (docs/design/m4-player-ux.md §1.6, §2.8; owner WP-E): the live player view model registers its machine
 * here and the instrumented suite reads it (and the archive session) like M3's GuideDebugProbe. Commit 0 ships the
 * plain holder.
 */
class PlayerDebugProbeImpl(override val archive: ArchivePlayback?) : PlayerDebugProbe {
    @Volatile override var current: PlayerStateMachine? = null
        private set

    override fun register(machine: PlayerStateMachine?) {
        current = machine
    }
}
