package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §17.1 `RingStressTest` (A-S11), multi-threaded: the real pump and flusher, two readers with random delays (one at
 * live, one behind and re-seeking), and main-thread index lookups. No torn read; the invariants hold. Runs 10 s by
 * default (`-Dthebox.ringStressSeconds=60` for the design's 60 s).
 */
class RingStressTest {
    @Test
    fun pumpFlusherTwoReadersAndLookups() {
        val seconds = System.getProperty("thebox.ringStressSeconds")?.toLongOrNull() ?: 10L
        val clock = ScaledClock(1)
        val flusher = RingFlusher("test-flush-stress")
        val ring = TsRingImpl(MemoryRingStorage(300 * BLOCK), clock, 16, RingIndexImpl.capacityFor(600_000), flusher)
        val ts = SyntheticTs(bytesPerSec = 1_000_000)
        val up = FakeTsUpstream(ts, chunk = 48 * 1024)
        up.pacing = { Thread.sleep(0, 200_000) } // ≈ 20+ MB/s
        val listener = RecordingWriterListener()
        val writer = RingWriterImpl(ring, TsIndexerImpl(), clock, "stress")
        writer.start(up, listener)
        val stop = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val checked = AtomicLong()

        fun readerLoop(seed: Int, behind: Boolean) = Thread {
            val rnd = Random(seed)
            val buf = ByteArray(128 * 1024)
            var r = ring.openReader(if (behind) 0 else ring.head / 188 * 188, ring.generation)
            try {
                while (!stop.get()) {
                    if (behind && rnd.nextInt(400) == 0) {
                        // A seek: re-open at a RAP somewhere in the window.
                        val ix = ring.index
                        val slot = ix.floorRap(ix.newestPtsMono() - rnd.nextLong(0, 10_000))
                        val off = if (slot >= 0) ix.offsetAt(slot) else -1L
                        if (off >= ring.tail) { r.close(); r = ring.openReader(off, ring.generation) }
                    }
                    val start = r.cursor
                    val n = try { r.read(buf, 0, 188 * (1 + rnd.nextInt(600))) } catch (e: RingPositionLostException) {
                        // Only possible when the seek target was evicted between the lookup and the open.
                        r.close(); r = ring.openReader(ring.tail, ring.generation); continue
                    }
                    if (r.cursor != start) checked.addAndGet(checkSynthetic(buf, 0, n, start).toLong())
                    if (behind) ring.setPin(PinOwner.PLAYHEAD, r.cursor)
                    if (rnd.nextInt(10) == 0) Thread.sleep(rnd.nextLong(0, if (behind) 20 else 5))
                }
            } catch (t: Throwable) { if (!stop.get()) error.compareAndSet(null, t) } finally { r.close() }
        }
        val readers = listOf(readerLoop(1, behind = false), readerLoop(2, behind = true))
        readers.forEach { it.start() }

        val end = System.nanoTime() + seconds * 1_000_000_000L
        val rnd = Random(3)
        var lookups = 0L
        while (System.nanoTime() < end && error.get() == null) {
            val h = ring.head
            val t = ring.tail
            check(h - t <= ring.capacityBytes) { "head − tail ${h - t}" }
            val ix = ring.index
            val now = ix.newestPtsMono()
            val f = ix.floorRap(now - rnd.nextLong(0, 30_000))
            if (f >= 0) check(ix.offsetAt(f) % 188 == 0L)
            val c = ix.ceilRap(now - rnd.nextLong(0, 30_000))
            if (c >= 0) check(ix.offsetAt(c) % 188 == 0L || ix.offsetAt(c) == -1L)
            lookups++
            if (lookups % 1000 == 0L) Thread.sleep(1)
        }
        stop.set(true)
        writer.close(); writer.awaitExit(2_000)
        ring.close()
        readers.forEach { it.join(3_000) }
        error.get()?.let { throw it }
        println("stress: ${ring.head / 1_000_000} MB, ${checked.get()} filler packets checked, $lookups lookups, events ${listener.events.take(5)}")
        assertTrue(checked.get() > 10_000)
        assertTrue(ring.head > 3 * ring.capacityBytes, "wrapped")
        assertTrue(!listener.has("segment:GAP"), "no discard: ring offsets stay stream offsets")
    }
}
