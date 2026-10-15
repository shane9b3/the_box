package app.thebox.core.guide.layout

import app.thebox.core.guide.CellFlags
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.fakes.RecordingCellSink
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §3.2.4, §5.1 "RowCellsBuilderTest". */
class RowCellsBuilderTest {
    private val utc = ZoneOffset.UTC
    private fun m(h: Int, mi: Int, day: Int = 14, zone: ZoneId = utc, month: Int = 10): Int =
        (LocalDateTime.of(2026, month, day, h, mi).atZone(zone).toEpochSecond() / 60).toInt()

    private fun labels(zone: ZoneId = utc) = TimeTables(zone, true, nowMs = { m(12, 0, zone = zone) * 60_000L })
    private val builder = RowCellsBuilderImpl(labels())

    private class P(val s: Int, val e: Int, val title: String = "p$s", val flags: Int = 0, val raw: Int = s)

    private fun window(vararg ps: P): EpgWindow {
        val w = EpgWindow(4)
        w.ensureCapacity(ps.size)
        ps.forEachIndexed { i, p ->
            w.channelIndex[i] = 0; w.startMin[i] = p.s; w.endMin[i] = p.e; w.rawStartMin[i] = p.raw
            w.textId[i] = 100 + i; w.flags[i] = p.flags; w.titles[i] = p.title
        }
        w.size = ps.size
        return w
    }

    private fun build(w: EpgWindow, from: Int, to: Int, noEpg: Boolean = false, b: RowCellsBuilderImpl = builder): List<RecordingCellSink.Added> {
        val sink = RecordingCellSink()
        val n = b.build(w, 0, w.size, from, to, noEpg, sink)
        assertEquals(n, sink.cells.size)
        return sink.cells
    }

    private fun assertTiles(cells: List<RecordingCellSink.Added>, from: Int, to: Int) {
        assertTrue(cells.first().start <= from, "first cell starts at or before the coverage")
        for (i in 1 until cells.size) assertEquals(cells[i - 1].end, cells[i].start, "contiguous at $i")
        assertTrue(cells.last().end >= to)
    }

    @Test
    fun emptyRowIsNoEpgHourBlocks() {
        val cells = build(window(), m(18, 30), m(21, 0), noEpg = true)
        assertEquals(listOf(m(18, 30) to m(19, 0), m(19, 0) to m(20, 0), m(20, 0) to m(21, 0)), cells.map { it.start to it.end })
        assertTrue(cells.all { it.kind == CellKind.NO_EPG && it.textId == -1 && it.title == null })
    }

    @Test
    fun rowWithNoProgrammesInRangeIsGaps() {
        val cells = build(window(), m(19, 0), m(21, 0))
        assertEquals(2, cells.size)
        assertTrue(cells.all { it.kind == CellKind.GAP })
    }

    @Test
    fun gapSplitAtHours() {
        // 19:47–21:10 → 3 GAP cells.
        val cells = build(window(P(m(19, 0), m(19, 47)), P(m(21, 10), m(22, 0))), m(19, 0), m(22, 0))
        assertEquals(
            listOf(
                Triple(m(19, 0), m(19, 47), CellKind.PROGRAMME),
                Triple(m(19, 47), m(20, 0), CellKind.GAP),
                Triple(m(20, 0), m(21, 0), CellKind.GAP),
                Triple(m(21, 0), m(21, 10), CellKind.GAP),
                Triple(m(21, 10), m(22, 0), CellKind.PROGRAMME),
            ),
            cells.map { Triple(it.start, it.end, it.kind) },
        )
        assertEquals(0, cells[1].flags and CellFlags.SLIVER)
    }

    @Test
    fun sliverFlagged() {
        val cells = build(window(P(m(19, 0), m(19, 57)), P(m(20, 2), m(21, 0))), m(19, 0), m(21, 0))
        val gaps = cells.filter { it.kind == CellKind.GAP }
        assertEquals(listOf(m(19, 57) to m(20, 0), m(20, 0) to m(20, 2)), gaps.map { it.start to it.end })
        assertTrue(gaps.all { (it.flags and CellFlags.SLIVER) != 0 })
        assertTiles(cells, m(19, 0), m(21, 0))
    }

    @Test
    fun overlapClipped() {
        val cells = build(window(P(m(19, 0), m(20, 10)), P(m(20, 0), m(21, 0))), m(19, 0), m(21, 0))
        assertEquals(listOf(m(19, 0) to m(20, 10), m(20, 10) to m(21, 0)), cells.map { it.start to it.end })
        assertEquals(m(20, 0), cells[1].rawStart, "raw start is the programme's, not the clipped start")
    }

    @Test
    fun nestedProgrammeSkipped() {
        val cells = build(window(P(m(19, 0), m(21, 0), "outer"), P(m(19, 30), m(20, 0), "inner"), P(m(21, 0), m(22, 0))), m(19, 0), m(22, 0))
        assertEquals(listOf("outer", "p${m(21, 0)}"), cells.map { it.title })
        assertTiles(cells, m(19, 0), m(22, 0))
    }

    @Test
    fun continuationMerged() {
        val cells = build(
            window(
                P(m(14, 0), m(20, 0), "film", flags = ProgrammeFlags.CONTINUED),
                P(m(20, 0), m(21, 0), "film", flags = ProgrammeFlags.CONTINUATION),
                P(m(21, 0), m(22, 0), "news"),
            ),
            m(19, 0), m(22, 0),
        )
        assertEquals(2, cells.size)
        assertEquals(m(14, 0), cells[0].start, "first cell keeps its true start before the coverage")
        assertEquals(m(21, 0), cells[0].end)
        assertEquals(0, cells[0].flags and ProgrammeFlags.CONTINUED)
        assertEquals("news", cells[1].title)
    }

    @Test
    fun loneContinuationAtCoverageStartEmittedAsIs() {
        val cells = build(window(P(m(18, 0), m(20, 0), "film", flags = ProgrammeFlags.CONTINUATION), P(m(20, 0), m(21, 0))), m(19, 0), m(21, 0))
        assertEquals(m(18, 0), cells[0].start)
        assertTrue((cells[0].flags and ProgrammeFlags.CONTINUATION) != 0)
        assertEquals(2, cells.size)
    }

    @Test
    fun programmeCrossingBothEdges() {
        val cells = build(window(P(m(17, 0), m(23, 0))), m(19, 0), m(21, 0))
        assertEquals(1, cells.size)
        assertEquals(m(17, 0) to m(23, 0), cells[0].start to cells[0].end)
    }

    @Test
    fun programmeStartingAfterCoverageIgnored() {
        val cells = build(window(P(m(19, 0), m(20, 0)), P(m(21, 0), m(22, 0))), m(19, 0), m(20, 30))
        assertEquals(listOf(CellKind.PROGRAMME, CellKind.GAP), cells.map { it.kind })
        assertEquals(m(20, 30), cells.last().end)
    }

    @Test
    fun dstDaysCutAtLocalHours() {
        val london = ZoneId.of("Europe/London")
        val b = RowCellsBuilderImpl(TimeTables(london, true, nowMs = { m(12, 0, day = 29, zone = london, month = 3) * 60_000L }))
        // Spring forward 2026-03-29: local 00:00–04:00 is 3 real hours (01:00 → 02:00).
        val from = m(0, 0, day = 29, zone = london, month = 3)
        val to = m(4, 0, day = 29, zone = london, month = 3)
        val spring = build(window(), from, to, noEpg = true, b = b)
        assertEquals(3, spring.size)
        assertTrue(spring.all { it.end - it.start == 60 })
        // Fall back 2026-10-25: local 00:00–03:00 is 4 real hours; every cut is a local hour.
        val b2 = RowCellsBuilderImpl(TimeTables(london, true, nowMs = { m(12, 0, day = 25, zone = london) * 60_000L }))
        val from2 = m(0, 0, day = 25, zone = london)
        val to2 = m(3, 0, day = 25, zone = london)
        val fall = build(window(), from2, to2, noEpg = true, b = b2)
        assertEquals(4, fall.size)
        val l2 = TimeTables(london, true, nowMs = { from2 * 60_000L })
        assertTrue(fall.all { l2.minuteOfDay(it.start) % 60 == 0 })
        // A gap across the fall-back hour, in a 30-min-offset zone too.
        val kolkata = ZoneId.of("Asia/Kolkata")
        val b3 = RowCellsBuilderImpl(TimeTables(kolkata, true, nowMs = { m(12, 0, zone = kolkata) * 60_000L }))
        val gaps = build(window(), m(19, 10, zone = kolkata), m(21, 0, zone = kolkata), b = b3)
        assertEquals(listOf(m(19, 10, zone = kolkata) to m(20, 0, zone = kolkata), m(20, 0, zone = kolkata) to m(21, 0, zone = kolkata)), gaps.map { it.start to it.end })
    }
}
