// WP-A (docs/design/m6-timeshift.md §4.1, A-B2, A-S8).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import java.nio.ByteBuffer
import java.util.concurrent.locks.StampedLock

/**
 * The direct-backed RAM block pool (§4.1): [blocks] × RING_BLOCK_BYTES, allocated once.
 *
 * Logical block `b` (bytes `[b × RING_BLOCK_BYTES, (b + 1) × RING_BLOCK_BYTES)`) always lives in slot `b mod blocks`, so
 * recycling is FIFO and a reader finds a block without any map. A slot may be reassigned to block `b + blocks` as soon
 * as block `b` is flushed (or dropped behind `tail`), whatever the reader cursors are (A-B2). Readers copy under the
 * slot's [StampedLock] optimistic stamp and fall back to `pread` on a mismatch.
 *
 * On Android `allocateDirect` is backed by a non-movable `byte[]` with `array()` (A-S8), so the writer reads upstream
 * straight into it and the flusher `pwrite`s from the direct buffer. A JVM direct buffer has no array: the pool then uses
 * a heap array wrapped in a heap buffer (tests and the pure-JVM fallback).
 */
internal class BlockPool(val blocks: Int) {
    init { require(blocks >= 2) { "pool needs at least 2 blocks" } }

    class Block internal constructor() {
        val buffer: ByteBuffer
        val array: ByteArray
        val arrayOffset: Int
        /** Flusher-only view of [buffer] (position/limit are set per pwrite; no allocation per block). */
        val flushView: ByteBuffer
        /** Write-locked by the writer while it reassigns the slot; readers validate an optimistic stamp around a copy. */
        val lock = StampedLock()
        /** The logical block this slot holds; −1 = none. Written only inside [lock]'s write lock. */
        @Volatile var blockNo: Long = -1L

        init {
            val direct = ByteBuffer.allocateDirect(RING_BLOCK_BYTES)
            if (direct.hasArray()) {
                buffer = direct; array = direct.array(); arrayOffset = direct.arrayOffset()
            } else {
                array = ByteArray(RING_BLOCK_BYTES); buffer = ByteBuffer.wrap(array); arrayOffset = 0
            }
            flushView = buffer.duplicate()
        }

        fun assign(b: Long) {
            val st = lock.writeLock()
            blockNo = b
            lock.unlockWrite(st)
        }
    }

    private val slots = Array(blocks) { Block() }

    fun slotIndex(blockNo: Long): Int = (blockNo % blocks).toInt()
    fun slotFor(blockNo: Long): Block = slots[slotIndex(blockNo)]

    /** Forgets every assignment (ring reset). */
    fun clear() { for (s in slots) s.assign(-1L) }

    /**
     * Replaces slot [index] with a fresh block (A-S1 fence): an old pump blocked in `read` across `reset()` still writes
     * into the array it was handed; the new generation must never share it. Not a steady-state path (once per zap at most).
     */
    fun quarantine(index: Int) {
        slots[index].assign(-1L)
        slots[index] = Block()
    }

    /** Heap held by the pool (blocks only). */
    val bytes: Long get() = blocks.toLong() * RING_BLOCK_BYTES
}
