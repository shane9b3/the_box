// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.5.
package app.thebox.ui.full

import androidx.media3.common.text.Cue
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.ui.root.ShellEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What FullScreenHost and AppRoot need from the player view model. Main thread only. */
interface PlayerHost {
    val machine: PlayerStateMachine
    /** The channel list's rows (main-only reads) and its "now" cache. */
    val listRows: GuideRows
    fun nowOf(row: GuideChannel): ProgrammeInfo?
    /** Current cues (FULL only; empty otherwise). */
    val cues: StateFlow<List<Cue>>
    /** Notice toasts and player toasts, buffered until collected (M3 H-B3). */
    val events: Flow<ShellEvent>
}
