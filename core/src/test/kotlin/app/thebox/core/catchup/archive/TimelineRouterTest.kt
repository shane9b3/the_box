package app.thebox.core.catchup.archive

import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.TimeRanges
import app.thebox.core.playback.timeshift.TimeshiftConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Every row of docs/design/m6-timeshift.md §9.1 (`TimelineRouterImpl`), the A-C5 gap rule, and the frames of §9.2
 * (A-B4): the router compares like with like whatever the correction and the skew, because every input is in P.
 */
class TimelineRouterTest {
    private val r = TimelineRouterImpl()
    private val edge = 10_000_000_000L
    private val min = 60_000L
    /** 20 min saved, nothing missing. */
    private val window = LocalWindow(edge - 20 * min, edge, TimeRanges.EMPTY)
    private val providerFrom = edge - 3 * 86_400_000L

    private fun route(target: Long, playhead: Long = edge, local: LocalWindow? = window, provider: Long? = null, sinceTune: Long = 600_000, limited: Boolean = false) =
        r.route(target, playhead, local, provider, edge, sinceTune, limited)

    @Test fun insideTheBufferIsLocalWithTheDirection() {
        assertEquals(TimelineTarget.Local(edge - 10 * min, -1), route(edge - 10 * min))
        assertEquals(TimelineTarget.Local(edge - 10 * min, -1), route(edge - 10 * min, provider = providerFrom), "local first on catch-up channels")
        assertEquals(TimelineTarget.Local(edge - 10 * min, 1), route(edge - 10 * min, playhead = edge - 15 * min), "forward: ceil")
        val margin = window.fromMs + TimeshiftConstants.LOCAL_MARGIN_MS
        assertEquals(TimelineTarget.Local(margin, -1), route(margin))
    }

    @Test fun liveSnapIsDirectionAware() {
        assertEquals(TimelineTarget.Live, route(edge))
        assertEquals(TimelineTarget.Live, route(edge + 5_000), "past the edge")
        assertEquals(TimelineTarget.Live, route(edge - 20_000, playhead = edge - 60_000), "forward into the snap")
        assertEquals(TimelineTarget.Local(edge - 20_000, -1), route(edge - 20_000, playhead = edge), "back 20 s from live is a rewind (M4 P-S1)")
    }

    @Test fun olderThanTheBufferGoesToTheProviderOnCatchUpChannels() {
        val t = edge - 40 * min
        assertEquals(TimelineTarget.Provider(edge - t), route(t, provider = providerFrom))
        assertEquals(TimelineTarget.Provider(edge - t), route(t, local = null, provider = providerFrom), "no buffer: M4")
        assertEquals(TimelineTarget.Provider(edge - providerFrom), route(providerFrom - 10 * min, provider = providerFrom), "clamped to the archive start")
        val inMargin = window.fromMs + 1_000
        assertEquals(TimelineTarget.Provider(edge - inMargin), route(inMargin, provider = providerFrom), "within LOCAL_MARGIN_MS of the start: the provider")
    }

    @Test fun olderThanTheBufferClampsWithoutCatchUp() {
        val t = edge - 40 * min
        assertEquals(TimelineTarget.Clamp(window.fromMs, ClampReason.START), route(t))
        assertEquals(TimelineTarget.Clamp(window.fromMs, ClampReason.NOTHING_YET), route(t, sinceTune = TimeshiftConstants.REWIND_NOTHING_YET_MS - 1))
        assertEquals(TimelineTarget.Clamp(window.fromMs, ClampReason.START), route(t, sinceTune = TimeshiftConstants.REWIND_NOTHING_YET_MS))
        assertEquals(TimelineTarget.Clamp(window.fromMs, ClampReason.LIMITED_TODAY), route(t, limited = true))
        assertEquals(TimelineTarget.Clamp(edge - min, ClampReason.START), route(t, playhead = edge - min, local = null), "no buffer, no archive: stay")
    }

    /**
     * Fix pass, UX review S3: a target inside a not-saved range (saving stopped while paused, 21:10–21:30) never floors
     * to the RAP before it: the provider at the target on a catch-up channel, else the range's end (ceiled).
     */
    @Test fun targetInsideAGap() {
        val gapFrom = edge - 15 * min
        val gapTo = edge - 5 * min
        val gapped = LocalWindow(edge - 20 * min, edge, TimeRanges(longArrayOf(gapFrom, gapTo)))
        val t = edge - 10 * min
        assertEquals(TimelineTarget.Provider(edge - t), route(t, local = gapped, provider = providerFrom), "with archive: the provider at the target")
        assertEquals(TimelineTarget.Local(gapTo, 1), route(t, local = gapped), "without archive: the gap's end")
        assertEquals(TimelineTarget.Local(gapTo, 1), route(gapFrom, local = gapped), "the gap's first instant")
        assertEquals(TimelineTarget.Local(gapTo, -1), route(gapTo, local = gapped), "the end itself is saved: an ordinary local seek")
        assertEquals(TimelineTarget.Local(gapFrom - min, -1), route(gapFrom - min, local = gapped), "before the gap: local")
        // Saving stopped and never resumed: the range reaches the live edge, so the target goes live.
        val open = LocalWindow(edge - 20 * min, edge, TimeRanges(longArrayOf(gapFrom, edge)))
        assertEquals(TimelineTarget.Live, route(t, local = open))
    }

    /** A-C5: provider only during an active time-shift on a catch-up channel, after a saving stop or a ≥ 30 s gap. */
    @Test fun gapRule() {
        val start = edge - 10 * min
        val provider = TimelineTarget.Provider(edge - start)
        assertEquals(provider, r.onGap(start, 12 * min, RecordStop.FULL_WHILE_PAUSED, timeShiftActive = true, hasArchive = true, liveEdgeMs = edge))
        assertEquals(provider, r.onGap(start, 2_000, RecordStop.SLOT_LOST, true, true, edge), "saving stopped: provider whatever the size")
        assertEquals(provider, r.onGap(start, TimeshiftConstants.GAP_PROVIDER_MIN_MS, null, true, true, edge))
        val local = TimelineTarget.Local(start + 5_000, 1)
        assertEquals(local, r.onGap(start, 5_000, null, true, true, edge), "a short reconnect hiccup stays local")
        assertEquals(TimelineTarget.Local(start + 12 * min, 1), r.onGap(start, 12 * min, RecordStop.HOLD_LIMIT, timeShiftActive = false, hasArchive = true, liveEdgeMs = edge), "a viewer at live is never pushed into the archive")
        assertEquals(TimelineTarget.Local(start + 12 * min, 1), r.onGap(start, 12 * min, RecordStop.HOLD_LIMIT, true, hasArchive = false, liveEdgeMs = edge), "no catch-up: skip")
    }

    /**
     * §9.2 (A-B4): with `correctionMs = −3,600,000` and `skewMs = +90,000`, P = wall + skew + correction for the buffer
     * and the provider alike, so the verdicts equal those of an uncorrected channel shifted by the same amount.
     */
    @Test fun framesWithCorrectionAndSkew() {
        val frames = ArchiveFrames(correctionMs = -3_600_000, skewMs = 90_000, displayShiftMs = 0)
        val wallNow = 1_760_000_000_000L
        val pEdge = frames.liveEdge(wallNow)
        val pFrom = frames.liveEdge(wallNow - 20 * min)
        val w = LocalWindow(pFrom, pEdge, TimeRanges.EMPTY)
        val pProvider = pEdge - 3 * 86_400_000L
        assertEquals(TimelineTarget.Local(pEdge - 5 * min, -1), r.route(pEdge - 5 * min, pEdge, w, pProvider, pEdge, 600_000, false))
        assertEquals(TimelineTarget.Provider(40 * min), r.route(pEdge - 40 * min, pEdge, w, pProvider, pEdge, 600_000, false), "a live-anchored distance, frame-free (M4 B2)")
        assertEquals(TimelineTarget.Live, r.route(pEdge - 10_000, pEdge - 20 * min, w, pProvider, pEdge, 600_000, false))
    }

    /** A-C1: `TimeRanges` compares by content, so a republished status with equal ranges is equal. */
    @Test fun timeRangesContentEquality() {
        val a = TimeRanges(longArrayOf(1, 2, 3, 4))
        val b = TimeRanges(longArrayOf(1, 2, 3, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, TimeRanges(longArrayOf(1, 2)))
        assertEquals(ArchiveStatus(notSaved = a, source = TimelineSource.LOCAL), ArchiveStatus(notSaved = b, source = TimelineSource.LOCAL))
        assertEquals(2, a.count); assertEquals(3, a.startAt(1)); assertEquals(4, a.endAt(1))
    }
}
