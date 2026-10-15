package app.thebox.ui.full

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.guide.ProgrammeInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * What the drawn state needs beyond the `PlayerHost` contract (lead rulings 2026-10-15, no contract change);
 * `PlayerViewModel` implements it and `FullScreenHost` reads it when its host does.
 */
interface PlayerExtras {
    /** `ArchivePlayback.status.value.failure` while the session is FAILED: the reason behind `ErrorCardState.archiveFailed`. */
    val archiveFailure: StateFlow<ArchiveFailure?>
    /**
     * Full facts (subtitle, description, genre, episode) of the info bar's focused programme, from `ProgrammeFeed.at`
     * (the `around` rows are title-only); null while loading or for another programme.
     */
    val focusedDetail: StateFlow<ProgrammeInfo?>
}
