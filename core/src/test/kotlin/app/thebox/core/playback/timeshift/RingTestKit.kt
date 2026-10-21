package app.thebox.core.playback.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Shared helpers for the WP-A ring tests (docs/design/m6-timeshift.md §17.1). */
internal object RingTestKit {
    const val BLOCK = RING_BLOCK_BYTES.toLong()

    /** A ring over [MemoryRingStorage] with a manual flusher (call [drainFlush]) unless [flusher] is given. */
    fun ring(
        capBlocks: Int,
        poolBlocks: Int,
        clock: MonoClock = FakeMonoClock(),
        indexEntries: Int = 4096,
        flusher: RingFlusher? = null,
        storage: MemoryRingStorage = MemoryRingStorage(capBlocks * BLOCK),
    ): Pair<TsRingImpl, MemoryRingStorage> = TsRingImpl(storage, clock, poolBlocks, indexEntries, flusher) to storage

    /** The byte every test writes at logical offset [l] of generation [gen]: a sync byte on packet starts. */
    fun pattern(gen: Long, l: Long): Byte = if (l % TS_PACKET == 0L) 0x47 else ((l * 31 + gen * 7 + (l ushr 16)) and 0xFF).toByte()

    fun newSlot(ring: TsRing) = RingSlot().also { it.generation = ring.generation }

    /**
     * Appends up to [bytes] (whole packets) of the pattern with claim/commit, adding an index RAP every [rapEveryPackets]
     * packets through the ring's sink. Returns the result of the first claim that was not OK, or OK.
     */
    fun append(
        ring: TsRingImpl,
        slot: RingSlot,
        bytes: Long,
        arrival: Long = 0L,
        rapEveryPackets: Int = 0,
        paused: Boolean = false,
        flushEachBlock: Boolean = true,
        timeoutMs: Long = 0,
    ): ClaimResult {
        var left = bytes - bytes % TS_PACKET
        while (left > 0) {
            if (flushEachBlock) drainFlush(ring)
            val r = ring.claim(TS_PACKET, timeoutMs, paused, slot)
            if (r != ClaimResult.OK) return r
            val n = minOf(left, slot.length.toLong()).toInt()
            val start = ring.head
            for (i in 0 until n) slot.array[slot.offset + i] = pattern(ring.generation, start + i)
            if (rapEveryPackets > 0) {
                var p = 0
                while (p < n) {
                    val off = start + p
                    if ((off / TS_PACKET) % rapEveryPackets == 0L) {
                        val pts = 90L * (off / TS_PACKET) // 1 ms per packet in the test clock domain
                        ring.sink.onRap(off, pts, pts, RingFlags.RAP)
                    }
                    p += TS_PACKET
                }
            }
            ring.commit(slot, n, arrival)
            left -= n
        }
        if (flushEachBlock) drainFlush(ring)
        return ClaimResult.OK
    }

    fun drainFlush(ring: TsRingImpl) { var guard = 0; while (ring.flushStep() && guard++ < 100_000) { /* drain */ } }

    /** Reads exactly [n] bytes (or fewer at the head) and checks them against the pattern. */
    fun readAndCheck(reader: RingReader, gen: Long, n: Int, buf: ByteArray = ByteArray(64 * 1024)): Int {
        var got = 0
        while (got < n) {
            val start = reader.cursor
            val r = reader.read(buf, 0, minOf(buf.size, n - got))
            assertTrue(r > 0, "read returned $r")
            for (i in 0 until r) if (buf[i] != pattern(gen, start + i)) fail("torn byte at ${start + i}: ${buf[i]} != ${pattern(gen, start + i)}")
            got += r
        }
        return got
    }

    /** §3.2 invariants 1 and 3 (tail on an entry or at a bound with no entry) plus file contents for flushed bytes. */
    fun checkInvariants(ring: TsRingImpl, storage: MemoryRingStorage? = null, gen: Long = ring.generation) {
        val t = ring.tail; val h = ring.head
        assertTrue(t in 0..h, "tail $t head $h")
        assertTrue(h - t <= ring.capacityBytes, "head − tail ${h - t} > capacity ${ring.capacityBytes}")
        val mp = ring.minPinOrMax()
        if (mp != Long.MAX_VALUE) assertTrue(mp in t..h, "min pin $mp outside [$t, $h]")
        assertEquals(0L, t % TS_PACKET, "tail not packet-aligned")
        assertEquals(0L, h % TS_PACKET, "head not packet-aligned")
        if (ring.index.size > 0) assertTrue(ring.index.entryOffset(0) >= t, "entry ${ring.index.entryOffset(0)} before tail $t")
        if (storage != null && !ring.ramOnly) {
            val from = maxOf(t, ring.fileValidFrom)
            val to = ring.flushed
            var l = from
            // Spot-check a few packets of the file part.
            var k = 0
            while (l < to && k < 64) {
                val pos = (RING_HEADER_BYTES + l % ring.capacityBytes).toInt()
                if (storage.data[pos] != pattern(gen, l)) fail("file byte at logical $l (file $pos) wrong")
                if (storage.data[pos + 1] != pattern(gen, l + 1)) fail("file byte at logical ${l + 1} wrong")
                l += maxOf(TS_PACKET.toLong(), (to - from) / 64 / TS_PACKET * TS_PACKET)
                k++
            }
        }
    }
}

/** Pumps a [SyntheticTs] through indexer → ring on the calling thread (the writer loop without a thread). */
internal class TsFeeder(
    val ring: TsRingImpl,
    val ts: SyntheticTs,
    val clock: FakeMonoClock,
    val indexer: TsIndexerImpl = TsIndexerImpl(),
    private val sink: IndexSink = ring.sink,
) {
    val slot = RingTestKit.newSlot(ring)
    /** Real stream rate in bytes per second (frames × frame size). */
    val bytesPerSec: Double = ts.actualBytesPerSec
    private var frac = 0.0
    var paced = true

    fun feed(bytes: Long, chunk: Int = 64 * 188): ClaimResult {
        var left = bytes
        while (left > 0) {
            RingTestKit.drainFlush(ring)
            val r = ring.claim(188, 0, false, slot)
            if (r != ClaimResult.OK) return r
            val want = minOf(left, slot.length.toLong(), chunk.toLong()).toInt()
            val n = ts.fill(slot.array, slot.offset, want)
            if (n == 0) break
            if (paced) {
                frac += n * 1000.0 / bytesPerSec
                val whole = frac.toLong(); frac -= whole
                clock.advance(whole)
            }
            val arrival = clock.nowMs()
            ring.beginScan(arrival)
            indexer.scan(slot.array, slot.offset, n, ring.head, arrival, sink)
            ring.commit(slot, n, arrival)
            left -= n
        }
        RingTestKit.drainFlush(ring)
        return ClaimResult.OK
    }

    fun feedSeconds(s: Double): ClaimResult = feed((s * bytesPerSec).toLong() / 188 * 188)
}

/** Records every [IndexSink] call. */
internal class RecordingSink : IndexSink, IndexClockSink {
    data class Rap(val offset: Long, val pts: Long, val pcr: Long, val flags: Int)
    val raps = ArrayList<Rap>()
    val anchors = ArrayList<Rap>()
    val boundaries = ArrayList<Pair<Long, SegmentReason>>()
    val psi = ArrayList<ByteArray>()
    val clocks = ArrayList<Pair<Long, Long>>()
    var newestPts = Long.MIN_VALUE
    override fun onRap(offset: Long, pts90k: Long, pcr90k: Long, flags: Int) { raps += Rap(offset, pts90k, pcr90k, flags) }
    override fun onAnchor(offset: Long, pcr90k: Long, flags: Int) { anchors += Rap(offset, -1, pcr90k, flags) }
    override fun onBoundary(offset: Long, reason: SegmentReason) { boundaries += offset to reason }
    override fun onPsi(packets: ByteArray, count: Int) { psi += packets.copyOf(count * 188) }
    override fun onClock(pcr90k: Long, arrivalMonoMs: Long) { clocks += pcr90k to arrivalMonoMs }
    override fun onPts(pts90k: Long) { newestPts = pts90k }
}

/** Feeds [ts] into [sink] through [indexer] (no ring) in chunks, with paced arrival from [clock]. */
internal fun scanStream(ts: SyntheticTs, indexer: TsIndexer, sink: IndexSink, seconds: Double, clock: FakeMonoClock, paced: Boolean = true, chunk: Int = 32 * 188) {
    val buf = ByteArray(chunk)
    val target = ts.frame + (seconds * ts.fps).toLong()
    var frac = 0.0
    while (ts.frame < target) {
        val start = ts.position
        val n = ts.fill(buf, 0, chunk)
        if (paced) {
            frac += n * 1000.0 / ts.actualBytesPerSec
            val w = frac.toLong(); frac -= w; clock.advance(w)
        }
        indexer.scan(buf, 0, n, start, clock.nowMs(), sink)
    }
}

/**
 * Checks bytes read from a ring that holds an unbroken [SyntheticTs] stream from logical offset 0: every packet starts
 * with 0x47 and every filler packet (PUSI = 0) carries `(L × 7 + 3) & 0x7F` at logical offset L. A torn read of any
 * filler packet fails. Returns the number of filler packets checked.
 */
internal fun checkSynthetic(buf: ByteArray, off: Int, len: Int, logical: Long): Int {
    require(logical % 188 == 0L && len % 188 == 0) { "unaligned check at $logical len $len" }
    var checked = 0
    var p = 0
    while (p < len) {
        val l = logical + p
        check(buf[off + p] == 0x47.toByte()) { "no sync byte at logical $l" }
        val pusi = buf[off + p + 1].toInt() and 0x40 != 0
        val afc = (buf[off + p + 3].toInt() ushr 4) and 3
        if (!pusi && afc == 1) {
            for (i in 4 until 188) {
                val want = (((l + i) * 7 + 3) and 0x7F).toByte()
                check(buf[off + p + i] == want) { "torn filler byte at logical ${l + i}" }
            }
            checked++
        }
        p += 188
    }
    return checked
}

/** A clock running [factor]× faster than real time (PIN_STALL_MS and friends in real tests). */
internal class ScaledClock(private val factor: Long) : app.thebox.core.playback.MonoClock {
    private val t0 = System.nanoTime()
    override fun nowMs(): Long = 1_000_000L + (System.nanoTime() - t0) * factor / 1_000_000L
}
