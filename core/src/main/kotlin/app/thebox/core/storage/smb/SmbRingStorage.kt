// WP-S1 (docs/design/m6-storage-locations.md §4.1–§4.5; A-B1, A-B3, A-B4, A-S2, A-S6, A-N3).
package app.thebox.core.storage.smb

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingHeaderCodec
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageLimits
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.locks.ReentrantLock

/**
 * The ring file in `<path>/TheBox-rewind/` on a network folder (§4.1), over the keeper's [link]. **No I/O** in
 * construction, `writeHeader`, `allocate`, `close` or `delete` (Must, A-B1): the file is created once by [create] on the
 * flusher (called by `FallbackRingStorage` before the first `pwrite`), reopens use `OPEN_EXISTING` (A-S2), interrupts are
 * cancels (A-B3) and only transport errors reconnect (A-B4). [relativePath] is relative to the share, `/`-separated.
 *
 * **Threads.** [create], [pwrite], [availableBytes] and [isUnlinked] run on the ring's dedicated flusher; [pread] on Media3
 * loader threads, concurrently with writes on the same handle (§4.2); [close]/[delete] on any thread, and they only post.
 *
 * **Failures (§4.3).** Every IOException goes through [RemoteLink.classify], interrupt first:
 * - CANCEL → `InterruptedIOException`; nothing else changes (no reconnect, no [lastFailure], epoch unchanged).
 * - LINK_LOST → one single-flight `link.reconnect(epoch)` (shared by the flusher and the readers), a reopen with
 *   `OPEN_EXISTING` and a retry at the same offset, until `SMB_RECONNECT_BUDGET_MS` after the operation's first failure
 *   (never past [RemoteLink.ioTimeoutMs]); then UNREACHABLE. Any reconnect sets [needsFreshFile] (§4.5).
 * - SLOW → fail at once; on a write also [needsFreshFile] (a timed-out WRITE may land later, §4.5).
 * - FULL / ACCESS → fail; REMOVED → [gone] (`isUnlinked()` → `STORAGE_EVICTED`).
 * A write failure sets [lastFailure]; read failures surface as the ring's `RingIoException` and leave it alone.
 *
 * Without a `FallbackRingStorage` in front (tests, tools), the first [pwrite] calls [create] itself with a deadline of one
 * I/O timeout.
 */
class SmbRingStorage(
    private val link: RemoteLink,
    val relativePath: String,
    override val capacityBytes: Long,
    private val limits: StorageLimits,
    private val clock: MonoClock,
) : RemoteRingStorage {
    override val blocking: Boolean get() = true

    private val createdMonoMs = clock.nowMs()
    private val parentDir = relativePath.substringBeforeLast('/', "")

    // ---------------------------------------------------------------- state (volatile: read on any thread)
    @Volatile private var closed = false
    @Volatile private var goneV = false
    @Volatile private var lastFailureV: StorageFailure? = null
    @Volatile private var freshV = false
    private val written = AtomicLong()
    @Volatile private var createAttempted = false
    @Volatile private var created = false

    /** The handle and the share it was opened on; replaced only under [stateLock] (reopen, close). */
    @Volatile private var file: RemoteFile? = null
    @Volatile private var share: RemoteShare? = null
    @Volatile private var fileEpoch = -1L
    private var retained = false
    private val stateLock = Any()
    private val createLock = Any()
    /** Single flight of the reopen after a reconnect (flusher + readers); interruptible (A-B3). */
    private val reopenLock = ReentrantLock()

    // ---------------------------------------------------------------- header (A-N3: two volatiles, no shared buffer)
    @Volatile private var hdrGen = 0L
    @Volatile private var hdrCap = 0L
    @Volatile private var headerDirty = false

    // ---------------------------------------------------------------- scratch
    private var writeScratch: ByteArray? = null                 // flusher only
    private val readScratch = ThreadLocal<ByteArray>()          // one per loader thread (JVM direct staging)
    private val rates = Rates()

    // ================================================================ RemoteRingStorage

    override val gone: Boolean get() = goneV
    override val lastFailure: StorageFailure? get() = lastFailureV
    override val needsFreshFile: Boolean get() = freshV
    override val remoteBytesWritten: Long get() = written.get()

    override fun create(deadlineMonoMs: Long): Boolean = synchronized(createLock) {
        if (created) return true
        if (createAttempted || closed) return false
        createAttempted = true
        try {
            val sh = link.share(deadlineMonoMs)
            checkDeadline(deadlineMonoMs)
            if (parentDir.isNotEmpty()) sh.mkdirs(parentDir)
            checkDeadline(deadlineMonoMs)
            val f = sh.open(relativePath, OpenMode.CREATE_OVERWRITE)
            var discard = false
            synchronized(stateLock) {
                if (closed) discard = true
                else { file = f; share = sh; fileEpoch = sh.epoch; link.retain(); retained = true }
            }
            if (discard) { f.closeAsync(); return false }
            if (clock.nowMs() > deadlineMonoMs) { lastFailureV = StorageFailure.UNREACHABLE; return false }
            writeHeaderNow(f)
            created = true
            true
        } catch (e: DeadlineException) {
            lastFailureV = StorageFailure.UNREACHABLE
            false
        } catch (e: IOException) {
            val fault = link.classify(e)
            if (fault.cls == FaultClass.CANCEL) {
                Thread.currentThread().interrupt() // keep the cancel visible to the caller
            } else {
                if (fault.cls == FaultClass.REMOVED) goneV = true
                lastFailureV = fault.failure
            }
            false
        }
    }

    override fun ratesBps(): LongArray = rates.bps(clock.nowMs())

    // ================================================================ RingStorage

    /** No I/O, ever (A-S6): sequential writes extend the file and readers never read past `flushed`. */
    override fun allocate(upToBytes: Long): Long = minOf(upToBytes, TimeshiftConstants.RING_HEADER_BYTES + capacityBytes)

    override fun pwrite(src: ByteBuffer, filePosition: Long): Int {
        if (closed || goneV) throw IOException("ring storage closed")
        if (!created) {
            if (createAttempted || !create(clock.nowMs() + link.ioTimeoutMs)) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("ring write cancelled")
                throw IOException("ring file not created")
            }
        }
        if (headerDirty) file?.let { writeHeaderNow(it) }
        val len = src.remaining()
        if (len == 0) return 0
        val arr: ByteArray
        val off: Int
        if (src.hasArray()) {
            arr = src.array(); off = src.arrayOffset() + src.position()
        } else {
            arr = writeScratch?.takeIf { it.size >= len } ?: ByteArray(maxOf(len, TimeshiftConstants.RING_BLOCK_BYTES)).also { writeScratch = it }
            src.duplicate().get(arr, 0, len); off = 0
        }
        retrying(write = true) { f -> f.write(arr, off, len, filePosition) }
        src.position(src.position() + len)
        written.addAndGet(len.toLong())
        rates.add(0, clock.nowMs(), len.toLong())
        return len
    }

    override fun pread(dst: ByteBuffer, filePosition: Long): Int {
        if (closed || goneV) throw IOException("ring storage closed")
        if (file == null) throw IOException("ring file not open") // a reader never opens or creates (A-S2)
        val len = dst.remaining()
        if (len == 0) return 0
        val arr: ByteArray
        val off: Int
        if (dst.hasArray()) {
            arr = dst.array(); off = dst.arrayOffset() + dst.position()
        } else {
            arr = readScratch.get()?.takeIf { it.size >= len } ?: ByteArray(maxOf(len, TimeshiftConstants.READER_STAGING_BYTES)).also { readScratch.set(it) }
            off = 0
        }
        var got = 0
        while (got < len) {
            val n = retrying(write = false) { f -> f.read(arr, off + got, len - got, filePosition + got) }
            if (n < 0) break
            if (n == 0) throw IOException("ring read returned 0")
            got += n
        }
        if (got > 0) {
            if (!dst.hasArray()) dst.put(arr, 0, got) else dst.position(dst.position() + got)
            rates.add(1, clock.nowMs(), got.toLong())
        }
        return if (got == 0) -1 else got
    }

    override fun isUnlinked(): Boolean = goneV

    override fun availableBytes(): Long {
        val sh = share ?: return limits.reportedAvailableBytes(Long.MAX_VALUE / 4) // not created yet: unknown, never halts growth
        val free = try { sh.freeBytes() } catch (e: IOException) {
            if (link.classify(e).cls == FaultClass.CANCEL) Thread.currentThread().interrupt()
            return 0L // §4.1: a failure answers 0 (growth halts)
        }
        return limits.reportedAvailableBytes(free)
    }

    override fun writeHeader(generation: Long, capacity: Long) {
        hdrGen = generation; hdrCap = capacity; headerDirty = true
    }

    override fun delete() {
        close()
        link.post {
            try { link.share(clock.nowMs()).delete(relativePath) } catch (_: IOException) { /* left to the sweep (§3.5) */ }
        }
    }

    override fun close() {
        val f: RemoteFile?
        val release: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true; goneV = true
            f = file; file = null; share = null
            release = retained; retained = false
        }
        if (f != null || release) link.post {
            f?.closeAsync()
            if (release) link.release()
        }
    }

    // ================================================================ internals

    private class DeadlineException : IOException("deadline passed")

    private companion object {
        /** Pause between two failed reconnect attempts of one operation (the link enforces SMB_RECONNECT_MIN_GAP_MS too). */
        const val RECONNECT_RETRY_PAUSE_MS = 250L
    }

    private fun checkDeadline(deadlineMonoMs: Long) { if (clock.nowMs() > deadlineMonoMs) throw DeadlineException() }

    /** Writes the header at file position 0 (flusher), clearing [headerDirty] first so a concurrent `writeHeader` wins. */
    private fun writeHeaderNow(f: RemoteFile) {
        headerDirty = false
        val b = RingHeaderCodec.encode(hdrGen, hdrCap, createdMonoMs, 0)
        retrying(write = true, start = f) { h -> h.write(b.array(), b.arrayOffset(), b.remaining(), 0L) }
    }

    /**
     * Runs [op] on the current handle with the §4.3 rules. LINK_LOST reconnects (single flight) and retries at the same
     * offset within the budget; every other class fails at once.
     */
    private inline fun <T> retrying(write: Boolean, start: RemoteFile? = null, op: (RemoteFile) -> T): T {
        var firstFailAt = Long.MIN_VALUE
        var f: RemoteFile = start ?: file ?: throw IOException("ring file not open")
        var epochOfF = fileEpoch
        while (true) {
            if (closed) throw IOException("ring storage closed")
            try {
                return op(f)
            } catch (e: IOException) {
                val fault = link.classify(e)
                when (fault.cls) {
                    FaultClass.CANCEL -> throw (e as? InterruptedIOException ?: InterruptedIOException("ring I/O cancelled").also { it.initCause(e) })
                    FaultClass.LINK_LOST -> {
                        val now = clock.nowMs()
                        if (firstFailAt == Long.MIN_VALUE) firstFailAt = now
                        freshV = true // a reconnect is coming; an in-flight WRITE may still land later (§4.5)
                        val deadline = firstFailAt + minOf(StorageConstants.SMB_RECONNECT_BUDGET_MS, link.ioTimeoutMs)
                        if (closed || now >= deadline || !reopen(epochOfF, deadline)) {
                            if (write) lastFailureV = if (goneV) StorageFailure.REMOVED else StorageFailure.UNREACHABLE
                            throw IOException("network folder unreachable", e)
                        }
                        f = file ?: throw IOException("ring storage closed")
                        epochOfF = fileEpoch
                    }
                    FaultClass.SLOW -> {
                        if (write) { freshV = true; lastFailureV = StorageFailure.SLOW }
                        throw e
                    }
                    FaultClass.REMOVED -> {
                        goneV = true
                        if (write) lastFailureV = StorageFailure.REMOVED
                        throw e
                    }
                    FaultClass.FULL, FaultClass.ACCESS -> {
                        if (write) lastFailureV = fault.failure
                        throw e
                    }
                }
            }
        }
    }

    /**
     * After LINK_LOST on a handle of [failedEpoch]: one thread reconnects and reopens with `OPEN_EXISTING` (never
     * truncating, A-S2); the others find the new handle. False when the budget passes or the file is gone. Waits are
     * interruptible: an interrupt surfaces as `InterruptedIOException` (CANCEL, A-B3).
     */
    private fun reopen(failedEpoch: Long, deadlineMonoMs: Long): Boolean {
        val waitMs = deadlineMonoMs - clock.nowMs()
        if (waitMs <= 0) return false
        try {
            if (!reopenLock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw InterruptedIOException("ring reconnect cancelled")
        }
        try {
            var failed = failedEpoch
            while (true) {
                if (closed) return false
                if (fileEpoch > failed && file != null) return true // another thread already reopened
                if (clock.nowMs() >= deadlineMonoMs) return false
                val sh = try { link.reconnect(failed, deadlineMonoMs) } catch (e: IOException) {
                    if (link.classify(e).cls == FaultClass.CANCEL) throw (e as? InterruptedIOException ?: InterruptedIOException("ring reconnect cancelled"))
                    // The server is not back yet: try again until the budget passes (the link spaces its attempts).
                    val left = deadlineMonoMs - clock.nowMs()
                    if (left <= 0 || closed) return false
                    try { Thread.sleep(minOf(left, RECONNECT_RETRY_PAUSE_MS)) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt(); throw InterruptedIOException("ring reconnect cancelled")
                    }
                    continue
                }
                if (closed) return false
                val nf = try { sh.open(relativePath, OpenMode.OPEN_EXISTING) } catch (e: IOException) {
                    when (link.classify(e).cls) {
                        FaultClass.CANCEL -> throw (e as? InterruptedIOException ?: InterruptedIOException("ring reopen cancelled"))
                        FaultClass.LINK_LOST -> { failed = sh.epoch; continue }
                        FaultClass.REMOVED -> { goneV = true; return false }
                        else -> return false
                    }
                }
                val old: RemoteFile?
                var discard = false
                synchronized(stateLock) {
                    if (closed) { discard = true; old = null }
                    else { old = file; file = nf; share = sh; fileEpoch = sh.epoch }
                }
                if (discard) { nf.closeAsync(); return false }
                old?.closeAsync() // dead with its connection; the server drops it at its own timeout (§4.1 full sharing)
                return true
            }
        } finally { reopenLock.unlock() }
    }

    /** Bytes per second over the last [WINDOW_S] seconds, per direction (0 = write, 1 = read). Allocation-free. */
    private class Rates {
        private val bytes = AtomicLongArray(2 * WINDOW_S)
        private val stamp = AtomicLongArray(2 * WINDOW_S)

        fun add(dir: Int, nowMs: Long, n: Long) {
            val sec = nowMs / 1_000L
            val i = dir * WINDOW_S + (sec % WINDOW_S).toInt()
            if (stamp.get(i) != sec) { stamp.set(i, sec); bytes.set(i, 0L) }
            bytes.addAndGet(i, n)
        }

        fun bps(nowMs: Long): LongArray {
            val sec = nowMs / 1_000L
            val out = LongArray(2)
            for (d in 0..1) {
                var sum = 0L
                for (k in 0 until WINDOW_S) {
                    val i = d * WINDOW_S + k
                    if (sec - stamp.get(i) < WINDOW_S) sum += bytes.get(i)
                }
                out[d] = sum / WINDOW_S
            }
            return out
        }

        companion object { const val WINDOW_S = 10 }
    }
}
