package app.thebox.core.guide.feed

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.GuideFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §3.2.1 (blocks of 32, LRU of 8) and B5 (`reloadLoaded`). */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideRowsCacheTest {
    private val channels = GuideFixtures.channels(1_000)

    /** Counts block loads and can hold them until released. */
    private class CountingLibrary(channels: List<GuideChannel>) : app.thebox.core.guide.GuideLibrary by FakeGuideLibrary(channels) {
        private val inner = FakeGuideLibrary(channels)
        val loads = ArrayList<Pair<Int, Int>>()
        var hold: CompletableDeferred<Unit>? = null
        var rename: String? = null
        override suspend fun channels(rowIds: LongArray, from: Int, to: Int): List<GuideChannel?> {
            loads += from to to
            hold?.await()
            val r = rename
            return inner.channels(rowIds, from, to).map { if (r == null) it else it?.copy(displayName = r) }
        }
    }

    private fun lineup(n: Int = 1_000, version: Long = 1, offset: Int = 0) =
        GuideLineup(GuideSource.All(1), version, LongArray(n) { channels[(it + offset) % channels.size].rowId }, 0)

    private fun TestScope.cache(lib: CountingLibrary) = GuideRowsCacheImpl(lib, this)

    @Test
    fun ensureLoadsBlocksAroundTheView() = runTest(StandardTestDispatcher()) {
        val lib = CountingLibrary(channels)
        val c = cache(lib)
        c.setLineup(lineup())
        assertEquals(1_000, c.size)
        assertNull(c.channel(100), "skeleton until loaded")
        val v0 = c.version
        c.ensure(100, 118)
        advanceUntilIdle()
        // [100 − 32, 118 + 32) = [68, 150) → blocks 2, 3, 4.
        assertEquals(listOf(64 to 96, 96 to 128, 128 to 160), lib.loads)
        assertEquals(channels[100].rowId, c.channel(100)!!.rowId)
        assertEquals(channels[159].rowId, c.channel(159)!!.rowId)
        assertNull(c.channel(160))
        assertTrue(c.version > v0)
        assertEquals(c.version, c.changes.value)
        assertEquals(105, c.indexOf(channels[105].rowId))
        assertEquals(-1, c.indexOf(-5))
        c.ensure(100, 118)
        advanceUntilIdle()
        assertEquals(3, lib.loads.size, "loaded blocks are not reloaded")
    }

    @Test
    fun lruKeepsEightBlocks() = runTest(StandardTestDispatcher()) {
        val lib = CountingLibrary(channels)
        val c = cache(lib)
        c.setLineup(lineup())
        for (top in listOf(0, 64, 128, 192, 256, 320, 384, 448, 512)) {
            c.ensure(top, top + 18)
            advanceUntilIdle()
        }
        var loaded = 0
        for (b in 0 until 32) if (c.channel(b * 32) != null) loaded++
        assertTrue(loaded <= GuideRowsCacheImpl.ROW_BLOCKS_MAX, "loaded $loaded blocks")
        assertNull(c.channel(0), "least recently ensured evicted")
        assertNotNull(c.channel(512))
    }

    @Test
    fun newLineupDropsBlocksAndStaleLoads() = runTest(StandardTestDispatcher()) {
        val lib = CountingLibrary(channels)
        val c = cache(lib)
        c.setLineup(lineup())
        lib.hold = CompletableDeferred()
        c.ensure(0, 18)
        runCurrent()
        c.setLineup(lineup(version = 2, offset = 500))
        lib.hold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(c.channel(0), "a load for the old lineup is dropped")
        lib.hold = null
        c.ensure(0, 18)
        advanceUntilIdle()
        assertEquals(channels[500].rowId, c.channel(0)!!.rowId)
        assertEquals(2L, c.lineupVersion)
        // The same lineup again keeps the blocks.
        val before = lib.loads.size
        c.setLineup(lineup(version = 2, offset = 500))
        c.ensure(0, 18)
        advanceUntilIdle()
        assertEquals(before, lib.loads.size)
    }

    @Test
    fun reloadLoadedReplacesBlocksInPlace() = runTest(StandardTestDispatcher()) {
        val lib = CountingLibrary(channels)
        val c = cache(lib)
        c.setLineup(lineup())
        c.ensure(0, 18)
        advanceUntilIdle()
        val v = c.version
        lib.rename = "Renamed"
        c.reloadLoaded()
        assertEquals("Channel 3", c.channel(3)!!.displayName, "old content stays until the reload arrives")
        advanceUntilIdle()
        assertEquals("Renamed", c.channel(3)!!.displayName)
        assertTrue(c.version > v)
        assertEquals(1_000, c.size, "lineup and indices kept")
    }

    @Test
    fun channelAtLoadsOnDemandAndTrimDropsFarBlocks() = runTest(StandardTestDispatcher()) {
        val lib = CountingLibrary(channels)
        val c = cache(lib)
        c.setLineup(lineup())
        assertEquals(channels[700].rowId, c.channelAt(700)!!.rowId)
        assertNull(c.channelAt(5_000))
        c.ensure(0, 18)
        advanceUntilIdle()
        assertNotNull(c.channel(700))
        c.trim(0, 18)
        assertNull(c.channel(700))
        assertNotNull(c.channel(10))
    }
}
