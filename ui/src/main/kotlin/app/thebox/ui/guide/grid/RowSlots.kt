package app.thebox.ui.guide.grid

/**
 * Slot table of the per-row recordings (O-2, `AndroidGuideCanvas`): which recording slot holds which row key, with
 * frame-aware eviction. Pure Kotlin so the policy is unit-tested without a hardware canvas.
 *
 * Device finding 2026-10-17: the first table hashed the key straight to one of 32 slots. Two rows of one frame (7 rows ×
 * 2 passes = 14 keys) collided most frames; the second re-recorded the first's `RenderNode`, and since the outer display
 * list holds the node by reference, the first row's backgrounds (or its text, logos and labels) vanished until the keys
 * changed, which made cells, names and logos flicker in and out while scrolling.
 *
 * The table now never reuses a slot that was recorded or replayed in the current frame: [find] marks a hit, [allocate]
 * evicts the least recently used slot of an earlier frame, and answers −1 when every slot is in use this frame (the
 * caller draws that row directly). [nextFrame] is called once per draw.
 */
class RowSlots(val size: Int) {
    private val keys = LongArray(size)
    /** Frame the slot was last recorded or replayed in; [EMPTY] when free. */
    private val lastFrame = IntArray(size) { EMPTY }
    private var frame = 0

    /** Starts a draw; the slots of earlier frames become evictable. */
    fun nextFrame() {
        frame++
        if (frame == Int.MAX_VALUE) { frame = 1; for (i in 0 until size) if (lastFrame[i] != EMPTY) lastFrame[i] = 0 }
    }

    /** The slot holding [key] (marked used this frame), or −1. */
    fun find(key: Long): Int {
        for (i in 0 until size) if (lastFrame[i] != EMPTY && keys[i] == key) { lastFrame[i] = frame; return i }
        return -1
    }

    /**
     * A slot for [key]: a free one, else the least recently used of an earlier frame; −1 when all [size] slots were
     * touched this frame. Never the slot of a key already found or allocated this frame.
     */
    fun allocate(key: Long): Int {
        var victim = -1
        var oldest = Int.MAX_VALUE
        for (i in 0 until size) {
            val f = lastFrame[i]
            if (f == frame) continue
            if (f < oldest) { oldest = f; victim = i }
        }
        if (victim < 0) return -1
        keys[victim] = key
        lastFrame[victim] = frame
        return victim
    }

    /** Whether [slot] holds [key] (debug / tests). */
    fun holds(slot: Int, key: Long): Boolean = lastFrame[slot] != EMPTY && keys[slot] == key

    /** Frees every slot. */
    fun clear() { lastFrame.fill(EMPTY) }

    private companion object { const val EMPTY = -1 }
}
