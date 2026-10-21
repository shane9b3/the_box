package app.thebox.core.full.state

import app.thebox.core.full.PausedHeader
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.rawEndMin
import app.thebox.core.playback.timeshift.RecordStop

/**
 * Pure scrub-bar arithmetic (docs/design/m4-player-ux.md §3.5.2, §3.5.3; review E21, B3). All times are programme-frame
 * epoch ms (§3.8.1); a [ProgrammeInfo] covers `[rawStartMin, rawEndMin) × 60,000` in that frame.
 */
object ScrubModel {
    /** One commit this long after the last scrub key (architecture review B5.3). */
    const val SCRUB_COMMIT_MS = 600L
    /** FF/RW step per press. */
    const val FF_RW_STEP_MS = 30_000L
    /** Without EPG the bar spans the playhead ± this (§3.5.1). */
    const val NO_EPG_HALF_SPAN_MS = 30L * 60_000L
    private const val MIN_MS = 60_000L

    /** Step for a ←/→ event held [heldMs] (E21: < 1 s 10 s, < 3 s 30 s, < 10 s 120 s, else 300 s). */
    fun stepMs(heldMs: Long): Long = when {
        heldMs < 1_000 -> 10_000L
        heldMs < 3_000 -> 30_000L
        heldMs < 10_000 -> 120_000L
        else -> 300_000L
    }

    /** FF/RW: [FF_RW_STEP_MS] per press; repeats use the table from its second row (§3.5.2). */
    fun ffRwStepMs(repeat: Int, heldMs: Long): Long = if (repeat <= 0) FF_RW_STEP_MS else stepMs(maxOf(heldMs, 1_000L))

    /** `clamp((preview ?: playhead) + deltaMs, archiveFromMs, liveEdgeMs)` (programme frame). */
    fun preview(previewMs: Long?, playheadMs: Long, deltaMs: Long, archiveFromMs: Long, liveEdgeMs: Long): Long =
        ((previewMs ?: playheadMs) + deltaMs).coerceIn(archiveFromMs, maxOf(archiveFromMs, liveEdgeMs))

    fun startMs(p: ProgrammeInfo): Long = p.rawStartMin * MIN_MS
    fun endMs(p: ProgrammeInfo): Long = p.rawEndMin * MIN_MS
    fun contains(p: ProgrammeInfo, t: Long): Boolean = t >= startMs(p) && t < endMs(p)

    /** Index of the programme of [programmes] (start order) containing [t], or −1. */
    fun indexAt(programmes: List<ProgrammeInfo>, t: Long): Int {
        for (i in programmes.indices) if (contains(programmes[i], t)) return i
        return -1
    }

    /** Bar bounds without EPG: [t − 30 min, t + 30 min] clamped to [archiveFromMs, liveEdgeMs] (§3.5.1). */
    fun noEpgBounds(t: Long, archiveFromMs: Long, liveEdgeMs: Long): LongArray {
        val hi = maxOf(archiveFromMs, liveEdgeMs)
        return longArrayOf((t - NO_EPG_HALF_SPAN_MS).coerceIn(archiveFromMs, hi), (t + NO_EPG_HALF_SPAN_MS).coerceIn(archiveFromMs, hi))
    }

    /**
     * Where SELECT on a chip (or PREV/NEXT_PROGRAMME) seeks: the programme's start, or null = live when it starts at or
     * beyond the live edge ("next beyond live → live", §3.5.3).
     */
    fun chipTarget(p: ProgrammeInfo, liveEdgeMs: Long): Long? = startMs(p).takeIf { it < liveEdgeMs }

    /**
     * (M6 §11.5) The header line of a paused LOCAL time-shift: saving ([stop] null), or why saving stopped. On a catch-up
     * channel every stop reads CATCHUP_COVERS (the provider fills the rest); storage stops have no header (their toast).
     */
    fun pausedHeader(stop: RecordStop?, catchUp: Boolean): PausedHeader? = when (stop) {
        null -> PausedHeader.SAVING
        RecordStop.FULL_WHILE_PAUSED -> if (catchUp) PausedHeader.CATCHUP_COVERS else PausedHeader.FULL
        RecordStop.HOLD_LIMIT -> if (catchUp) PausedHeader.CATCHUP_COVERS else PausedHeader.HOLD_LIMIT
        RecordStop.SLOT_LOST, RecordStop.PREEMPTED -> if (catchUp) PausedHeader.CATCHUP_COVERS else PausedHeader.SLOT_LOST
        RecordStop.UPSTREAM_FAILED, RecordStop.DISK_ERROR, RecordStop.STORAGE_EVICTED, RecordStop.DISK_TOO_SLOW, RecordStop.SESSION_END -> null
    }

    /** Merges [more] into [into] by raw start, start order, later answers replacing earlier copies. */
    fun merge(into: List<ProgrammeInfo>, more: List<ProgrammeInfo>): List<ProgrammeInfo> {
        if (into.isEmpty()) return more.sortedBy { it.startMin }
        val byStart = LinkedHashMap<Int, ProgrammeInfo>(into.size + more.size)
        for (p in into) byStart[p.rawStartMin] = p
        for (p in more) byStart[p.rawStartMin] = p
        return byStart.values.sortedBy { it.startMin }
    }
}
