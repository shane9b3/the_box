package app.thebox.core.playback.timeshift

import app.thebox.core.playback.MonoClock
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A settable [MonoClock] (thread-safe). */
class FakeMonoClock(start: Long = 1_000_000L) : MonoClock {
    private val now = AtomicLong(start)
    override fun nowMs(): Long = now.get()
    fun set(ms: Long) = now.set(ms)
    fun advance(ms: Long): Long = now.addAndGet(ms)
}

/**
 * An in-memory [RingStorage] with fault injection (docs/design/m6-timeshift.md §17.1 `FlushTest`): write and read
 * failures, unlink, available space, write latency. Positional calls are allocation-free.
 */
class MemoryRingStorage(override val capacityBytes: Long, var available: Long = Long.MAX_VALUE / 4) : RingStorage {
    val data = ByteArray((TimeshiftConstants.RING_HEADER_BYTES + capacityBytes).toInt())
    @Volatile var failWrites: IOException? = null
    @Volatile var failReads: IOException? = null
    @Volatile var failAllocate: IOException? = null
    @Volatile var unlinked = false
    @Volatile var writeDelayMs = 0L
    @Volatile var closed = false
    @Volatile var deleted = false
    @Volatile var allocated = 0L; private set
    val bytesWritten = AtomicLong()
    val writes = AtomicLong()
    val headers = AtomicLong()
    @Volatile var lastHeaderGeneration = -1L

    override fun allocate(upToBytes: Long): Long {
        failAllocate?.let { throw it }
        if (upToBytes > allocated) allocated = minOf(upToBytes, data.size.toLong())
        return allocated
    }

    override fun pwrite(src: ByteBuffer, filePosition: Long): Int {
        if (closed) throw IOException("closed")
        failWrites?.let { throw it }
        if (writeDelayMs > 0) Thread.sleep(writeDelayMs)
        val n = src.remaining()
        require(filePosition + n <= data.size) { "write past end: $filePosition + $n > ${data.size}" }
        src.get(data, filePosition.toInt(), n)
        bytesWritten.addAndGet(n.toLong()); writes.incrementAndGet()
        return n
    }

    override fun pread(dst: ByteBuffer, filePosition: Long): Int {
        if (closed) throw IOException("closed")
        failReads?.let { throw it }
        val n = minOf(dst.remaining().toLong(), data.size - filePosition).toInt()
        if (n <= 0) return -1
        dst.put(data, filePosition.toInt(), n)
        return n
    }

    override fun isUnlinked(): Boolean = unlinked
    override fun availableBytes(): Long = available
    override fun writeHeader(generation: Long, capacity: Long) {
        failWrites?.let { throw it }
        val h = RingHeaderCodec.encode(generation, capacity, 0L, 0)
        h.get(data, 0, TimeshiftConstants.RING_HEADER_BYTES)
        headers.incrementAndGet(); lastHeaderGeneration = generation
    }
    override fun delete() { close(); deleted = true }
    override fun close() { closed = true }
}

/**
 * A scripted [TsUpstream] over a [SyntheticTs] (or any byte source): chunked reads, open/read failures, EOF, a read
 * that blocks until [release] (the A-S1 fence), and [terminate] that unblocks a read. Records the call order.
 */
class FakeTsUpstream(
    private val source: SyntheticTs? = SyntheticTs(),
    var chunk: Int = 16 * 1024,
    val name: String = "up",
) : TsUpstream {
    val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    @Volatile var openFailure: IOException? = null
    /** Throws this from the read after [failAfterBytes] bytes. */
    @Volatile var readFailure: IOException? = null
    @Volatile var failAfterBytes = Long.MAX_VALUE
    @Volatile var eofAfterBytes = Long.MAX_VALUE
    /** Called before each read (the test can play LiveTsDataSource's reconnect hook here). */
    @Volatile var beforeRead: (() -> Unit)? = null
    /** When set, a read blocks until [release] or [terminate]. */
    @Volatile var blockReads = false
    @Volatile var pacing: (() -> Unit)? = null
    @Volatile private var terminated = false
    val isTerminated: Boolean get() = terminated
    private var gate = CountDownLatch(1)
    val delivered = AtomicLong()
    val reads = AtomicLong()
    @Volatile var blockedNow = false; private set

    override fun open() {
        log += "open:$name"
        openFailure?.let { throw it }
        if (terminated) throw InterruptedIOException("terminated")
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        reads.incrementAndGet()
        beforeRead?.invoke()
        if (blockReads) {
            blockedNow = true
            while (!terminated && !gate.await(10, TimeUnit.MILLISECONDS)) { /* wait */ }
            blockedNow = false
        }
        if (terminated) throw InterruptedIOException("terminated")
        pacing?.invoke()
        if (terminated) throw InterruptedIOException("terminated")
        val d = delivered.get()
        val rf = readFailure
        if (rf != null && d + SyntheticTs.PACKET > failAfterBytes) throw rf
        if (d + SyntheticTs.PACKET > eofAfterBytes) return -1
        // Nested two-argument minOf: the vararg overload would allocate per read (RingAllocationTest).
        val want = minOf(minOf(len.toLong(), chunk.toLong()), minOf(eofAfterBytes - d, if (rf != null) failAfterBytes - d else Long.MAX_VALUE)).toInt()
        val n = source?.fill(dst, off, want) ?: run { val w = want / SyntheticTs.PACKET * SyntheticTs.PACKET; java.util.Arrays.fill(dst, off, off + w, 0x47); w }
        if (n == 0) return 0
        delivered.addAndGet(n.toLong())
        return n
    }

    fun release() { blockReads = false; gate.countDown(); gate = CountDownLatch(1) }

    override fun close() { log += "close:$name" }
    override fun terminate() { log += "terminate:$name"; terminated = true; gate.countDown() }
}

/** A [RingWriterListener] that records every call with the thread it ran on. */
class RecordingWriterListener : RingWriterListener {
    val events: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val threads: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val failures: MutableList<IOException> = java.util.Collections.synchronizedList(ArrayList())
    val firstBytes = CountDownLatch(1)
    override fun onFirstBytes() { events += "firstBytes"; threads += Thread.currentThread().name; firstBytes.countDown() }
    override fun onFailure(e: IOException) { events += "failure:${e.javaClass.simpleName}"; failures += e; threads += Thread.currentThread().name }
    override fun onStopped(reason: RecordStop) { events += "stopped:$reason"; threads += Thread.currentThread().name }
    override fun onSegment(reason: SegmentReason, gapMs: Long) { events += "segment:$reason"; threads += Thread.currentThread().name }
    fun has(e: String) = synchronized(events) { events.contains(e) }
    fun count(prefix: String) = synchronized(events) { events.count { it.startsWith(prefix) } }
}

/**
 * A scripted [RingReader] for `:player` tests (WP-B's `RingDataSource`): returns each queued chunk, then throws each
 * queued exception; blocks (cancellably) when the script is empty, like a reader at the live head.
 */
class FakeRingReader(override val generation: Long = 1L, startOffset: Long = 0L) : RingReader {
    private val script = java.util.concurrent.LinkedBlockingDeque<Any>()
    @Volatile var closed = false; private set
    @Volatile private var cancelled = false
    @Volatile override var cursor: Long = startOffset; private set
    override var segment: Int = 0
    @Volatile override var atHead: Boolean = true

    fun enqueue(bytes: ByteArray) { script.add(bytes) }
    fun enqueue(e: IOException) { script.add(e) }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        while (true) {
            if (cancelled || closed) throw InterruptedIOException("cancelled")
            val next = script.pollFirst(10, TimeUnit.MILLISECONDS) ?: continue
            if (next is IOException) throw next
            val b = next as ByteArray
            val n = minOf(len, b.size)
            System.arraycopy(b, 0, dst, off, n)
            if (n < b.size) script.addFirst(b.copyOfRange(n, b.size))
            cursor += n
            return n
        }
    }

    override fun cancel() { cancelled = true }
    override fun close() { closed = true; cancelled = true }
}

/**
 * A [TsRing] that records calls and hands out [FakeRingReader]s (WP-B's controller tests). Head, tail, stop and the
 * index are settable; claim/commit are not supported (use [TsRingImpl] over [MemoryRingStorage] for real data).
 */
class FakeTsRing(override var capacityBytes: Long = 64L * 1024 * 1024) : TsRing {
    override var tail: Long = 0
    override var head: Long = 0
    override var generation: Long = 1
    override var saving: Boolean = true
    override var stop: RecordStop? = null
    override var mode: BufferMode = BufferMode.PERSIST
        set(value) { calls += "mode:$value"; field = value }
    override var index: RingIndexView = RingIndexImpl()
    override var persistedBytes: Long = 0L
    override var persistListener: ((Long) -> Unit)? = null
    val pins = HashMap<Int, Long>()
    val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val readers: MutableList<FakeRingReader> = java.util.Collections.synchronizedList(ArrayList())
    var closed = false

    override fun claim(minBytes: Int, timeoutMs: Long, paused: Boolean, slot: RingSlot): ClaimResult = throw UnsupportedOperationException("FakeTsRing has no data path")
    override fun commit(slot: RingSlot, length: Int, arrivalMonoMs: Long) = throw UnsupportedOperationException("FakeTsRing has no data path")
    override fun startSegment(reason: SegmentReason, gapMs: Long) { calls += "startSegment:$reason" }
    override fun stopRecording(reason: RecordStop) { calls += "stop:$reason"; if (stop == null) stop = reason; saving = false }
    override fun setPin(owner: Int, offset: Long) { calls += "pin:$owner:$offset"; synchronized(pins) { pins[owner] = offset } }
    override fun clearPin(owner: Int) { calls += "unpin:$owner"; synchronized(pins) { pins.remove(owner) } }
    override fun openReader(fromOffset: Long, generation: Long): RingReader {
        calls += "open:$fromOffset:$generation"
        return FakeRingReader(generation, fromOffset).also { readers += it }
    }
    override fun reset(): Long { calls += "reset"; head = 0; tail = 0; stop = null; return ++generation }
    override fun close() { calls += "close"; closed = true }
}
