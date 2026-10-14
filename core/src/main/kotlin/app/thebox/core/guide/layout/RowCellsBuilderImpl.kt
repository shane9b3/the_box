package app.thebox.core.guide.layout

import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.CellSink
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.TimeLabels

/**
 * Builds one row's cells (docs/design/m3-guide.md §3.2.4). Pure; called by the pager on `box-guide-epg`.
 *
 * 1. `noEpg`: NO_EPG blocks cut at every local hour boundary across [fromMin, toMin).
 * 2. Otherwise a cursor walks the programmes; a programme wholly inside its predecessor is skipped (S10b), a hole
 *    becomes GAP cells, a programme starts at `max(start, cursor)` (the first one keeps a true start before
 *    `fromMin`), and the cursor moves to its end. The tail is a GAP. Cells never overlap.
 * 3. Gaps are cut at local hour boundaries; a piece shorter than [GridConstants.SLIVER_MIN] carries
 *    [CellFlags.SLIVER].
 * 4. CONTINUATION segments are merged into the programme they continue (one cell, the continuation's end); a lone
 *    continuation at the coverage start is emitted as is.
 *
 * Hour boundaries come from [labels] (`minuteOfDay`, device zone), so DST days cut at local hours.
 */
class RowCellsBuilderImpl(private val labels: TimeLabels = TimeTables()) : RowCellsBuilder {

    override fun build(w: EpgWindow, from: Int, to: Int, fromMin: Int, toMin: Int, noEpg: Boolean, out: CellSink): Int {
        if (toMin <= fromMin) return 0
        if (noEpg) return hourBlocks(fromMin, toMin, CellKind.NO_EPG, sliverFlag = false, out)
        var n = 0
        var c = fromMin
        var i = from
        while (i < to) {
            val ps = w.startMin[i]
            var pe = w.endMin[i]
            if (ps >= toMin) break
            // Merge the continuation chain that follows this segment (6 h splits, M1).
            var j = i + 1
            while (j < to && (w.flags[j] and ProgrammeFlags.CONTINUATION) != 0 && w.startMin[j] <= pe && w.endMin[j] > pe) {
                pe = w.endMin[j]
                j++
            }
            if (pe <= c) { i = j; continue }   // wholly inside its predecessor (S10b)
            if (ps > c) {
                n += hourBlocks(c, ps, CellKind.GAP, sliverFlag = true, out)
                c = ps
            }
            val start = if (n == 0 && ps < c) ps else maxOf(ps, c)
            val flags = if (j > i + 1) w.flags[i] and ProgrammeFlags.CONTINUED.inv() else w.flags[i]
            out.add(start, pe, w.rawStartMin[i], CellKind.PROGRAMME, flags, w.textId[i], w.titles[i])
            n++
            c = pe
            i = j
        }
        if (c < toMin) n += hourBlocks(c, toMin, CellKind.GAP, sliverFlag = true, out)
        return n
    }

    /** Emits [kind] cells over [a, b) cut at local hour boundaries. */
    private fun hourBlocks(a: Int, b: Int, kind: Byte, sliverFlag: Boolean, out: CellSink): Int {
        var n = 0
        var s = a
        while (s < b) {
            val mod = labels.minuteOfDay(s) % 60
            val e = minOf(b, s + (60 - mod))
            val flags = if (sliverFlag && e - s < GridConstants.SLIVER_MIN) CellFlags.SLIVER else 0
            out.add(s, e, s, kind, flags, -1, null)
            n++
            s = e
        }
        return n
    }
}
