package app.thebox.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.IdlingMediaCodecAdapterFactory
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Day-0 check 3 (docs/design/m2-playback-core.md §5.4, §6.8, review S14): a real `ExoPlayer` under Robolectric with
 * `ShadowMediaCodecConfig`, `IdlingMediaCodecAdapterFactory`, a `Surface(SurfaceTexture(1))` and the 2 s TS fixture,
 * fed from memory and from `mockwebserver3`, reaches the first rendered frame. Decides the WP-C/WP-D test strategy;
 * the results are recorded in `docs/design/m2-day0-results.md`. Lead-owned; WP-C's `RealPlayerTest` supersedes it.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Day0RealExoPlayerTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val players = mutableListOf<ExoPlayer>()
    private var server: MockWebServer? = null

    @After fun tearDown() {
        players.forEach { it.release() }
        server?.close()
    }

    @Test
    fun tsFromMemoryWithFakeClockRendersFirstFrameAndEnds() {
        val clock = FakeClock(/* isAutoAdvancing = */ true)
        val (player, seen) = newPlayer(clock)
        val bytes = fixture("h264_aac_2s.ts")
        player.setMediaSource(tsSource(DataSource.Factory { ByteArrayDataSource(bytes) }))
        player.prepare()
        player.play()

        TestPlayerRunHelper.runUntilRenderedFirstFrame(player)
        // The first frame is rendered while still BUFFERING (the renderer outputs it before READY).
        seen.stateAtFirstFrame = player.playbackState
        assertEquals(MimeTypes.VIDEO_H264, seen.videoFormat?.sampleMimeType)
        assertEquals(MimeTypes.AUDIO_AAC, seen.audioFormat?.sampleMimeType)
        assertTrue(seen.videoDecoder, seen.videoDecoder.isNotEmpty())
        assertTrue(seen.audioDecoder, seen.audioDecoder.isNotEmpty())

        // A finite file: the whole clip plays through with the shadow codecs.
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
        assertTrue(seen.states.toString(), Player.STATE_READY in seen.states)
        val v = player.videoDecoderCounters!!
        val a = player.audioDecoderCounters!!
        report("memory+FakeClock", seen, player, extra = "audioQueued=${a.queuedInputBufferCount} audioRendered=${a.renderedOutputBufferCount}")
        assertTrue("video frames rendered: ${v.renderedOutputBufferCount}", v.renderedOutputBufferCount >= 40)
    }

    @Test
    fun tsFromMockWebServerWithSystemClockRendersFirstFrame() {
        val server = MockWebServer().also { this.server = it }
        server.start()
        // Chunked, no Content-Length: like a live TS response.
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "video/mp2t").chunkedBody(Buffer().write(fixture("h264_aac_2s.ts")), 16 * 1024).build())

        val (player, seen) = newPlayer(Clock.DEFAULT)
        val http = OkHttpDataSource.Factory(OkHttpClient())
        player.setMediaSource(tsSource(http, server.url("/live/u/p/1.ts").toString()))
        player.prepare()
        player.play()

        TestPlayerRunHelper.runUntilRenderedFirstFrame(player)
        seen.stateAtFirstFrame = player.playbackState
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals(null, request.headers["Range"])
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
        assertTrue(seen.states.toString(), Player.STATE_READY in seen.states)
        report("mockwebserver3+Clock.DEFAULT", seen, player, extra = "range=${request.headers["Range"]}")
        // Real clock: the video renderer drops "late" frames at random (27–99 rendered over runs), so only assert progress.
        assertTrue(player.videoDecoderCounters!!.renderedOutputBufferCount >= 1)
    }

    @Test
    fun throttledLiveLikeBodyRendersFirstFrame() {
        // Bytes trickle in (~160 KB/s, close to real time for this clip) over a chunked response, as from a live panel.
        val server = MockWebServer().also { this.server = it }
        server.start()
        server.enqueue(
            MockResponse.Builder().code(200).chunkedBody(Buffer().write(fixture("h264_aac_2s.ts")), 8 * 1024)
                .throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS).build(),
        )
        val (player, seen) = newPlayer(Clock.DEFAULT)
        player.setMediaSource(tsSource(OkHttpDataSource.Factory(OkHttpClient()), server.url("/live/u/p/2.ts").toString()))
        player.prepare()
        player.play()
        val t0 = System.nanoTime()
        TestPlayerRunHelper.runUntilRenderedFirstFrame(player)
        val firstFrameWallMs = (System.nanoTime() - t0) / 1_000_000
        seen.stateAtFirstFrame = player.playbackState
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
        report("throttled+Clock.DEFAULT", seen, player, extra = "firstFrameWallMs=$firstFrameWallMs")
        assertTrue(seen.states.toString(), Player.STATE_READY in seen.states)
        assertTrue(player.videoDecoderCounters!!.renderedOutputBufferCount >= 1)
    }

    @Test
    fun mp2AudioGetsTheShadowMpegL2Decoder() {
        val (player, seen) = newPlayer(FakeClock(/* isAutoAdvancing = */ true))
        val bytes = fixture("h264_mp2_2s.ts")
        player.setMediaSource(tsSource(DataSource.Factory { ByteArrayDataSource(bytes) }))
        player.prepare()
        player.play()
        TestPlayerRunHelper.runUntilRenderedFirstFrame(player)
        report("mp2", seen, player)
        assertEquals(MimeTypes.AUDIO_MPEG_L2, seen.audioFormat?.sampleMimeType)
        assertTrue(seen.audioDecoder, seen.audioDecoder.isNotEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private class Seen {
        var videoDecoder = ""
        var audioDecoder = ""
        var videoFormat: Format? = null
        var audioFormat: Format? = null
        var firstFrameAtMs = -1L
        var audioAdvancing = false
        var stateAtFirstFrame = -1
        val states = mutableListOf<Int>()
    }

    private fun newPlayer(clock: Clock): Pair<ExoPlayer, Seen> {
        val renderers = object : DefaultRenderersFactory(context) {
            // The S14 seam: without it the async MediaCodec threads run freely and the tests flake.
            private val idling = IdlingMediaCodecAdapterFactory(context, clock)
            override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = idling
        }
        val player = TestExoPlayerBuilder(context).setClock(clock).setRenderersFactory(renderers).build()
        players += player
        player.setVideoSurface(Surface(SurfaceTexture(1)))
        val seen = Seen()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                seen.videoDecoder = decoderName
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                seen.audioDecoder = decoderName
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                seen.videoFormat = format
            }
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                seen.audioFormat = format
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (seen.firstFrameAtMs < 0) seen.firstFrameAtMs = eventTime.realtimeMs
            }
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                seen.states += state
            }
            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                seen.audioAdvancing = true
            }
        })
        return player to seen
    }

    /** The design's TS source: only TsExtractor, TS_FLAGS, the session placeholder URI (§3.1.3). */
    private fun tsSource(factory: DataSource.Factory, uri: String = "thebox-session://1"): ProgressiveMediaSource {
        val flags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(flags).setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        val tsOnly = androidx.media3.extractor.ExtractorsFactory { extractors.createExtractors().filterIsInstance<TsExtractor>().toTypedArray() }
        return ProgressiveMediaSource.Factory(factory, tsOnly).createMediaSource(MediaItem.Builder().setUri(Uri.parse(uri)).setMediaId("1").build())
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("ts/$name")) { "missing fixture ts/$name" }.use { it.readBytes() }

    private fun report(label: String, seen: Seen, player: ExoPlayer, extra: String = "") {
        val v = player.videoDecoderCounters
        println(
            "DAY0[$label] video=${seen.videoDecoder} ${seen.videoFormat?.sampleMimeType} ${seen.videoFormat?.width}x${seen.videoFormat?.height} " +
                "audio=${seen.audioDecoder} ${seen.audioFormat?.sampleMimeType} ch=${seen.audioFormat?.channelCount} " +
                "rendered=${v?.renderedOutputBufferCount} dropped=${v?.droppedBufferCount} skipped=${v?.skippedOutputBufferCount} " +
                "audioAdvancing=${seen.audioAdvancing} stateAtFirstFrame=${seen.stateAtFirstFrame} states=${seen.states} positionMs=${player.currentPosition} durationMs=${player.duration.takeIf { it != C.TIME_UNSET }} $extra",
        )
    }
}
