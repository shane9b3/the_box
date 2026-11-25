package app.thebox.player.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.model.CatchupMode
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.StreamFormat
import app.thebox.player.internal.TsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** [MediaSourceBuilder] (§3.1.3): source type, URI hygiene, transport choice and per-origin policies. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaSourceBuilderTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clip = EngineTestSupport.fixture("h264_aac_2s.ts")
    private val origins = mutableListOf<LoadOrigin>()
    private val builder = MediaSourceBuilder { origin -> origins += origin; DefaultLoadErrorHandlingPolicy() }
    private val realUrl = "http://panel.example:8080/timeshift/user/secret/60/2026-10-13:20-00/1.ts"

    private val archive = ArchiveRequest(
        url = realUrl, headers = emptyMap(), mode = CatchupMode.entries.first(), variant = "xc-path",
        requestStart = Instant.parse("2026-10-13T20:00:00Z"), requestEnd = null, nativeSeekRange = null,
    )

    @Test
    fun liveTsIsProgressiveOverLiveTsWithTheSessionUri() {
        for (format in listOf(StreamFormat.TS, StreamFormat.UNKNOWN)) {
            val transport = EngineTestSupport.FakeTransport(clip)
            val built = builder.build(EngineTestSupport.context(tuneId = 42), format, TsCompat.DEFAULT, transport, null)
            assertTrue(built.source is ProgressiveMediaSource)
            assertEquals(Uri.parse("thebox-session://42"), built.source.mediaItem.localConfiguration!!.uri)
            assertEquals(listOf("liveTs"), transport.calls)
            assertFalse("no URL in the MediaItem", built.source.mediaItem.toString().contains("panel.example"))
        }
        assertEquals(listOf(LoadOrigin.PROGRESSIVE, LoadOrigin.PROGRESSIVE), origins)
    }

    @Test
    fun nonHttpSchemesAreUnsupportedBeforeAnyTransport() {
        // Code review 2026-10-14, N-S3: rtmp/rtsp/udp/rtp/mms/mmsh never reach a data source (no 15 s of "network"
        // retries); the exception names the scheme, never the URL.
        for (url in listOf("rtmp://h.example/app/user/pass", "rtsp://h.example/s", "udp://@239.0.0.1:1234", "rtp://239.0.0.1:5004",
            "mms://h.example/s", "mmsh://h.example/s", "RTMP://h.example/a")) {
            val transport = EngineTestSupport.FakeTransport(clip)
            val e = try {
                builder.build(EngineTestSupport.context(resolved = EngineTestSupport.resolved(url)), StreamFormat.TS, TsCompat.DEFAULT, transport, null)
                null
            } catch (x: UnsupportedStreamFormatException) { x }
            assertNotNull(url, e)
            assertEquals(url.substringBefore("://").lowercase(), e!!.scheme)
            assertFalse(e.message!!.contains("h.example") || e.message!!.contains("239.0.0.1"))
            assertTrue("no transport for $url", transport.calls.isEmpty())
        }
        // http/https are built.
        val transport = EngineTestSupport.FakeTransport(clip)
        builder.build(EngineTestSupport.context(resolved = EngineTestSupport.resolved("https://h.example/1.ts")), StreamFormat.TS, TsCompat.DEFAULT, transport, null)
        assertEquals(listOf("liveTs"), transport.calls)
    }

    @Test
    fun compatLevelReachesTheExtractor() {
        val ctx = EngineTestSupport.context()
        fun mode(compat: TsCompat): Int {
            val ts = builder.tsExtractors(ctx, compat).createExtractors().single().underlyingImplementation
            return TsExtractor::class.java.getDeclaredField("mode").apply { isAccessible = true }.getInt(ts)
        }
        assertEquals(TsExtractor.MODE_SINGLE_PMT, mode(TsCompat.DEFAULT))
        assertEquals(TsExtractor.MODE_MULTI_PMT, mode(TsCompat.MULTI_PMT))
    }

    @Test
    fun hlsUsesTheRealUriChunklessPreparationAndTheTsFlags() {
        val transport = EngineTestSupport.FakeTransport(clip)
        val url = "http://panel.example:8080/live/u/p/1.m3u8"
        val built = builder.build(EngineTestSupport.context(resolved = EngineTestSupport.resolved(url, StreamFormat.HLS)), StreamFormat.HLS, TsCompat.DEFAULT, transport, null)
        val hls = built.source as HlsMediaSource
        val item = hls.mediaItem
        assertEquals(Uri.parse(url), item.localConfiguration!!.uri)
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration!!.mimeType)
        assertEquals(0.97f, item.liveConfiguration.minPlaybackSpeed, 0f)
        assertEquals(1.03f, item.liveConfiguration.maxPlaybackSpeed, 0f)
        assertEquals(listOf("http"), transport.calls)
        assertEquals(listOf(LoadOrigin.HLS_MANIFEST), origins)
        assertTrue(field<Boolean>(hls, HlsMediaSource::class.java, "allowChunklessPreparation"))
        val extractorFactory = field<Any>(hls, HlsMediaSource::class.java, "extractorFactory") as DefaultHlsExtractorFactory
        assertEquals(TsExtractorsFactory.TS_FLAGS, field<Int>(extractorFactory, DefaultHlsExtractorFactory::class.java, "payloadReaderFactoryFlags"))
    }

    @Test
    fun keepUpWithLiveOffPinsHlsLiveAtNormalSpeed() {
        // Smooth playback 2026-11-23: with the setting off the live speed control has no room to leave 1.0×.
        val url = "http://panel.example:8080/live/u/p/1.m3u8"
        val built = builder.build(
            EngineTestSupport.context(resolved = EngineTestSupport.resolved(url, StreamFormat.HLS)), StreamFormat.HLS, TsCompat.DEFAULT,
            EngineTestSupport.FakeTransport(clip), null, null, liveSpeedCorrection = false,
        )
        val item = built.source.mediaItem
        assertEquals(1f, item.liveConfiguration.minPlaybackSpeed, 0f)
        assertEquals(1f, item.liveConfiguration.maxPlaybackSpeed, 0f)
    }

    @Test
    fun archiveHlsHasNoLiveSpeedBand() {
        val url = "http://panel.example:8080/timeshift/u/p/60/x/1.m3u8"
        val built = builder.build(EngineTestSupport.context(resolved = EngineTestSupport.resolved(url, StreamFormat.HLS)), StreamFormat.HLS, TsCompat.DEFAULT, EngineTestSupport.FakeTransport(clip), archive)
        val item = built.source.mediaItem
        assertEquals(androidx.media3.common.C.RATE_UNSET, item.liveConfiguration.minPlaybackSpeed, 0f)
        assertEquals(listOf(LoadOrigin.ARCHIVE), origins)
    }

    @Test
    fun progressiveOtherIsGenericProgressiveOverHttp() {
        val transport = EngineTestSupport.FakeTransport(clip)
        val built = builder.build(EngineTestSupport.context(tuneId = 3), StreamFormat.PROGRESSIVE_OTHER, TsCompat.DEFAULT, transport, null)
        assertTrue(built.source is ProgressiveMediaSource)
        assertEquals(Uri.parse("thebox-session://3"), built.source.mediaItem.localConfiguration!!.uri)
        assertEquals(listOf("http"), transport.calls)
    }

    @Test
    fun dashIsNotBuilt() {
        try {
            builder.build(EngineTestSupport.context(), StreamFormat.DASH, TsCompat.DEFAULT, EngineTestSupport.FakeTransport(clip), null)
            fail("DASH must be refused")
        } catch (e: UnsupportedStreamFormatException) {
            assertEquals(StreamFormat.DASH, e.format)
        }
    }

    @Test
    fun archiveTsPlaysOverHttpWithTheRealUrlHiddenFromMedia3() {
        val sources = mutableListOf<EngineTestSupport.StallingDataSource>()
        val recording = EngineTestSupport.FakeTransport(DataSource.Factory { EngineTestSupport.StallingDataSource(clip).also { synchronized(sources) { sources += it } } })
        val built = builder.build(EngineTestSupport.context(tuneId = 9, resolved = EngineTestSupport.resolved(realUrl)), StreamFormat.TS, TsCompat.DEFAULT, recording, archive)
        assertEquals(listOf("http"), recording.calls)
        assertEquals(listOf(LoadOrigin.ARCHIVE), origins)
        assertEquals(Uri.parse("thebox-session://9"), built.source.mediaItem.localConfiguration!!.uri)

        val bundle = EngineTestSupport.bundle(context, FakeClock(true))
        try {
            bundle.player.setMediaSource(built.source)
            bundle.player.prepare()
            bundle.player.play()
            TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
            val uris = synchronized(sources) { sources.flatMap { it.openedUris } }
            assertTrue(uris.isNotEmpty())
            assertTrue("the transport sees the real archive URL: $uris", uris.all { it.toString() == realUrl })
            assertNull(bundle.player.playerError)
        } finally {
            bundle.player.release()
        }
    }

    @Test
    fun sessionUriDataSourceSwapsOnlyThePlaceholder() {
        val upstream = EngineTestSupport.StallingDataSource(ByteArray(10))
        val placeholder = Uri.parse("thebox-session://5")
        val ds = SessionUriDataSource(upstream, placeholder, realUrl)
        assertNull(ds.uri)
        ds.open(DataSpec.Builder().setUri(placeholder).setPosition(188L).build())
        assertEquals(Uri.parse(realUrl), upstream.openedUris.single())
        assertEquals(placeholder, ds.uri)
        assertTrue(ds.responseHeaders.isEmpty())
        val buf = ByteArray(10)
        assertEquals(10, ds.read(buf, 0, 10))
        ds.close()
        assertNull(ds.uri)

        // Any other URI (e.g. an HLS segment) passes through untouched.
        val other = EngineTestSupport.StallingDataSource(ByteArray(1))
        SessionUriDataSource(other, placeholder, realUrl).open(DataSpec(Uri.parse("http://cdn.example/seg1.ts")))
        assertEquals(Uri.parse("http://cdn.example/seg1.ts"), other.openedUris.single())
    }

    // ---- Sports Hub clips (docs/design/m6-sports-hub.md §6.5, A-S3)

    private val clipHls = "https://manifest.example/hls/master.m3u8?fastly_token=FAKETOKEN0123456789"
    private val clipMp4 = "https://cdn.example/pmp4/main.mp4?fastly_token=FAKETOKEN0123456789"
    private val clipVtt = "https://cdn.example/text/text.vtt?fastly_token=FAKETOKENvtt456789"

    /**
     * The recorded NHL master lists its own English SUBTITLES rendition (DEFAULT=NO): an HLS clip uses it and side-loads
     * nothing, even when the lookup also names a WebVTT file.
     */
    @Test
    fun clipHlsUsesTheMastersOwnCaptionsAndSideloadsNothing() {
        assertTrue("settled by the recorded master", MediaSourceBuilder.CLIP_HLS_MASTER_CARRIES_CAPTIONS)
        val transport = EngineTestSupport.FakeTransport(clip)
        val built = builder.build(
            EngineTestSupport.context(tuneId = 21, resolved = EngineTestSupport.resolved(clipHls, StreamFormat.HLS)),
            StreamFormat.HLS, TsCompat.DEFAULT, transport, null, clip = ClipMedia(clipVtt),
        )
        assertTrue("no merge beside the master", built.source is HlsMediaSource)
        assertEquals(listOf("http"), transport.calls)
        assertEquals(listOf(LoadOrigin.HLS_MANIFEST), origins)
        assertFalse("the captions token never reaches Media3", built.source.mediaItem.toString().contains("vtt456789"))
    }

    @Test
    fun clipHlsWithoutCaptionsIsThePlainHlsSourceWithNoLiveBand() {
        val transport = EngineTestSupport.FakeTransport(clip)
        val built = builder.build(
            EngineTestSupport.context(tuneId = 22, resolved = EngineTestSupport.resolved(clipHls, StreamFormat.HLS)),
            StreamFormat.HLS, TsCompat.DEFAULT, transport, null, clip = ClipMedia(null),
        )
        assertTrue(built.source is HlsMediaSource)
        assertEquals(androidx.media3.common.MediaItem.LiveConfiguration.UNSET, built.source.mediaItem.liveConfiguration)
        assertEquals(Uri.parse(clipHls), built.source.mediaItem.localConfiguration!!.uri)
    }

    @Test
    fun clipMp4HidesItsAddressAndAlwaysMergesCaptions() {
        val transport = EngineTestSupport.FakeTransport(clip)
        val built = builder.build(
            EngineTestSupport.context(tuneId = 23, resolved = EngineTestSupport.resolved(clipMp4, StreamFormat.PROGRESSIVE_OTHER)),
            StreamFormat.PROGRESSIVE_OTHER, TsCompat.DEFAULT, transport, null, clip = ClipMedia(clipVtt),
        )
        assertTrue(built.source is MergingMediaSource)
        assertEquals(listOf(LoadOrigin.ARCHIVE, LoadOrigin.ARCHIVE), origins)
        assertFalse(built.source.mediaItem.toString().contains("FAKETOKEN"))
        try {
            builder.build(EngineTestSupport.context(), StreamFormat.DASH, TsCompat.DEFAULT, transport, null, clip = ClipMedia(null))
            fail("a DASH clip must be refused")
        } catch (e: UnsupportedStreamFormatException) {
            assertEquals(StreamFormat.DASH, e.format)
        }
    }

    /**
     * The progressive clip path end to end on a real player: the media and the side-loaded WebVTT both open their real
     * addresses through the session's transport (behind placeholders), and the English captions track is selected by
     * default once text is on.
     */
    @Test
    fun aProgressiveClipPlaysWithItsSideloadedEnglishCaptions() {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.500\nGoal!\n".toByteArray()
        val opened = mutableListOf<Uri>()
        val routing = DataSource.Factory {
            object : androidx.media3.datasource.BaseDataSource(true) {
                private var inner: androidx.media3.datasource.ByteArrayDataSource? = null
                private var uri: Uri? = null
                override fun open(dataSpec: DataSpec): Long {
                    synchronized(opened) { opened += dataSpec.uri }
                    uri = dataSpec.uri
                    val bytes = if (dataSpec.uri.toString().contains(".vtt")) vtt else clip
                    return androidx.media3.datasource.ByteArrayDataSource(bytes).also { inner = it }.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner!!.read(buffer, offset, length)
                override fun getUri(): Uri? = uri
                override fun close() { inner?.close(); inner = null }
            }
        }
        val transport = EngineTestSupport.FakeTransport(routing)
        val built = builder.build(
            EngineTestSupport.context(tuneId = 24, resolved = EngineTestSupport.resolved(clipMp4, StreamFormat.PROGRESSIVE_OTHER)),
            StreamFormat.PROGRESSIVE_OTHER, TsCompat.DEFAULT, transport, null, clip = ClipMedia(clipVtt),
        )
        val bundle = EngineTestSupport.bundle(context, FakeClock(true))
        try {
            // Subtitles on, as the controller's applyTuneDefaults(…, subtitlesEnabled = true) leaves it; no preferred language.
            TrackController(bundle).applyTuneDefaults(listOf("en"), emptyList(), null, subtitlesEnabled = true)
            bundle.player.setMediaSource(built.source)
            bundle.player.prepare()
            bundle.player.play()
            TestPlayerRunHelper.runUntilRenderedFirstFrame(bundle.player)
            assertNull(bundle.player.playerError)
            val text = bundle.player.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            assertEquals("one side-loaded text track", 1, text.size)
            assertEquals("en", text.single().getTrackFormat(0).language)
            assertTrue("selected by its default flag", text.single().isSelected)
            val uris = synchronized(opened) { opened.map { it.toString() } }
            assertTrue(uris.toString(), clipMp4 in uris && clipVtt in uris)
        } finally {
            bundle.player.release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, type: Class<*>, name: String): T =
        type.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
}
