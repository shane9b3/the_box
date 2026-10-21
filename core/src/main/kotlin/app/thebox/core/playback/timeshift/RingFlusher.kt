// WP-A (docs/design/m6-timeshift.md §4.1, §4.4, A-S7, A-S8).
package app.thebox.core.playback.timeshift

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/**
 * The process's one `box-ts-flush` daemon thread (§4.1). It `pwrite`s each committed range of every registered ring from
 * the block's direct buffer, flushes a partial block after FLUSH_PARTIAL_MS, checks `st_nlink` every NLINK_CHECK_MS and
 * grows the file by extents. The queue is implicit and bounded: per ring it is `[flushed, flushRequested)`, which the
 * RAM pool bounds (the writer waits for a slot whose block is not flushed yet, then stops with DISK_TOO_SLOW).
 *
 * Waits are `LockSupport.parkNanos` with a [TICK_MS] tick, so the loop allocates nothing per block (§15).
 */
internal class RingFlusher(private val threadName: String = "box-ts-flush") {
    private val rings = CopyOnWriteArrayList<TsRingImpl>()
    @Volatile private var thread: Thread? = null
    @Volatile private var signalled = false

    fun register(ring: TsRingImpl) {
        rings.addIfAbsent(ring)
        ensureThread()
        wake()
    }

    fun unregister(ring: TsRingImpl) { rings.remove(ring) }

    fun wake() {
        signalled = true
        thread?.let { LockSupport.unpark(it) }
    }

    /** The flusher thread's id (allocation tests). */
    val threadId: Long get() = thread?.id ?: -1L

    @Synchronized private fun ensureThread() {
        if (thread != null) return
        val t = Thread(::loop, threadName)
        t.isDaemon = true
        thread = t
        t.start()
    }

    private fun loop() {
        while (true) {
            var worked = false
            val n = rings.size
            for (i in 0 until n) {
                val r = try { rings[i] } catch (_: IndexOutOfBoundsException) { break }
                try { if (r.flushStep()) worked = true } catch (_: RuntimeException) { /* one ring's bug must not stop the others */ }
            }
            if (!worked) {
                if (!signalled) LockSupport.parkNanos(this, TICK_MS * 1_000_000L)
                signalled = false
            }
        }
    }

    companion object {
        /** Wake-up tick: partial-block flushes and nlink checks need no faster cadence. */
        const val TICK_MS = 100L

        /** The process's flusher. */
        val shared: RingFlusher by lazy { RingFlusher() }
    }
}
