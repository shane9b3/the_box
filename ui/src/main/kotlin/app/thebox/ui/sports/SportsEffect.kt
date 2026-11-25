// Contract (Sports Hub commit 0): docs/design/m6-sports-hub.md §4.1 (A-S6), §6.1 (R-S5). Frozen; changes go through the lead.
package app.thebox.ui.sports

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.sports.hub.ResumePoint

/**
 * What the Sports page asks the guide side to do (`SportsViewModel.effects` → `MainActivity` →
 * `GuideViewModel.onSportsEffect`, WP-3).
 */
sealed interface SportsEffect {
    /** Tune [channel] through the guarded controller, then `GuideEvent.SportsClosed(channel, tuned = true, archive = false)` (§5.5). */
    data class Tune(val channel: GuideChannel) : SportsEffect
    /** Play [info] from the archive (Search's `playArchive` path), then `SportsClosed(…, archive = true)` (§5.6). */
    data class PlayArchive(val channel: GuideChannel, val info: ProgrammeInfo) : SportsEffect
    /**
     * ClipSession's resume (§6.6). [ownerTuneId] (verification review L-e, additive): the tune id that owned the player
     * when the clip ended; a non-clip tune past it made before the resume runs wins. -1 = unknown (read at delivery).
     */
    data class Resume(val point: ResumePoint, val ownerTuneId: Long = -1L) : SportsEffect
}

/** What AppRoot reads about the clip layer (§6.5). */
@Immutable
data class ClipUi(
    /** The layer is up and the hole is FULL (true only after `playClip` returned). */
    val showing: Boolean = false,
    /** The clip cycle's tune id; AppRoot keeps the hole black until a frame renders after it. */
    val tuneId: Long = -1,
)

/** (Commit 0) The `AppRoot.sportsClip` default: no clip, ever (tests and hosts without a Sports page). */
object NoSportsClip : State<ClipUi> {
    override val value: ClipUi = ClipUi()
}
