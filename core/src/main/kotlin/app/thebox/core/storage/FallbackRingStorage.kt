// WP-S2 (docs/design/m6-storage-locations.md §5.2, §3.4, §4.2; lead rulings 2 and 5; A-B1).
package app.thebox.core.storage

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingStorage
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The [RingStorage] the ring sees for every SMB cycle (§5.2, lead ruling 2): the first `pwrite` (flusher) calls
 * `primary.create(now + deadlineMs)`; true → delegate to [primary] from then on; false (deadline or any failure, nothing
 * has reached the folder) → close the primary (posted), build [secondary] on the flusher (this device, with the wear
 * counter), write the header there and delegate to it; [switchedTo] = INTERNAL, [fallbackReason] from the primary's
 * `lastFailure`. No switch after a successful create. [blocking] is true, so the ring keeps its dedicated flusher.
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
) : RingStorage {
    /** Null until the switch; then the kind written to ([StorageKind.INTERNAL]). */
    @Volatile var switchedTo: StorageKind? = null; private set
    /** Why the cycle switched (UNREACHABLE, SIGN_IN_NEEDED, NO_SPACE_THERE); null while not switched. */
    @Volatile var fallbackReason: LocationFallback? = null; private set

    /** The local storage after the switch; null before (or when the switch failed). */
    @Volatile var secondaryStorage: RingStorage? = null; private set

    /** Mono ms of the current cycle's first successful `pwrite`; −1 until then (§5.4 grace). */
    @Volatile var firstWriteMonoMs: Long = -1L; private set

    private val lock = Any()
    /** Where calls go after the decision: [primary] or the secondary. Null = undecided. */
    @Volatile private var target: RingStorage? = null
    @Volatile private var closed = false
    @Volatile private var deleteRequested = false
    /** Ensures the decision runs once (the flusher is the only caller of [pwrite], but stay safe). */
    private var decided = false
    @Volatile private var headerGeneration = Long.MIN_VALUE
    @Volatile private var headerCapacity = 0L
    /** The largest file size the ring asked [allocate] for before the decision (replayed on the secondary). */
    @Volatile private var allocatedHigh = 0L

    override val capacityBytes: Long get() = primary.capacityBytes
    override val blocking: Boolean get() = true

    /** The factory starts a new cycle on this storage (a zap reuse): the grace counts from that cycle's first write. */
    fun beginCycle() { firstWriteMonoMs = -1L }

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
        val ok = try { primary.create(clock.nowMs() + deadlineMs) } catch (e: IOException) { false }
        if (closed) throw IOException("ring storage closed")
        if (ok) {
            synchronized(lock) { target = primary }
            return primary
        }
        // The early switch: nothing has reached the folder (the create never succeeded). Guard anyway: a primary that
        // wrote bytes cannot be abandoned under the ring's [fileValidFrom, flushed) range (§18 R2).
        if (primary.remoteBytesWritten > 0) throw IOException("network folder failed after its first write")
        val reason = when (primary.lastFailure) {
            StorageFailure.ACCESS -> LocationFallback.SIGN_IN_NEEDED
            StorageFailure.FULL -> LocationFallback.NO_SPACE_THERE
            else -> LocationFallback.UNREACHABLE
        }
        try { primary.close() } catch (t: Throwable) { /* posted; best effort */ }
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
            closedMeanwhile = closed
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

    private companion object {
        const val HEADER = app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES.toLong()
    }
}
