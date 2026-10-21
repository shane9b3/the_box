// Contract (M6 time-shift commit 0): docs/design/m6-timeshift.md §16.3, §9.1. Verbatim; changes go through the lead.
package app.thebox.core.catchup.archive

import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.TimeRanges

/**
 * The local buffer as the router sees it, in the **programme frame** (§9.2): [fromMs] = P(oldest RAP), [liveEdgeMs] =
 * P(live), [gaps] = the parts that were not saved.
 */
data class LocalWindow(val fromMs: Long, val liveEdgeMs: Long, val gaps: TimeRanges)

/** Where a seek target or a gap goes (§9.1). Times are programme frame. */
sealed interface TimelineTarget {
    /** Inside the local buffer; [direction] < 0 floors to a RAP, > 0 ceils (A-S4). */
    data class Local(val programmeMs: Long, val direction: Int) : TimelineTarget
    /** M4's live-anchored archive request (`backMs = edge − target`); ends the ring. */
    data class Provider(val backMs: Long) : TimelineTarget
    /** Older than the buffer on a channel without archive: land on [programmeMs] and toast [reason]. */
    data class Clamp(val programmeMs: Long, val reason: ClampReason) : TimelineTarget
    /** Within LIVE_SNAP_MS of the edge (direction-aware, M4 P-S1). */
    data object Live : TimelineTarget
}

/** The toast of a [TimelineTarget.Clamp]: REWIND_START, REWIND_NOTHING_YET or REWIND_LIMITED_TODAY. */
enum class ClampReason { START, NOTHING_YET, LIMITED_TODAY }

/** Pure routing between the local buffer and the provider archive (§9.1). Implemented by `TimelineRouterImpl` (WP-C). */
interface TimelineRouter {
    fun route(targetMs: Long, playheadMs: Long, local: LocalWindow?, providerFromMs: Long?, liveEdgeMs: Long, sinceTuneMs: Long, limitedToday: Boolean): TimelineTarget
    /** (A-C5) Provider only when [timeShiftActive] and ([stop] != null or [gapMs] ≥ GAP_PROVIDER_MIN_MS) on an archive channel. */
    fun onGap(gapStartMs: Long, gapMs: Long, stop: RecordStop?, timeShiftActive: Boolean, hasArchive: Boolean, liveEdgeMs: Long): TimelineTarget
}
