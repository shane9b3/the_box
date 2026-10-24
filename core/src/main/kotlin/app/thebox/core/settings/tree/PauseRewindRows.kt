// Storage locations commit 0 (docs/design/m6-storage-locations.md §9.1, §9.5, §12.4).
package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.EffectTiming.NEXT_TUNE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.Visibility.ADVANCED
import app.thebox.core.settings.Visibility.SIMPLE

/**
 * The "Pause and rewind" sub-page of Playback (`SettingsRoute.PauseRewind`, §9.1), reached by
 * [PlaybackRows.PAUSE_REWIND]. Listed in `SimpleRowCountTest.SUB_PAGE_SOURCES`: the link counts as Playback's one simple
 * row (the M5 §3.3.13 amendment, §9.1 "Budget"). The ids are the M6 time-shift ones, unchanged, so stored preferences
 * and `GuideDebugReceiver` keep working.
 *
 * Owner "A" while `WpaPages` builds the page (commit 0; `WpaPages.apply` handles only owner "A" rows); WP-S4 moves the
 * page to `StoragePages`. "Save to" is owner "S4" from the start: commit 0 builds no row for it.
 */
object PauseRewindRows : RowSource {
    /** "Rewind length" (was "Pause and rewind"): SIMPLE, NEXT_TUNE (Off deletes the file at once). */
    val TIMESHIFT = RowId("playback.timeshift")
    /** "Save to": a SIMPLE Link to `SaveLocation`, NEXT_TUNE. */
    val TIMESHIFT_LOCATION = RowId("playback.timeshift_location")
    /** "Keep recording while paused": ADVANCED, LIVE. */
    val TIMESHIFT_HOLD = RowId("playback.timeshift_hold")
    /** "Protect built-in storage": ADVANCED, LIVE. */
    val TIMESHIFT_PROTECT = RowId("playback.timeshift_protect")

    override val rows: List<RowSpec> = listOf(
        a(TIMESHIFT, SIMPLE, NEXT_TUNE),
        // Owner "S4": WP-S4 builds it (StoragePages); commit 0 does not show it, so the app behaves as before.
        RowSpec(TIMESHIFT_LOCATION, SectionId.PLAYBACK, SIMPLE, NEXT_TUNE, "S4"),
        a(TIMESHIFT_HOLD, ADVANCED, LIVE),
        a(TIMESHIFT_PROTECT, ADVANCED, LIVE),
    )

    private fun a(id: RowId, v: Visibility, e: EffectTiming) = RowSpec(id, SectionId.PLAYBACK, v, e, "A")
}
