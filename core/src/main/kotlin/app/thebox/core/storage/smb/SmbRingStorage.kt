// WP-S1 (docs/design/m6-storage-locations.md §4.1–§4.5; A-B1, A-B3, A-B4, A-S2, A-S6, A-N3). Fix pass, core half
// (2026-10-24): C-B1, C-S3, C-S4, C-N1, C-N2, C-N3.
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
 * **Threads.** [create] runs on `FallbackRingStorage`'s create thread (C-B1) or the flusher; [pwrite], [availableBytes]
 * and [isUnlinked] on the ring's dedicated flusher; [pread] on Media3 loader threads, concurrently with writes on the
 * same handle (§4.2); [close]/[delete] on any thread, and they only post.
 *
 * **Failures (§4.3).** Every IOException goes through [RemoteLink.classify], interrupt first:
 * - CANCEL → `InterruptedIOException`; nothing else changes (no reconnect, no [lastFailure], epoch unchanged).
 * - LINK_LOST → one single-flight `link.reconnect(epoch)` (shared by the flusher and the readers), a reopen with
 *   `OPEN_EXISTING` and a retry at the same offset, until `SMB_RECONNECT_BUDGET_MS` after the operation's first failure
 *   (never past [RemoteLink.ioTimeoutMs]); then UNREACHABLE. Any reconnect sets [needsFreshFile] (§4.5). (C-S3) A
 *   LINK_LOST inside [create] reconnects once, within the create's deadline, then starts the create again.
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
    /** C-N1: the header generation when [freshV] was first set; a later generation must not write into this file. */
    @Volatile private var freshGeneration = Long.MIN_VALUE
    /** C-N1: the generation of the header last written to the file; data writes belong to it. Flusher only. */
    @Volatile private var writtenGeneration = Long.MIN_VALUE
    private val written = AtomicLong()
    @Volatile private var createAttempted = false
    @Volatile private var created = false
    /** C-N2: a CREATE went to the server, so a delete has a file to remove. */
    @Volatile private var createSent = false
    /** C-B1: a delete came while (or before) the create ran: a late handle's file is removed too. */
    @Volatile private var deleteRequested = false
    /** C-S4: the last free space the server answered (−1 = never), the answer when a later query fails. */
    @Volatile private var lastFreeV = -1L

    /** C-N3: the handle, the share it was opened on and its epoch, swapped as one; replaced only under [stateLock]. */
    private class Handle(val file: RemoteFile, val share: RemoteShare) { val epoch: Long get() = share.epoch }
    @Volatile private var handle: Handle? = null
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

    override fun create(deadlineMonoMs: Long): Boolean = synchronized(createLock) { createLocked(deadlineMonoMs) }

    private fun createLocked(deadlineMonoMs: Long): Boolean {
        if (created) return true
        if (createAttempted || closed) return false
        createAttempted = true
        var reconnected = false
        var sh: RemoteShare? = null
        while (true) {
            try {
                val s = sh ?: link.share(deadlineMonoMs)
                sh = s
                checkDeadline(deadlineMonoMs)
                if (parentDir.isNotEmpty()) s.mkdirs(parentDir)
                checkDeadline(deadlineMonoMs)
                createSent = true // C-N2
                val f = s.open(relativePath, OpenMode.CREATE_OVERWRITE)
                var discard = false
                synchronized(stateLock) {
                    if (closed) discard = true
                    else { handle = Handle(f, s); link.retain(); retained = true }
                }
                if (discard) {
                    // C-B1: closed while the CREATE was in flight (the switch came first): close the late handle and,
                    // when a delete was asked for, remove its file. Both posted, never on this thread's caller.
                    f.closeAsync()
                    if (deleteRequested) postRm()
                    return false
                }
                if (clock.nowMs() > deadlineMonoMs) { lastFailureV = StorageFailure.UNREACHABLE; return false }
                writeHeaderNow(f)
                created = true
                return true
            } catch (e: DeadlineException) {
                lastFailureV = StorageFailure.UNREACHABLE
                return false
            } catch (e: IOException) {
                if (closed) return false
                val fault = link.classify(e)
                if (fault.cls == FaultClass.CANCEL) {
                    Thread.currentThread().interrupt() // keep the cancel visible to the caller
                    return false
                }
                // C-S3: a connection that died since the keeper's last keepalive (a standby, a router reboot) is one
                // ~100 ms reconnect away; never switch to this device for it. Once, inside the create's deadline.
                if (fault.cls == FaultClass.LINK_LOST && !reconnected && clock.nowMs() < deadlineMonoMs) {
                    reconnected = true
                    val failedEpoch = sh?.epoch ?: link.epoch
                    sh = try { link.reconnect(failedEpoch, deadlineMonoMs) } catch (re: IOException) {
                        if (link.classify(re).cls == FaultClass.CANCEL) Thread.currentThread().interrupt()
                        else lastFailureV = StorageFailure.UNREACHABLE
                        return false
                    }
                    continue
                }
                if (fault.cls == FaultClass.REMOVED) goneV = true
                lastFailureV = fault.failure
                return false
            }
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
        // C-N1: a reconnect (or a timed-out WRITE) of an earlier generation may still land in this file; a new
        // generation never writes into it (the zap's reuse check ran before that flag was set). RAM-only, inside the grace.
        if (freshV && freshGeneration != hdrGen) {
            lastFailureV = lastFailureV ?: StorageFailure.UNREACHABLE
            throw IOException("network folder file needs replacing after a reconnect")
        }
        if (headerDirty) handle?.let { writeHeaderNow(it.file) }
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
        if (handle == null) throw IOException("ring file not open") // a reader never opens or creates (A-S2)
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

    /**
     * C-S4: the free space, minus the reserve offset (§3.4). Only a FULL answer halts growth (0); a timeout, a lost link
     * or a cancel answers the last value the server gave, else "unknown, never halts" — a busy NAS must not shrink the
     * cycle's ring for good (`TsRingImpl.maybeShrink`). A real FULL also fails the next `pwrite` (`DISK_ERROR (FULL)`).
     */
    override fun availableBytes(): Long {
        val sh = handle?.share ?: return limits.reportedAvailableBytes(Long.MAX_VALUE / 4) // not created yet: unknown, never halts growth
        val free = try { sh.freeBytes() } catch (e: IOException) {
            val cls = link.classify(e).cls
            if (cls == FaultClass.CANCEL) Thread.currentThread().interrupt()
            if (cls == FaultClass.FULL) return 0L
            val last = lastFreeV
            return limits.reportedAvailableBytes(if (last >= 0) last else Long.MAX_VALUE / 4)
        }
        lastFreeV = free
        return limits.reportedAvailableBytes(free)
    }

    override fun writeHeader(generation: Long, capacity: Long) {
        hdrGen = generation; hdrCap = capacity; headerDirty = true
    }

    /** Never waits (A-B1). C-N2: the `rm` is posted only when a CREATE was sent; nothing else exists on the server. */
    override fun delete() {
        deleteRequested = true
        close()
        if (createSent) postRm()
    }

    override fun close() {
        val f: RemoteFile?
        val release: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true; goneV = true
            f = handle?.file; handle = null
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

    private fun postRm() = link.post {
        try { link.share(clock.nowMs()).delete(relativePath) } catch (_: IOException) { /* left to the sweep (§3.5) */ }
    }

    /** C-B1: a create step past its deadline, or for a storage closed meanwhile (the switch came first), stops here. */
    private fun checkDeadline(deadlineMonoMs: Long) { if (closed || clock.nowMs() > deadlineMonoMs) throw DeadlineException() }

    /** C-N1: [opGeneration] is the generation of the operation that may still land (a header's, or the data's). */
    private fun markFresh(opGeneration: Long) {
        if (!freshV) { freshGeneration = opGeneration; freshV = true }
    }

    /** Writes the header at file position 0 (flusher), clearing [headerDirty] first so a concurrent `writeHeader` wins. */
    private fun writeHeaderNow(f: RemoteFile) {
        headerDirty = false
        val g = hdrGen
        val b = RingHeaderCodec.encode(g, hdrCap, createdMonoMs, 0)
        retrying(write = true, start = f, opGeneration = g) { h -> h.write(b.array(), b.arrayOffset(), b.remaining(), 0L) }
        writtenGeneration = g
    }

    /**
     * Runs [op] on the current handle with the §4.3 rules. LINK_LOST reconnects (single flight) and retries at the same
     * offset within the budget; every other class fails at once.
     */
    private inline fun <T> retrying(write: Boolean, start: RemoteFile? = null, opGeneration: Long = writtenGeneration, op: (RemoteFile) -> T): T {
        var firstFailAt = Long.MIN_VALUE
        // C-N3: the handle and its epoch are read once, together; a reopen between two reads can no longer pair the old
        // handle with the new epoch (and then tear down the healthy new connection).
        val h0 = handle
        var f: RemoteFile = start ?: h0?.file ?: throw IOException("ring file not open")
        var epochOfF = if (start == null || h0?.file === start) h0?.epoch ?: link.epoch else link.epoch
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
                        markFresh(opGeneration) // a reconnect is coming; an in-flight WRITE may still land later (§4.5)
                        val deadline = firstFailAt + minOf(StorageConstants.SMB_RECONNECT_BUDGET_MS, link.ioTimeoutMs)
                        if (closed || now >= deadline || !reopen(epochOfF, deadline)) {
                            if (write) lastFailureV = if (goneV) StorageFailure.REMOVED else StorageFailure.UNREACHABLE
                            throw IOException("network folder unreachable", e)
                        }
                        val h = handle ?: throw IOException("ring storage closed")
                        f = h.file
                        epochOfF = h.epoch
                    }
                    FaultClass.SLOW -> {
                        if (write) { markFresh(opGeneration); lastFailureV = StorageFailure.SLOW }
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
                handle?.let { if (it.epoch > failed) return true } // another thread already reopened
                if (clock.nowMs() >= deadlineMonoMs) return false
                val sh = try { link.reconnect(failed, deadlineMonoMs) } catch (e: IOException) {
                    if (link.classify(e).cls == FaultClass.CANCEL) throw (e as? InterruptedIOException ?: InterruptedIOException("ring reconnect cancelled"))
                    // The server is not back yet (or the link was retired, C-S1): try again until the budget passes.
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
                    else { old = handle?.file; handle = Handle(nf, sh) }
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
