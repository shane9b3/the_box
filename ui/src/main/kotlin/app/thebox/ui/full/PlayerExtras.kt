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
    /**
     * (U-S12, code review 2026-10-16) `playback.audioOutput` is "Always passthrough": the stats overlay's audio line says
     * so and where Back to Auto is (B2). Hosts without settings answer false.
     */
    val audioOutputForced: StateFlow<Boolean> get() = NOT_FORCED

    /** (M6 time-shift, docs/design/m6-timeshift.md §11.5) The paused lines' and the dim card's values; default: none. */
    val pausedFacts: StateFlow<PausedFacts> get() = NO_PAUSED_FACTS

    /** (M6 §13, U-N3) The stats overlay's rewind line ("Rewind: 18:20 saved of 30:00 · 1.1 GB"); null = none. */
    val rewindStat: StateFlow<String?> get() = NO_REWIND_STAT

    /**
     * (Score bug commit 0, docs/design/m6-score-bug.md §4.4, A-B2) A text track is selected and enabled: a bottom-corner
     * score bug shows at the top corner on the same side. Default: none.
     */
    val textTrackOn: StateFlow<Boolean> get() = NO_TEXT_TRACK

    /** (Score bug commit 0, §4.4, §4.7, A-B2) What `ScoreBug` reads; null = no score feature in this host. */
    val scoreBug: app.thebox.core.sports.ScoreBugSource? get() = null
}

/**
 * (M6 §11.5) What the paused header line and the dim card read beyond `PlayerUiState`: [mediaKeys] picks "Press OK or ▶"
 * over "Press OK" (product line 94); [dimmed] since [dimSinceMs] draws the dim layer and the drifting card;
 * [pauseRemainingMs] is "Can stay paused N more min"; [windowMs] "rewind holds 30 min"; [holdMs] "after 10 min".
 */
data class PausedFacts(
    val mediaKeys: Boolean = false,
    val dimmed: Boolean = false,
    val dimSinceMs: Long? = null,
    val pauseRemainingMs: Long? = null,
    val windowMs: Long? = null,
    val holdMs: Long? = null,
)

private val NOT_FORCED: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
private val NO_PAUSED_FACTS: StateFlow<PausedFacts> = kotlinx.coroutines.flow.MutableStateFlow(PausedFacts())
private val NO_REWIND_STAT: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)
private val NO_TEXT_TRACK: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
