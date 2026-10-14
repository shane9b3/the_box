package app.thebox.core.guide.layout

import app.thebox.core.epg.EpgWindow
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.GridContext
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GridMove
import app.thebox.core.guide.GridMoveResult
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.RowWatchable
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.GuideFixtures.Cell
import app.thebox.core.guide.fakes.RecordingCellSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** docs/design/m3-guide.md §3.3, §3.4.2, §5.1 "GuideNavigatorTest". */
class GuideNavigatorTest {
    private val nav = GuideNavigatorImpl()
    private val now = GuideFixtures.NOW_MIN          // 19:47 UTC
    private val h19 = GuideFixtures.floor30(now) - 30 // 19:00
    private fun at(h: Int, m: Int = 0) = h19 + (h - 19) * 60 + m

    private fun ctx(
        data: GuideWindowData?,
        size: Int = 10,
        earliest: Int = now - 2 * 1440,
        latest: Int = now + 7 * 1440,
        snap: Boolean = false,
        watch: (Int) -> Int = { now },
    ) = GridContext(size, data, now, earliest, latest, snap, RowWatchable { watch(it) })

    /** Every row: 30-min programmes from 15:00 to 23:00. */
    private fun halfHours(rows: Int = 10): GuideWindowData {
        val from = at(15)
        val to = at(23)
        return GuideFixtures.windowData(List(rows) { List(16) { k -> Cell(from + 30 * k, from + 30 * k + 30, title = "r$it p$k") } }, fromMin = from, toMin = to)
    }

    private fun moved(r: GridMoveResult): GridFocus { assertIs<GridMoveResult.Moved>(r); return r.focus }

    private fun focus(row: Int, start: Int, end: Int, anchor: Int = start, ws: Int = h19, top: Int = 0, kind: Byte = CellKind.PROGRAMME) =
        GridFocus(row, start, end, kind, anchor, ws, top)

    // --- placement ------------------------------------------------------------------------------------------------

    @Test
    fun focusAtNowPlacesWindowAndContext() {
        val c = ctx(halfHours())
        val f = nav.focusAt(4, now, c)
        assertEquals(h19, f.windowStartMin)
        assertEquals(now, f.anchorMin)
        assertEquals(at(19, 30) to at(20), f.cellStart to f.cellEnd)
        assertEquals(3, f.topRow)
        assertEquals(0, nav.focusAt(0, now, c).topRow)
        assertEquals(4, nav.focusAt(9, now, c).topRow, "clamped to size − 6")
    }

    @Test
    fun rightWithinWindowKeepsWindow() {
        val c = ctx(halfHours())
        val f = moved(nav.move(focus(2, at(19, 30), at(20), now), GridMove.RIGHT, 0, c))
        assertEquals(at(20) to at(20, 30), f.cellStart to f.cellEnd)
        assertEquals(at(20), f.anchorMin)
        assertEquals(h19, f.windowStartMin)
    }

    @Test
    fun rightIntoTailPagesWindow() {
        val c = ctx(halfHours())
        // A cell starting at 21:30 is in the last 30 min of the 19:00 window → ws' = floor30(21:30) − 30 = 21:00.
        val f = moved(nav.move(focus(2, at(21), at(21, 30)), GridMove.RIGHT, 0, c))
        assertEquals(at(21, 30), f.cellStart)
        assertEquals(at(21), f.windowStartMin)
        assertEquals(at(21, 30), f.anchorMin)
    }

    @Test
    fun leftFromFutureMovesThenLeftEdgeFromOnNowWithoutArchive() {
        val c = ctx(halfHours())
        val f = moved(nav.move(focus(2, at(20), at(20, 30)), GridMove.LEFT, 0, c))
        assertEquals(at(19, 30), f.cellStart)
        assertEquals(GridMoveResult.LeftEdge, nav.move(f, GridMove.LEFT, 0, c))
        // Holding Left (STEP_BACK) still reaches the past.
        val s = moved(nav.move(f, GridMove.STEP_BACK, 0, c))
        assertEquals(at(18, 30), s.windowStartMin)
        assertEquals(f.anchorMin - 30, s.anchorMin)
        assertEquals(at(19), s.cellStart)
        // A focused cell already in the non-watchable past moves Left normally.
        val p = moved(nav.move(s, GridMove.LEFT, 0, c))
        assertEquals(at(18, 30), p.cellStart)
    }

    @Test
    fun archiveRowLeftEdgeAtArchiveLimit() {
        val limit = at(17, 10)
        val c = ctx(halfHours(), watch = { if (it == 2) limit else now })
        // Past cells after the limit are watchable: Left moves.
        val f = moved(nav.move(focus(2, at(19, 30), at(20), now), GridMove.LEFT, 0, c))
        assertEquals(at(19), f.cellStart)
        // 17:30 starts after the limit; 17:00 starts before it → LeftEdge at 17:30.
        val f2 = moved(nav.move(focus(2, at(18), at(18, 30), ws = at(17)), GridMove.LEFT, 0, c))
        assertEquals(at(17, 30), f2.cellStart)
        assertEquals(GridMoveResult.LeftEdge, nav.move(f2, GridMove.LEFT, 0, c))
        // STEP_BACK goes past the limit.
        assertIs<GridMoveResult.Moved>(nav.move(f2, GridMove.STEP_BACK, 0, c))
        // Another row without archive: LeftEdge from the on-now cell.
        assertEquals(GridMoveResult.LeftEdge, nav.move(focus(3, at(19, 30), at(20), now), GridMove.LEFT, 0, c))
    }

    @Test
    fun leftEdgeAtEarliest() {
        val c = ctx(halfHours(), earliest = at(15))
        assertEquals(GridMoveResult.LeftEdge, nav.move(focus(2, at(15), at(15, 30), ws = at(15)), GridMove.LEFT, 0, c))
    }

    @Test
    fun longProgrammeOnLeftJumpsWindowToItsStart() {
        val from = at(12)
        val data = GuideFixtures.windowData(listOf(listOf(Cell(at(14), at(20), title = "film"), Cell(at(20), at(21)), Cell(at(21), at(23)))), fromMin = from, toMin = at(23))
        val c = ctx(data, size = 1, watch = { at(10) })
        val f = moved(nav.move(focus(0, at(20), at(21), ws = at(19, 30)), GridMove.LEFT, 0, c))
        assertEquals(at(14), f.cellStart)
        assertEquals(at(13, 30), f.windowStartMin)
        assertEquals(at(14), f.anchorMin)
    }

    @Test
    fun rightBlockedAtLatest() {
        val c = ctx(halfHours(), latest = at(20))
        assertEquals(GridMoveResult.Blocked, nav.move(focus(2, at(19, 30), at(20)), GridMove.RIGHT, 0, c))
    }

    // --- vertical -------------------------------------------------------------------------------------------------

    @Test
    fun upDownKeepAnchorAndEdges() {
        val rows = listOf(
            listOf(Cell(at(15), at(23))),
            listOf(Cell(at(15), at(19, 40)), Cell(at(19, 40), at(23))),
        )
        val c = ctx(GuideFixtures.windowData(rows, fromMin = at(15), toMin = at(23)), size = 2)
        val f = moved(nav.move(focus(0, at(15), at(23), anchor = at(19, 50)), GridMove.DOWN, 0, c))
        assertEquals(1, f.row)
        assertEquals(at(19, 40), f.cellStart)
        assertEquals(at(19, 50), f.anchorMin, "anchor kept")
        assertEquals(h19, f.windowStartMin, "window kept")
        assertEquals(GridMoveResult.Blocked, nav.move(f, GridMove.DOWN, 0, c))
        val up = moved(nav.move(f, GridMove.UP, 0, c))
        assertEquals(0, up.row)
        assertEquals(GridMoveResult.TopEdge, nav.move(up, GridMove.UP, 0, c))
    }

    @Test
    fun verticalScrollKeepsFocusAtIndexOneToFour() {
        val c = ctx(null, size = 40)
        var f = nav.focusAt(0, now, c)
        val tops = ArrayList<Int>()
        repeat(20) { f = moved(nav.move(f, GridMove.DOWN, 0, c)); tops += f.topRow; assertTrue(f.row - f.topRow in 1..4 || f.topRow == 0) }
        assertEquals(listOf(0, 0, 0, 0, 1, 2), tops.take(6))
        repeat(20) { f = moved(nav.move(f, GridMove.UP, 0, c)); assertTrue(f.row - f.topRow in 1..4 || f.topRow == 0) }
        assertEquals(0, f.row)
        // At the end of the list focus reaches index 5.
        var g = nav.focusAt(39, now, c)
        assertEquals(34, g.topRow)
        g = moved(nav.move(g, GridMove.UP, 0, c))
        assertEquals(34, g.topRow)
    }

    @Test
    fun pageMovesSixRowsAndSixTopRows() {
        val c = ctx(halfHours(30), size = 30)
        val f = moved(nav.move(focus(8, at(19, 30), at(20), now, top = 6), GridMove.PAGE_DOWN, 0, c))
        assertEquals(14, f.row); assertEquals(12, f.topRow); assertEquals(now, f.anchorMin)
        val b = moved(nav.move(f, GridMove.PAGE_UP, 0, c))
        assertEquals(8, b.row); assertEquals(6, b.topRow)
        val top = moved(nav.move(focus(3, at(19, 30), at(20), now, top = 2), GridMove.PAGE_UP, 0, c))
        assertEquals(0, top.row); assertEquals(0, top.topRow)
        assertEquals(GridMoveResult.Blocked, nav.move(top, GridMove.PAGE_UP, 0, c))
        val end = moved(nav.move(focus(27, at(19, 30), at(20), now, top = 24), GridMove.PAGE_DOWN, 0, c))
        assertEquals(29, end.row); assertEquals(24, end.topRow)
        assertEquals(GridMoveResult.Blocked, nav.move(end, GridMove.PAGE_DOWN, 0, c))
    }

    // --- time -----------------------------------------------------------------------------------------------------

    @Test
    fun timePageAndStep() {
        val c = ctx(halfHours())
        val f = moved(nav.move(focus(2, at(19, 30), at(20), now), GridMove.TIME_FORWARD, 0, c))
        assertEquals(at(22), f.windowStartMin)
        assertEquals(now + 180, f.anchorMin)
        assertEquals(at(22, 30), f.cellStart)
        val b = moved(nav.move(f, GridMove.TIME_BACK, 0, c))
        assertEquals(h19, b.windowStartMin); assertEquals(now, b.anchorMin)
        val s = moved(nav.move(b, GridMove.STEP_FORWARD, 0, c))
        assertEquals(at(19, 30), s.windowStartMin); assertEquals(now + 30, s.anchorMin)
        // Clamped at latest: window cannot pass ceil30(latest) − 180.
        val c2 = ctx(halfHours(), latest = at(23))
        val l = moved(nav.move(focus(2, at(19, 30), at(20), now), GridMove.TIME_FORWARD, 0, c2))
        assertEquals(at(20), l.windowStartMin)
        assertTrue(l.anchorMin < at(23))
        val l2 = nav.move(l, GridMove.TIME_FORWARD, 0, c2)
        if (l2 is GridMoveResult.Moved) assertEquals(at(20), l2.focus.windowStartMin)
        val l3 = nav.move(focus(2, at(22, 30), at(23), at(22, 59), ws = at(20)), GridMove.TIME_FORWARD, 0, c2)
        assertEquals(GridMoveResult.Blocked, l3)
    }

    @Test
    fun toRowFocusesAtNowAndToTimeIsTheDayJump() {
        val c = ctx(halfHours(30), size = 30)
        val r = moved(nav.move(focus(2, at(21), at(21, 30), ws = at(20, 30)), GridMove.TO_ROW, 17, c))
        assertEquals(17, r.row); assertEquals(now, r.anchorMin); assertEquals(h19, r.windowStartMin); assertEquals(16, r.topRow)
        val target = now + 1440
        val t = moved(nav.move(focus(2, at(19, 30), at(20), now), GridMove.TO_TIME, target, c))
        assertEquals(target, t.anchorMin)
        assertEquals(h19 + 1440, t.windowStartMin)
        assertEquals(2, t.row)
        assertEquals(CellKind.LOADING, t.cellKind, "outside the snapshot")
    }

    @Test
    fun animateFollowsSnap() {
        val f = focus(2, at(19, 30), at(20), now)
        assertEquals(true, (nav.move(f, GridMove.RIGHT, 0, ctx(halfHours())) as GridMoveResult.Moved).animate)
        assertEquals(false, (nav.move(f, GridMove.RIGHT, 0, ctx(halfHours(), snap = true)) as GridMoveResult.Moved).animate)
    }

    // --- cells ----------------------------------------------------------------------------------------------------

    private fun withSliver(): GuideWindowData {
        val sink = RecordingCellSink()
        val w = EpgWindow(4)
        w.startMin[0] = at(15); w.endMin[0] = at(19, 57); w.rawStartMin[0] = at(15); w.titles[0] = "a"
        w.startMin[1] = at(20, 2); w.endMin[1] = at(23); w.rawStartMin[1] = at(20, 2); w.titles[1] = "b"
        w.size = 2
        RowCellsBuilderImpl(TimeTables(java.time.ZoneOffset.UTC, true, nowMs = { now * 60_000L })).build(w, 0, 2, at(15), at(23), false, sink)
        val cells = sink.cells.map { Cell(it.start, it.end, it.kind, it.title, it.flags) }
        return GuideFixtures.windowData(listOf(cells, listOf(Cell(at(15), at(23)))), fromMin = at(15), toMin = at(23))
    }

    @Test
    fun sliversAreSkipped() {
        val d = withSliver()
        val c = ctx(d, size = 2)
        val r = moved(nav.move(focus(0, at(15), at(19, 57)), GridMove.RIGHT, 0, c))
        assertEquals(at(20, 2), r.cellStart, "right skips both slivers")
        val l = moved(nav.move(r, GridMove.LEFT, 0, ctx(d, size = 2, watch = { at(10) })))
        assertEquals(at(15), l.cellStart, "left skips both slivers")
        // Vertical onto a sliver: the next focusable cell.
        val v = moved(nav.move(focus(1, at(15), at(23), anchor = at(19, 58)), GridMove.UP, 0, c))
        assertEquals(at(20, 2), v.cellStart)
        assertEquals(at(19, 58), v.anchorMin)
    }

    @Test
    fun loadingCellsOutsideCoverage() {
        val d = GuideFixtures.windowData(listOf(listOf(Cell(at(18), at(20, 10)))), fromMin = at(18), toMin = at(20))
        val c = ctx(d, size = 3)
        assertEquals(CellKind.LOADING, nav.kindAt(2, now, c))
        val p = nav.cellAt(2, now, c)
        assertEquals(at(19, 30) to at(20), (p shr 32).toInt() to p.toInt())
        // Right after the last known cell, the LOADING block starts at its end.
        val q = nav.cellAt(0, at(20, 15), c)
        assertEquals(at(20, 10) to at(20, 30), (q shr 32).toInt() to q.toInt())
        val before = nav.cellAt(0, at(17, 50), c)
        assertEquals(at(17, 30) to at(18), (before shr 32).toInt() to before.toInt())
        assertEquals(CellKind.LOADING, nav.kindAt(0, at(17, 50), ctx(null)))
    }

    @Test
    fun resolveAfterSnapshotChange() {
        val old = halfHours()
        val f = nav.focusAt(2, now, ctx(old))
        assertEquals(at(19, 30), f.cellStart)
        val new = GuideFixtures.windowData(List(10) { listOf(Cell(at(15), at(19, 15)), Cell(at(19, 15), at(20, 45)), Cell(at(20, 45), at(23))) }, fromMin = at(15), toMin = at(23), seq = 2)
        val r = moved(nav.move(f, GridMove.RESOLVE, 0, ctx(new)))
        assertEquals(at(19, 15) to at(20, 45), r.cellStart to r.cellEnd)
        assertEquals(now, r.anchorMin)
        assertEquals(f.windowStartMin, r.windowStartMin)
    }

    // --- property test --------------------------------------------------------------------------------------------

    private fun randomData(rnd: Random, firstRow: Int, rows: Int, from: Int, to: Int): GuideWindowData {
        val builder = RowCellsBuilderImpl(TimeTables(java.time.ZoneOffset.UTC, true, nowMs = { now * 60_000L }))
        val durations = intArrayOf(1, 3, 5, 10, 15, 30, 45, 60, 120, 240)
        val all = List(rows) {
            val sink = RecordingCellSink()
            val noEpg = rnd.nextInt(8) == 0
            val w = EpgWindow(16)
            var t = from - rnd.nextInt(200)
            var n = 0
            while (!noEpg && t < to) {
                if (rnd.nextInt(6) == 0) t += durations[rnd.nextInt(durations.size)] // hole
                val d = durations[rnd.nextInt(durations.size)]
                w.ensureCapacity(n + 1)
                w.startMin[n] = t; w.endMin[n] = t + d; w.rawStartMin[n] = t; w.textId[n] = n; w.titles[n] = "p$n"
                n++; t += d
            }
            w.size = n
            builder.build(w, 0, n, from, to, noEpg, sink)
            sink.cells.map { Cell(it.start, it.end, it.kind, it.title, it.flags) }
        }
        return GuideFixtures.windowData(all, firstRow = firstRow, fromMin = from, toMin = to)
    }

    @Test
    fun anchorInvariantsOverRandomWalks() {
        val rnd = Random(20261014)
        val moves = GridMove.entries
        var steps = 0
        repeat(10_000) { walk ->
            val size = 1 + rnd.nextInt(30)
            val earliest = now - 2 * 1440 - rnd.nextInt(60)
            val latest = now + 3 * 1440 + rnd.nextInt(60)
            val first = rnd.nextInt(size)
            val rows = 1 + rnd.nextInt(size - first)
            val from = now - 600 + rnd.nextInt(300)
            var data: GuideWindowData? = if (rnd.nextInt(10) == 0) null else randomData(rnd, first, rows, from, from + 900)
            val watch = IntArray(size) { if (rnd.nextBoolean()) now - 1440 * (1 + rnd.nextInt(7)) else now }
            fun c() = GridContext(size, data, now, earliest, latest, rnd.nextBoolean(), RowWatchable { watch[it] })
            var f = nav.focusAt(rnd.nextInt(size), now, c())
            check(f, c(), "walk $walk start")
            repeat(1 + rnd.nextInt(20)) {
                val mv = moves[rnd.nextInt(moves.size)]
                if (mv == GridMove.RESOLVE && rnd.nextBoolean()) data = randomData(rnd, first, rows, from, from + 900)
                val arg = when (mv) {
                    GridMove.TO_ROW -> rnd.nextInt(size)
                    GridMove.TO_TIME -> earliest - 100 + rnd.nextInt(latest - earliest + 200)
                    else -> 0
                }
                val ctx = c()
                when (val r = nav.move(f, mv, arg, ctx)) {
                    is GridMoveResult.Moved -> { f = r.focus; check(f, ctx, "walk $walk step $it $mv"); steps++ }
                    GridMoveResult.TopEdge -> assertEquals(0, f.row)
                    GridMoveResult.LeftEdge -> assertEquals(GridMove.LEFT, mv)
                    GridMoveResult.Blocked -> Unit
                }
            }
        }
        assertTrue(steps > 50_000, "walks exercised $steps moves")
    }

    private fun check(f: GridFocus, c: GridContext, where: String) {
        val lo = GuideFixtures.floor30(c.earliestMin)
        val ceilLatest = GuideFixtures.floor30(c.latestMin + 29)
        val hi = maxOf(lo, ceilLatest - 180)
        fun ok(cond: Boolean, msg: String) { if (!cond) fail("$where: $msg ($f)") }
        ok(f.windowStartMin % 30 == 0, "window on a :00/:30 boundary")
        ok(f.windowStartMin in lo..hi, "window clamped to [$lo, $hi]")
        ok(f.anchorMin in c.earliestMin until c.latestMin, "anchor within limits")
        ok(f.anchorMin >= f.windowStartMin && f.anchorMin < f.windowStartMin + 180, "anchor visible")
        ok(f.row in 0 until c.lineupSize, "row in lineup")
        ok(f.topRow in 0..maxOf(0, c.lineupSize - 6), "top row clamped")
        ok(f.row - f.topRow in 0..5, "focus row visible")
        val packed = nav.cellAt(f.row, f.cellStart, c)
        ok((packed shr 32).toInt() == f.cellStart && packed.toInt() == f.cellEnd, "focus is a real cell")
        ok(nav.kindAt(f.row, f.cellStart, c) == f.cellKind, "kind matches")
        ok(f.cellEnd > f.cellStart, "non-empty cell")
        // Not a sliver.
        val d = c.data
        if (d != null && d.covers(f.row, f.cellStart)) {
            val r = f.row - d.firstRow
            for (i in d.rowFirstCell[r] until d.rowFirstCell[r] + d.rowCellCount[r]) {
                if (d.cellStart[i] == f.cellStart) ok((d.cellFlags[i] and CellFlags.SLIVER) == 0, "not a sliver")
            }
        }
        // The cell contains the anchor, unless the anchor's own cell is a sliver (then a neighbour is focused).
        if (f.anchorMin < f.cellStart || f.anchorMin >= f.cellEnd) {
            val a = nav.cellAt(f.row, f.anchorMin, c)
            val aStart = (a shr 32).toInt()
            var sliver = false
            if (d != null && d.covers(f.row, f.anchorMin)) {
                val r = f.row - d.firstRow
                for (i in d.rowFirstCell[r] until d.rowFirstCell[r] + d.rowCellCount[r]) {
                    if (d.cellStart[i] == aStart) sliver = (d.cellFlags[i] and CellFlags.SLIVER) != 0
                }
            }
            ok(sliver, "anchor ${f.anchorMin} outside the focused cell and not on a sliver")
        }
    }
}
