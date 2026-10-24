// WP-A (docs/design/m6-timeshift.md §4.1, §4.4, A-S7, A-S8). Storage locations commit 0: the owned-flusher signature
// and the choice (docs/design/m6-storage-locations.md §4.2, §12.4); WP-S1 fills the owned flusher's exit.
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
internal class RingFlusher(
    private val threadName: String = "box-ts-flush",
    /**
     * (Storage locations commit 0, docs/design/m6-storage-locations.md §4.2, §12.4; A-B2) True for the dedicated flusher
     * of one [RingStorage.blocking] ring (`box-ts-flush-net` / `-ext`): its thread exits once that ring unregistered and
     * closed, so an SMB stall never shares a thread with another ring. False = [shared] (lives for the process).
     * (WP-S1) The exit: once no ring is registered, the loop ends after its current step (an in-flight `pwrite` of the
     * closed ring returns within one I/O timeout, since the storage checks `closed` before every retry). A later
     * [register] starts a new thread.
     */
    val owned: Boolean = false,
) {
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

    /** (WP-S1, tests) The live flusher thread, null once an owned flusher exited (or before the first [register]). */
    internal val liveThread: Thread? get() = thread

    @Synchronized private fun ensureThread() {
        if (thread != null) return
        val t = Thread(::loop, threadName)
        t.isDaemon = true
        thread = t
        t.start()
    }

    private fun loop() {
        while (true) {
            if (owned && rings.isEmpty() && exitIfIdle()) return
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

    /** An owned flusher with no ring left ends its thread (A-B2). Under the [ensureThread] monitor, so a racing [register] restarts it. */
    @Synchronized private fun exitIfIdle(): Boolean {
        if (rings.isNotEmpty()) return false
        thread = null
        return true
    }

    companion object {
        /** Wake-up tick: partial-block flushes and nlink checks need no faster cadence. */
        const val TICK_MS = 100L

        /** The process's flusher. */
        val shared: RingFlusher by lazy { RingFlusher() }

        /** (Storage locations §4.2) The dedicated flusher thread of a ring on a network folder. */
        const val NET_THREAD = "box-ts-flush-net"
        /** (Storage locations §4.2) The dedicated flusher thread of a ring on a USB drive. */
        const val EXT_THREAD = "box-ts-flush-ext"

        /**
         * (Storage locations commit 0, §12.4) The production flusher choice: a [RingStorage.blocking] storage gets its own
         * owned flusher ([NET_THREAD] for a remote storage or the fallback storage, [EXT_THREAD] otherwise); every other
         * storage shares [shared].
         */
        fun forStorage(storage: RingStorage): RingFlusher = when {
            !storage.blocking -> shared
            storage is RemoteRingStorage || storage is app.thebox.core.storage.FallbackRingStorage -> RingFlusher(NET_THREAD, owned = true)
            else -> RingFlusher(EXT_THREAD, owned = true)
        }
    }
}
