package app.thebox.core.guide

import app.thebox.core.common.Hash64
import app.thebox.core.guide.fakes.FakeGuideMetrics
import app.thebox.core.guide.fakes.FakeNavigator
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Commit-0 checks on the M3 contracts and the shared fakes (docs/design/m3-guide.md §1.1, §2, Appendix A.1): the
 * normative geometry, the snapshot and reminder-index invariants the WPs rely on, and that the fakes behave.
 */
class GuideContractsTest {
    @Test
    fun timelineIs1558PxFor180Minutes() {
        assertEquals(1558f, GuideGeometry.TIMELINE_W)
        assertEquals(GuideGeometry.PANEL_W - 2 * GuideGeometry.PANEL_BORDER - 2 * GuideGeometry.PANEL_PAD_X - GuideGeometry.LABEL_W, GuideGeometry.TIMELINE_W)
        assertTrue(abs(GuideGeometry.PX_PER_MIN - 8.6556f) < 1e-3f)
        assertEquals(GuideGeometry.ROW_H + GuideGeometry.ROW_GAP, GuideGeometry.ROW_PITCH)
        assertEquals(GuideGeometry.CANVAS_W, GuideGeometry.PANEL_L + GuideGeometry.PANEL_W + GuideGeometry.HEAD_R)
    }

    @Test
    fun prototypeNowLineAt19h47() {
        // Appendix A.1 / §5.1: window 19:00–22:00, now 19:47 → 200 + 1558 × 47/180 = 606.8 px from the content edge,
        // 687.8 px from the screen's left edge (64 + 1 + 16 + 606.8).
        val m = FakeGuideMetrics(1f)
        val windowStart = GuideFixtures.floor30(GuideFixtures.NOW_MIN) - 30
        assertEquals(GuideFixtures.NOW_MIN - 47, windowStart)
        val x = m.xOf(GuideFixtures.NOW_MIN, windowStart, 0f)
        assertTrue(abs(x - 687.8f) < 0.05f, "x=$x")
    }

    @Test
    fun halfMinuteScrollOffsetShifts4Point33Px() {
        // §2.12 item 3: the animated fraction is a small offset from an Int window start, exact at any epoch minute.
        val m = FakeGuideMetrics(1f)
        val windowStart = GuideFixtures.floor30(GuideFixtures.NOW_MIN) - 30
        val x0 = m.xOf(GuideFixtures.NOW_MIN, windowStart, 0f)
        val x1 = m.xOf(GuideFixtures.NOW_MIN, windowStart, 0.5f)
        assertTrue(abs((x0 - x1) - 4.33f) < 0.01f, "shift=${x0 - x1}")
    }

    @Test
    fun identityHashIsStableAndKeysTheReminderIndex() {
        val a = GuideFixtures.channel(1)
        assertEquals(GuideChannel.hashOf(GuideFixtures.identity(1)), a.identityHash)
        assertTrue(a.identityHash != GuideFixtures.channel(2).identityHash)
        val idx = ReminderIndex.of(listOf(a.identityHash to 100, a.identityHash to 50))
        assertTrue(idx.contains(a.identityHash, 50))
        assertTrue(idx.contains(a.identityHash, 100))
        assertFalse(idx.contains(a.identityHash, 51))
        assertEquals(2, idx.size)
        assertEquals(Hash64.long(a.identityHash, 50L), Hash64.long(a.identityHash, 50L))
    }

    @Test
    fun fixtureSnapshotTilesEveryRow() {
        val from = GuideFixtures.NOW_MIN - 180
        val to = GuideFixtures.NOW_MIN + 360
        val data = GuideFixtures.windowData(GuideFixtures.syntheticRows(18, from, to, noEpgRows = setOf(3)), fromMin = from, toMin = to)
        assertEquals(18, data.rowCount)
        assertEquals(RowStatus.NO_EPG, data.rowStatus[3])
        for (r in 0 until data.rowCount) {
            val f = data.rowFirstCell[r]
            val n = data.rowCellCount[r]
            assertTrue(data.cellStart[f] <= from && data.cellEnd[f + n - 1] >= to, "row $r covers the window")
            for (i in f + 1 until f + n) assertEquals(data.cellEnd[i - 1], data.cellStart[i], "row $r contiguous")
        }
        assertTrue(data.covers(0, from) && !data.covers(18, from) && !data.covers(0, to))
    }

    @Test
    fun fakeNavigatorEdges() {
        val from = GuideFixtures.NOW_MIN - 180
        val to = GuideFixtures.NOW_MIN + 360
        val data = GuideFixtures.windowData(GuideFixtures.syntheticRows(10, from, to), fromMin = from, toMin = to)
        val ctx = GridContext(
            lineupSize = 10, data = data, nowMin = GuideFixtures.NOW_MIN, earliestMin = from, latestMin = to, snap = false,
            watchableFrom = RowWatchable { GuideFixtures.NOW_MIN },
        )
        val nav = FakeNavigator()
        val start = nav.focusAt(0, GuideFixtures.NOW_MIN, ctx)
        assertEquals(CellKind.PROGRAMME, start.cellKind)
        assertIs<GridMoveResult.TopEdge>(nav.move(start, GridMove.UP, 0, ctx))
        assertIs<GridMoveResult.LeftEdge>(nav.move(start, GridMove.LEFT, 0, ctx))
        val down = assertIs<GridMoveResult.Moved>(nav.move(start, GridMove.DOWN, 0, ctx)).focus
        assertEquals(1, down.row)
        assertEquals(start.anchorMin, down.anchorMin)
        val right = assertIs<GridMoveResult.Moved>(nav.move(start, GridMove.RIGHT, 0, ctx)).focus
        assertEquals(start.cellEnd, right.cellStart)
        assertIs<GridMoveResult.Blocked>(nav.move(start.copy(row = 9), GridMove.DOWN, 0, ctx))
    }

    @Test
    fun fakeReminderBookStoresShiftAndRejectsDuplicates() = runTest {
        val book = FakeReminderBook()
        val ch = GuideFixtures.channel(4, epgShiftMin = 60)
        val info = GuideFixtures.info(startMin = 1_060, endMin = 1_090, shiftMin = 60)
        assertTrue(book.set(ch, info, 60))
        assertFalse(book.set(ch, info, 60))
        val p = book.pending.value.single()
        assertEquals(1_000, p.rawStartMin)
        assertEquals(60, p.shiftMin)
        assertTrue(book.index.value.contains(ch.identityHash, 1_000))
        book.markFired(p.id)
        assertTrue(book.pending.value.isEmpty())
        assertTrue(book.set(ch, info, 60), "re-setting a fired reminder succeeds (N5)")
    }

    @Test
    fun deviceClassThresholds() {
        val gib = 1L shl 30
        assertTrue(DeviceClass(lowRam = false, totalMemBytes = (1.4 * gib).toLong(), memoryClassMb = 256).lowEnd)
        assertFalse(DeviceClass(lowRam = false, totalMemBytes = (1.85 * gib).toLong(), memoryClassMb = 256).lowEnd)
        assertTrue(DeviceClass(lowRam = false, totalMemBytes = 4 * gib, memoryClassMb = 192).lowEnd)
        assertTrue(DeviceClass(lowRam = true, totalMemBytes = 4 * gib, memoryClassMb = 512).lowEnd)
        assertTrue(DeviceClass(lowRam = false, totalMemBytes = (0.9 * gib).toLong(), memoryClassMb = 128).stillByDefault)
        assertFalse(DeviceClass(lowRam = false, totalMemBytes = (1.4 * gib).toLong(), memoryClassMb = 128).stillByDefault)
    }
}
