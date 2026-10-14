package app.thebox.player.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StallDetectors
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.WatchdogSample
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.TsCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real `ExoPlayer` assembly tests (docs/design/m2-playback-core.md §5.3 WP-C, §5.4), built with [PlayerFactory] and
 * [MediaSourceBuilder]. Day-0 constraints: no READY assertion at the first frame; nothing about audio output (shadow
 * audio decoders drop every sample, so audio is never "expected"); counters only with in-memory bytes + `FakeClock`.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealPlayerTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(/* isAutoAdvancing = */ true)
    private val bundles = mutableListOf<PlayerBundle>()

    @After fun tearDown() {
        bundles.forEach { it.player.release() }
    }

    private fun newBundle(settings: PlaybackSettings = PlaybackSettings(), ffmpegSupports: ((String) -> Boolean)? = { false }) =
        EngineTestSupport.bundle(context, clock, settings, ffmpegSupports).also { bundles += it }

    private class Seen : AnalyticsListener {
        var videoDecoder: String? = null
        var audioDecoder: String? = null
        var videoFormat: Format? = null
        var audioFormat: Format? = null
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            videoFormat = format
        }
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            audioFormat = format
        }
    }

    private fun ExoPlayer.seen(): Seen = Seen().also { addAnalyticsListener(it) }

    // ------------------------------------------------------------------ R-1: assembly

    @Test
    fun liveTsPlaysToFirstFrameThroughFactoryAndBuilder() {
        val bundle = newBundle()
        val seen = bundle.player.seen()
        val transport = EngineTestSupport.FakeTransport(EngineTestSupport.fixture("h264_aac_2s.ts"))
        val built = EngineTestSupport.mediaSources().build(EngineTestSupport.context(tuneId = 7), StreamFormat.TS, TsCompat.DEFAULT, transport, archive = null)

        assertEquals(listOf("liveTs"), transport.calls)
        assertTrue(built.source is ProgressiveMediaSource)
        assertEquals("thebox-session://7", built.source.mediaItem.localConfiguration!!.uri.toString())
        assertEquals("7", built.source.mediaItem.mediaId)
        assertTrue(built.telemetry === transport.telemetry)

        bundle.player.setMediaSource(built.source)
        bundle.player.prepare()
        bundle.player.play()
        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)

        assertEquals(MimeTypes.VIDEO_H264, seen.videoFormat?.sampleMimeType)
        assertTrue("video decoder ${seen.videoDecoder}", !seen.videoDecoder.isNullOrEmpty())
        assertTrue(bundle.player.videoDecoderCounters!!.renderedOutputBufferCount >= 1)
        TestPlayerRunHelper.runUntilPlaybackState(bundle.player, Player.STATE_ENDED)
        assertNull(bundle.player.playerError)
    }

    // ------------------------------------------------------------------ MP2: FFmpeg-preferring vs platform path

    @Test
    fun mp2UsesThePlatformDecoderWhenFfmpegIsNotPreferred() {
        val bundle = newBundle(PlaybackSettings(preferSoftwareAudio = false), ffmpegSupports = { true })
        val seen = bundle.player.seen()
        playFixture(bundle, "h264_mp2_2s.ts")
        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
        TestPlayerRunHelper.runUntilPlaybackState(bundle.player, Player.STATE_ENDED)

        assertEquals(MimeTypes.AUDIO_MPEG_L2, seen.audioFormat?.sampleMimeType)
        assertTrue("platform (shadow) MP2 decoder expected, got ${seen.audioDecoder}", !seen.audioDecoder.isNullOrEmpty())
        val audio = bundle.player.currentTracks.groups.single { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals(C.FORMAT_HANDLED, audio.getTrackSupport(0))
    }

    @Test
    fun mp2HidesPlatformDecodersWhenFfmpegIsPreferredAndSupportsIt() {
        val bundle = newBundle(PlaybackSettings(preferSoftwareAudio = true), ffmpegSupports = { it == MimeTypes.AUDIO_MPEG_L2 })
        assertEquals(setOf(MimeTypes.AUDIO_MPEG_L2), bundle.codecPolicy.softwareAudioMimes)
        val seen = bundle.player.seen()
        playFixture(bundle, "h264_mp2_2s.ts")
        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
        TestPlayerRunHelper.runUntilPlaybackState(bundle.player, Player.STATE_ENDED)

        // The platform renderer reports MP2 unsupported (empty decoder list), so the track is left to the FFmpeg
        // renderer, whose natives cannot load under Robolectric: no platform audio decoder is ever initialised.
        assertNull("no platform audio decoder may be used, got ${seen.audioDecoder}", seen.audioDecoder)
        val audio = bundle.player.currentTracks.groups.single { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, audio.getTrackSupport(0))
        assertEquals("video still plays", MimeTypes.VIDEO_H264, seen.videoFormat?.sampleMimeType)
    }

    @Test
    fun mp2StaysOnThePlatformWhenFfmpegCannotDecodeIt() {
        // Preferred, but FFmpeg does not support the MIME type (no AAR / natives): never hide the platform decoder.
        val bundle = newBundle(PlaybackSettings(preferSoftwareAudio = true), ffmpegSupports = { false })
        bundle.codecPolicy.softwareAudioMimes = setOf(MimeTypes.AUDIO_MPEG_L2)
        val seen = bundle.player.seen()
        playFixture(bundle, "h264_mp2_2s.ts")
        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
        TestPlayerRunHelper.runUntilPlaybackState(bundle.player, Player.STATE_ENDED)
        assertTrue(!seen.audioDecoder.isNullOrEmpty())
    }

    // ------------------------------------------------------------------ AdaptiveLoadControl on a real player (R-5, S4)

    @Test
    fun loadControlProfileChangeIsHonouredAtTheNextEvaluation() {
        val bundle = newBundle()
        val alc = bundle.loadControl as AdaptiveLoadControl
        // Start threshold far above what the source can ever deliver (it stalls after the 2 s clip).
        alc.profile = BufferProfile(startMs = 600_000, afterRebufferMs = 600_000, minBufferMs = 10_000, maxBufferMs = 20_000, targetBytes = 48 shl 20, level = -1)
        val data = EngineTestSupport.fixture("h264_aac_2s.ts")
        val transport = EngineTestSupport.FakeTransport(DataSource.Factory { EngineTestSupport.StallingDataSource(data) })
        val built = EngineTestSupport.mediaSources().build(EngineTestSupport.context(), StreamFormat.TS, TsCompat.DEFAULT, transport, null)
        // Shadow audio decoders swallow every sample, so with a stalled source the audio renderer would never be
        // "ready" and Media3 would never ask the LoadControl; video alone keeps the renderers ready.
        bundle.player.trackSelectionParameters = bundle.player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        bundle.player.setMediaSource(built.source)
        bundle.player.prepare()
        bundle.player.play()

        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
        val before = alc.startEvaluations
        val t0 = clock.elapsedRealtime()
        // 3 s of (fake) time with the decision evaluated again; well below the 30 s stuck-buffering backstop.
        TestPlayerRunHelper.advance(bundle.player).untilBackgroundThreadCondition {
            clock.elapsedRealtime() - t0 >= 3_000 && alc.startEvaluations > before
        }
        assertEquals("still waiting for 600 s of media", Player.STATE_BUFFERING, bundle.player.playbackState)

        alc.profile = alc.profile.copy(startMs = 500, afterRebufferMs = 500)
        TestPlayerRunHelper.runUntilPlaybackState(bundle.player, Player.STATE_READY)
        assertNull(bundle.player.playerError)
    }

    @Test
    fun prepareStopReleaseNeverHitsADeprecatedLoadControlOverload() {
        val bundle = newBundle()
        val alc = bundle.loadControl as AdaptiveLoadControl
        assertEquals(0L, alc.getBackBufferDurationUs(PlayerId.UNSET))
        assertFalse(alc.retainBackBufferFromKeyframe(PlayerId.UNSET))
        repeat(2) {
            playFixture(bundle, "h264_aac_2s.ts")
            TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
            bundle.player.stop()
            TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(bundle.player)
            assertEquals("stop frees the media buffer", 0, alc.bytesAllocated)
        }
        assertNull(bundle.player.playerError)
        bundle.player.release()
        bundles.remove(bundle)
    }

    // ------------------------------------------------------------------ dead-track guard (S5)

    @Test
    fun deadAc3PidAndScte27PidDoNotBlockPrepare() {
        val bundle = newBundle()
        val events = mutableListOf<EngineTestSupport.RecordedEvent>()
        val ts = EngineTestSupport.withExtraPmtEntries(EngineTestSupport.fixture("h264_aac_2s.ts"), listOf(0x81 to 0x333, 0x82 to 0x334))
        val built = EngineTestSupport.mediaSources().build(
            EngineTestSupport.context(events = events), StreamFormat.TS, TsCompat.DEFAULT, EngineTestSupport.FakeTransport(ts), null,
        )
        bundle.player.setMediaSource(built.source)
        bundle.player.prepare()
        bundle.player.play()
        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)

        val groups = bundle.player.currentTracks.groups
        assertEquals(1, groups.count { it.type == C.TRACK_TYPE_VIDEO })
        val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals("only the fed AAC track, not the dead AC-3 PID", 1, audio.size)
        assertEquals(MimeTypes.AUDIO_AAC, audio.single().getTrackFormat(0).sampleMimeType)
        val dead = synchronized(events) { events.filter { it.code == MediaSourceBuilder.DEAD_TRACK_CODE } }
        assertEquals(1, dead.size)
        assertEquals(PlaybackEventType.SNIFF, dead.single().type)
        assertEquals("pid=${0x333} type=audio", dead.single().detail)
    }

    // ------------------------------------------------------------------ watchdog sampling (§3.7, B6)

    private class RecordingDetectors : StallDetectors {
        val samples = mutableListOf<LongArray>()
        var resets = 0
        override fun onSample(s: WatchdogSample): StallVerdict? {
            samples += longArrayOf(s.positionMs, s.bufferedDurationMs, s.bytesTotal, s.videoRenderedCount)
            return null
        }
        override fun reset(nowMs: Long) {
            resets++
        }
    }

    @Test
    fun watchdogFillsTheSampleFromTheRealPlayer() {
        val bundle = newBundle()
        val detectors = RecordingDetectors()
        val watchdog = PlaybackWatchdog(bundle.player, detectors, MonoClock { clock.elapsedRealtime() })
        val telemetry = EngineTestSupport.FakeTelemetry().apply {
            bytesTotal = 95_128
            lastByteAtMs = clock.elapsedRealtime()
        }
        var milestone: Boolean? = null
        watchdog.firstMediaListener = { milestone = it }
        watchdog.bind(telemetry)
        watchdog.setExpectations(videoExpected = true, audioExpected = false) // shadow audio never advances (S14)
        watchdog.setSessionKind(isLive = true, isHls = false)
        playFixture(bundle, "h264_aac_2s.ts")
        watchdog.beginSession()

        watchdog.sampleNow()
        val s0 = watchdog.sample
        assertEquals(0L, s0.positionMs + s0.bufferedDurationMs)
        assertFalse(s0.firstFrameRendered)

        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
        TestPlayerRunHelper.advance(bundle.player).untilPositionAtLeast(600)
        watchdog.sampleNow()
        val s = watchdog.sample
        assertEquals(bundle.player.currentPosition, s.positionMs)
        assertEquals(bundle.player.totalBufferedDuration, s.bufferedDurationMs)
        assertEquals("buffered position", bundle.player.bufferedPosition, s.positionMs + s.bufferedDurationMs)
        assertTrue("buffered media grew: ${detectors.samples.map { it.toList() }}", s.positionMs + s.bufferedDurationMs > 1_000)
        assertTrue(s.positionMs >= 600)
        assertEquals(95_128L, s.bytesTotal)
        assertTrue(s.lastByteAgeMs >= 0)
        assertFalse(s.inDataSourceReconnect)
        assertTrue(s.isLive)
        assertFalse(s.isHls)
        assertTrue(s.playWhenReady)
        assertTrue(s.firstFrameRendered)
        assertTrue(s.msSinceFirstFrame > 0)
        assertTrue(s.videoExpected)
        assertFalse(s.audioExpected)
        assertTrue(s.videoRenderedCount >= 1)
        assertEquals(bundle.player.videoDecoderCounters!!.droppedBufferCount.toLong(), s.videoDroppedCount)
        assertFalse(s.tunneling)
        assertFalse(s.audioPassthrough)
        assertTrue(s.msSinceSeekOrPrepare > 0)
        assertEquals(2, detectors.samples.size)
        assertTrue("reset on beginSession", detectors.resets >= 1)
        assertEquals("first-media milestone is the first video frame", true, milestone)
        watchdog.release()
    }

    @Test
    fun watchdogTickDoesNotAllocate() {
        val bundle = newBundle()
        val noop = object : StallDetectors {
            override fun onSample(s: WatchdogSample): StallVerdict? = null
            override fun reset(nowMs: Long) = Unit
        }
        val watchdog = PlaybackWatchdog(bundle.player, noop, MonoClock { clock.elapsedRealtime() })
        watchdog.bind(EngineTestSupport.FakeTelemetry().apply { lastByteAtMs = 1 })
        playFixture(bundle, "h264_aac_2s.ts")
        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
        repeat(2_000) { watchdog.sampleNow() } // warm-up: class init, JIT
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        @Suppress("DEPRECATION") val thread = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(thread)
        repeat(1_000) { watchdog.sampleNow() }
        val allocated = mx.getThreadAllocatedBytes(thread) - before
        // Any per-tick allocation would be ≥ 16 KB over 1,000 ticks; allow only the measurement's own noise.
        assertTrue("allocated $allocated bytes over 1,000 ticks", allocated < 1_024)
        watchdog.release()
    }

    @Test
    fun milestoneIsAudioWhenTheVideoTypeIsDisabled() {
        val bundle = newBundle()
        val watchdog = PlaybackWatchdog(bundle.player, RecordingDetectors(), MonoClock { clock.elapsedRealtime() })
        TrackController(bundle).setVideoDisabled(true) // HDR poster / HIDDEN
        var milestone: Boolean? = null
        watchdog.firstMediaListener = { milestone = it }
        watchdog.beginSession()
        playFixture(bundle, "h264_aac_2s.ts")
        TestPlayerRunHelper.runUntilPlaybackState(bundle.player, Player.STATE_ENDED)
        // Audio-only milestone: the shadow audio sink never advances, so it is not reached here; it must not be a frame.
        assertNull(milestone)
        assertFalse(TrackController(bundle).apply { update(bundle.player.currentTracks) }.videoMilestoneExpected())
        watchdog.release()
    }

    // ------------------------------------------------------------------ stats and tracks from the real player

    @Test
    fun statsCollectsDecoderFormatAndCounters() {
        val bundle = newBundle()
        val stats = StatsCollector(bundle.player, MonoClock { clock.elapsedRealtime() })
        val telemetry = EngineTestSupport.FakeTelemetry().apply {
            bitrateBps = 380_000
            httpStatus = 200
            finalHost = "panel.example:8080"
            sniffed = StreamFormat.TS
        }
        stats.bind(telemetry, StreamFormat.UNKNOWN)
        playFixture(bundle, "h264_aac_2s.ts")
        TestPlayerRunHelper.runUntilPlaybackState(bundle.player, Player.STATE_ENDED)

        val s = stats.snapshot()
        assertEquals(MimeTypes.VIDEO_H264, s.videoMime)
        assertNotNull(s.videoDecoder)
        assertEquals(320, s.width)
        assertEquals(180, s.height)
        assertEquals(HdrKind.SDR, s.hdr)
        assertEquals(MimeTypes.AUDIO_AAC, s.audioMime)
        assertEquals(2, s.audioChannels)
        assertNotNull(s.audioDecoder)
        assertFalse(s.audioPassthrough)
        assertEquals(bundle.player.videoDecoderCounters!!.droppedBufferCount.toLong(), s.droppedFrames)
        assertEquals(380_000L, s.streamBitrateBps)
        assertEquals(200, s.httpStatus)
        assertEquals("panel.example:8080", s.host)
        assertEquals(StreamFormat.TS, s.format)
        assertFalse(s.tunneling)
        stats.release()
    }

    @Test
    fun trackControllerMapsTheFixtureTracks() {
        val bundle = newBundle()
        val tracks = TrackController(bundle)
        playFixture(bundle, "h264_aac_2s.ts")
        TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
        val info = tracks.update(bundle.player.currentTracks)

        val aac = info.audio.single()
        assertEquals("AAC", aac.codec)
        assertEquals(2, aac.channels)
        assertTrue(aac.selected)
        assertTrue(aac.supported)
        assertFalse(aac.describesVideo)
        assertEquals(C.TRACK_TYPE_AUDIO, aac.ref.type)
        assertTrue(tracks.videoMilestoneExpected())
        assertEquals(MimeTypes.VIDEO_H264, tracks.video.mimeType)
        assertEquals(320, tracks.video.width)
        assertEquals(HdrKind.SDR, tracks.video.hdr)
        assertFalse("progressive x264 clip", tracks.video.interlacedHint)
        assertNull("live TS declares no frame rate", tracks.video.declaredFps)

        tracks.setVideoDisabled(true)
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(bundle.player)
        tracks.update(bundle.player.currentTracks)
        assertFalse(tracks.videoMilestoneExpected())
        assertTrue(tracks.videoDisabled)
    }

    private fun playFixture(bundle: PlayerBundle, name: String) {
        val built = EngineTestSupport.mediaSources().build(
            EngineTestSupport.context(), StreamFormat.TS, TsCompat.DEFAULT, EngineTestSupport.FakeTransport(EngineTestSupport.fixture(name)), null,
        )
        bundle.player.setMediaSource(built.source)
        bundle.player.prepare()
        bundle.player.play()
    }
}
