package app.thebox.core.guide.layout

import app.thebox.core.guide.CellKind
import app.thebox.core.guide.GridContext
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GridMove
import app.thebox.core.guide.GridMoveResult
import app.thebox.core.guide.GuideGeometry
import app.thebox.core.guide.GuideNavigator
import app.thebox.core.guide.RowStatus
import app.thebox.core.guide.TimeLabels
import app.thebox.core.guide.layout.GridConstants.LEAD_MIN
import app.thebox.core.guide.layout.GridConstants.PAGE_ROWS
import app.thebox.core.guide.layout.GridConstants.TAIL_MIN
import app.thebox.core.guide.layout.GridConstants.TIME_PAGE_MIN
import app.thebox.core.guide.layout.GridConstants.TIME_STEP_MIN

/**
 * Every grid move of docs/design/m3-guide.md §3.4.2 with the anchor and window rules of §3.3.2.
 *
 * - Horizontal moves (LEFT/RIGHT) focus the neighbouring focusable cell; the window pages to
 *   `clampWs(floor30(start) − LEAD_MIN)` when the cell starts left of the window or in its last `TAIL_MIN`;
 *   `anchor = max(start, ws', earliest)`.
 * - Vertical moves (UP/DOWN/PAGE) keep the window and the anchor and focus the cell containing the anchor (a sliver
 *   resolves to the next focusable cell, else the previous); the top row keeps focus at visible index 1…4.
 * - Time moves (TIME/STEP) shift window and anchor together; TO_TIME is the day-jump placement; TO_ROW focuses a
 *   row at now (number entry, §3.9.4); RESOLVE re-finds the anchor's cell after a new snapshot.
 * - Minutes outside the snapshot are LOADING cells cut at local :00/:30 (and at the edges of known cells).
 *
 * Main thread only: the cell lookup uses instance scratch fields so a move allocates nothing but its result.
 * [labels] gives local :00/:30 boundaries; null means UTC (zones with a :15/:45 offset then align to UTC).
 */
class GuideNavigatorImpl(private val labels: TimeLabels? = null) : GuideNavigator {

    // Scratch: the cell found by the last locate().
    private var cS = 0
    private var cE = 0
    private var cK: Byte = CellKind.LOADING
    private var cF = 0

    override fun move(from: GridFocus, move: GridMove, arg: Int, ctx: GridContext): GridMoveResult {
        if (ctx.lineupSize <= 0) return GridMoveResult.Blocked
        val row = from.row.coerceIn(0, ctx.lineupSize - 1)
        return when (move) {
            GridMove.LEFT -> left(from, row, ctx)
            GridMove.RIGHT -> right(from, row, ctx)
            GridMove.UP -> if (row <= 0) GridMoveResult.TopEdge else vertical(from, row - 1, from.topRow, ctx)
            GridMove.DOWN -> if (row >= ctx.lineupSize - 1) GridMoveResult.Blocked else vertical(from, row + 1, from.topRow, ctx)
            GridMove.PAGE_UP -> {
                val r = maxOf(0, row - PAGE_ROWS)
                if (r == row) GridMoveResult.Blocked else vertical(from, r, from.topRow - PAGE_ROWS, ctx)
            }
            GridMove.PAGE_DOWN -> {
                val r = minOf(ctx.lineupSize - 1, row + PAGE_ROWS)
                if (r == row) GridMoveResult.Blocked else vertical(from, r, from.topRow + PAGE_ROWS, ctx)
            }
            GridMove.TIME_BACK -> time(from, row, -TIME_PAGE_MIN, ctx)
            GridMove.TIME_FORWARD -> time(from, row, TIME_PAGE_MIN, ctx)
            GridMove.STEP_BACK -> time(from, row, -TIME_STEP_MIN, ctx)
            GridMove.STEP_FORWARD -> time(from, row, TIME_STEP_MIN, ctx)
            GridMove.TO_ROW -> moved(focusAt(arg.coerceIn(0, ctx.lineupSize - 1), ctx.nowMin, ctx), ctx)
            GridMove.TO_TIME -> {
                val anchor = clampAnchor(arg, ctx)
                val ws = clampWs(floor30(anchor) - LEAD_MIN, ctx)
                resolveAt(row, anchor, ctx)
                moved(GridFocus(row, cS, cE, cK, anchor, ws, topFor(row, from.topRow, ctx.lineupSize)), ctx)
            }
            GridMove.RESOLVE -> {
                resolveAt(row, from.anchorMin, ctx)
                moved(GridFocus(row, cS, cE, cK, from.anchorMin, clampWs(from.windowStartMin, ctx), topFor(row, from.topRow, ctx.lineupSize)), ctx)
            }
        }
    }

    override fun focusAt(row: Int, atMin: Int, ctx: GridContext): GridFocus {
        val r = if (ctx.lineupSize <= 0) 0 else row.coerceIn(0, ctx.lineupSize - 1)
        val anchor = clampAnchor(atMin, ctx)
        val ws = clampWs(floor30(anchor) - LEAD_MIN, ctx)
        resolveAt(r, anchor, ctx)
        val top = clamp(r - 1, 0, maxOf(0, ctx.lineupSize - GuideGeometry.VISIBLE_ROWS))
        return GridFocus(r, cS, cE, cK, anchor, ws, top)
    }

    override fun cellAt(row: Int, minute: Int, ctx: GridContext): Long {
        locate(row, minute, ctx)
        return (cS.toLong() shl 32) or (cE.toLong() and 0xffffffffL)
    }

    override fun kindAt(row: Int, minute: Int, ctx: GridContext): Byte {
        locate(row, minute, ctx)
        return cK
    }

    // --- moves ---------------------------------------------------------------------------------------------------

    private fun left(from: GridFocus, row: Int, ctx: GridContext): GridMoveResult {
        if (from.cellStart <= ctx.earliestMin) return GridMoveResult.LeftEdge
        val watchFrom = ctx.watchableFrom.earliestWatchableMin(row)
        val curWatchable = from.cellStart >= watchFrom || from.cellEnd > ctx.nowMin
        locate(row, from.cellStart - 1, ctx)
        var guard = 0
        while (isSliver() && cS > ctx.earliestMin && guard++ < MAX_SKIP) locate(row, cS - 1, ctx)
        if (isSliver()) return GridMoveResult.LeftEdge
        val targetWatchable = cS >= watchFrom || cE > ctx.nowMin
        if (curWatchable && !targetWatchable) return GridMoveResult.LeftEdge
        return horizontal(from, row, ctx)
    }

    private fun right(from: GridFocus, row: Int, ctx: GridContext): GridMoveResult {
        if (from.cellEnd >= ctx.latestMin) return GridMoveResult.Blocked
        locate(row, from.cellEnd, ctx)
        var guard = 0
        while (isSliver() && cE < ctx.latestMin && guard++ < MAX_SKIP) locate(row, cE, ctx)
        if (isSliver()) return GridMoveResult.Blocked
        return horizontal(from, row, ctx)
    }

    /** Places the window for the cell in the scratch fields (§3.3.2 "Horizontal move"). */
    private fun horizontal(from: GridFocus, row: Int, ctx: GridContext): GridMoveResult {
        val ws = from.windowStartMin
        val ws2 = if (cS < ws || cS >= ws + GuideGeometry.WINDOW_MIN - TAIL_MIN) clampWs(floor30(cS) - LEAD_MIN, ctx) else clampWs(ws, ctx)
        val anchor = clampAnchor(maxOf(cS, ws2), ctx)
        return moved(GridFocus(row, cS, cE, cK, anchor, ws2, topFor(row, from.topRow, ctx.lineupSize)), ctx)
    }

    private fun vertical(from: GridFocus, newRow: Int, topHint: Int, ctx: GridContext): GridMoveResult {
        resolveAt(newRow, from.anchorMin, ctx)
        return moved(GridFocus(newRow, cS, cE, cK, from.anchorMin, from.windowStartMin, topFor(newRow, topHint, ctx.lineupSize)), ctx)
    }

    private fun time(from: GridFocus, row: Int, delta: Int, ctx: GridContext): GridMoveResult {
        val ws = clampWs(from.windowStartMin + delta, ctx)
        val anchor = clampAnchor(clamp(from.anchorMin + delta, ws, ws + GuideGeometry.WINDOW_MIN - 1), ctx)
        if (ws == from.windowStartMin && anchor == from.anchorMin) return GridMoveResult.Blocked
        resolveAt(row, anchor, ctx)
        return moved(GridFocus(row, cS, cE, cK, anchor, ws, topFor(row, from.topRow, ctx.lineupSize)), ctx)
    }

    private fun moved(f: GridFocus, ctx: GridContext): GridMoveResult = GridMoveResult.Moved(f, animate = !ctx.snap)

    // --- helpers -------------------------------------------------------------------------------------------------

    /** Edge scrolling with one row of lookahead (§3.3.2 "Vertical scroll"). */
    private fun topFor(row: Int, top: Int, size: Int): Int =
        clamp(clamp(top, row - 4, row - 1), 0, maxOf(0, size - GuideGeometry.VISIBLE_ROWS))

    /** Cell containing [anchor]; a sliver resolves to the next focusable cell, else the previous one. */
    private fun resolveAt(row: Int, anchor: Int, ctx: GridContext) {
        locate(row, anchor, ctx)
        if (!isSliver()) return
        val sliverStart = cS
        var guard = 0
        while (isSliver() && cE < ctx.latestMin && guard++ < MAX_SKIP) locate(row, cE, ctx)
        if (!isSliver()) return
        locate(row, sliverStart - 1, ctx)
        guard = 0
        while (isSliver() && cS > ctx.earliestMin && guard++ < MAX_SKIP) locate(row, cS - 1, ctx)
    }

    private fun isSliver(): Boolean = (cF and CellFlags.SLIVER) != 0

    /** Fills the scratch fields with the cell of [row] containing [minute] (a LOADING block outside the data). */
    private fun locate(row: Int, minute: Int, ctx: GridContext) {
        val d = ctx.data
        var s = floor30(minute)
        var e = floor30(s + 30 + 15) // next local :00/:30 boundary (robust to a 15-min-offset DST jump)
        if (e <= minute) e = s + 30
        if (d != null && row >= d.firstRow && row < d.firstRow + d.rowCount) {
            val r = row - d.firstRow
            val n = d.rowCellCount[r]
            if (d.rowStatus[r] != RowStatus.PENDING && n > 0) {
                val f = d.rowFirstCell[r]
                var lo = f
                var hi = f + n - 1
                var idx = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (d.cellStart[mid] <= minute) { idx = mid; lo = mid + 1 } else hi = mid - 1
                }
                if (idx >= 0 && minute < d.cellEnd[idx]) {
                    cS = d.cellStart[idx]; cE = d.cellEnd[idx]; cK = d.cellKind[idx]; cF = d.cellFlags[idx]
                    return
                }
                if (idx >= 0) s = maxOf(s, d.cellEnd[idx])
                val next = if (idx >= 0) idx + 1 else f
                if (next < f + n) e = minOf(e, d.cellStart[next])
            }
        }
        cS = s; cE = e; cK = CellKind.LOADING; cF = 0
    }

    private fun floor30(m: Int): Int = if (labels == null) Math.floorDiv(m, 30) * 30 else m - labels.minuteOfDay(m) % 30

    private fun ceil30(m: Int): Int { val f = floor30(m); return if (f == m) m else floor30(f + 45) }

    /** `clampWs(m) = clamp(m, floor30(earliest), max(floor30(earliest), ceil30(latest) − 180))` (§3.3.1). */
    private fun clampWs(m: Int, ctx: GridContext): Int {
        val lo = floor30(ctx.earliestMin)
        val hi = maxOf(lo, ceil30(ctx.latestMin) - GuideGeometry.WINDOW_MIN)
        return clamp(m, lo, hi)
    }

    private fun clampAnchor(m: Int, ctx: GridContext): Int = clamp(m, ctx.earliestMin, maxOf(ctx.earliestMin, ctx.latestMin - 1))

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    private companion object {
        /** Bound on consecutive slivers skipped (a row is never more than a few slivers in a row). */
        const val MAX_SKIP = 64
    }
}
