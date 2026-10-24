// WP-S2 (docs/design/m6-storage-locations.md §5.2, §3.4, §4.2; lead rulings 2 and 5; A-B1). Fix pass, core half
// (2026-10-24): C-B1 (the switch is bounded by a timer and by the pool), C-S3 (the switch is reported).
package app.thebox.core.storage

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingPoolWatcher
import app.thebox.core.playback.timeshift.RingStorage
import app.thebox.core.playback.timeshift.TimeshiftConstants
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The [RingStorage] the ring sees for every SMB cycle (§5.2, lead ruling 2): the first `pwrite` (flusher) starts
 * `primary.create(now + deadlineMs)`; true → delegate to [primary] from then on; false (deadline or any failure, nothing
 * has reached the folder) → delete the primary (posted), build [secondary] on the flusher (this device, with the wear
 * counter), write the header there and delegate to it; [switchedTo] = INTERNAL, [fallbackReason] from the primary's
 * `lastFailure`. No switch after a successful create. [blocking] is true, so the ring keeps its dedicated flusher.
 *
 * **C-B1: the switch never waits on the folder.** `create` runs on its own short-lived thread ([creator], never
 * `box-smb`), because one smbj round trip can take up to `SMB_IO_TIMEOUT_MS` and a create is several. The flusher waits
 * for its answer in [TICK_MS] slices and switches at the first of:
 * - [deadlineMs] after the first `pwrite` (`SMB_OPEN_DEADLINE_MS`, or `_SMALL_POOL_MS` for the 96-block pool);
 * - the time the ring's RAM pool lasts at the measured rate (never below `ASSUMED_BYTES_PER_SEC`) times [POOL_MARGIN];
 * - the pool [SWITCH_AT_POOL_USE] full.
 * So a 4K channel (≈ 3.5 MB/s fills 160 blocks in ≈ 3 s) still switches before the pump's `PUMP_BLOCK_MAX_MS` wait ends
 * in `DISK_TOO_SLOW`. A create that answers after the switch is discarded off the flusher: the primary is deleted at the
 * switch (`close` + a posted `rm` once a CREATE was sent), so its late handle is closed and its file removed. The pool
 * figures come from the ring ([RingPoolWatcher], attached by `TsRingImpl`); without them the assumed rate bounds it.
 *
 * **C-S3:** [onSwitched] (flusher, must not block) hears every switch with the primary's failure, so the target source
 * can demote a folder that refuses the file or kick the keeper; the factory wires it.
 *
 * [deadlineMs] is SMB_OPEN_DEADLINE_MS (160-block pool) or SMB_OPEN_DEADLINE_SMALL_POOL_MS (96). No I/O in the
 * constructor (A-B1).
 *
 * Before the decision (the ring's constructor, `reset()`, the flusher's first extent and space checks) nothing touches
 * the network: [writeHeader] goes to the primary (which only records it, §3.6), [allocate] answers the bound without I/O
 * (the high-water mark is replayed on the secondary at the switch, so its extents are really reserved), [availableBytes]
 * answers "plenty" (the cycle was sized at `prepare`, §5.1) and [isUnlinked] is false.
 *
 * [close] and [delete] never wait (A-B1): the primary posts its own work; the secondary (a local file) is closed or
 * deleted on [background] (`box-ts-discard` in production).
 *
 * WP-S2 additions (not in the commit-0 stub): [firstWriteMonoMs] / [beginCycle] for the start-of-cycle grace (§5.4),
 * [secondaryStorage] for the factory's wear and status reporting, [background].
 */
class FallbackRingStorage(
    val primary: RemoteRingStorage,
    private val secondary: () -> RingStorage,
    val deadlineMs: Long,
    private val clock: MonoClock,
    private val background: (Runnable) -> Unit = { it.run() },
    /** C-B1: where `primary.create` runs (a new daemon thread per storage in production; tests may run it inline). */
    private val creator: (Runnable) -> Unit = { r -> Thread(r, "box-smb-create").apply { isDaemon = true }.start() },
) : RingStorage, RingPoolWatcher {
    /** Null until the switch; then the kind written to ([StorageKind.INTERNAL]). */
    @Volatile var switchedTo: StorageKind? = null; private set
    /** Why the cycle switched (UNREACHABLE, SIGN_IN_NEEDED, NO_SPACE_THERE); null while not switched. */
    @Volatile var fallbackReason: LocationFallback? = null; private set

    /** The local storage after the switch; null before (or when the switch failed). */
    @Volatile var secondaryStorage: RingStorage? = null; private set

    /** Mono ms of the current cycle's first successful `pwrite`; −1 until then (§5.4 grace). */
    @Volatile var firstWriteMonoMs: Long = -1L; private set

    /** C-S3: told (on the flusher; must not block) when the cycle switched, with the primary's failure. */
    @Volatile var onSwitched: ((StorageFailure?) -> Unit)? = null

    private val lock = Any()
    /** Where calls go after the decision: [primary] or the secondary. Null = undecided. */
    @Volatile private var target: RingStorage? = null
    @Volatile private var closed = false
    @Volatile private var deleteRequested = false
    /** Ensures the decision runs once (the flusher is the only caller of [pwrite], but stay safe). */
    private var decided = false
    /** C-B1: the ring stopped saving before the decision; the switch must not announce a fallback (one toast). */
    @Volatile private var abandoned = false
    @Volatile private var headerGeneration = Long.MIN_VALUE
    @Volatile private var headerCapacity = 0L
    /** The largest file size the ring asked [allocate] for before the decision (replayed on the secondary). */
    @Volatile private var allocatedHigh = 0L
    /** C-B1: the ring's RAM pool and its unflushed bytes ([RingPoolWatcher]); 0 / null = unknown. */
    @Volatile private var poolBytes = 0L
    @Volatile private var unflushed: (() -> Long)? = null

    override val capacityBytes: Long get() = primary.capacityBytes
    override val blocking: Boolean get() = true

    /** True until the first `pwrite` decided where the cycle saves (C-B1: a stop then is not the folder's to report). */
    val undecided: Boolean get() = target == null

    /** The factory starts a new cycle on this storage (a zap reuse): the grace counts from that cycle's first write. */
    fun beginCycle() { firstWriteMonoMs = -1L }

    /**
     * C-B1: the ring stopped saving (a disk stop) while the decision was still open. Returns true when it was open: the
     * decision then ends without a fallback announcement, so the viewer gets the stop's one toast, not a second.
     */
    fun abandonUndecided(): Boolean = synchronized(lock) {
        if (target != null) false else { abandoned = true; true }
    }

    override fun attachPool(poolBytes: Long, unflushedBytes: () -> Long) {
        this.poolBytes = poolBytes
        this.unflushed = unflushedBytes
    }

    override fun allocate(upToBytes: Long): Long {
        val t = target
        if (t != null) return t.allocate(upToBytes)
        val bounded = minOf(upToBytes, HEADER + capacityBytes)
        if (bounded > allocatedHigh) allocatedHigh = bounded
        return bounded
    }

    override fun pwrite(src: ByteBuffer, filePosition: Long): Int {
        val t = target ?: decide()
        val n = t.pwrite(src, filePosition)
        if (n > 0 && firstWriteMonoMs < 0) firstWriteMonoMs = clock.nowMs()
        return n
    }

    override fun pread(dst: ByteBuffer, filePosition: Long): Int {
        val t = target ?: throw IOException("ring file not created yet")
        return t.pread(dst, filePosition)
    }

    override fun isUnlinked(): Boolean {
        if (closed) return true
        return target?.isUnlinked() ?: false
    }

    override fun availableBytes(): Long = target?.availableBytes() ?: Long.MAX_VALUE

    override fun writeHeader(generation: Long, capacity: Long) {
        headerGeneration = generation
        headerCapacity = capacity
        val t = target
        if (t != null) t.writeHeader(generation, capacity) else primary.writeHeader(generation, capacity)
    }

    override fun delete() {
        deleteRequested = true
        closeInternal(delete = true)
    }

    override fun close() = closeInternal(delete = deleteRequested)

    private fun closeInternal(delete: Boolean) {
        val sec: RingStorage?
        synchronized(lock) {
            val wasClosed = closed
            closed = true
            sec = secondaryStorage
            if (wasClosed && !delete) return
        }
        // The primary posts its own CLOSE / rm (A-B1); never on this thread.
        try { if (delete) primary.delete() else primary.close() } catch (t: Throwable) { /* best effort; the sweep cleans */ }
        if (sec != null) background(Runnable {
            try { if (delete) sec.delete() else sec.close() } catch (t: Throwable) { /* best effort */ }
        })
    }

    /** Flusher: the one decision of §5.2. */
    private fun decide(): RingStorage {
        synchronized(lock) {
            target?.let { return it }
            if (closed) throw IOException("ring storage closed")
            if (decided) throw IOException("ring storage unusable after a failed switch")
            decided = true
        }
        val ok = awaitCreate() // C-B1: bounded by the deadline and the pool, whatever each create step takes
        if (closed) throw IOException("ring storage closed")
        if (ok && !abandoned) {
            synchronized(lock) { target = primary }
            return primary
        }
        // The early switch: nothing has reached the folder (the create never succeeded, or answered too late). Guard
        // anyway: a primary that wrote bytes cannot be abandoned under the ring's [fileValidFrom, flushed) range (§18 R2).
        if (primary.remoteBytesWritten > 0) throw IOException("network folder failed after its first write")
        val failure = primary.lastFailure
        val reason = when (failure) {
            StorageFailure.ACCESS -> LocationFallback.SIGN_IN_NEEDED
            StorageFailure.FULL -> LocationFallback.NO_SPACE_THERE
            else -> LocationFallback.UNREACHABLE
        }
        // Deleted, not only closed: a late CREATE is then closed and its file removed off the flusher (C-B1).
        try { primary.delete() } catch (t: Throwable) { /* posted; best effort */ }
        if (abandoned) throw IOException("ring stopped saving before the network folder answered")
        try { onSwitched?.invoke(failure ?: StorageFailure.UNREACHABLE) } catch (t: Throwable) { /* never fails the flusher */ }
        val sec = secondary()
        try {
            if (allocatedHigh > 0) sec.allocate(allocatedHigh)
            if (headerGeneration != Long.MIN_VALUE) sec.writeHeader(headerGeneration, headerCapacity)
        } catch (e: IOException) {
            background(Runnable { try { sec.delete() } catch (t: Throwable) { } })
            throw e
        }
        val closedMeanwhile: Boolean
        synchronized(lock) {
            closedMeanwhile = closed || abandoned
            if (!closedMeanwhile) {
                secondaryStorage = sec
                switchedTo = StorageKind.INTERNAL
                fallbackReason = reason
                target = sec
            }
        }
        if (closedMeanwhile) {
            background(Runnable { try { sec.delete() } catch (t: Throwable) { } })
            throw IOException("ring storage closed")
        }
        return sec
    }

    /**
     * C-B1: runs `primary.create` on [creator] and waits on the flusher until it answers, or until the switch is due
     * ([switchDue]). False = switch (a refusal, the deadline, the pool, or a close).
     */
    private fun awaitCreate(): Boolean {
        val start = clock.nowMs()
        val answer = ArrayBlockingQueue<Boolean>(1)
        creator(Runnable {
            val ok = try { primary.create(start + deadlineMs) } catch (e: IOException) { false } catch (e: RuntimeException) { false }
            answer.offer(ok)
        })
        val used0 = poolUse()
        while (true) {
            if (closed || abandoned) return false
            val r = try { answer.poll(TICK_MS, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("ring storage decision cancelled")
            }
            if (r != null) return r
            if (switchDue(start, used0)) return false
        }
    }

    /** C-B1: the deadline, the pool's time at the measured rate × [POOL_MARGIN], or the pool [SWITCH_AT_POOL_USE] full. */
    private fun switchDue(start: Long, used0: Long): Boolean {
        val elapsed = clock.nowMs() - start
        if (elapsed >= deadlineMs) return true
        val pool = poolBytes
        if (pool <= 0L) return false // no pool figures (tests, tools): the deadline alone
        val used = poolUse()
        if (used >= (pool * SWITCH_AT_POOL_USE).toLong()) return true
        val measured = if (elapsed >= RATE_MIN_MS) (used - used0).coerceAtLeast(0L) * 1_000L / elapsed else 0L
        val rate = maxOf(measured, TimeshiftConstants.ASSUMED_BYTES_PER_SEC)
        val bound = ((pool - used0).coerceAtLeast(0L) * 1_000L / rate * POOL_MARGIN).toLong()
        return elapsed >= bound
    }

    private fun poolUse(): Long = try { unflushed?.invoke() ?: 0L } catch (e: RuntimeException) { 0L }

    companion object {
        private const val HEADER = TimeshiftConstants.RING_HEADER_BYTES.toLong()
        /** C-B1: how often the flusher re-checks the deadline and the pool while the create runs. */
        const val TICK_MS = 10L
        /** C-B1: switch when the pool is this full (25 % free), whatever the deadline says. */
        const val SWITCH_AT_POOL_USE = 0.75
        /** C-B1: switch by this fraction of the time the pool lasts at the measured (or assumed) rate. */
        const val POOL_MARGIN = 0.6
        /** C-B1: the rate is measured only after this long (the first blocks come in bursts). */
        const val RATE_MIN_MS = 200L
    }
}
