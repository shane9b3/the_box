// Contract (M3 commit 0): docs/design/m3-guide.md §2.2. Verbatim; changes go through the lead.
package app.thebox.core.guide

/**
 * Device-px geometry for one window size. All values pixel-snapped as §3.1 says. Immutable; a new instance per
 * window size. [scale] = device px per design px (BoxDesignScale's value).
 */
interface GuideMetrics {
    val scale: Float
    val panelLeft: Float; val panelTop: Float; val panelRight: Float; val panelBottom: Float
    /** Left edge of the timeline (x of minute windowStart) and its width. */
    val timelineLeft: Float; val timelineWidth: Float
    val labelLeft: Float; val labelWidth: Float
    val headerTop: Float; val headerBottom: Float
    /** Top of visible row 0 and the row pitch/height in device px (rows are pixel-snapped). */
    val rowsTop: Float; val rowPitch: Float; val rowHeight: Float
    val rowsBottom: Float
    val pxPerMin: Float
    fun px(designPx: Float): Float
    /**
     * x of display minute [min] for a window starting at [windowStartMin] + [scrollOffsetMin]. Absolute minutes stay
     * `Int` (an epoch minute in a Float has 2-minute spacing, §2.12 item 3); the animated fraction is the small
     * [scrollOffsetMin] (about −180..+180). Compute `(min − windowStartMin)` in Int, then subtract the offset.
     */
    fun xOf(min: Int, windowStartMin: Int, scrollOffsetMin: Float): Float
    /** Device-px rect of the mini-player hole, rounded to whole pixels: [left, top, right, bottom]. */
    fun holeRect(out: IntArray)
}

fun interface GuideMetricsFactory { fun create(windowWidthPx: Int, windowHeightPx: Int): GuideMetrics }

/** Builds the cells of one row (WP-A). Called by the pager on `box-guide-epg`. Appends to the builder arrays. */
interface RowCellsBuilder {
    /**
     * [w] holds this row's programmes at indices [from, to) (sorted by start, as EpgReader.window returns them).
     * Appends cells tiling [fromMin, toMin) to [out] and returns the number appended. [noEpg] = the row has no
     * EPG ref or no data: emit NO_EPG blocks only.
     */
    fun build(w: app.thebox.core.epg.EpgWindow, from: Int, to: Int, fromMin: Int, toMin: Int, noEpg: Boolean, out: CellSink): Int
}

/** Growable struct-of-arrays target used while building a snapshot (WP-B owns the implementation). */
interface CellSink {
    fun add(start: Int, end: Int, rawStart: Int, kind: Byte, flags: Int, textId: Int, title: String?)
}

/** Where focus can be inside the grid. All minutes are display epoch minutes. */
data class GridFocus(
    val row: Int,
    val cellStart: Int,
    val cellEnd: Int,
    val cellKind: Byte,
    val anchorMin: Int,
    val windowStartMin: Int,
    val topRow: Int,
)

enum class GridMove { LEFT, RIGHT, UP, DOWN, PAGE_UP, PAGE_DOWN, TIME_BACK, TIME_FORWARD, STEP_BACK, STEP_FORWARD, TO_ROW, TO_TIME, RESOLVE }

sealed interface GridMoveResult {
    /** [animate]: horizontal/vertical scroll may animate (false = snap). */
    data class Moved(val focus: GridFocus, val animate: Boolean) : GridMoveResult
    /**
     * Left with no earlier *watchable* cell (decision note accelerator 1; reviewed 2026-10-14, lead §7 item 4):
     * on a row without usable archive, Left from the cell containing now; on an archive row, Left at the archive
     * limit. Holding Left (STEP_BACK), RW and the day strip still reach the non-watchable past.
     */
    data object LeftEdge : GridMoveResult
    /** Up on lineup row 0 (product S7: goes to the header row). */
    data object TopEdge : GridMoveResult
    /** Nothing to do (bottom row, latest minute). */
    data object Blocked : GridMoveResult
}

/** Inputs of a move. [data] may be null (nothing loaded): every cell is LOADING. */
class GridContext(
    val lineupSize: Int,
    val data: GuideWindowData?,
    val nowMin: Int,
    val earliestMin: Int,
    val latestMin: Int,
    /** Snap instead of animate (key repeat beyond SNAP_AFTER_REPEATS, low-end mode). */
    val snap: Boolean,
    /** Earliest watchable display minute of lineup row [row] (now − archiveDays × 1440, or now without usable archive). */
    val watchableFrom: RowWatchable,
)

fun interface RowWatchable { fun earliestWatchableMin(row: Int): Int }

interface GuideNavigator {
    /** [arg]: target row for TO_ROW, target minute for TO_TIME; ignored otherwise. Pure; no allocation besides the result. */
    fun move(from: GridFocus, move: GridMove, arg: Int, ctx: GridContext): GridMoveResult
    /** Initial focus: [row] at [atMin] with the window placed by §3.3.2. */
    fun focusAt(row: Int, atMin: Int, ctx: GridContext): GridFocus
    /** The cell containing [minute] on [row] packed as (start shl 32) or (end and 0xffffffff); kind via [kindAt]. */
    fun cellAt(row: Int, minute: Int, ctx: GridContext): Long
    fun kindAt(row: Int, minute: Int, ctx: GridContext): Byte
}

/**
 * Allocation-free time labels in the device zone (§3.5). (reviewed 2026-10-14, S12) Offsets come from a precomputed
 * transition table (±16 days around now, rebuilt by [reset] and at local midnight); [minuteOfDay] and [dayOf] are
 * integer arithmetic, never `ZoneRules.getOffset(Instant)`. [reset] builds new immutable tables and publishes them
 * through one `@Volatile` reference, so readers on main and on `box-guide-epg` always see a consistent snapshot.
 */
interface TimeLabels {
    /** "19:30" / "7:30" for display epoch minute [min]. Returned strings are interned table entries. */
    fun hm(min: Int): String
    /** Minute of day in the device zone. */
    fun minuteOfDay(min: Int): Int
    /** Local day number (epoch day in the device zone). */
    fun dayOf(min: Int): Int
    /** "Tue 13 Oct". */
    fun dayLabel(day: Int): String
    /** "TONIGHT", "THIS AFTERNOON", … (§3.5.2). */
    fun contextLabel(windowStartMin: Int, nowMin: Int): String
    /** Duration "30 min", "1 h", "1 h 30 min" (prototype durLabel). */
    fun duration(minutes: Int): String
    /** Rebuild the zone tables (zone or 12/24 h change). */
    fun reset(zone: java.time.ZoneId, clock24: Boolean)
    /**
     * (code review 2026-10-14, G-S3, G-L7) Bumped by every [reset]: caches of formatted times (the pager's cell time
     * texts, the renderer's ruler widths) compare it instead of probing a label.
     */
    val version: Int get() = 0
}
