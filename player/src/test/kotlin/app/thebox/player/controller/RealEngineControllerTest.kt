package app.thebox.player.controller

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.broker.ZapDebouncerImpl
import app.thebox.core.playback.buffer.BufferProfileFsmImpl
import app.thebox.core.playback.diag.PlaybackEventLogImpl
import app.thebox.core.playback.errors.ErrorLadderImpl
import app.thebox.core.playback.errors.ReResolveLimiterImpl
import app.thebox.player.api.InMemoryPlaybackSettings
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.player.broker.StreamBrokerRegistryImpl
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.FakeDeviceState
import app.thebox.player.controller.fakes.FakeMemory
import app.thebox.player.controller.fakes.FakeProvider
import app.thebox.player.controller.fakes.FakeResolver
import app.thebox.player.controller.fakes.Journal
import app.thebox.player.controller.fakes.stream
import app.thebox.player.datasource.ByteWatchdog
import app.thebox.player.datasource.PlaybackHttpClients
import app.thebox.player.datasource.TransportFactoryImpl
import app.thebox.player.engine.DeviceCapabilities
import app.thebox.player.engine.EngineTestSupport
import app.thebox.player.engine.MediaSourceBuilder
import app.thebox.player.engine.PlayerFactory
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.PlayerFactoryApi
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.TransportFactory
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Design R-4 (integration, 2026-10-13): the controller driving the **real** engine (`PlayerFactory`, `MediaSourceBuilder`,
 * `DefaultEngineEnvironment`, `ErrorLadderImpl`, `ReResolveLimiterImpl`, `BufferProfileFsmImpl`, `StreamBrokerRegistryImpl`)
 * with in-memory bytes or the real WP-B transport against `mockwebserver3`. `Clock.DEFAULT` throughout (day-0 case B): the
 * controller's own coroutine delays share the Robolectric clock with the player, which an auto-advancing `FakeClock`
 * does not (it never advanced the loader here). Day-0 constraints (§6.9): no READY assertion at the first frame, no
 * audio milestone, no frame counts.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealEngineControllerTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val journal = Journal()
    private val resolver = FakeResolver(journal)
    private val provider = FakeProvider()
    private val settings = InMemoryPlaybackSettings()
    private val mono = MonoClock { SystemClock.elapsedRealtime() }
    private val events = PlaybackEventLogImpl(mono)
    private var bundle: PlayerBundle? = null
    private var server: MockWebServer? = null

    @After fun tearDown() {
        bundle?.player?.release()
        server?.close()
    }

    /** The real factory with the S14 hooks; captures the bundle and gives it a surface (first frame needs one). */
    private inner class CapturingFactory(private val delegate: PlayerFactory) : PlayerFactoryApi {
        override fun create(looper: Looper, softwareAudioFallback: Boolean): PlayerBundle =
            delegate.create(looper, softwareAudioFallback).also {
                it.player.setVideoSurface(Surface(SurfaceTexture(1)))
                bundle = it
            }
    }

    private fun controller(transport: TransportFactory, exoClock: Clock): PlaybackControllerImpl {
        val factory = CapturingFactory(EngineTestSupport.factory(context, exoClock))
        return PlaybackControllerImpl(
            context, provider, FakeMemory(), settings, resolver, StreamBrokerRegistryImpl(mono), transport, factory, MediaSourceBuilder(),
            events, mono, ErrorLadderImpl({ settings.current.streamSwitchTimeoutMs }), ReResolveLimiterImpl(), ZapDebouncerImpl(),
            BufferProfileFsmImpl(), DefaultEngineEnvironment(context, DeviceCapabilities(context), settings, mono),
            ZapImportGate({ SystemClock.elapsedRealtime() }, 200), MediaSessionFactory.None, FakeDeviceState(),
        )
    }

    private val player: ExoPlayer get() = checkNotNull(bundle).player

    /** Real time passes for the sockets while the paused main looper (and so the controller's delays) advances slowly. */
    private fun runUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in $timeoutMs ms; events=${events.events().map { "${it.type}:${it.code}" }}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
    }

    /** In-memory transport: per tune, either the TS fixture or a source that blocks until its session ends. */
    private class MemoryTransport(private val bytes: ByteArray, private val stallFor: Set<String>) : TransportFactory {
        val ended = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        private val blocking = HashMap<Long, MutableList<BlockingSource>>()

        override fun liveTs(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry> {
            opened += ctx.tuneId
            val stall = ctx.resolved.identity?.stableKey?.value in stallFor
            val factory = DataSource.Factory {
                if (stall) BlockingSource().also { synchronized(blocking) { blocking.getOrPut(ctx.tuneId) { mutableListOf() } += it } } else ByteArrayDataSource(bytes)
            }
            return factory to EngineTestSupport.FakeTelemetry()
        }

        override fun http(ctx: StreamSessionContext) = liveTs(ctx)

        override fun endSession(tuneId: Long) {
            ended += tuneId
            synchronized(blocking) { blocking.remove(tuneId) }?.forEach { it.close() }
        }
    }

    /** A live body that never sends a byte (a panel that accepted the connection and stalls). */
    private class BlockingSource : BaseDataSource(true) {
        private val closed = CountDownLatch(1)
        private var uri: Uri? = null
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            closed.await(30, TimeUnit.SECONDS)
            return C.RESULT_END_OF_INPUT
        }
        override fun getUri(): Uri? = uri
        override fun close() {
            closed.countDown()
            if (uri != null) transferEnded()
            uri = null
        }
    }

    @Test
    fun liveTsTuneReachesFirstMediaThroughTheRealEngine() {
        val transport = MemoryTransport(EngineTestSupport.fixture("h264_aac_2s.ts"), stallFor = emptySet())
        val c = controller(transport, Clock.DEFAULT)
        c.tune(TuneTarget.Channel(CH1))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("the controller created the player", bundle)

        runUntil { events.events().any { it.type == PlaybackEventType.FIRST_FRAME } }

        val zap = events.zaps().single()
        assertEquals("ok", zap.outcome)
        assertNotNull(zap.firstFrameMs)
        assertTrue(events.events().any { it.type == PlaybackEventType.FIRST_FRAME && it.code == "video" })
        assertTrue("no ladder decision on a clean tune", events.events().none { it.type == PlaybackEventType.DECISION })
        assertEquals("thebox-session://${zap.tuneId}", player.currentMediaItem?.localConfiguration?.uri.toString())
        assertTrue(c.state.value !is PlaybackState.Error)
    }

    @Test
    fun zapDuringConnectingEndsTheOldSessionFirstAndPlaysTheNewChannel() {
        val transport = MemoryTransport(EngineTestSupport.fixture("h264_aac_2s.ts"), stallFor = setOf(CH1.stableKey.value))
        val c = controller(transport, Clock.DEFAULT)
        c.tune(TuneTarget.Channel(CH1))
        shadowOf(Looper.getMainLooper()).idle()
        val first = transport.opened.single()
        assertTrue(c.state.value is PlaybackState.Connecting)

        c.tune(TuneTarget.Channel(CH2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(first), transport.ended.take(1))
        val second = transport.opened.last()
        assertTrue(second > first)

        runUntil { events.events().any { it.type == PlaybackEventType.FIRST_FRAME } }
        val zaps = events.zaps()
        assertEquals(listOf("superseded", "ok"), zaps.map { it.outcome })
        assertEquals(CH2.stableKey.value, zaps.last().stableKey)
        assertEquals("thebox-session://$second", player.currentMediaItem?.localConfiguration?.uri.toString())
    }

    @Test
    fun forbiddenOnOpenRetriesThenReResolvesThenFailsForbidden() {
        val s = MockWebServer().also { server = it }
        repeat(5) { s.enqueue(MockResponse.Builder().code(403).body("forbidden").build()) }
        s.start()
        val url = s.url("/live/u/p/1.ts").toString()
        resolver.next = { _ ->
            val r = stream(CH1)
            ResolveOutcome.Ok(
                ResolvedStream(
                    r.identity, r.brokerKey, url, r.headers, r.formatHint, r.xtream, r.output, r.baseIndex, r.baseCount, "${s.hostName}:${s.port}",
                    r.ignoreTlsErrors, r.liveUrlTemplate, r.headerTemplates,
                ),
            )
        }
        // No UA fallback: §4.3 L2 RetrySame → L3 ReResolve(refreshAccount) → L6 Fail(FORBIDDEN) (account ACTIVE, 0/1).
        settings.update { it.copy(autoUserAgentFallback = false) }
        val transport = TransportFactoryImpl(PlaybackHttpClients(OkHttpClient()), ByteWatchdog(mono), mono)
        val c = controller(transport, Clock.DEFAULT)

        c.tune(TuneTarget.Channel(CH1))
        runUntil { c.state.value is PlaybackState.Error }

        val error = (c.state.value as PlaybackState.Error).error
        assertEquals(PlaybackErrorCode.FORBIDDEN, error.code)
        assertEquals(403, error.detail.httpStatus)
        val decisions = events.events().filter { it.type == PlaybackEventType.DECISION }.map { it.code }
        assertEquals(listOf("RetrySame", "ReResolve", "Fail"), decisions)
        assertEquals("one open per attempt", 3, s.requestCount)
        assertEquals("the re-resolve asked for a fresh account", 1, provider.refreshes)
        assertEquals(2, resolver.calls.size)
        assertEquals("error:play.forbidden", events.zaps().single().outcome)
    }
}
