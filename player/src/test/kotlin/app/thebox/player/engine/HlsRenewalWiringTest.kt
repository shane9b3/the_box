package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.model.CatchupMode
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StreamFormat
import app.thebox.player.datasource.ByteWatchdog
import app.thebox.player.datasource.HlsPlaylistRenewal
import app.thebox.player.datasource.PlaybackHttpClients
import app.thebox.player.datasource.TransportFactoryImpl
import app.thebox.player.internal.RingOpen
import app.thebox.player.internal.TsCompat
import app.thebox.player.timeshift.FakeTsRing
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * (HLS playlist renewal, design §6.8) Only a live HLS source gets a renewal router: TS (live, archive, ring), HLS
 * archive, HLS and MP4 clips and progressive build none, through the real transport factory.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HlsRenewalWiringTest {
    private val clock = MonoClock { System.nanoTime() / 1_000_000 }
    private val transport = TransportFactoryImpl(PlaybackHttpClients(OkHttpClient()), ByteWatchdog(clock), clock)
    private val builder = MediaSourceBuilder { DefaultLoadErrorHandlingPolicy() }
    private val hlsUrl = "http://panel.example:8080/live/u/p/1.m3u8"
    private val archive = ArchiveRequest(
        url = "http://panel.example:8080/timeshift/u/p/60/2026-10-13:20-00/1.ts", headers = emptyMap(),
        mode = CatchupMode.entries.first(), variant = "xc-path", requestStart = Instant.parse("2026-10-13T20:00:00Z"),
        requestEnd = null, nativeSeekRange = null,
    )

    private fun ctx(tuneId: Long, url: String, format: StreamFormat) =
        EngineTestSupport.context(tuneId = tuneId, resolved = EngineTestSupport.resolved(url, format))

    @Test
    fun onlyLiveHlsBuildsARouter() {
        val before = HlsPlaylistRenewal.created.get()
        builder.build(ctx(101, "http://panel.example/live/u/p/1.ts", StreamFormat.TS), StreamFormat.TS, TsCompat.DEFAULT, transport, null)
        builder.build(ctx(102, archive.url, StreamFormat.TS), StreamFormat.TS, TsCompat.DEFAULT, transport, archive)
        builder.build(ctx(103, hlsUrl, StreamFormat.HLS), StreamFormat.HLS, TsCompat.DEFAULT, transport, archive)
        builder.build(ctx(104, "https://cdn.example/clip/master.m3u8?t=1", StreamFormat.HLS), StreamFormat.HLS, TsCompat.DEFAULT, transport, null, clip = ClipMedia(null))
        builder.build(ctx(105, "https://cdn.example/clip/goal.mp4?t=1", StreamFormat.PROGRESSIVE_OTHER), StreamFormat.PROGRESSIVE_OTHER, TsCompat.DEFAULT, transport, null, clip = ClipMedia(null))
        builder.build(ctx(106, "http://panel.example/movie/u/p/1.mp4", StreamFormat.PROGRESSIVE_OTHER), StreamFormat.PROGRESSIVE_OTHER, TsCompat.DEFAULT, transport, null)
        val ring = FakeTsRing()
        builder.build(
            ctx(107, "http://panel.example/live/u/p/1.ts", StreamFormat.TS), StreamFormat.TS, TsCompat.DEFAULT, transport, null,
            RingOpen(ring, ring.generation, 0, EngineTestSupport.FakeTelemetry()),
        )
        assertEquals(before, HlsPlaylistRenewal.created.get())

        val live = builder.build(ctx(108, hlsUrl, StreamFormat.HLS), StreamFormat.HLS, TsCompat.DEFAULT, transport, null)
        assertTrue(live.source is HlsMediaSource)
        assertEquals(before + 1, HlsPlaylistRenewal.created.get())
        // The MediaItem is unchanged: the provider URL, as before (no new copy anywhere else).
        assertEquals(hlsUrl, live.source.mediaItem.localConfiguration!!.uri.toString())
        // One router per session, however many times the factory is asked.
        transport.liveHls(ctx(108, hlsUrl, StreamFormat.HLS))
        assertEquals(before + 1, HlsPlaylistRenewal.created.get())
        for (id in 101L..108L) transport.endSession(id)
    }
}
