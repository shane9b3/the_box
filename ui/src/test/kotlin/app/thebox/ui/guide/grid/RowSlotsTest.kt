package app.thebox.ui.guide.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device finding 2026-10-17: the hashed 32-slot table let two rows of one frame share a slot, so the later row's
 * recording replaced the earlier one's before the frame was drawn (cells, names and logos flickering while scrolling).
 */
class RowSlotsTest {
    /** Keys shaped like the renderer's (two passes × rows): any 14 distinct keys must get 14 distinct slots in one frame. */
    @Test fun distinctKeysOfOneFrameNeverShareASlot() {
        val slots = RowSlots(48)
        slots.nextFrame()
        val taken = HashSet<Int>()
        for (pass in 0..1) for (row in 0 until 7) {
            val key = (0x9E3779B97F4A7C15uL.toLong() * (row + 1)) xor (pass.toLong() shl 29)
            assertEquals(-1, slots.find(key))
            val slot = slots.allocate(key)
            assertTrue("slot allocated", slot >= 0)
            assertTrue("slot $slot reused in the same frame", taken.add(slot))
        }
    }

    @Test fun aKeyOfAnEarlierFrameReplaysFromItsSlot() {
        val slots = RowSlots(4)
        slots.nextFrame()
        val a = slots.allocate(1L)
        val b = slots.allocate(2L)
        slots.nextFrame()
        assertEquals(a, slots.find(1L))
        assertEquals(b, slots.find(2L))
        assertTrue(slots.holds(a, 1L))
    }

    @Test fun evictionTakesTheLeastRecentlyUsedSlotOfAnEarlierFrame() {
        val slots = RowSlots(3)
        slots.nextFrame()
        val a = slots.allocate(1L)
        val b = slots.allocate(2L)
        val c = slots.allocate(3L)
        slots.nextFrame()
        assertEquals(b, slots.find(2L)) // 2 is used this frame
        slots.nextFrame()
        assertEquals(c, slots.find(3L)) // 3 is the newest; 1 is the oldest
        val d = slots.allocate(4L)
        assertEquals("the oldest slot goes", a, d)
        assertTrue(slots.holds(d, 4L))
        assertEquals(-1, slots.find(1L))
        assertNotEquals("never a slot touched this frame", c, d)
    }

    @Test fun aFullFrameAnswersMinusOneInsteadOfReusingASlot() {
        val slots = RowSlots(2)
        slots.nextFrame()
        assertTrue(slots.allocate(1L) >= 0)
        assertTrue(slots.allocate(2L) >= 0)
        assertEquals(-1, slots.allocate(3L))
        slots.nextFrame()
        assertTrue("free again next frame", slots.allocate(3L) >= 0)
    }

    @Test fun clearForgetsEveryKey() {
        val slots = RowSlots(2)
        slots.nextFrame()
        slots.allocate(1L)
        slots.clear()
        assertEquals(-1, slots.find(1L))
        assertTrue(slots.allocate(2L) >= 0)
    }
}
