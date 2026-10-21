// M6 time-shift, WP-C (docs/design/m6-timeshift.md §9.1, A-C5, U-B2).
package app.thebox.core.catchup.archive

import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.TimeshiftConstants

/**
 * Pure routing between the device's time-shift buffer and the provider archive (§9.1). Every time is in one frame: the
 * player passes programme-frame values (§9.2); the controller's gap rule (R14) passes mono values, which is harmless
 * because it reads only whether the verdict is [TimelineTarget.Provider].
 *
 * [route], first match wins:
 * 1. at or past the live edge, or within `LIVE_SNAP_MS` of it **and** later than the playhead (direction-aware, M4
 *    P-S1) → [TimelineTarget.Live];
 * 2. (fix pass, product, 2026-10-19, UX review S3) inside a not-saved range of the buffer ([LocalWindow.gaps]): on a
 *    catch-up channel → [TimelineTarget.Provider] at the target ("a gap the buffer lost goes to the provider", U-B2);
 *    without catch-up → [TimelineTarget.Local] at the gap's end, ceiled (the player toasts GAP_SKIPPED), or
 *    [TimelineTarget.Live] when the gap reaches the live edge. Never the RAP before the gap (§6 floors a backward seek);
 * 3. inside the buffer: at least `LOCAL_MARGIN_MS` after its oldest saved instant → [TimelineTarget.Local], floored
 *    for a backward target and ceiled for a forward one (A-S4);
 * 4. older than the buffer (or no buffer) on a catch-up channel → [TimelineTarget.Provider] with the live-anchored
 *    distance `edge − max(target, providerFrom)` (M4 B2);
 * 5. otherwise → [TimelineTarget.Clamp] on the oldest saved instant (the playhead when there is no buffer), with the
 *    reason: NOTHING_YET within `REWIND_NOTHING_YET_MS` of tuning, LIMITED_TODAY when the daily cap shortened the
 *    window, else START.
 *
 * [onGap] (A-C5): the provider only on a catch-up channel during an active time-shift, and only when saving stopped
 * ([stop] != null) or the gap is at least `GAP_PROVIDER_MIN_MS`; otherwise the next segment, locally.
 */
class TimelineRouterImpl : TimelineRouter {
    override fun route(
        targetMs: Long,
        playheadMs: Long,
        local: LocalWindow?,
        providerFromMs: Long?,
        liveEdgeMs: Long,
        sinceTuneMs: Long,
        limitedToday: Boolean,
    ): TimelineTarget {
        if (targetMs >= liveEdgeMs) return TimelineTarget.Live
        if (targetMs >= liveEdgeMs - TimeshiftConstants.LIVE_SNAP_MS && targetMs > playheadMs) return TimelineTarget.Live
        if (local != null) {
            val gap = gapAt(local.gaps, targetMs)
            if (gap >= 0) {
                if (providerFromMs != null) return TimelineTarget.Provider(backMs = (liveEdgeMs - maxOf(targetMs, providerFromMs)).coerceAtLeast(0L))
                val end = local.gaps.endAt(gap)
                return if (end >= liveEdgeMs) TimelineTarget.Live else TimelineTarget.Local(end, 1)
            }
        }
        if (local != null && targetMs >= local.fromMs + TimeshiftConstants.LOCAL_MARGIN_MS) {
            return TimelineTarget.Local(targetMs, if (targetMs > playheadMs) 1 else -1)
        }
        if (providerFromMs != null) return TimelineTarget.Provider(backMs = (liveEdgeMs - maxOf(targetMs, providerFromMs)).coerceAtLeast(0L))
        val reason = when {
            sinceTuneMs < TimeshiftConstants.REWIND_NOTHING_YET_MS -> ClampReason.NOTHING_YET
            limitedToday -> ClampReason.LIMITED_TODAY
            else -> ClampReason.START
        }
        return TimelineTarget.Clamp(local?.fromMs ?: playheadMs, reason)
    }

    /** The index of the not-saved range of [gaps] holding [t] (`start ≤ t < end`), or −1. */
    private fun gapAt(gaps: app.thebox.core.playback.timeshift.TimeRanges, t: Long): Int {
        for (i in 0 until gaps.count) if (t >= gaps.startAt(i) && t < gaps.endAt(i)) return i
        return -1
    }

    override fun onGap(gapStartMs: Long, gapMs: Long, stop: RecordStop?, timeShiftActive: Boolean, hasArchive: Boolean, liveEdgeMs: Long): TimelineTarget =
        if (hasArchive && timeShiftActive && (stop != null || gapMs >= TimeshiftConstants.GAP_PROVIDER_MIN_MS)) {
            TimelineTarget.Provider(backMs = (liveEdgeMs - gapStartMs).coerceAtLeast(0L))
        } else {
            TimelineTarget.Local(gapStartMs + gapMs, 1)
        }
}
