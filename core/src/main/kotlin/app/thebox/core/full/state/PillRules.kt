package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.PillState
import app.thebox.core.guide.TunedState

/**
 * WP-A stub (docs/design/m4-player-ux.md §1.2, §3.2.3): the state pill for both machines and the mini player. [tuned]
 * is always the visible state (ArchiveVisibility).
 */
object PillRules {
    fun pill(tuned: TunedState, status: ArchiveStatus?, nowMs: Long): PillState = TODO("WP-A")
}
