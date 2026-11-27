package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.common.BoxLog
import app.thebox.core.model.CatchupMode
import app.thebox.core.playback.StreamFormat
import app.thebox.player.datasource.HlsEdgeDataSourceFactory
import app.thebox.player.datasource.HlsEdgeProbe
import app.thebox.player.internal.RingOpen
import app.thebox.player.internal.TsCompat
import app.thebox.player.timeshift.FakeTsRing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * (HLS edge hand-over, stage 0) Only a live HLS source in a debug build gets the `HlsEdge` wrapper: TS (live, archive,
 * ring), HLS archive, HLS and MP4 clips, progressive and the release log level build none and track nothing.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HlsEdgeWiringTest {
    private val savedLevel = BoxLog.minLevel
    private val savedOut = HlsEdgeProbe.out
    private val lines = mutableListOf<String>()
    private val builder = MediaSourceBuilder { DefaultLoadErrorHandlingPolicy() }
    private val bytes = ByteArray(188)
    private val hlsUrl = "http://panel.example:8080/live/u/p/1.m3u8"
    private val archive = ArchiveRequest(
        url = "http://panel.example:8080/timeshift/u/p/60/2026-10-13:20-00/1.ts", headers = emptyMap(),
        mode = CatchupMode.entries.first(), variant = "xc-path", requestStart = Instant.parse("2026-10-13T20:00:00Z"),
        requestEnd = null, nativeSeekRange = null,
    )

    @Before fun setUp() {
        BoxLog.minLevel = BoxLog.DEBUG
        HlsEdgeProbe.resetForTest()
        HlsEdgeProbe.out = { lines += it }
    }

    @After fun tearDown() {
        BoxLog.minLevel = savedLevel
        HlsEdgeProbe.out = savedOut
        HlsEdgeProbe.resetForTest()
    }

    private fun ctx(tuneId: Long, url: String, format: StreamFormat) =
        EngineTestSupport.context(tuneId = tuneId, resolved = EngineTestSupport.resolved(url, format))

    @Test
    fun nonLiveHlsPathsBuildNoWrapper() {
        val before = HlsEdgeDataSourceFactory.factories.get()
        val t = EngineTestSupport.FakeTransport(bytes)
        builder.build(ctx(1, "http://panel.example/live/u/p/1.ts", StreamFormat.TS), StreamFormat.TS, TsCompat.DEFAULT, t, null)
        builder.build(ctx(2, archive.url, StreamFormat.TS), StreamFormat.TS, TsCompat.DEFAULT, t, archive)
        builder.build(ctx(3, hlsUrl, StreamFormat.HLS), StreamFormat.HLS, TsCompat.DEFAULT, t, archive)
        builder.build(ctx(4, "https://cdn.example/clip/master.m3u8?t=1", StreamFormat.HLS), StreamFormat.HLS, TsCompat.DEFAULT, t, null, clip = ClipMedia(null))
        builder.build(ctx(5, "https://cdn.example/clip/goal.mp4?t=1", StreamFormat.PROGRESSIVE_OTHER), StreamFormat.PROGRESSIVE_OTHER, TsCompat.DEFAULT, t, null, clip = ClipMedia(null))
        builder.build(ctx(6, "http://panel.example/movie/u/p/1.mp4", StreamFormat.PROGRESSIVE_OTHER), StreamFormat.PROGRESSIVE_OTHER, TsCompat.DEFAULT, t, null)
        val ring = FakeTsRing()
        builder.build(
            ctx(7, "http://panel.example/live/u/p/1.ts", StreamFormat.TS), StreamFormat.TS, TsCompat.DEFAULT, t, null,
            RingOpen(ring, ring.generation, 0, EngineTestSupport.FakeTelemetry()),
        )
        assertEquals(before, HlsEdgeDataSourceFactory.factories.get())
        assertEquals(0, HlsEdgeProbe.trackedCount())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun liveHlsIsWrappedOnlyAtTheDebugLevel() {
        val before = HlsEdgeDataSourceFactory.factories.get()
        BoxLog.minLevel = BoxLog.INFO
        val release = builder.build(ctx(10, hlsUrl, StreamFormat.HLS), StreamFormat.HLS, TsCompat.DEFAULT, EngineTestSupport.FakeTransport(bytes), null)
        assertTrue(release.source is HlsMediaSource)
        assertEquals(before, HlsEdgeDataSourceFactory.factories.get())
        assertFalse(HlsEdgeProbe.isTracked(10))

        BoxLog.minLevel = BoxLog.DEBUG
        val debug = builder.build(ctx(11, hlsUrl, StreamFormat.HLS), StreamFormat.HLS, TsCompat.DEFAULT, EngineTestSupport.FakeTransport(bytes), null)
        assertTrue(debug.source is HlsMediaSource)
        assertEquals(before + 1, HlsEdgeDataSourceFactory.factories.get())
        assertTrue(HlsEdgeProbe.isTracked(11))
        // Same MediaItem as without the probe: the real URI and the live band.
        assertEquals(release.source.mediaItem.localConfiguration!!.uri, debug.source.mediaItem.localConfiguration!!.uri)
        assertEquals(release.source.mediaItem.liveConfiguration, debug.source.mediaItem.liveConfiguration)
        assertTrue(lines.single(), lines.single().startsWith("start t=11 origin=panel.example:8080 chan=#"))
    }
}
