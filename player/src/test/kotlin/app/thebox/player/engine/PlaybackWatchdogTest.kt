package app.thebox.player.engine

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StallDetectors
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.WatchdogSample
import app.thebox.player.internal.PlayerBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** [PlaybackWatchdog] ticking, guards it owns, event-driven sample fields and the first-media milestone (§3.7, B2, S11). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackWatchdogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var bundle: PlayerBundle
    private lateinit var watchdog: PlaybackWatchdog
    private val detectors = Detectors()
    private val clock = MonoClock { SystemClock.uptimeMillis() }

    private class Detectors : StallDetectors {
        var samples = 0
        var verdictOn = -1
        var resets = 0
        override fun onSample(s: WatchdogSample): StallVerdict? {
            samples++
            return if (samples == verdictOn) StallVerdict(StallKind.FROZEN_VIDEO, "test") else null
        }
        override fun reset(nowMs: Long) {
            resets++
        }
    }

    private val eventTime = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)

    @Before fun setUp() {
        bundle = EngineTestSupport.bundle(context, FakeClock(true))
        watchdog = PlaybackWatchdog(bundle.player, detectors, clock)
    }

    @After fun tearDown() {
        watchdog.release()
        bundle.player.release()
    }

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    @Test
    fun ticksOncePerSecondOnlyWhilePlayWhenReady() {
        watchdog.start { }
        idle(3_000)
        assertEquals("not ticking while playWhenReady is false", 0, detectors.samples)

        bundle.player.playWhenReady = true
        watchdog.onPlayWhenReadyChanged(eventTime, true, 0)
        idle(3_000)
        assertEquals(3, detectors.samples)

        bundle.player.playWhenReady = false
        watchdog.onPlayWhenReadyChanged(eventTime, false, 0)
        idle(3_000)
        assertEquals(3, detectors.samples)

        bundle.player.playWhenReady = true
        watchdog.onPlayWhenReadyChanged(eventTime, true, 0)
        idle(1_000)
        watchdog.stop()
        idle(3_000)
        assertEquals(4, detectors.samples)
    }

    @Test
    fun verdictReachesTheCallback() {
        detectors.verdictOn = 2
        val verdicts = mutableListOf<StallVerdict>()
        bundle.player.playWhenReady = true
        watchdog.start { verdicts += it }
        idle(3_000)
        assertEquals(listOf(StallKind.FROZEN_VIDEO), verdicts.map { it.kind })
        assertEquals("keeps sampling after a verdict (cool-downs are the detectors' job)", 3, detectors.samples)
    }

    @Test
    fun sampleIsReusedNotReallocated() {
        val first = watchdog.sample
        watchdog.sampleNow()
        watchdog.sampleNow()
        assertTrue(first === watchdog.sample)
    }

    @Test
    fun underrunsAreCountedOverTheLastThreeSeconds() {
        repeat(5) { watchdog.onAudioUnderrun(eventTime, 4096, 20, 30) }
        watchdog.sampleNow()
        assertEquals(5, watchdog.sample.audioUnderrunsLast3s)
        idle(2_000)
        watchdog.onAudioUnderrun(eventTime, 4096, 20, 30)
        watchdog.sampleNow()
        assertEquals(6, watchdog.sample.audioUnderrunsLast3s)
        idle(1_500)
        watchdog.sampleNow()
        assertEquals(1, watchdog.sample.audioUnderrunsLast3s)
    }

    @Test
    fun passthroughAndAudioAdvancingFollowTheAudioTrack() {
        watchdog.onAudioTrackInitialized(eventTime, AudioSink.AudioTrackConfig(C.ENCODING_AC3, 48_000, 0, false, false, 0))
        watchdog.onAudioPositionAdvancing(eventTime, 0)
        watchdog.sampleNow()
        assertTrue(watchdog.sample.audioPassthrough)
        assertTrue(watchdog.sample.audioAdvancing)

        // A new AudioTrack (decoder fallback, PCM): the position must start advancing again.
        watchdog.onAudioTrackInitialized(eventTime, AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT, 48_000, 0, false, false, 0))
        watchdog.sampleNow()
        assertFalse(watchdog.sample.audioPassthrough)
        assertFalse(watchdog.sample.audioAdvancing)
    }

    @Test
    fun playStartAndFirstFrameTimesForTheAudioStartDetector() {
        watchdog.beginSession()
        watchdog.onRenderedFirstFrame(eventTime, Any(), 0)
        idle(1_500)
        watchdog.onIsPlayingChanged(eventTime, true) // e.g. after an AFR hold (S11)
        idle(500)
        watchdog.sampleNow()
        assertTrue(watchdog.sample.firstFrameRendered)
        assertEquals(2_000L, watchdog.sample.msSinceFirstFrame)
        assertEquals(500L, watchdog.sample.msSincePlayStart)
        assertEquals(2_000L, watchdog.sample.msSinceSeekOrPrepare)
    }

    @Test
    fun resetRestartsTheSeekGraceButKeepsTheFirstFrame() {
        watchdog.beginSession()
        watchdog.onRenderedFirstFrame(eventTime, Any(), 0)
        idle(5_000)
        val before = detectors.resets
        watchdog.reset()
        watchdog.sampleNow()
        assertEquals(0L, watchdog.sample.msSinceSeekOrPrepare)
        assertTrue(watchdog.sample.firstFrameRendered)
        assertEquals(before + 1, detectors.resets)

        watchdog.beginSession()
        watchdog.sampleNow()
        assertFalse(watchdog.sample.firstFrameRendered)
    }

    @Test
    fun expectationsCombineWithTheSelectedTracks() {
        watchdog.setExpectations(videoExpected = true, audioExpected = true)
        watchdog.sampleNow()
        assertFalse("no tracks selected yet", watchdog.sample.videoExpected)
        assertFalse(watchdog.sample.audioExpected)

        watchdog.onTracksChanged(eventTime, tracks(video = true, audio = true))
        watchdog.sampleNow()
        assertTrue(watchdog.sample.videoExpected)
        assertTrue(watchdog.sample.audioExpected)

        watchdog.setExpectations(videoExpected = false, audioExpected = true) // poster / HIDDEN
        watchdog.sampleNow()
        assertFalse(watchdog.sample.videoExpected)
    }

    @Test
    fun firstMediaIsTheFirstFrameWhenVideoIsSelected() {
        val reached = mutableListOf<Boolean>()
        watchdog.firstMediaListener = { reached += it }
        watchdog.beginSession()
        watchdog.onTracksChanged(eventTime, tracks(video = true, audio = true))
        watchdog.onAudioPositionAdvancing(eventTime, 0)
        assertTrue("audio does not count when video is expected", reached.isEmpty())
        watchdog.onRenderedFirstFrame(eventTime, Any(), 0)
        watchdog.onRenderedFirstFrame(eventTime, Any(), 0)
        assertEquals(listOf(true), reached)
    }

    @Test
    fun firstMediaIsAudioForRadioAndIsNotChangedByLaterTracks() {
        val reached = mutableListOf<Boolean>()
        watchdog.firstMediaListener = { reached += it }
        watchdog.beginSession()
        watchdog.onTracksChanged(eventTime, tracks(video = false, audio = true))
        watchdog.onTracksChanged(eventTime, tracks(video = true, audio = true)) // later change does not re-decide
        watchdog.onRenderedFirstFrame(eventTime, Any(), 0)
        assertTrue(reached.isEmpty())
        watchdog.onAudioPositionAdvancing(eventTime, 0)
        assertEquals(listOf(false), reached)

        watchdog.beginSession() // next tune decides again
        watchdog.onTracksChanged(eventTime, tracks(video = true, audio = true))
        watchdog.onRenderedFirstFrame(eventTime, Any(), 0)
        assertEquals(listOf(false, true), reached)
    }

    @Test
    fun telemetryFeedsBytesAndLastByteAge() {
        val t = EngineTestSupport.FakeTelemetry()
        watchdog.bind(t)
        watchdog.sampleNow()
        assertEquals("no byte yet: no age", 0L, watchdog.sample.lastByteAgeMs)
        t.bytesTotal = 1234
        t.lastByteAtMs = clock.nowMs()
        t.inReconnect = true
        idle(700)
        watchdog.sampleNow()
        assertEquals(1234L, watchdog.sample.bytesTotal)
        assertEquals(700L, watchdog.sample.lastByteAgeMs)
        assertTrue(watchdog.sample.inDataSourceReconnect)
        watchdog.setSessionKind(isLive = false, isHls = true)
        watchdog.bind(null)
        watchdog.sampleNow()
        assertEquals(0L, watchdog.sample.bytesTotal)
        assertFalse(watchdog.sample.isLive)
        assertTrue(watchdog.sample.isHls)
    }

    private fun tracks(video: Boolean, audio: Boolean): Tracks {
        val groups = mutableListOf<Tracks.Group>()
        if (video) groups += group("v", Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        if (audio) groups += group("a", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        return Tracks(groups)
    }

    private fun group(id: String, f: Format) = Tracks.Group(TrackGroup(id, f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
}
