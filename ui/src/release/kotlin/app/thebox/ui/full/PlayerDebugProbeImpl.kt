package app.thebox.ui.full

import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.full.PlayerStateMachine

/** Release builds (docs/design/m4-player-ux.md §1.6, §2.8): a no-op; nothing holds the machine or the archive session. */
class PlayerDebugProbeImpl(@Suppress("UNUSED_PARAMETER") archive: ArchivePlayback?) : PlayerDebugProbe {
    override val current: PlayerStateMachine? get() = null
    override val archive: ArchivePlayback? get() = null
    override fun register(machine: PlayerStateMachine?) = Unit
}
