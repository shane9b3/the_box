// WP-A (docs/design/m6-timeshift.md §5.1, §5.3, A-B2, A-S1, A-S8, A-S9).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.PSI_MAX_PACKETS
import app.thebox.core.playback.timeshift.TimeshiftConstants.READER_STAGING_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.READER_WAIT_SLICE_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

/**
 * One reader cursor over the ring (§5.1), opened with [TsRing.openReader].
 *
 * - The segment's PSI (PAT + PMTs, §3.4) comes first, then the bytes from the start offset (a RAP's packet).
 * - [read] returns `n > 0` bytes, whole packets whenever `len ≥ 188`; it never returns end of input. At `head` it parks
 *   in [READER_WAIT_SLICE_MS] slices until a commit, [cancel] or an interrupt (`InterruptedIOException`).
 * - It never crosses a segment boundary: at the next segment's boundary it throws [RingBoundaryException] with that
 *   segment's first RAP (waiting at the boundary until that RAP exists).
 * - At `head` after saving stopped it throws `RingEndException(stop)`; below `tail` `RingPositionLostException`; on a
 *   `pread` failure `RingIoException`; after `reset()` (or on a ring that was closed) `RingEndException(SESSION_END)`.
 * - The cursor is pinned (owner ≥ PinOwner.FIRST_READER), so `tail` never passes it and nothing it reads is overwritten.
 * - Copies (A-S8): from RAM one sequence-checked `arraycopy`; from the file one `pread` into a per-reader direct staging
 *   buffer, then one `arraycopy`. A block recycled during the copy fails the stamp and is re-read from the file (A-B2).
 */
class RingReaderImpl internal constructor(
    private val ring: TsRingImpl,
    override val generation: Long,
    startOffset: Long,
    private val pinId: Int,
    private val fenced: Boolean,
) : RingReader {
    @Volatile private var cur = startOffset
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    @Volatile private var waitingThread: Thread? = null
    private var seg: Int = -1
    private val psi = ByteArray(PSI_MAX_PACKETS * TS_PACKET)
    private var psiLen = 0
    private var psiPos = 0
    private var staging: ByteBuffer? = null

    // counters (§15 copy count; tests)
    @Volatile internal var ramCopies = 0L; private set
    @Volatile internal var fileReads = 0L; private set
    @Volatile internal var stampMisses = 0L; private set
    /** Test hook: runs between the RAM copy and the stamp validation. */
    @Volatile internal var afterRamCopyHook: (() -> Unit)? = null

    init {
        if (!fenced) {
            seg = ring.index.segmentOf(startOffset)
            psiLen = ring.index.copyPsi(seg, psi) * TS_PACKET
        }
    }

    override val cursor: Long get() = cur
    override val segment: Int get() = seg
    override val atHead: Boolean get() = waitingThread != null || ring.head - cur <= RING_BLOCK_BYTES

    @Throws(IOException::class)
    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            if (cancelled || closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("ring read cancelled")
            if (fenced || ring.generation != generation || ring.isClosed) throw RingEndException(RecordStop.SESSION_END)
            if (psiPos < psiLen) {
                var n = minOf(len, psiLen - psiPos)
                if (len >= TS_PACKET) n -= n % TS_PACKET
                System.arraycopy(psi, psiPos, dst, off, n)
                psiPos += n
                return n
            }
            val c = cur
            val t = ring.tail
            if (c < t) throw RingPositionLostException(c, t)
            val ended = ring.ended
            val h = ring.head
            val nb = ring.index.nextBoundary(seg)
            val atBoundary = c >= nb
            if (atBoundary) {
                val next = (seg + 1) and RingIndexImpl.SEG_MASK
                val rap = ring.index.firstRapOffset(next)
                if (rap >= 0) {
                    throw RingBoundaryException(rap, ring.index.segmentReason(next), ring.index.segmentGapMs(next), ring.index.gapStartMono(next))
                }
            } else {
                val limit = minOf(h, nb)
                if (c < limit) return copy(dst, off, len, c, limit)
            }
            if (ended) throw RingEndException(ring.stop ?: RecordStop.SESSION_END)
            await(c, atBoundary)
        }
    }

    override fun cancel() {
        cancelled = true
        waitingThread?.let { LockSupport.unpark(it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelled = true
        waitingThread?.let { LockSupport.unpark(it) }
        ring.releaseReader(pinId, this)
    }

    private fun await(c: Long, atBoundary: Boolean) {
        val me = Thread.currentThread()
        waitingThread = me
        ring.registerWaiter(pinId, me)
        try {
            // Re-check after registering: a commit in between has already unparked us or is visible now.
            if (cancelled || closed || ring.ended || ring.generation != generation || ring.isClosed) return
            if (atBoundary) {
                if (ring.index.firstRapOffset((seg + 1) and RingIndexImpl.SEG_MASK) >= 0) return
            } else if (ring.head > c || ring.index.nextBoundary(seg) <= c) return
            LockSupport.parkNanos(this, READER_WAIT_SLICE_MS * 1_000_000L)
        } finally {
            ring.registerWaiter(pinId, null)
            waitingThread = null
        }
    }

    private fun copy(dst: ByteArray, off: Int, len: Int, c: Long, limit: Long): Int {
        var want = minOf(len.toLong(), limit - c).toInt()
        if (len >= TS_PACKET) {
            val mis = ((c + want) % TS_PACKET).toInt()
            if (want - mis > 0) want -= mis
        }
        var done = 0
        while (done < want) {
            val pos = c + done
            val b = pos / RING_BLOCK_BYTES
            val inBlock = (pos % RING_BLOCK_BYTES).toInt()
            val chunk = minOf(want - done, RING_BLOCK_BYTES - inBlock)
            if (!fromRam(b, inBlock, dst, off + done, chunk)) fromFile(pos, dst, off + done, chunk)
            done += chunk
        }
        cur = c + want
        ring.advancePin(pinId, this, c + want)
        return want
    }

    private fun fromRam(b: Long, inBlock: Int, dst: ByteArray, off: Int, n: Int): Boolean {
        val blk = ring.pool.slotFor(b)
        val st = blk.lock.tryOptimisticRead()
        if (st == 0L || blk.blockNo != b) return false
        System.arraycopy(blk.array, blk.arrayOffset + inBlock, dst, off, n)
        afterRamCopyHook?.invoke()
        if (!blk.lock.validate(st)) { stampMisses++; return false }
        ramCopies++
        return true
    }

    private fun fromFile(pos: Long, dst: ByteArray, off: Int, n: Int) {
        if (pos < ring.fileValidFrom || pos + n > ring.flushed) {
            val t = ring.tail
            if (pos < t) throw RingPositionLostException(pos, t)
            throw RingIoException(IOException("ring bytes at $pos are neither in RAM nor in the file"))
        }
        val buf = staging ?: ByteBuffer.allocateDirect(READER_STAGING_BYTES).also { staging = it }
        (buf as Buffer).clear()
        (buf as Buffer).limit(n)
        try { ring.readFile(buf, pos) } catch (e: IOException) { throw RingIoException(e) }
        (buf as Buffer).flip()
        buf.get(dst, off, n)
        fileReads++
    }
}
