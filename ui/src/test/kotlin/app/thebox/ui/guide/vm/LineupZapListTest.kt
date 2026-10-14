package app.thebox.ui.guide.vm

import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.GuideFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/design/m3-guide.md §3.16.3, review S9. */
class LineupZapListTest {
    private val channels = GuideFixtures.channels(5, archiveEvery = 0)
    private fun id(i: Int) = channels[i].identity

    @Test
    fun stepsWithWrap() = runTest {
        val lib = FakeGuideLibrary(channels)
        val zap = LineupZapList(lib.lineup(GuideSource.All(1), false).first(), lib)
        assertEquals(id(1), zap.neighbour(id(0), +1))
        assertEquals(id(4), zap.neighbour(id(0), -1))
        assertEquals(id(0), zap.neighbour(id(4), +1))
        assertEquals(id(2), zap.neighbour(id(0), +2))
    }

    @Test
    fun capturedRowIdsSurviveALineupReEmission() = runTest {
        val lib = FakeGuideLibrary(channels)
        val captured = lib.lineup(GuideSource.All(1), false).first()
        val zap = LineupZapList(captured, lib)
        // The library re-emits a different lineup (channel 2 hidden); the captured list does not change.
        lib.hideChannel(id(2))
        assertEquals(4, lib.lineup(GuideSource.All(1), false).first().rowIds.size)
        assertEquals("id is from the captured version", "lineup:" + captured.version, zap.id)
        assertEquals(id(2), zap.neighbour(id(1), +1))
    }

    @Test
    fun purgedIdsAreSkipped() = runTest {
        val lib = FakeGuideLibrary(channels)
        val ids = longArrayOf(channels[0].rowId, 424242L, channels[2].rowId)
        val zap = LineupZapList(GuideLineup(GuideSource.All(1), 1, ids, 0), lib)
        assertEquals(id(2), zap.neighbour(id(0), +1))
        assertEquals(id(0), zap.neighbour(id(2), +1))
        assertEquals(id(0), zap.neighbour(id(2), -1))
    }

    @Test
    fun absentFromStartsAtPositionZero() = runTest {
        val lib = FakeGuideLibrary(channels)
        val zap = LineupZapList(GuideLineup(GuideSource.All(1), 1, longArrayOf(channels[1].rowId, channels[3].rowId), 0), lib)
        assertEquals(id(1), zap.neighbour(id(4), +1))
        assertEquals(id(3), zap.neighbour(id(4), -1))
    }

    @Test
    fun emptyOrAllPurgedReturnsNull() = runTest {
        val lib = FakeGuideLibrary(channels)
        assertNull(LineupZapList(GuideLineup(GuideSource.All(1), 1, LongArray(0), 0), lib).neighbour(id(0), 1))
        assertNull(LineupZapList(GuideLineup(GuideSource.All(1), 1, longArrayOf(1L, 2L), 0), lib).neighbour(id(0), 1))
    }
}
