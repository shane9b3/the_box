// Contract (M4 commit 0, review B1).
package app.thebox.core.catchup.archive

import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState

/** What both machines and the mini player show while an archive session owns playback (§3.8.2). Pure. */
object ArchiveVisibility {
    private val HANDOFF = setOf(ArchivePhase.SEEKING, ArchivePhase.CONTINUING, ArchivePhase.PROBING)

    fun visible(tuned: TunedState, status: ArchiveStatus?): TunedState {
        if (status == null || status.phase == ArchivePhase.IDLE || status.phase == ArchivePhase.FAILED) return tuned
        if (!tuned.isArchive || tuned.identityHash != status.identityHash || tuned.tuneId < status.ownedTuneId) return tuned
        val waiting = tuned.phase == MiniPhase.ERROR || tuned.phase == MiniPhase.CONNECTING || tuned.phase == MiniPhase.BUFFERING
        if (!waiting) return tuned
        val handoff = status.phase in HANDOFF || (status.phase == ArchivePhase.PLAYING && tuned.phase == MiniPhase.ERROR)
        return tuned.copy(
            phase = if (handoff) MiniPhase.PLAYING else MiniPhase.CONNECTING,
            error = null,
            errorDetail = null,
            handoff = handoff,
        )
    }
}
