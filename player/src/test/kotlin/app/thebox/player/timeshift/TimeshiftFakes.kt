package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.ClaimResult
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingBoundaryException
import app.thebox.core.playback.timeshift.RingEndException
import app.thebox.core.playback.timeshift.RingIndexView
import app.thebox.core.playback.timeshift.RingReader
import app.thebox.core.playback.timeshift.RingSlot
import app.thebox.core.playback.timeshift.RingWriter
import app.thebox.core.playback.timeshift.RingWriterFactory
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TimeshiftPolicy
import app.thebox.core.playback.timeshift.TsIndexer
import app.thebox.core.playback.timeshift.IndexSink
import app.thebox.core.playback.timeshift.TsRing
import app.thebox.core.playback.timeshift.TsRingFactory
import app.thebox.core.playback.timeshift.TsUpstream
import app.thebox.core.settings.StorageProtection
import app.thebox.core.settings.TimeshiftHold
import app.thebox.core.settings.TimeshiftLength
import app.thebox.core.playback.timeshift.FakeTsUpstream
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.WriterHooks
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// WP-B's fakes of WP-A's ring, reader and writer (docs/design/m6-timeshift.md §17.2). Lead reconciliation R6: `:player`
// tests now see `:core`'s test fixtures; the upstream fake is WP-A's `FakeTsUpstream`. The ring and reader below stay:
// they carry a data path (append, scripted boundaries, readers that block at the head) that WP-A's recording
// `FakeTsRing`/`FakeRingReader` do not have, so they are not duplicates. Same simple names, different package: these win
// inside `app.thebox.player.*` tests without an explicit import.

/**
 * An in-memory [TsRing]: bytes appended by the test ([append]) instead of a writer, RAPs added by the test ([addRap]),
 * readers that block at the head on a condition (interruptible, with a cancel flag) and throw scripted ring exceptions.
 */
class FakeTsRing(override var capacityBytes: Long = 64L shl 20) : TsRing {
    val lock = ReentrantLock()
    private val data = java.io.ByteArrayOutputStream()
    private val grew = lock.newCondition()

    @Volatile override var tail: Long = 0L
    override val head: Long get() = lock.withLock { data.size().toLong() }
    @Volatile override var generation: Long = 1L
    @Volatile override var saving: Boolean = true
    @Volatile override var stop: RecordStop? = null
    @Volatile override var mode: BufferMode = BufferMode.PERSIST
    @Volatile override var persistedBytes: Long = 0L
    @Volatile override var persistListener: ((Long) -> Unit)? = null
    val fakeIndex = FakeIndex()
    override val index: RingIndexView get() = fakeIndex

    val pins = HashMap<Int, Long>()
    val pinHistory = mutableListOf<Pair<Int, Long>>()
    val readers = mutableListOf<FakeRingReader>()
    var resets = 0
    var closed = false
    /** Boundaries a reader throws when its cursor reaches the offset. */
    val boundaries = HashMap<Long, RingBoundaryException>()
    /** Saving ended: a reader at the head throws `RingEndException(endStop)`. */
    @Volatile var endStop: RecordStop? = null

    fun append(bytes: ByteArray) = lock.withLock {
        data.write(bytes)
        grew.signalAll()
    }

    fun end(stop: RecordStop) = lock.withLock {
        endStop = stop
        saving = false
        this.stop = stop
        grew.signalAll()
    }

    fun addRap(offset: Long, ptsMono: Long) = fakeIndex.add(offset, ptsMono)

    fun bytesAt(from: Long, n: Int): ByteArray = lock.withLock { data.toByteArray().copyOfRange(from.toInt(), from.toInt() + n) }

    override fun claim(minBytes: Int, timeoutMs: Long, paused: Boolean, slot: RingSlot): ClaimResult = ClaimResult.STOPPED
    override fun commit(slot: RingSlot, length: Int, arrivalMonoMs: Long) = Unit
    override fun startSegment(reason: SegmentReason, gapMs: Long) = Unit
    override fun stopRecording(reason: RecordStop) {
        stop = reason
        saving = false
    }
    override fun setPin(owner: Int, offset: Long) {
        pins[owner] = offset
        pinHistory += owner to offset
    }
    override fun clearPin(owner: Int) {
        pins.remove(owner)
    }

    override fun openReader(fromOffset: Long, generation: Long): RingReader =
        FakeRingReader(this, fromOffset, generation).also { synchronized(readers) { readers += it } }

    override fun reset(): Long {
        resets++
        generation++
        lock.withLock {
            data.reset()
            tail = 0
            grew.signalAll()
        }
        fakeIndex.clear()
        endStop = null
        stop = null
        saving = true
        return generation
    }

    override fun close() {
        closed = true
    }

    internal fun readAt(reader: FakeRingReader, dst: ByteArray, off: Int, len: Int): Int {
        lock.lockInterruptibly()
        try {
            while (true) {
                if (reader.generation != generation) throw RingEndException(RecordStop.SESSION_END)
                if (reader.cancelled) throw InterruptedIOException("reader cancelled")
                // A boundary ends the segment the reader started in; a reader opened at the next segment's start reads on.
                if (reader.cursor > reader.start) boundaries[reader.cursor]?.let { throw it }
                if (reader.cursor < tail) throw app.thebox.core.playback.timeshift.RingPositionLostException(reader.cursor, tail)
                val available = data.size() - reader.cursor
                if (available >= 188) {
                    // Whole packets, and never past a scripted boundary.
                    var n = minOf(len, available.toInt()) / 188 * 188
                    val nextBoundary = boundaries.keys.filter { it > reader.cursor }.minOrNull()
                    if (nextBoundary != null) n = minOf(n.toLong(), nextBoundary - reader.cursor).toInt()
                    if (n > 0) {
                        System.arraycopy(data.toByteArray(), reader.cursor.toInt(), dst, off, n)
                        reader.cursor += n
                        return n
                    }
                }
                endStop?.let { throw RingEndException(it) }
                reader.atHeadFlag = true
                try {
                    grew.await(READ_SLICE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("reader interrupted")
                } finally {
                    reader.atHeadFlag = false
                }
            }
        } finally {
            lock.unlock()
        }
    }

    internal fun wake() = lock.withLock { grew.signalAll() }

    companion object {
        const val READ_SLICE_MS = 50L
    }
}

class FakeRingReader(private val ring: FakeTsRing, from: Long, override val generation: Long) : RingReader {
    val start: Long = from
    @Volatile override var cursor: Long = from
    override val segment: Int get() = 0
    @Volatile var atHeadFlag = false
    /** Tests can force the at-head answer (null = derived from blocking). */
    @Volatile var forceAtHead: Boolean? = null
    override val atHead: Boolean get() = forceAtHead ?: (atHeadFlag || cursor >= ring.head)
    @Volatile var cancelled = false
    @Volatile var closed = false
    @Volatile var readThread: Thread? = null

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        readThread = Thread.currentThread()
        return ring.readAt(this, dst, off, len)
    }

    override fun cancel() {
        cancelled = true
        ring.wake()
    }

    override fun close() {
        closed = true
        cancel()
    }
}

/** A RAP list as the index (slots = list positions); mono lookups by PTS mono. */
class FakeIndex : RingIndexView {
    private val offsets = ArrayList<Long>()
    private val pts = ArrayList<Long>()
    @Volatile var newestPts: Long = 0L

    fun add(offset: Long, ptsMono: Long) = synchronized(this) {
        offsets += offset
        pts += ptsMono
        if (ptsMono > newestPts) newestPts = ptsMono
    }

    fun clear() = synchronized(this) {
        offsets.clear()
        pts.clear()
        newestPts = 0L
    }

    override val size: Int get() = synchronized(this) { offsets.size }
    override fun floorRap(monoMs: Long): Int = synchronized(this) { pts.indexOfLast { it <= monoMs } }
    override fun ceilRap(monoMs: Long): Int = synchronized(this) { pts.indexOfFirst { it >= monoMs } }
    override fun newestRap(): Int = synchronized(this) { offsets.size - 1 }
    override fun oldestRap(): Int = synchronized(this) { if (offsets.isEmpty()) -1 else 0 }
    override fun offsetAt(slot: Int): Long = synchronized(this) { offsets[slot] }
    override fun ptsMonoAt(slot: Int): Long = synchronized(this) { pts[slot] }
    override fun flagsAt(slot: Int): Int = 1
    override fun segmentAt(slot: Int): Int = 0
    override fun newestPtsMono(): Long = newestPts
    override fun monoOfOffset(offset: Long): Long = synchronized(this) {
        val i = offsets.indexOfLast { it <= offset }
        if (i < 0) pts.firstOrNull() ?: 0L else pts[i]
    }
    override fun floorRapEntry(monoMs: Long, out: LongArray): Int = synchronized(this) { entry(floorRap(monoMs), out) }
    override fun ceilRapEntry(monoMs: Long, out: LongArray): Int = synchronized(this) { entry(ceilRap(monoMs), out) }
    override fun newestRapEntry(out: LongArray): Int = synchronized(this) { entry(newestRap(), out) }
    override fun oldestRapEntry(out: LongArray): Int = synchronized(this) { entry(oldestRap(), out) }
    private fun entry(slot: Int, out: LongArray): Int {
        if (slot >= 0) { out[0] = offsets[slot]; out[1] = pts[slot] }
        return slot
    }
    override fun segmentReason(segment: Int): SegmentReason = SegmentReason.FIRST
    override fun segmentGapMs(segment: Int): Long = 0L
}

/** Records every call; the test drives the listener (as the pump thread would). */
class FakeRingWriter : RingWriter {
    val calls = mutableListOf<String>()
    var listener: RingWriterListener? = null
    var upstream: TsUpstream? = null
    val upstreams = mutableListOf<TsUpstream>()
    var pausedHold: Long? = null
    /** The A-C4 cap's answer (WP-A's writer decides it; `RingWriterTest` covers the real one). */
    @Volatile var allowReconnect = true
    var reconnectAsks = 0
    val reconnectCauses = mutableListOf<Pair<app.thebox.core.playback.timeshift.ReconnectCause, Boolean>>()

    override fun start(upstream: TsUpstream, listener: RingWriterListener) {
        calls += "start"
        this.upstream = upstream
        upstreams += upstream
        this.listener = listener
    }
    override fun restart(upstream: TsUpstream, reason: SegmentReason) {
        calls += "restart:${reason.name}"
        this.upstream?.terminate()
        this.upstream = upstream
        upstreams += upstream
    }
    override fun stop(reason: RecordStop) { calls += "stop:${reason.name}" }
    override fun onPause(nowMonoMs: Long, holdLimitMs: Long?) { calls += "pause"; pausedHold = holdLimitMs }
    override fun onResume() { calls += "resume" }
    override fun awaitExit(timeoutMs: Long): Boolean { calls += "awaitExit"; return true }
    override fun close() { calls += "close" }
    override fun reconnectAllowed(cause: app.thebox.core.playback.timeshift.ReconnectCause, firstOfLoss: Boolean): Boolean {
        reconnectAsks++
        reconnectCauses += cause to firstOfLoss
        return allowReconnect
    }
}

class FakeWriterFactory : RingWriterFactory {
    val writers = mutableListOf<FakeRingWriter>()
    val last: FakeRingWriter get() = writers.last()
    override fun create(ring: TsRing, indexer: TsIndexer, clock: MonoClock, tag: String): RingWriter = FakeRingWriter().also { writers += it }
}

class FakeIndexer : TsIndexer {
    var splices = 0
    override fun scan(buf: ByteArray, off: Int, len: Int, logicalOffset: Long, arrivalMonoMs: Long, sink: IndexSink) = Unit
    override fun onSplice() { splices++ }
    override fun reset() = Unit
}

class FakeWriterTelemetry : StreamTelemetry {
    @Volatile override var bytesTotal = 0L
    @Volatile override var lastByteAtMs = 0L
    @Volatile override var firstByteAtMs = 0L
    @Volatile override var bitrateBps = 0L
    @Volatile override var httpStatus = 0
    @Volatile override var finalHost: String? = null
    @Volatile override var redirects = 0
    @Volatile override var inReconnect = false
    @Volatile override var reconnects = 0
    @Volatile override var sniffed = StreamFormat.TS
    @Volatile override var waitingForLease = false
    @Volatile override var readPendingSinceMs = 0L
}

/** Records every `TransportFactory.writer` call (R9); a test's `TransportFactory` delegates its `writer` here. */
internal class FakeWriterTransport {
    class Opened(val ctx: StreamSessionContext, val hooks: WriterHooks, val upstream: FakeTsUpstream)
    val opened = mutableListOf<Opened>()
    val telemetry = FakeWriterTelemetry()
    val last: Opened get() = opened.last()
    fun writer(ctx: StreamSessionContext, hooks: WriterHooks): Pair<TsUpstream, StreamTelemetry> {
        val u = FakeTsUpstream(source = null)
        opened += Opened(ctx, hooks, u)
        return u to telemetry
    }
}

/** 30 min (or null for OFF), capacity as given, hold/daily cap configurable. */
class FakePolicy(
    var capacity: Long = 256L shl 20,
    var hold: Long? = null,
    var daily: Long? = null,
    var modeAnswer: BufferMode = BufferMode.PERSIST,
) : TimeshiftPolicy {
    override fun lengthMs(setting: TimeshiftLength, totalStorageBytes: Long): Long? = if (setting == TimeshiftLength.OFF) null else 1_800_000L
    override fun capacityBytes(lengthMs: Long, bytesPerSec: Long, availableBytes: Long, totalBytes: Long, currentFileBytes: Long): Long = capacity
    override fun holdLimitMs(setting: TimeshiftHold): Long? = hold
    override fun dailyLimitBytes(setting: StorageProtection, totalStorageBytes: Long): Long? = daily
    override fun mode(setting: TimeshiftLength, writtenTodayBytes: Long, dailyLimitBytes: Long?, needsPersist: Boolean): BufferMode = modeAnswer
    override fun pauseRemainingMs(holdRemainingMs: Long?, freeCapacityBytes: Long, bytesPerSec: Long): Long = 17 * 60_000L
}

class FakeRingFactory(val ring: FakeTsRing = FakeTsRing()) : TsRingFactory {
    var creates = 0
    var lastLengthMs = -1L
    override fun create(storage: app.thebox.core.playback.timeshift.RingStorage, clock: MonoClock, ramBlocks: Int, indexEntries: Int, lengthMs: Long): TsRing {
        creates++
        lastLengthMs = lengthMs
        ring.capacityBytes = storage.capacityBytes
        return ring
    }
}

/** A posix seam whose fallocate is unsupported (the sparse path) and whose nlink comes from the test. */
internal class FakePosix(var fallocateOk: Boolean = false, var links: Long = 1L, var enospc: Boolean = false) : RingPosix {
    var fallocates = 0
    override fun fallocate(fd: java.io.FileDescriptor, offset: Long, len: Long): Boolean {
        fallocates++
        if (enospc) throw IOException("ENOSPC")
        return fallocateOk
    }
    override fun nlink(fd: java.io.FileDescriptor): Long = links
}

/** Minimal valid TS bytes: [packets] null packets (PID 0x1FFF). */
fun nullPackets(packets: Int): ByteArray {
    val b = ByteArray(188 * packets)
    for (i in 0 until packets) {
        b[i * 188] = 0x47
        b[i * 188 + 1] = 0x1F
        b[i * 188 + 2] = 0xFF.toByte()
        b[i * 188 + 3] = 0x10
    }
    return b
}
