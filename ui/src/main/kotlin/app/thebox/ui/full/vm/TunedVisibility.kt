package app.thebox.ui.full.vm

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.ArchiveVisibility
import app.thebox.core.guide.TunedState

/**
 * `ArchiveVisibility.visible` (B1) as both view models apply it (docs/design/m4-player-ux.md §2.3a), plus one guard the
 * contract lacks: `ArchivePlaybackImpl` raises `ownedTuneId` to the next cycle **before** `tuneArchive`, so for one
 * emission the controller still reports the session's *previous* cycle (e.g. `Error(CATCHUP_NOT_IN_ARCHIVE)` of the
 * variant that just failed) with a tuneId below `ownedTuneId`. The contract rule treats any lower tuneId as foreign and
 * passes the ERROR through (found by `PlayerViewModelTest.probeNeverShowsTheCard`). While a session is active, an
 * archive state of the **same channel** with a lower tuneId is the session's own earlier cycle (a zap away changes the
 * identity, a live tune clears `isArchive`), so it is judged as if owned. Reported to the lead as a contract problem.
 */
object TunedVisibility {
    fun visible(mapped: TunedState, status: ArchiveStatus?): TunedState {
        if (status == null || status.phase == ArchivePhase.IDLE || status.phase == ArchivePhase.FAILED) return mapped
        val earlierOwnCycle = mapped.isArchive && mapped.identityHash == status.identityHash && mapped.tuneId < status.ownedTuneId
        val judged = if (earlierOwnCycle) mapped.copy(tuneId = status.ownedTuneId) else mapped
        val v = ArchiveVisibility.visible(judged, status)
        return if (earlierOwnCycle) v.copy(tuneId = mapped.tuneId) else v
    }
}
