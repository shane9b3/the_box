// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.8.
package app.thebox.ui.full

/** The live player machine for the instrumented suite (debug holder; release no-op), like M3's GuideDebugProbe. */
interface PlayerDebugProbe {
    fun register(machine: app.thebox.core.full.PlayerStateMachine?)
    val current: app.thebox.core.full.PlayerStateMachine?
    val archive: app.thebox.core.catchup.archive.ArchivePlayback?
}
