package app.thebox.data.xtream

import app.thebox.core.common.AuthReason
import app.thebox.core.common.BoxLog
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.XtreamCatalogDecoderImpl
import app.thebox.data.contracts.XtreamAction
import app.thebox.data.contracts.XtreamConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class XtreamClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val sleeps = Collections.synchronizedList(ArrayList<Long>())
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val client = XtreamClient(http, XtreamCatalogDecoderImpl(), Dispatchers.IO, { 1_234_000L }, { sleeps += it })

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/xtream/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun base(s: MockWebServer = server) = s.url("/").toString().trimEnd('/')

    private fun conn(vararg bases: String = arrayOf(base()), preferred: Int = 0, id: Long = 1, ua: String? = null) =
        XtreamConnection(id, bases.toList(), "us@r", "p&ss+w", ua, mapOf("X-Extra" to "1"), preferred)

    private fun ok(body: String, type: String = "text/html") =
        MockResponse.Builder().code(200).setHeader("Content-Type", type).body(body).build()

    private fun status(code: Int, retryAfter: String? = null) =
        MockResponse.Builder().code(code).apply { if (retryAfter != null) setHeader("Retry-After", retryAfter) }.body("").build()

    private fun deadBase(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    private fun <T> Outcome<T>.value(): T = (this as? Outcome.Ok)?.value ?: throw AssertionError("expected Ok, got $this")
    private fun Outcome<*>.error(): SourceError = (this as? Outcome.Err)?.error ?: throw AssertionError("expected Err, got $this")

    @Test fun loginWithWrongContentTypeAndRequestShape(): Unit = runBlocking {
        server.enqueue(ok(fixture("login_ok.json")))
        val login = client.login(conn()).value()
        assertEquals(0, login.baseIndexUsed)
        assertEquals(1_234_000L, login.account.measuredAtDeviceMs)
        assertEquals("Europe/Berlin", login.account.server.timezone)
        val req = server.takeRequest()
        assertEquals("/player_api.php?username=us%40r&password=p%26ss%2Bw", req.target)
        assertEquals(XtreamClient.DEFAULT_USER_AGENT, req.headers["User-Agent"])
        assertEquals("1", req.headers["X-Extra"])
    }

    @Test fun playlistUserAgentWins(): Unit = runBlocking {
        server.enqueue(ok(fixture("login_ok.json")))
        client.login(conn(ua = "VLC/3.0.20")).value()
        assertEquals("VLC/3.0.20", server.takeRequest().headers["User-Agent"])
    }

    @Test fun rotatesOnConnectFailure(): Unit = runBlocking {
        server.enqueue(ok(fixture("login_ok.json")))
        val login = client.login(conn(deadBase(), base())).value()
        assertEquals(1, login.baseIndexUsed)
    }

    @Test fun rotatesOn5xxAnd52xAndWrapsFromThePreferredIndex(): Unit = runBlocking {
        MockWebServer().use { second ->
            second.start()
            server.enqueue(ok(fixture("login_ok.json")))
            second.enqueue(status(503))
            // preferred = 1 → second first (503), then wraps to index 0
            val login = client.login(conn(base(), base(second), preferred = 1)).value()
            assertEquals(0, login.baseIndexUsed)
            assertEquals(1, second.requestCount)

            server.enqueue(status(522))
            second.enqueue(ok(fixture("login_ok.json")))
            assertEquals(1, client.login(conn(base(), base(second))).value().baseIndexUsed)
        }
    }

    @Test fun allBasesFail(): Unit = runBlocking {
        val e = client.login(conn(deadBase(), deadBase())).error()
        assertEquals(NetworkKind.CONNECT, (e as SourceError.Network).kind)
        server.enqueue(status(500))
        assertEquals(SourceError.Http(500), client.login(conn(base())).error())
    }

    @Test fun backsOffOn429HonouringRetryAfter(): Unit = runBlocking {
        server.enqueue(status(429, retryAfter = "5"))
        server.enqueue(status(429, retryAfter = "120"))
        server.enqueue(status(429))
        server.enqueue(ok(fixture("login_ok.json")))
        client.login(conn()).value()
        assertEquals(listOf(5_000L, 30_000L, 8_000L), sleeps.toList())
    }

    @Test fun gives429UpAfterThreeRetries(): Unit = runBlocking {
        repeat(4) { server.enqueue(status(429)) }
        assertEquals(SourceError.Http(429), client.login(conn()).error())
        assertEquals(listOf(2_000L, 4_000L, 8_000L), sleeps.toList())
        assertEquals(4, server.requestCount)
    }

    @Test fun authMapping(): Unit = runBlocking {
        fun expect(expected: SourceError, response: MockResponse) {
            server.enqueue(response)
            assertEquals(expected, runBlocking { client.login(conn()).error() })
        }
        val bad = SourceError.Auth(AuthReason.BAD_CREDENTIALS)
        expect(bad, status(401))
        expect(bad, status(513))
        expect(SourceError.Http(403), status(403))
        expect(SourceError.Http(404), status(404))
        expect(bad, ok(fixture("login_auth0.json"), "application/json"))
        expect(bad, ok("[]"))
        expect(bad, ok(""))
        expect(SourceError.Auth(AuthReason.UNKNOWN), ok(fixture("login_html.html")))
        expect(SourceError.Auth(AuthReason.EXPIRED), ok(fixture("login_expired.json")))
        expect(SourceError.Auth(AuthReason.BANNED), ok(fixture("login_banned.json")))
        // auth failures never rotate
        assertEquals(10, server.requestCount)
    }

    @Test fun downloadStreamsToFile(): Unit = runBlocking {
        val body = fixture("live_streams.json")
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/html; charset=utf-8").setHeader("ETag", "\"v1\"").body(body).build())
        val dest = tmp.newFolder().resolve("live.json")
        val r = client.download(conn(), XtreamAction.LIVE_STREAMS, mapOf("category_id" to "1"), dest).value()
        assertEquals(body.toByteArray().size.toLong(), r.bytes)
        assertEquals(dest.length(), r.bytes)
        assertEquals("\"v1\"", r.etag)
        assertEquals("text/html", r.contentType)
        assertEquals("utf-8", r.charset?.lowercase())
        assertFalse(java.io.File(dest.path + ".part").exists())
        assertEquals("/player_api.php?username=us%40r&password=p%26ss%2Bw&action=get_live_streams&category_id=1", server.takeRequest().target)
        // The file decodes (sniffed, Content-Type ignored).
        val n = dest.inputStream().use { XtreamCatalogDecoderImpl().liveChannels(it, emptyList()).count() }
        assertEquals(19, n)
    }

    @Test fun downloadRejectsHtmlEmptyAndAuthBodies(): Unit = runBlocking {
        val dir = tmp.newFolder()
        val dest = dir.resolve("x.json")
        server.enqueue(ok("<html><body>expired</body></html>"))
        assertEquals(SourceError.NotASource(NotASourceKind.HTML_PAGE), client.download(conn(), XtreamAction.LIVE_STREAMS, emptyMap(), dest).error())
        server.enqueue(ok(""))
        assertEquals(SourceError.NotASource(NotASourceKind.EMPTY), client.download(conn(), XtreamAction.LIVE_STREAMS, emptyMap(), dest).error())
        server.enqueue(ok("""{"user_info":{"auth":0}}"""))
        assertEquals(SourceError.Auth(AuthReason.BAD_CREDENTIALS), client.download(conn(), XtreamAction.LIVE_STREAMS, emptyMap(), dest).error())
        assertFalse(dest.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun seriesInfoFallsBackToSeriesParam(): Unit = runBlocking {
        server.enqueue(ok("[]"))
        server.enqueue(ok(fixture("series_info_map.json")))
        val dest = tmp.newFolder().resolve("s.json")
        client.download(conn(), XtreamAction.SERIES_INFO, mapOf("series_id" to "301"), dest).value()
        assertTrue(server.takeRequest().target.endsWith("&action=get_series_info&series_id=301"))
        assertTrue(server.takeRequest().target.endsWith("&action=get_series_info&series=301"))
        assertTrue(dest.readText().contains("\"episodes\""))
    }

    @Test fun semaphoreLimitsConcurrencyPerPlaylist(): Unit = runBlocking {
        val inFlight = AtomicInteger()
        val max = AtomicInteger()
        val body = fixture("login_ok.json")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = inFlight.incrementAndGet()
                max.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                Thread.sleep(300)
                inFlight.decrementAndGet()
                return ok(body)
            }
        }
        (1..6).map { async(Dispatchers.IO) { client.login(conn()) } }.awaitAll().forEach { it.value() }
        assertEquals(3, max.get())
        // Another playlist has its own permits.
        max.set(0)
        (1..4).map { i -> async(Dispatchers.IO) { client.login(conn(id = 100L + i)) } }.awaitAll()
        assertEquals(4, max.get())
    }

    @Test fun largeDownloadStreams(): Unit = runBlocking {
        val size = 100L * 1024 * 1024
        server.enqueue(
            MockResponse.Builder().code(200).body(object : MockResponseBody {
                override val contentLength: Long get() = size
                override fun writeTo(sink: BufferedSink) {
                    val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
                    chunk[0] = '['.code.toByte()
                    var left = size
                    var first = true
                    while (left > 0) {
                        val n = minOf(left, chunk.size.toLong()).toInt()
                        if (!first) chunk[0] = ' '.code.toByte()
                        first = false
                        if (left == n.toLong()) chunk[n - 1] = ']'.code.toByte()
                        sink.write(chunk, 0, n)
                        left -= n
                    }
                }
            }).build(),
        )
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val dest = tmp.newFolder().resolve("big.json")
        val r = client.download(conn(), XtreamAction.VOD_STREAMS, emptyMap(), dest).value()
        val after = rt.totalMemory() - rt.freeMemory()
        assertEquals(size, r.bytes)
        assertEquals(size, dest.length())
        assertTrue("heap grew by ${(after - before) / 1_000_000} MB", after - before < 64L * 1024 * 1024)
    }

    @Test fun cancellationCancelsTheCallAndCleansUp(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body("[" + "{\"stream_id\":1},".repeat(20_000) + "{}]")
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS).build(),
        )
        val dest = tmp.newFolder().resolve("c.json")
        val job = launch(Dispatchers.IO) { client.download(conn(), XtreamAction.LIVE_STREAMS, emptyMap(), dest) }
        delay(600)
        withTimeout(5_000) {
            job.cancel()
            job.join()
        }
        assertTrue(job.isCancelled)
        Thread.sleep(300)
        assertFalse(dest.exists())
        assertFalse(java.io.File(dest.path + ".part").exists())
    }

    @Test fun shortEpgAndSimpleDataTable(): Unit = runBlocking {
        server.enqueue(ok(fixture("short_epg_base64.json")))
        val l = client.shortEpg(conn(), 101, TimeBase(), 4).value()
        assertEquals("Morning News", l[0].title)
        assertTrue(server.takeRequest().target.endsWith("&action=get_short_epg&stream_id=101&limit=4"))
        server.enqueue(ok(fixture("simple_data_table.json")))
        val t = client.simpleDataTable(conn(), 101, TimeBase(serverZone = ZoneId.of("Europe/Berlin"))).value()
        assertEquals(1_791_919_800L, t[2].startUtcSec)
        server.enqueue(ok(""))
        assertTrue(client.shortEpg(conn(), 101, TimeBase(), 4).value().isEmpty())
    }

    @Test fun logsAreRedacted(): Unit = runBlocking {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val previous = BoxLog.sink
        val previousLevel = BoxLog.minLevel
        BoxLog.sink = BoxLog.Sink { _, _, m, _ -> lines += m }
        BoxLog.minLevel = BoxLog.DEBUG
        try {
            server.enqueue(status(503))
            server.enqueue(ok(fixture("login_ok.json")))
            val c = XtreamConnection(7, listOf(deadBase(), base(), base()), "secretuser", "secretpass", null)
            client.login(c).value()
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.none { it.contains("secretuser") || it.contains("secretpass") })
        } finally {
            BoxLog.sink = previous
            BoxLog.minLevel = previousLevel
        }
    }

    @Test fun errorMapping() {
        assertEquals(NetworkKind.DNS, XtreamHttpErrors.fromIo(java.net.UnknownHostException("x")).kind)
        assertEquals(NetworkKind.TIMEOUT, XtreamHttpErrors.fromIo(java.net.SocketTimeoutException()).kind)
        assertEquals(NetworkKind.CONNECT, XtreamHttpErrors.fromIo(java.net.ConnectException()).kind)
        assertEquals(NetworkKind.TLS, XtreamHttpErrors.fromIo(javax.net.ssl.SSLHandshakeException("x")).kind)
        assertEquals(NetworkKind.RESET, XtreamHttpErrors.fromIo(java.net.SocketException("Connection reset")).kind)
        assertEquals(NetworkKind.OTHER, XtreamHttpErrors.fromIo(java.io.IOException("x")).kind)
        assertTrue(XtreamHttpErrors.isRotatable(520))
        assertTrue(XtreamHttpErrors.isRotatable(524))
        assertFalse(XtreamHttpErrors.isRotatable(513))
        assertFalse(XtreamHttpErrors.isRotatable(404))
        assertEquals(2_000L, XtreamHttpErrors.retryDelayMs("soon", 0))
        assertEquals(0L, XtreamHttpErrors.retryDelayMs("0", 2))
    }
}
