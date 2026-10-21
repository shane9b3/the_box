// Contract (M4 commit 0, review B1; integration 2026-10-15: earlier own cycles count as owned).
package app.thebox.core.catchup.archive

import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState

/** What both machines and the mini player show while an archive session owns playback (§3.8.2). Pure. */
object ArchiveVisibility {
    private val HANDOFF = setOf(ArchivePhase.SEEKING, ArchivePhase.CONTINUING, ArchivePhase.PROBING)

    /**
     * While a session is active, an archive state of the session's channel is judged as owned whatever its tuneId:
     * `tuneId ≥ ownedTuneId` is the current cycle (or a later re-prepare of it), and a **lower** tuneId is the session's
     * own previous cycle, which the controller still reports for one emission after `ArchivePlaybackImpl` raised
     * `ownedTuneId` in the same turn as `tuneArchive` (e.g. the `Error(CATCHUP_NOT_IN_ARCHIVE)` of the variant that just
     * failed; integration 2026-10-15, found by `PlayerViewModelTest.probeNeverShowsTheCard`). A zap away changes the
     * identity and a live tune clears `isArchive`, so neither is hidden.
     */
    fun visible(tuned: TunedState, status: ArchiveStatus?): TunedState {
        if (status == null || status.phase == ArchivePhase.IDLE || status.phase == ArchivePhase.FAILED) return tuned
        // (M6 time-shift commit 0, §11.4) A LOCAL session's re-prepares are live tunes: hide their Connecting like a provider hand-off's.
        if ((!tuned.isArchive && status.source != TimelineSource.LOCAL) || tuned.identityHash != status.identityHash) return tuned
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
