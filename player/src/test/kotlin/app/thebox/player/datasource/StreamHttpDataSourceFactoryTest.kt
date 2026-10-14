package app.thebox.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.thebox.core.playback.ConnectionLimitException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** §5.3 WP-B `StreamHttpDataSourceFactory`: one UA, Range passes through, one lease per HLS session, placeholder URIs. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamHttpDataSourceFactoryTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun factory(ctxUrl: String, broker: FakeBroker = FakeBroker(), scope: StandaloneScope = StandaloneScope(), telemetry: SessionTelemetry = SessionTelemetry(REAL_CLOCK)) =
        StreamHttpDataSourceFactory(sessionCtx(ctxUrl, broker = broker), client, telemetry, scope, REAL_CLOCK)

    private fun readAll(ds: androidx.media3.datasource.DataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buf, 0, n)
        }
    }

    @Test
    fun resolvedHeadersAreSentOnceWithASingleUserAgent() {
        server.enqueue(MockResponse.Builder().code(200).body("#EXTM3U\n").build())
        val ds = factory(server.url("/hls/1.m3u8").toString()).createDataSource()
        ds.open(DataSpec.Builder().setUri(server.url("/hls/1.m3u8").toString()).build())
        readAll(ds)
        ds.close()
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(listOf("TestUA/1.0"), req.headers.values("User-Agent"))
        assertNull(req.headers["Range"])
    }

    @Test
    fun rangePassesThroughForArchiveSeeks() {
        val file = ByteArray(10_000) { it.toByte() }
        server.enqueue(
            MockResponse.Builder().code(206).body(Buffer().write(file, 1_000, 500))
                .addHeader("Content-Range", "bytes 1000-1499/10000").build(),
        )
        val ds = factory(server.url("/timeshift/u/p/60/2026-10-13:20-00/1.ts").toString()).createDataSource()
        val spec = DataSpec.Builder().setUri(server.url("/timeshift/u/p/60/2026-10-13:20-00/1.ts").toString()).setPosition(1_000).setLength(500).build()
        assertEquals(500L, ds.open(spec))
        assertArrayEquals(file.copyOfRange(1_000, 1_500), readAll(ds))
        ds.close()
        assertEquals("bytes=1000-1499", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Range"])
    }

    @Test
    fun aSessionPlaceholderUriIsReplacedAndStaysHidden() {
        server.enqueue(MockResponse.Builder().code(200).body("archive").build())
        server.enqueue(MockResponse.Builder().code(404).body("gone").build())
        val f = factory(server.url("/timeshift/user/pass/1.ts").toString())
        val ds = f.createDataSource()
        ds.open(DataSpec.Builder().setUri("thebox-session://7").build())
        assertEquals(Uri.parse("thebox-session://7"), ds.uri)
        assertTrue(ds.responseHeaders.isEmpty())
        assertEquals("archive", String(readAll(ds)))
        ds.close()
        assertEquals("/timeshift/user/pass/1.ts", server.takeRequest(1, TimeUnit.SECONDS)!!.target)
        // A failure carries the placeholder, no URL, headers or body.
        val second = f.createDataSource()
        try {
            second.open(DataSpec.Builder().setUri("thebox-session://7").build())
            fail("404 expected")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(404, e.responseCode)
            assertEquals("thebox-session://7", e.dataSpec.uri.toString())
            assertTrue(e.headerFields.isEmpty())
            assertEquals(0, e.responseBody.size)
        }
        second.close()
    }

    @Test
    fun oneLeaseCoversTheWholeHlsSession() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(200).body("x$it").build()) }
        val broker = FakeBroker()
        val f = factory(server.url("/hls/1.m3u8").toString(), broker = broker)
        for (path in listOf("/hls/1.m3u8", "/hls/seg1.ts", "/hls/seg2.ts")) {
            val ds = f.createDataSource()
            ds.open(DataSpec.Builder().setUri(server.url(path).toString()).build())
            readAll(ds)
            ds.close()
        }
        assertEquals(1, broker.leases.size)
        assertEquals(3, broker.lease.attachCount.get())
        assertEquals(3, broker.lease.detachCount.get())
        assertEquals(0, broker.lease.releaseCount.get()) // kept until endSession
    }

    @Test
    fun preemptionMakesEveryLaterOpenPreemptedAndEndReleases() {
        server.enqueue(MockResponse.Builder().code(200).body("m").build())
        val broker = FakeBroker()
        val scope = StandaloneScope()
        val f = factory(server.url("/hls/1.m3u8").toString(), broker = broker, scope = scope)
        val first = f.createDataSource()
        first.open(DataSpec.Builder().setUri(server.url("/hls/1.m3u8").toString()).build())
        readAll(first)
        first.close()
        broker.lease.preempt()
        val second = f.createDataSource()
        try {
            second.open(DataSpec.Builder().setUri(server.url("/hls/seg.ts").toString()).build())
            fail("pre-empted")
        } catch (e: LiveStreamException) {
            assertEquals(LiveFailureKind.PREEMPTED, e.kind)
        }
        second.close()
        assertEquals(1, server.requestCount)
        assertEquals(1, broker.leases.size) // a pre-empted session never takes a new lease
        scope.end()
        assertEquals(1, broker.lease.releaseCount.get())
        val third = f.createDataSource()
        try {
            third.open(DataSpec.Builder().setUri(server.url("/hls/seg.ts").toString()).build())
            fail("ended")
        } catch (e: LiveStreamException) {
            assertEquals(LiveFailureKind.PREEMPTED, e.kind)
        }
    }

    @Test
    fun aRefusedLeaseIsLocalLimit() {
        val broker = FakeBroker().apply { refuse = ConnectionLimitException(2, 2) }
        val ds = factory(server.url("/hls/1.m3u8").toString(), broker = broker).createDataSource()
        try {
            ds.open(DataSpec.Builder().setUri(server.url("/hls/1.m3u8").toString()).build())
            fail("limit")
        } catch (e: LiveStreamException) {
            assertEquals(LiveFailureKind.LOCAL_LIMIT, e.kind)
            assertTrue(e.suppressed.single() is ConnectionLimitException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnfinishedBodyIsCancelledBeforeCloseAndAFinishedOneKeepsTheConnection() {
        val telemetry = SessionTelemetry(REAL_CLOCK)
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(200_000))).build())
        server.enqueue(MockResponse.Builder().code(200).body("small").build())
        server.enqueue(MockResponse.Builder().code(200).body("next").build())
        val f = factory(server.url("/seg.ts").toString(), telemetry = telemetry)
        val big = f.createDataSource()
        big.open(DataSpec.Builder().setUri(server.url("/big.ts").toString()).build())
        big.read(ByteArray(1_000), 0, 1_000)
        big.close() // cancelled: the connection is not reused
        val small = f.createDataSource()
        small.open(DataSpec.Builder().setUri(server.url("/small.ts").toString()).build())
        readAll(small)
        small.close()
        val next = f.createDataSource()
        next.open(DataSpec.Builder().setUri(server.url("/next.ts").toString()).build())
        readAll(next)
        next.close()
        val r1 = server.takeRequest(1, TimeUnit.SECONDS)!!
        val r2 = server.takeRequest(1, TimeUnit.SECONDS)!!
        val r3 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertFalse("the cancelled call's connection is not reused", r1.connectionIndex == r2.connectionIndex)
        assertEquals("a fully read body keeps its keep-alive connection", r2.connectionIndex, r3.connectionIndex)
        assertTrue(telemetry.bytesTotal >= 1_000 + 5 + 4)
    }
}
