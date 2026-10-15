package app.thebox.core.guide.fakes

import app.thebox.core.epg.EpgWindow
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.CellSink
import app.thebox.core.guide.GridContext
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GridMove
import app.thebox.core.guide.GridMoveResult
import app.thebox.core.guide.GuideGeometry as G
import app.thebox.core.guide.GuideMetrics
import app.thebox.core.guide.GuideMetricsFactory
import app.thebox.core.guide.GuideNavigator
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.TimeLabels
import java.time.ZoneId
import kotlin.math.roundToInt

/** Unsnapped §2.1 geometry at [scale] (WP-A's `GuideMetricsImpl` pixel-snaps; this fake does not). */
class FakeGuideMetrics(override val scale: Float = 1f) : GuideMetrics {
    override val panelLeft = G.PANEL_L * scale
    override val panelTop = G.PANEL_T * scale
    override val panelRight = (G.PANEL_L + G.PANEL_W) * scale
    override val panelBottom = (G.PANEL_T + G.PANEL_H) * scale
    override val labelLeft = (G.PANEL_L + G.PANEL_BORDER + G.PANEL_PAD_X) * scale
    override val labelWidth = G.LABEL_W * scale
    override val timelineLeft = labelLeft + labelWidth
    override val timelineWidth = G.TIMELINE_W * scale
    override val headerTop = (G.PANEL_T + G.PANEL_BORDER) * scale
    override val headerBottom = headerTop + G.HEADER_H * scale
    override val rowsTop = headerBottom
    override val rowPitch = G.ROW_PITCH * scale
    override val rowHeight = G.ROW_H * scale
    override val rowsBottom = (G.PANEL_T + G.PANEL_H - G.PANEL_BORDER - G.PANEL_PAD_B) * scale
    override val pxPerMin = G.PX_PER_MIN * scale
    override fun px(designPx: Float): Float = designPx * scale
    override fun xOf(min: Int, windowStartMin: Int, scrollOffsetMin: Float): Float =
        timelineLeft + ((min - windowStartMin) - scrollOffsetMin) * pxPerMin
    override fun holeRect(out: IntArray) {
        out[0] = (G.HOLE_L * scale).roundToInt(); out[1] = (G.HOLE_T * scale).roundToInt()
        out[2] = ((G.HOLE_L + G.HOLE_W) * scale).roundToInt(); out[3] = ((G.HOLE_T + G.HOLE_H) * scale).roundToInt()
    }
}

class FakeGuideMetricsFactory : GuideMetricsFactory {
    override fun create(windowWidthPx: Int, windowHeightPx: Int): GuideMetrics =
        FakeGuideMetrics(minOf(windowWidthPx / G.CANVAS_W, windowHeightPx / G.CANVAS_H))
}

/** Growable recorder for [CellSink]. */
class RecordingCellSink : CellSink {
    data class Added(val start: Int, val end: Int, val rawStart: Int, val kind: Byte, val flags: Int, val textId: Int, val title: String?)
    val cells = ArrayList<Added>()
    override fun add(start: Int, end: Int, rawStart: Int, kind: Byte, flags: Int, textId: Int, title: String?) {
        cells += Added(start, end, rawStart, kind, flags, textId, title)
    }
}

/**
 * Simplified builder (not §3.2.4): copies programmes clipped to nothing, fills holes with one GAP cell each, and
 * emits one NO_EPG cell for a [noEpg] row. Enough for pager/renderer tests that do not test cell rules.
 */
class FakeRowCellsBuilder : RowCellsBuilder {
    var calls = 0
    override fun build(w: EpgWindow, from: Int, to: Int, fromMin: Int, toMin: Int, noEpg: Boolean, out: CellSink): Int {
        calls++
        if (noEpg || from >= to) {
            out.add(fromMin, toMin, fromMin, CellKind.NO_EPG, 0, -1, null)
            return 1
        }
        var n = 0
        var t = fromMin
        for (i in from until to) {
            if (w.endMin[i] <= t) continue
            if (w.startMin[i] > t) { out.add(t, w.startMin[i], t, CellKind.GAP, 0, -1, null); n++ }
            out.add(w.startMin[i], w.endMin[i], w.rawStartMin[i], CellKind.PROGRAMME, w.flags[i], w.textId[i], w.titles[i]); n++
            t = w.endMin[i]
        }
        if (t < toMin) { out.add(t, toMin, t, CellKind.GAP, 0, -1, null); n++ }
        return n
    }
}

/**
 * Simplified navigator (not the §3.4.2 rules): moves cell by cell over [GridContext.data], 30-minute LOADING blocks
 * outside it, rows clamp to the lineup, Up on row 0 is [GridMoveResult.TopEdge], Left before
 * [GridContext.watchableFrom] is [GridMoveResult.LeftEdge]. The state machine's tests start on it and switch to
 * `GuideNavigatorImpl` after WP-A merges (§1.7).
 */
class FakeNavigator : GuideNavigator {
    val moves = ArrayList<GridMove>()

    override fun move(from: GridFocus, move: GridMove, arg: Int, ctx: GridContext): GridMoveResult {
        moves += move
        val last = ctx.lineupSize - 1
        return when (move) {
            GridMove.LEFT -> {
                val m = from.cellStart - 1
                if (m < ctx.watchableFrom.earliestWatchableMin(from.row) || m < ctx.earliestMin) GridMoveResult.LeftEdge
                else moved(focusOn(from.row, m, from, ctx, anchorToStart = true), ctx)
            }
            GridMove.RIGHT -> if (from.cellEnd >= ctx.latestMin) GridMoveResult.Blocked else moved(focusOn(from.row, from.cellEnd, from, ctx, anchorToStart = true), ctx)
            GridMove.UP -> if (from.row == 0) GridMoveResult.TopEdge else moved(focusOn(from.row - 1, from.anchorMin, from, ctx), ctx)
            GridMove.DOWN -> if (from.row >= last) GridMoveResult.Blocked else moved(focusOn(from.row + 1, from.anchorMin, from, ctx), ctx)
            GridMove.PAGE_UP -> if (from.row == 0) GridMoveResult.Blocked else moved(focusOn(maxOf(0, from.row - PAGE), from.anchorMin, from, ctx), ctx)
            GridMove.PAGE_DOWN -> if (from.row >= last) GridMoveResult.Blocked else moved(focusOn(minOf(last, from.row + PAGE), from.anchorMin, from, ctx), ctx)
            GridMove.TIME_BACK, GridMove.STEP_BACK -> {
                val m = maxOf(ctx.earliestMin, from.anchorMin - if (move == GridMove.TIME_BACK) 180 else 30)
                if (m == from.anchorMin) GridMoveResult.Blocked else moved(focusOn(from.row, m, from, ctx), ctx)
            }
            GridMove.TIME_FORWARD, GridMove.STEP_FORWARD -> {
                val m = minOf(ctx.latestMin - 1, from.anchorMin + if (move == GridMove.TIME_FORWARD) 180 else 30)
                if (m == from.anchorMin) GridMoveResult.Blocked else moved(focusOn(from.row, m, from, ctx), ctx)
            }
            GridMove.TO_ROW -> moved(focusOn(arg.coerceIn(0, maxOf(0, last)), from.anchorMin, from, ctx), ctx)
            GridMove.TO_TIME -> moved(focusOn(from.row, arg, from, ctx), ctx)
            GridMove.RESOLVE -> moved(focusOn(from.row, from.anchorMin, from, ctx), ctx)
        }
    }

    override fun focusAt(row: Int, atMin: Int, ctx: GridContext): GridFocus {
        val cell = cellAt(row, atMin, ctx)
        val ws = GuideFixtures.floor30(atMin) - 30
        return GridFocus(row, (cell shr 32).toInt(), cell.toInt(), kindAt(row, atMin, ctx), atMin, ws, maxOf(0, row - 2))
    }

    override fun cellAt(row: Int, minute: Int, ctx: GridContext): Long {
        val d = ctx.data
        if (d != null && d.covers(row, minute)) {
            val r = row - d.firstRow
            val f = d.rowFirstCell[r]
            for (i in f until f + d.rowCellCount[r]) {
                if (minute >= d.cellStart[i] && minute < d.cellEnd[i]) return pack(d.cellStart[i], d.cellEnd[i])
            }
        }
        val s = GuideFixtures.floor30(minute)
        return pack(s, s + 30)
    }

    override fun kindAt(row: Int, minute: Int, ctx: GridContext): Byte {
        val d = ctx.data ?: return CellKind.LOADING
        if (!d.covers(row, minute)) return CellKind.LOADING
        val r = row - d.firstRow
        val f = d.rowFirstCell[r]
        for (i in f until f + d.rowCellCount[r]) if (minute >= d.cellStart[i] && minute < d.cellEnd[i]) return d.cellKind[i]
        return CellKind.LOADING
    }

    private fun focusOn(row: Int, minute: Int, from: GridFocus, ctx: GridContext, anchorToStart: Boolean = false): GridFocus {
        val cell = cellAt(row, minute, ctx)
        val s = (cell shr 32).toInt()
        val e = cell.toInt()
        val anchor = if (anchorToStart) maxOf(s, ctx.earliestMin) else minute
        var ws = from.windowStartMin
        if (s < ws || s >= ws + G.WINDOW_MIN) ws = GuideFixtures.floor30(s) - 30
        var top = from.topRow
        if (row < top) top = row
        if (row >= top + G.VISIBLE_ROWS) top = row - G.VISIBLE_ROWS + 1
        return GridFocus(row, s, e, kindAt(row, minute, ctx), anchor, ws, top)
    }

    private fun moved(f: GridFocus, ctx: GridContext) = GridMoveResult.Moved(f, animate = !ctx.snap)

    private fun pack(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xffffffffL)

    private companion object { const val PAGE = 6 }
}

/** UTC, 24 h, no tables: "19:30", "D20375" day labels, prototype-style durations. */
class FakeTimeLabels : TimeLabels {
    var zone: ZoneId = ZoneId.of("UTC"); private set
    var clock24 = true; private set
    override fun hm(min: Int): String {
        val m = minuteOfDay(min)
        val h = m / 60
        return if (clock24) "%02d:%02d".format(h, m % 60) else "%d:%02d".format(if (h % 12 == 0) 12 else h % 12, m % 60)
    }
    override fun minuteOfDay(min: Int): Int = Math.floorMod(min, 1440)
    override fun dayOf(min: Int): Int = Math.floorDiv(min, 1440)
    override fun dayLabel(day: Int): String = "D$day"
    override fun contextLabel(windowStartMin: Int, nowMin: Int): String = if (dayOf(windowStartMin) == dayOf(nowMin)) "TODAY" else dayLabel(dayOf(windowStartMin))
    override fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
    override fun reset(zone: ZoneId, clock24: Boolean) { this.zone = zone; this.clock24 = clock24; version++; resets++ }
    override var version: Int = 0; private set
    var resets = 0; private set
}
