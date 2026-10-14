package app.thebox.player.engine

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** [StatsCollector] cadence (≤ 2 Hz, only while enabled) and [FpsEstimator] estimation and snapping (§3.13, §3.9.3). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatsAndFpsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    // ------------------------------------------------------------------ stats cadence

    @Test
    fun statsPublishAtMostTwiceASecondAndOnlyWhileEnabled() {
        val bundle = EngineTestSupport.bundle(context, FakeClock(true))
        val stats = StatsCollector(bundle.player, MonoClock { SystemClock.uptimeMillis() })
        try {
            idle(2_000)
            assertEquals("disabled: nothing published", 0L, stats.publishCount)

            stats.setEnabled(true)
            assertEquals("immediate first value", 1L, stats.publishCount)
            val t0 = stats.stats.value.updatedAtElapsedMs
            idle(2_000)
            assertEquals("one publish per 500 ms", 5L, stats.publishCount)
            assertEquals(2_000L, stats.stats.value.updatedAtElapsedMs - t0)

            stats.setEnabled(false)
            val frozen = stats.stats.value
            idle(3_000)
            assertEquals(5L, stats.publishCount)
            assertTrue("frozen at the last value", frozen === stats.stats.value)

            stats.setEnabled(true)
            stats.setEnabled(true) // idempotent: no second loop
            idle(1_000)
            assertEquals(8L, stats.publishCount)
        } finally {
            stats.release()
            bundle.player.release()
        }
    }

    @Test
    fun statsCarryControllerInputsAndResetPerSession() {
        val bundle = EngineTestSupport.bundle(context, FakeClock(true))
        val stats = StatsCollector(bundle.player, MonoClock { SystemClock.uptimeMillis() })
        try {
            val profile = BufferProfile(1_500, 5_000, 15_000, 30_000, 24 shl 20, 1)
            stats.bufferProfile = profile
            stats.afr = AfrMode.ALWAYS
            stats.displayRefreshHz = 50f
            stats.fps = 25f
            stats.brokerSnapshot = { EngineTestSupport.NoBroker.snapshot() }
            val telemetry = EngineTestSupport.FakeTelemetry().apply { reconnects = 2; redirects = 1 }
            stats.bind(telemetry, StreamFormat.HLS)
            stats.fps = 25f
            val s = stats.snapshot()
            assertEquals(profile, s.bufferProfile)
            assertEquals(AfrMode.ALWAYS, s.afr)
            assertEquals(50f, s.displayRefreshHz)
            assertEquals(25f, s.fps)
            assertEquals("test", s.broker?.brokerKey)
            assertEquals(2, s.reconnects)
            assertEquals(1, s.redirects)
            assertEquals("container format when the transport did not sniff", StreamFormat.HLS, s.format)
            assertNull("0 status reported as unknown", s.httpStatus)

            stats.onVideoInputFormatChanged(
                AnalyticsTestTime.zero, Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(3840).setHeight(2160).build(), null,
            )
            stats.onDroppedVideoFrames(AnalyticsTestTime.zero, 7, 1_000)
            assertEquals(MimeTypes.VIDEO_H265, stats.snapshot().videoMime)
            assertEquals(7L, stats.snapshot().droppedFrames)
            stats.bind(null, StreamFormat.TS)
            assertNull(stats.snapshot().videoMime)
            assertEquals(0L, stats.snapshot().droppedFrames)
            assertNull("fps is per session", stats.snapshot().fps)
        } finally {
            stats.release()
            bundle.player.release()
        }
    }

    // ------------------------------------------------------------------ fps estimator

    private val broadcastRates = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f)

    /** Stand-in for WP-A's `DisplayModePicker.snapFps`: nearest broadcast rate within 2 %. */
    private val snap: (Float) -> Float? = { fps ->
        broadcastRates.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) / it <= 0.02f }
    }

    private val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()

    private fun estimate(trueFps: Double, frames: Int = 40, startUs: Long = 1_000_000L): Pair<FpsEstimator, MutableList<Float>> {
        val delivered = mutableListOf<Float>()
        val estimator = FpsEstimator(snap, Looper.getMainLooper()) { delivered += it }
        feed(estimator, trueFps, frames, startUs)
        return estimator to delivered
    }

    private fun feed(estimator: FpsEstimator, trueFps: Double, frames: Int, startUs: Long, format: Format = video) {
        for (i in 0 until frames) {
            val pts = startUs + (i * 1_000_000.0 / trueFps).roundToLong()
            estimator.onVideoFrameAboutToBeRendered(pts, 0, format, null)
        }
        idle(0)
    }

    @Test
    fun snapsEveryBroadcastRate() {
        for ((rate, expected) in listOf(
            24_000.0 / 1_001 to 23.976f, 24.0 to 24f, 25.0 to 25f, 30_000.0 / 1_001 to 29.97f,
            30.0 to 30f, 50.0 to 50f, 60_000.0 / 1_001 to 59.94f, 60.0 to 60f,
        )) {
            val (estimator, delivered) = estimate(rate)
            assertEquals("rate $rate", listOf(expected), delivered)
            assertEquals("raw estimate for $rate", rate, estimator.lastRawFps.toDouble(), rate * 0.001)
        }
    }

    @Test
    fun needsThirtyFramesAndDeliversOnce() {
        val (_, delivered) = estimate(25.0, frames = 30) // 29 deltas
        assertTrue(delivered.isEmpty())
        val (estimator, once) = estimate(25.0, frames = 31)
        assertEquals(listOf(25f), once)
        feed(estimator, 50.0, 60, startUs = 10_000_000)
        assertEquals("done until reset", listOf(25f), once)
        estimator.reset()
        feed(estimator, 50.0, 40, startUs = 20_000_000)
        assertEquals(listOf(25f, 50f), once)
    }

    @Test
    fun ignoresGapsAndReorderedFrames() {
        val delivered = mutableListOf<Float>()
        val estimator = FpsEstimator(snap, Looper.getMainLooper()) { delivered += it }
        var pts = 0L
        for (i in 0 until 45) {
            pts += if (i % 10 == 9) 2_000_000 else 40_000 // a 2 s gap every 10 frames
            estimator.onVideoFrameAboutToBeRendered(pts, 0, video, null)
            if (i == 20) estimator.onVideoFrameAboutToBeRendered(pts - 80_000, 0, video, null) // out of order
        }
        idle(0)
        assertEquals(listOf(25f), delivered)
    }

    @Test
    fun declaredFrameRateWinsAndUnsnappableRatesKeepMeasuring() {
        val delivered = mutableListOf<Float>()
        val estimator = FpsEstimator(snap, Looper.getMainLooper()) { delivered += it }
        estimator.onVideoFrameAboutToBeRendered(0, 0, video.buildUpon().setFrameRate(50f).build(), null)
        idle(0)
        assertEquals(listOf(50f), delivered)

        estimator.reset()
        feed(estimator, 15.0, 40, startUs = 0) // 15 fps: no broadcast rate within 2 %
        assertEquals(listOf(50f), delivered)
        assertEquals(15.0, estimator.lastRawFps.toDouble(), 0.05)
    }

    @Test
    fun aResetDropsAnEstimateStillInFlight() {
        val delivered = mutableListOf<Float>()
        val estimator = FpsEstimator(snap, Looper.getMainLooper()) { delivered += it }
        for (i in 0 until 31) estimator.onVideoFrameAboutToBeRendered(i * 40_000L, 0, video, null)
        estimator.reset() // the tune changed before the main thread ran the callback
        idle(0)
        assertTrue(delivered.isEmpty())
    }
}

@OptIn(UnstableApi::class)
internal object AnalyticsTestTime {
    val zero = androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime(
        0, androidx.media3.common.Timeline.EMPTY, 0, null, 0, androidx.media3.common.Timeline.EMPTY, 0, null, 0, 0,
    )
}
