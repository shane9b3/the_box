package app.thebox.player.timeshift

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.errors.LoadRetryRulesImpl
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingBoundaryException
import app.thebox.core.playback.timeshift.RingEndException
import app.thebox.core.playback.timeshift.RingException
import app.thebox.core.playback.timeshift.RingIoException
import app.thebox.core.playback.timeshift.RingReader
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.player.datasource.BoxLoadErrorHandlingPolicy
import app.thebox.player.datasource.ByteWatchdog
import app.thebox.player.datasource.PlaybackHttpClients
import app.thebox.player.datasource.StandaloneScope
import app.thebox.player.datasource.TransportFactoryImpl
import app.thebox.player.engine.EngineTestSupport
import app.thebox.player.internal.RingOpen
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** §5.1, §5.2 (A-B3), A-S9, A-S11: `RingDataSource` over a fake ring, and its telemetry gating. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RingDataSourceTest {
    private val clock = MonoClock { System.nanoTime() / 1_000_000 }
    private val ring = FakeTsRing()
    private val writer = FakeWriterTelemetry()
    private var busy = false

    private fun telemetry() = RingReaderTelemetry(writer, { busy }, clock)

    private fun source(start: Long = 0L, t: RingReaderTelemetry? = telemetry(), scope: StandaloneScope = StandaloneScope()) =
        RingDataSource(5, RingOpen(ring, ring.generation, start, t ?: telemetry()), t, scope)

    @Test
    fun opensAtTheStartOffsetReturnsUnsetLengthAndWholePackets() {
        val bytes = nullPackets(10).also { it[188 * 4 + 5] = 42 }
        ring.append(bytes)
        val ds = source(start = 188L * 4)
        assertEquals(C.LENGTH_UNSET.toLong(), ds.open(DataSpec(MediaSourceUri)))
        assertEquals(1, ring.readers.size)
        assertEquals(188L * 4, ring.readers.single().cursor - 188) // one packet read inside open()
        val buf = ByteArray(188 * 3)
        val n = ds.read(buf, 0, buf.size)
        assertEquals(188, n) // the packet staged by open() first
        assertEquals(42, buf[5].toInt())
        val m = ds.read(buf, 0, buf.size)
        assertEquals(188 * 3, m)
        assertEquals("thebox-session://5", ds.uri.toString())
        assertTrue(ds.responseHeaders.isEmpty())
        ds.close()
        assertTrue(ring.readers.single().closed)
    }

    @Test
    fun positionIsIgnoredAndCounted() {
        ring.append(nullPackets(2))
        val t = telemetry()
        val ds = source(t = t)
        ds.open(DataSpec.Builder().setUri(MediaSourceUri).setPosition(1234).build())
        assertEquals(1, t.positionIgnoredCount.get())
        ds.close()
    }

    @Test
    fun blocksAtTheHeadAndWakesOnACommit() {
        ring.append(nullPackets(1))
        val ds = source()
        ds.open(DataSpec(MediaSourceUri))
        val buf = ByteArray(188 * 4)
        assertEquals(188, ds.read(buf, 0, buf.size))
        val got = AtomicReference<Int>()
        val done = CountDownLatch(1)
        Thread { got.set(ds.read(buf, 0, buf.size)); done.countDown() }.start()
        assertFalse("must block at the head", done.await(200, TimeUnit.MILLISECONDS))
        ring.append(nullPackets(2))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(376, got.get())
        ds.close()
    }

    @Test
    fun terminateCancelsABlockedReadWithInterruptedIoException() {
        val scope = StandaloneScope()
        ring.append(nullPackets(1))
        val ds = source(scope = scope)
        ds.open(DataSpec(MediaSourceUri))
        ds.read(ByteArray(188), 0, 188)
        val error = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val t0 = SystemClock.uptimeMillis()
        Thread {
            try { ds.read(ByteArray(188), 0, 188) } catch (e: Throwable) { error.set(e) }
            done.countDown()
        }.start()
        Thread.sleep(100)
        scope.end() // TuneSession.end() → transport scope → Terminable (A-S9)
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertTrue("got ${error.get()}", error.get() is InterruptedIOException)
        assertTrue(SystemClock.uptimeMillis() - t0 < 2_000)
    }

    @Test
    fun loaderInterruptCancelsABlockedRead() {
        ring.append(nullPackets(1))
        val ds = source()
        ds.open(DataSpec(MediaSourceUri))
        ds.read(ByteArray(188), 0, 188)
        val error = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val th = Thread {
            try { ds.read(ByteArray(188), 0, 188) } catch (e: Throwable) { error.set(e) }
            done.countDown()
        }
        th.start()
        Thread.sleep(100)
        th.interrupt() // Loader.cancelLoading interrupts the loader thread (facts §2)
        assertTrue(done.await(300, TimeUnit.MILLISECONDS))
        assertTrue("got ${error.get()}", error.get() is InterruptedIOException)
    }

    @Test
    fun ringExceptionsSurfaceAndNothingEverEndsTheInput() {
        ring.append(nullPackets(4))
        ring.boundaries[188L * 2] = RingBoundaryException(188L * 3, SegmentReason.PCR_JUMP, 0, 0)
        val ds = source()
        ds.open(DataSpec(MediaSourceUri))
        val buf = ByteArray(188 * 8)
        assertEquals(188, ds.read(buf, 0, buf.size))
        assertEquals(188, ds.read(buf, 0, buf.size)) // stops before the boundary
        try {
            ds.read(buf, 0, buf.size)
            fail("boundary expected")
        } catch (e: RingBoundaryException) {
            assertEquals(188L * 3, e.nextRapOffset)
        }
        ds.close()

        val ring2 = FakeTsRing().apply { append(nullPackets(1)) }
        val ds2 = RingDataSource(6, RingOpen(ring2, ring2.generation, 0, telemetry()), null)
        ds2.open(DataSpec(MediaSourceUri))
        ds2.read(buf, 0, buf.size)
        ring2.end(RecordStop.HOLD_LIMIT)
        try {
            ds2.read(buf, 0, buf.size)
            fail("end expected")
        } catch (e: RingEndException) {
            assertEquals(RecordStop.HOLD_LIMIT, e.stop)
        }
    }

    @Test
    fun aReaderOfAnOldGenerationEndsWithSessionEnd() {
        ring.append(nullPackets(2))
        val ds = source()
        ds.open(DataSpec(MediaSourceUri))
        ring.reset()
        try {
            ds.read(ByteArray(376), 0, 376)
            ds.read(ByteArray(376), 0, 376)
            fail("session end expected")
        } catch (e: RingEndException) {
            assertEquals(RecordStop.SESSION_END, e.stop)
        }
    }

    @Test
    fun otherReaderFailuresAreWrappedAsRingIoAndZeroIsNeverReturned() {
        val badRing = object : app.thebox.core.playback.timeshift.TsRing by ring {
            override fun openReader(fromOffset: Long, generation: Long): RingReader = object : RingReader {
                var calls = 0
                override val cursor = 0L
                override val segment = 0
                override val generation = generation
                override val atHead = false
                override fun read(dst: ByteArray, off: Int, len: Int): Int {
                    calls++
                    if (calls == 1) return 188
                    if (calls == 2) throw IOException("EIO")
                    return 0
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
        val ds = RingDataSource(7, RingOpen(badRing, 1, 0, telemetry()), null)
        ds.open(DataSpec(MediaSourceUri))
        ds.read(ByteArray(188), 0, 188)
        try { ds.read(ByteArray(188), 0, 188); fail() } catch (e: RingIoException) { assertTrue(e.cause is IOException) }
        try { ds.read(ByteArray(188), 0, 188); fail() } catch (e: RingIoException) { /* a 0 from the reader is a bug, never EOF */ }
    }

    @Test
    fun everyRingExceptionIsFatalUnderTheLoadErrorPolicy() {
        val policy = BoxLoadErrorHandlingPolicy(LoadOrigin.PROGRESSIVE, LoadRetryRulesImpl())
        for (e in listOf<IOException>(
            RingBoundaryException(0, SegmentReason.GAP, 0, 0), RingEndException(RecordStop.FULL_WHILE_PAUSED),
            app.thebox.core.playback.timeshift.RingPositionLostException(0, 1), RingIoException(IOException("x")),
        )) {
            assertTrue(e is RingException)
            val info = LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(0, DataSpec(MediaSourceUri), 0), MediaLoadData(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE), e, 1,
            )
            assertEquals(e.javaClass.simpleName, C.TIME_UNSET, policy.getRetryDelayMsFor(info))
        }
    }

    // ------------------------------------------------------------------ telemetry (§5.2, A-B3)

    @Test
    fun telemetryReportsTheWriterOnlyWhileTheReaderIsBlockedAtTheHead() {
        writer.readPendingSinceMs = 777
        writer.inReconnect = true
        writer.waitingForLease = true
        writer.httpStatus = 200
        ring.append(nullPackets(4))
        val t = telemetry()
        val ds = source(t = t)
        ds.open(DataSpec(MediaSourceUri))
        val reader = ring.readers.single()
        // Behind live (cursor below the head, not blocked): the recorder's state never shows.
        reader.forceAtHead = false
        assertEquals(0L, t.readPendingSinceMs)
        assertFalse(t.inReconnect)
        assertFalse(t.waitingForLease)
        assertEquals(200, t.httpStatus) // stats only
        // Blocked at the head: the writer's pending-read age and reconnect show.
        reader.forceAtHead = true
        val done = CountDownLatch(1)
        assertEquals(188, ds.read(ByteArray(188 * 8), 0, 188 * 8)) // the packet staged by open()
        assertEquals(188 * 3, ds.read(ByteArray(188 * 8), 0, 188 * 8)) // the rest: now at the head
        Thread { try { ds.read(ByteArray(188), 0, 188) } catch (e: IOException) { } ; done.countDown() }.start()
        Thread.sleep(100)
        assertEquals(777L, t.readPendingSinceMs)
        assertTrue(t.inReconnect)
        assertFalse("bytes were delivered: no lease wait any more", t.waitingForLease)
        // A writer restart pending (B3: a RetrySame delay) reads as reconnecting at the head.
        writer.inReconnect = false
        busy = true
        assertTrue(t.inReconnect)
        ds.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(0L, t.readPendingSinceMs)
        assertTrue(t.bytesTotal >= 188 * 4)
        assertTrue(t.firstByteAtMs > 0)
    }

    @Test
    fun transportRingRegistersTheReaderSoEndSessionCancelsIt() {
        val transport = TransportFactoryImpl(PlaybackHttpClients(OkHttpClient()), ByteWatchdog(clock), clock)
        ring.append(nullPackets(1))
        val ctx = EngineTestSupport.context(tuneId = 9)
        val t = telemetry()
        val (factory, tel) = transport.ring(ctx, RingOpen(ring, ring.generation, 0, t))
        assertTrue(tel === t)
        val ds = factory.createDataSource()
        ds.open(DataSpec(MediaSourceUri))
        ds.read(ByteArray(188), 0, 188)
        val error = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread { try { ds.read(ByteArray(188), 0, 188) } catch (e: Throwable) { error.set(e) }; done.countDown() }.start()
        Thread.sleep(100)
        transport.endSession(9)
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertTrue(error.get() is InterruptedIOException)
    }

    private companion object {
        val MediaSourceUri: android.net.Uri = android.net.Uri.parse("thebox-session://5")
    }
}
