package app.thebox.player.datasource

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.errors.LoadRetryRulesImpl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * (design §8.1) The captured hand-over through Media3's real `DefaultHlsPlaylistTracker` (1.11.1) over the live HLS data
 * sources: the tracker pins the post-redirect URL, reloads it every target duration (1 s here, Robolectric's clock),
 * and must see the renewed playlist as an ordinary reload of the same URL.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HlsRenewalTrackerTest {
    private lateinit var fake: FakeOriginEdge
    private val savedOut = HlsPlaylistRenewal.out
    private val lines = CopyOnWriteArrayList<String>()

    private val snapshots = CopyOnWriteArrayList<HlsMediaPlaylist>()
    private val loadUris = CopyOnWriteArrayList<String>()
    private val errors = CopyOnWriteArrayList<Pair<IOException, Boolean>>()
    private lateinit var tracker: DefaultHlsPlaylistTracker

    private val broker = FakeBroker()
    private val events = EventRecorder()

    @Before fun setUp() {
        fake = FakeOriginEdge(td = 1)
        fake.autoAdvance = true
        HlsPlaylistRenewal.out = { lines += it }
    }

    @After fun tearDown() {
        if (::tracker.isInitialized) tracker.stop()
        HlsPlaylistRenewal.out = savedOut
        fake.close()
    }

    private fun startTracker(): TransportSession {
        val ctx = sessionCtx(fake.originUrl, broker = broker, events = events)
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val session = TransportSession(ctx, telemetry)
        val renewal = session.renewal { HlsPlaylistRenewal(ctx, HlsRenewalBudget(), OnlineCheck.ALWAYS, REAL_CLOCK, telemetry) }
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val factory = RenewingHlsDataSourceFactory(ctx, client, telemetry, session, REAL_CLOCK, renewal)
        tracker = DefaultHlsPlaylistTracker(
            factory,
            BoxLoadErrorHandlingPolicy(LoadOrigin.HLS_MANIFEST, LoadRetryRulesImpl()),
            DefaultHlsPlaylistParserFactory(),
            /* cmcdConfiguration = */ null,
            /* downloadExecutorSupplier = */ null,
        )
        val dispatcher = MediaSourceEventListener.EventDispatcher()
        dispatcher.addEventListener(
            Handler(Looper.getMainLooper()),
            object : MediaSourceEventListener {
                override fun onLoadCompleted(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    loadUris += loadEventInfo.uri.toString()
                    loadUris += loadEventInfo.dataSpec.uri.toString()
                }

                override fun onLoadError(
                    windowIndex: Int,
                    mediaPeriodId: MediaSource.MediaPeriodId?,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean,
                ) {
                    loadUris += loadEventInfo.uri.toString()
                    errors += error to wasCanceled
                }
            },
        )
        tracker.start(Uri.parse(fake.originUrl), dispatcher, { snapshots += it }, DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build())
        return session
    }

    private fun pumpUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what (snapshots=${snapshots.size}, errors=$errors)" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            Thread.sleep(10)
        }
    }

    private fun segmentUrls(p: HlsMediaPlaylist): List<String> = p.segments.map { UriUtil.resolve(p.baseUri, it.url) }

    @Test
    fun theTrackerKeepsItsUrlAndReadsTheRenewedPlaylistAsAReload() {
        val session = startTracker()
        pumpUntil("two reloads") { snapshots.size >= 3 }
        val logical = fake.playlistUrl("tok1")
        val primary = tracker.multivariantPlaylist!!.mediaPlaylistUrls.single()
        assertEquals(logical, primary.toString())

        fake.expireAll()
        pumpUntil("the renewed playlist") { snapshots.last().let { s -> segmentUrls(s).any { it.contains("/hls/tok2/") } } }
        val renewedAt = snapshots.size
        pumpUntil("two more reloads") { snapshots.size >= renewedAt + 2 }

        // No error anywhere, the tracker's identity and base unchanged, the sequence continuous.
        assertTrue("$errors", errors.isEmpty())
        tracker.maybeThrowPrimaryPlaylistRefreshError()
        assertEquals(primary, tracker.multivariantPlaylist!!.mediaPlaylistUrls.single())
        val last = tracker.getPlaylistSnapshot(primary, false)!!
        assertEquals(logical, last.baseUri)
        for (i in 1 until snapshots.size) assertTrue(snapshots[i].mediaSequence >= snapshots[i - 1].mediaSequence)
        // Segments now resolve to the new playlist's references, on the same edge.
        assertTrue(segmentUrls(last).all { it.startsWith(fake.edge.url("/hls/tok2/").toString()) })
        // Media3 never saw O's answer or the new URL: every load it logged is O (the first load) or its pinned URL.
        assertTrue(loadUris.toString(), loadUris.all { it == fake.originUrl || it == logical })
        // Exactly one renewal; reloads now go to the new URL; one lease throughout.
        assertEquals(2, fake.originHits.get())
        assertTrue(fake.edgePaths().takeLast(2).all { it == "/hls/tok2/index.m3u8" })
        assertEquals(1, lines.count { it.startsWith("renewed") })
        assertTrue(events.events.none { it.first == PlaybackEventType.STALL })
        assertEquals(1, broker.leases.size)
        assertEquals(1, session.telemetry.playlistRenewals)
    }

    @Test
    fun aRestartedSequenceIsATypedFatalErrorAndAnImmediateSignal() {
        startTracker()
        pumpUntil("two reloads") { snapshots.size >= 3 }
        fake.renewalMsn = { 0L }
        fake.expireAll()
        pumpUntil("the fallback") { errors.isNotEmpty() }
        val (error, canceled) = errors.single()
        assertTrue("$error", error is HlsRenewalMisaligned)
        assertTrue("fatal: no Media3 retry", canceled)
        assertEquals(listOf("${HlsPlaylistRenewal.FALLBACK_CODE} misaligned:msn_back"), events.events.filter { it.first == PlaybackEventType.STALL }.map { it.second })
        // The renewed URL never reached Media3.
        assertFalse(error.toString().contains("tok2"))
        assertTrue(loadUris.none { it.contains("tok2") })
        assertEquals(2, fake.originHits.get())
    }
}
