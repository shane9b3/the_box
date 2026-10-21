package app.thebox.player.timeshift

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.ClaimResult
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingEndException
import app.thebox.core.playback.timeshift.RingException
import app.thebox.core.playback.timeshift.RingSlot
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.player.engine.EngineTestSupport
import app.thebox.player.internal.RingOpen
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** §17.2 `RingDataSourceTest` against WP-A's **real** `TsRingImpl` over `AndroidRingStorage` in a temp dir. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RingDataSourceRealRingTest {
    private val root: File = Files.createTempDirectory("ts-real").toFile()
    private val clock = MonoClock { System.nanoTime() / 1_000_000 }
    private val storage = TimeshiftStorageImpl(
        File(root, "cache/timeshift"), File(root, "nb/w"), 5, clock,
        posix = FakePosix(), statFs = { TimeshiftStorageImpl.Space(32_000_000_000L, 10_000_000_000L) },
        counterIo = java.util.concurrent.Executor { it.run() },
    )

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun bytesCommittedToARealRingReadBackThroughTheDataSourceAndTheEndSurfacesAtTheHead() {
        val st = storage.openStorage(16L * TimeshiftConstants.RING_BLOCK_BYTES)
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + st.capacityBytes)
        val ring = TsRingImpl(st, clock, 4, 256)
        try {
            val payload = EngineTestSupport.fixture("h264_aac_2s.ts")
            val slot = RingSlot().apply { generation = ring.generation }
            var off = 0
            while (off < payload.size) {
                assertEquals(ClaimResult.OK, ring.claim(TimeshiftConstants.TS_PACKET, 1_000, false, slot))
                val n = minOf(slot.length, payload.size - off) / TimeshiftConstants.TS_PACKET * TimeshiftConstants.TS_PACKET
                System.arraycopy(payload, off, slot.array, slot.offset, n)
                ring.commit(slot, n, clock.nowMs())
                off += n
            }
            val t = RingReaderTelemetry(FakeWriterTelemetry(), { false }, clock)
            val ds = RingDataSource(3, RingOpen(ring, ring.generation, 0, t), t)
            assertEquals(C.LENGTH_UNSET.toLong(), ds.open(DataSpec(Uri.parse("thebox-session://3"))))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (out.size() < payload.size) {
                val n = ds.read(buf, 0, buf.size)
                assertTrue(n > 0 && n % TimeshiftConstants.TS_PACKET == 0)
                out.write(buf, 0, n)
            }
            val got = out.toByteArray()
            // The reader may inject the segment's PSI first (§5.1): the payload is the tail of what was read.
            assertArrayEquals(payload, got.copyOfRange(got.size - payload.size, got.size))

            val error = AtomicReference<Throwable>()
            val done = CountDownLatch(1)
            Thread {
                try { ds.read(buf, 0, buf.size) } catch (e: Throwable) { error.set(e) }
                done.countDown()
            }.start()
            Thread.sleep(150)
            assertTrue("blocks at the head", done.count == 1L)
            ring.stopRecording(RecordStop.HOLD_LIMIT)
            assertTrue(done.await(2, TimeUnit.SECONDS))
            val e = error.get()
            assertTrue("got $e", e is RingException)
            assertEquals(RecordStop.HOLD_LIMIT, (e as RingEndException).stop)
            ds.close()
        } finally {
            ring.close()
            st.delete()
        }
    }

    /**
     * Fix pass S7: a stream joined mid-GOP (the first frames are not a RAP). The cycle's first reader (`firstRap`)
     * opens at the generation's first RAP, so the extractor's position 0 is that RAP's PTS, which is the origin the
     * controller resolves (`ceilRap(monoOfOffset(tail))`); before, it opened at offset 0 and the origin was off by up to a GOP.
     */
    @Test
    fun theFirstReaderOfACycleOpensAtTheFirstRap() {
        val st = storage.openStorage(64L * TimeshiftConstants.RING_BLOCK_BYTES)
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + st.capacityBytes)
        val ring = TsRingImpl(st, clock, 8, 1_024)
        val synthetic = app.thebox.core.playback.timeshift.SyntheticTs(fps = 25, gopFrames = 50)
        val skip = ByteArray(synthetic.frameBytes * 12)
        synthetic.fill(skip, 0, skip.size) // joined 12 frames into a 2 s GOP
        val writer = app.thebox.core.playback.timeshift.RingWriterImpl(ring, app.thebox.core.playback.timeshift.TsIndexerImpl(), clock, "s7")
        try {
            val t = RingReaderTelemetry(FakeWriterTelemetry(), { false }, clock)
            val ds = RingDataSource(4, RingOpen(ring, ring.generation, 0, t, firstRap = true), t)
            val opened = java.util.concurrent.Executors.newSingleThreadExecutor()
            val open = opened.submit<Long> { ds.open(DataSpec(Uri.parse("thebox-session://4"))) }
            Thread.sleep(50)
            writer.start(
                app.thebox.core.playback.timeshift.FakeTsUpstream(synthetic).also { it.pacing = { Thread.sleep(1) } },
                app.thebox.core.playback.timeshift.RecordingWriterListener(),
            )
            assertEquals(C.LENGTH_UNSET.toLong(), open.get(5, TimeUnit.SECONDS))
            val e = LongArray(2)
            assertTrue(ring.index.oldestRapEntry(e) >= 0)
            assertTrue("the first RAP is not at the start: ${e[0]}", e[0] > 0)
            val cursor = t.reader!!.cursor
            assertTrue("opened at the first RAP ${e[0]}, cursor $cursor", cursor >= e[0] && cursor <= e[0] + 16L * TimeshiftConstants.TS_PACKET)
            ds.close()
            opened.shutdownNow()
        } finally {
            writer.close()
            writer.awaitExit(2_000)
            ring.close()
            st.delete()
        }
    }
}
