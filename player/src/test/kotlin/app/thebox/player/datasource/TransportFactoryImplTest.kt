package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TeeDataSource
import app.thebox.core.playback.NetworkFailureKind
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * `TransportFactoryImpl` sessions (shared telemetry, endSession), `PlaybackHttpClients` variants, and the
 * `LiveStreamException` mapping helpers.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransportFactoryImplTest {
    private lateinit var server: MockWebServer
    private val marker = Interceptor { it.proceed(it.request()) }
    private val base = OkHttpClient.Builder().addInterceptor(marker).build()
    private val clients = PlaybackHttpClients(base)
    private val executor = Executors.newCachedThreadPool()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { executor.shutdownNow(); server.close() }

    @Test
    fun liveFactoryBuildsATeeOverTheLiveSourceAndSharesTheSessionTelemetry() {
        val transport = TransportFactoryImpl(clients, ByteWatchdog(REAL_CLOCK), REAL_CLOCK)
        val ctx = sessionCtx(server.url("/live/1.ts").toString(), tuneId = 3)
        val (live, liveTelemetry) = transport.liveTs(ctx)
        val (_, httpTelemetry) = transport.http(ctx)
        assertTrue(live.createDataSource() is TeeDataSource)
        assertSame(liveTelemetry, httpTelemetry)
        assertEquals(1, transport.openSessions())
        transport.endSession(3)
        transport.endSession(3) // idempotent
        assertEquals(0, transport.openSessions())
        assertNotSame(liveTelemetry, transport.liveTs(ctx).second) // a later tune with the same id starts fresh
    }

    @Test
    fun endSessionDuringABlockedLiveReadClosesTheSocketPromptly() {
        val stalled = LiveBody(FIXTURE.copyOf(3 * PKT), stall = true)
        val flood = FloodBody(FIXTURE.copyOf(16 * PKT))
        server.enqueue(liveResponse(stalled))
        server.enqueue(liveResponse(flood))
        try {
            val broker = FakeBroker()
            val transport = TransportFactoryImpl(clients, ByteWatchdog(REAL_CLOCK), REAL_CLOCK)
            // 1. A stalled read (the Media3 loader parked in a socket read).
            val ds = transport.liveTs(sessionCtx(server.url("/live/1.ts").toString(), broker = broker, tuneId = 1)).first.createDataSource()
            ds.open(DataSpec.Builder().setUri("thebox-session://1").build())
            readExactly(ds, 3 * PKT)
            val blocked = executor.submit<Int> { ds.read(ByteArray(PKT), 0, PKT) }
            Thread.sleep(300)
            val t0 = System.nanoTime()
            transport.endSession(1)
            val cause = try { blocked.get(2, TimeUnit.SECONDS); null } catch (e: ExecutionException) { e.cause }
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("endSession → blocked read returned in $ms ms")
            assertTrue("returned in $ms ms", ms < 1_000)
            assertTrue("got $cause", cause is LiveStreamException && cause.kind == LiveFailureKind.PREEMPTED)
            assertEquals(1, broker.lease.releaseCount.get())
            ds.close()

            // 2. A flowing stream: the server sees the disconnect right after endSession.
            val ds2 = transport.liveTs(sessionCtx(server.url("/live/2.ts").toString(), broker = broker, tuneId = 2)).first.createDataSource()
            ds2.open(DataSpec.Builder().setUri("thebox-session://2").build())
            readExactly(ds2, 50_000)
            val t1 = System.nanoTime()
            transport.endSession(2)
            assertTrue("server saw the disconnect", flood.disconnected.await(2, TimeUnit.SECONDS))
            println("endSession → server saw the disconnect after ${(System.nanoTime() - t1) / 1_000_000} ms")
            ds2.close()
            assertEquals(1, broker.leases[1].releaseCount.get())
        } finally {
            stalled.release()
        }
    }

    @Test
    fun clientVariantsKeepTheBaseInterceptorsAndScopeTheRelaxedTlsToItsPlaylists() {
        val strict = clients.live(ignoreTlsErrors = false)
        val relaxed = clients.live(ignoreTlsErrors = true)
        assertSame(strict, clients.live(false))
        assertNotSame(strict, relaxed)
        assertNotSame(strict, clients.segments(false))
        for (c in listOf(strict, relaxed, clients.segments(false), clients.segments(true))) {
            assertEquals(PlaybackHttpClients.LIVE_CONNECT_TIMEOUT_MS.toInt(), c.connectTimeoutMillis)
            assertEquals(PlaybackHttpClients.LIVE_READ_TIMEOUT_MS.toInt(), c.readTimeoutMillis)
            assertEquals(0, c.callTimeoutMillis)
            assertTrue(c.followRedirects)
            assertTrue(c.followSslRedirects)
            assertTrue(c.interceptors.contains(marker))
            assertSame(base.connectionPool, c.connectionPool)
            assertTrue(c.dispatcher.maxRequestsPerHost >= 16)
        }
        assertSame(strict.dispatcher, relaxed.dispatcher)
        assertNotSame(base.dispatcher, strict.dispatcher)
        // Only the relaxed clients accept any host name / certificate.
        assertTrue(relaxed.hostnameVerifier.verify("not-the-cert-host.example", null))
        assertFalse(strict.hostnameVerifier === relaxed.hostnameVerifier)
        assertNotSame(strict.sslSocketFactory, relaxed.sslSocketFactory)
        assertSame(base.hostnameVerifier, strict.hostnameVerifier)
    }

    @Test
    fun networkKindIsTakenFromTheRootCauseThroughMedia3AndOkHttpWrappers() {
        val spec = DataSpec.Builder().setUri("thebox-session://1").build()
        fun wrapped(root: IOException): IOException =
            HttpDataSource.HttpDataSourceException.createForIOException(IOException(ExecutionException(root)), spec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        assertEquals(NetworkFailureKind.DNS, networkKindOf(wrapped(UnknownHostException("h"))))
        assertEquals(NetworkFailureKind.CONNECT, networkKindOf(wrapped(ConnectException("refused"))))
        assertEquals(NetworkFailureKind.TIMEOUT, networkKindOf(wrapped(SocketTimeoutException("connect timed out"))))
        assertEquals(NetworkFailureKind.TLS, networkKindOf(wrapped(SSLHandshakeException("bad cert"))))
        assertEquals(NetworkFailureKind.RESET, networkKindOf(wrapped(SocketException("Connection reset"))))
        assertEquals(NetworkFailureKind.CLEARTEXT, networkKindOf(HttpDataSource.CleartextNotPermittedException(IOException("x"), spec)))
        // The outer Media3 code is 2001 for all of them (why the root walk is needed, S3).
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, (wrapped(UnknownHostException("h")) as DataSourceException).reason)
        assertTrue(rootCause(wrapped(UnknownHostException("h"))) is UnknownHostException)
        assertTrue(isInterruption(wrapped(InterruptedIOException("interrupted"))))
        assertFalse(isInterruption(wrapped(SocketTimeoutException("t"))))
    }

    @Test
    fun typedFailureCodesFollowTheDesignTable() {
        val spec = DataSpec.Builder().setUri("thebox-session://1").build()
        fun ex(kind: LiveFailureKind, phase: FailureInfo.Phase = FailureInfo.Phase.READ, net: NetworkFailureKind? = null) =
            LiveStreamException(kind, FailureInfo(null, null, 0, 0, phase, net, null, 0), spec, null)
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, ex(LiveFailureKind.HTTP_STATUS).reason)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, ex(LiveFailureKind.NETWORK, net = NetworkFailureKind.DNS).reason)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, ex(LiveFailureKind.NETWORK, net = NetworkFailureKind.TIMEOUT).reason)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, ex(LiveFailureKind.RECONNECT_EXHAUSTED, net = NetworkFailureKind.TIMEOUT).reason)
        for (k in listOf(LiveFailureKind.HTML_PAGE, LiveFailureKind.TINY_BODY, LiveFailureKind.FORMAT_IS_HLS, LiveFailureKind.FORMAT_UNRECOGNIZED)) {
            assertEquals(k.name, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, ex(k, FailureInfo.Phase.SNIFF).reason)
        }
        for (k in listOf(LiveFailureKind.PCR_DISCONTINUITY, LiveFailureKind.LOCAL_LIMIT, LiveFailureKind.PREEMPTED)) {
            assertEquals(k.name, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, ex(k).reason)
        }
        assertEquals(HttpDataSource.HttpDataSourceException.TYPE_OPEN, ex(LiveFailureKind.HTML_PAGE, FailureInfo.Phase.SNIFF).type)
        assertEquals(HttpDataSource.HttpDataSourceException.TYPE_READ, ex(LiveFailureKind.RECONNECT_EXHAUSTED, FailureInfo.Phase.RECONNECT).type)
        assertEquals("live pcr_discontinuity", ex(LiveFailureKind.PCR_DISCONTINUITY).message)
    }
}
