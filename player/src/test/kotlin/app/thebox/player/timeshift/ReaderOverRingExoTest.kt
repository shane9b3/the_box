package app.thebox.player.timeshift

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.timeshift.RingBoundaryException
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.player.controller.FailureClassifier
import app.thebox.player.datasource.ByteWatchdog
import app.thebox.player.datasource.PlaybackHttpClients
import app.thebox.player.datasource.TransportFactoryImpl
import app.thebox.player.engine.EngineTestSupport
import app.thebox.player.engine.MediaSourceBuilder
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.RingOpen
import app.thebox.player.internal.TsCompat
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §17.2 `ReaderOverRingExoTest` and `ReaderCancelTest` (A-S11): a **real** ExoPlayer (PlayerFactory + MediaSourceBuilder)
 * reading a fake ring through `RingDataSource`. Day-0 constraints as in `RealPlayerTest` (no audio expectations) and
 * `RealEngineControllerTest` (an endless source never reaches READY under Robolectric's shadow codecs, so the first
 * rendered frame stands in for READY).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderOverRingExoTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(/* isAutoAdvancing = */ true)
    private val mono = MonoClock { System.nanoTime() / 1_000_000 }
    private val transport = TransportFactoryImpl(PlaybackHttpClients(OkHttpClient()), ByteWatchdog(mono), mono)
    private val fixture = EngineTestSupport.fixture("h264_aac_2s.ts")
    private val ring = FakeTsRing()
    private var bundle: PlayerBundle? = null

    @After fun tearDown() {
        bundle?.player?.release()
    }

    private fun player(): PlayerBundle = EngineTestSupport.bundle(context, clock).also {
        // A short start threshold: the ring never ends, so READY comes from the start buffer, not from end of input.
        it.loadControl.profile = BufferProfile(startMs = 300, afterRebufferMs = 500, minBufferMs = 1_000, maxBufferMs = 2_000, targetBytes = 8 shl 20, level = -1)
        bundle = it
    }

    private fun source(tuneId: Long, offset: Long) = MediaSourceBuilder().build(
        EngineTestSupport.context(tuneId = tuneId), StreamFormat.TS, TsCompat.DEFAULT, transport, archive = null,
        ring = RingOpen(ring, ring.generation, offset, RingReaderTelemetry(FakeWriterTelemetry(), { false }, mono)),
    )

    @Test
    fun prepareAtAMidStreamRapRendersWithPositionZeroAtThatRap() {
        ring.append(fixture)
        val b = player()
        val start = 244L * 188 // PAT, PMT, then the second video RAP (packet 246)
        b.player.setMediaSource(source(11, start).source)
        b.player.prepare()
        b.player.play()
        TestPlayerRunHelper.runUntilRenderedFirstFrame(b.player)

        val reader = ring.readers.first()
        assertTrue("reader opened at the RAP", reader.cursor > start)
        // A fresh TimestampAdjuster per prepare: position 0 = the first PTS after the RAP (facts §1.3).
        assertTrue("position ${b.player.currentPosition}", b.player.currentPosition in 0..1_200)
        assertFalse(b.player.isCurrentMediaItemSeekable)
    }

    @Test
    fun aBoundaryFailsTheLoadThenAReaderOnlyRePrepareContinuesWithoutStop() {
        ring.append(fixture)
        ring.append(fixture) // a second segment with its own timestamps
        ring.boundaries[fixture.size.toLong()] = RingBoundaryException(fixture.size.toLong(), SegmentReason.PCR_JUMP, 0, 0)
        val b = player()
        b.player.setMediaSource(source(12, 0).source)
        b.player.prepare()
        b.player.play()
        val error: PlaybackException = TestPlayerRunHelper.runUntilError(b.player)
        val signal = FailureClassifier().classify(error, FailureClassifier.Facts(currentTuneId = 12, sessionStartMs = 0))
        assertTrue("signal $signal", signal is FailureSignal.RingBoundary)
        assertEquals(fixture.size.toLong(), (signal as FailureSignal.RingBoundary).nextRapOffset)

        // The hand-off: a new reader at the next segment, setMediaSource + prepare with no stop() in between.
        b.player.setMediaSource(source(13, signal.nextRapOffset).source, true)
        b.player.prepare()
        TestPlayerRunHelper.runUntilRenderedFirstFrame(b.player)
        assertTrue(b.player.currentPosition in 0..1_200)
        assertEquals(2, ring.readers.size)
    }

    @Test
    fun releaseCancelsAReaderBlockedAtTheHeadQuickly() {
        ring.append(fixture)
        val b = player()
        b.player.setMediaSource(source(14, 0).source)
        b.player.prepare()
        b.player.play()
        TestPlayerRunHelper.runUntilRenderedFirstFrame(b.player)
        val reader = ring.readers.single()
        // Wait for the loader to drain the ring and block at the head.
        val deadline = System.currentTimeMillis() + 5_000
        while (reader.cursor < fixture.size && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(100)
        val t0 = System.currentTimeMillis()
        b.player.release()
        bundle = null
        while (!reader.closed && System.currentTimeMillis() - t0 < 1_000) Thread.sleep(5)
        val ms = System.currentTimeMillis() - t0
        assertTrue("reader closed after release", reader.closed)
        assertTrue("loader exited in $ms ms", ms <= 300)
        val thread = reader.readThread
        if (thread != null) {
            thread.join(300)
            // The loader's executor thread may be reused by Media3; it must not be stuck in our read.
            assertFalse(thread.stackTrace.any { it.className.contains("FakeTsRing") })
        }
    }
}
