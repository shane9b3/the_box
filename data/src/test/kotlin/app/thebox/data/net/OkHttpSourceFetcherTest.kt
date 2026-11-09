package app.thebox.data.net

import app.thebox.core.common.AuthReason
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.io.Compression
import app.thebox.data.contracts.FetchRequest
import app.thebox.data.contracts.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** §5.3 SourceFetcher: conditional GET / 304, `.part` + rename, maxBytes, error mapping (plain JUnit + MockWebServer). */
class OkHttpSourceFetcherTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val fetcher = OkHttpSourceFetcher(HttpModule.downloadClient(HttpModule.baseClient()), Dispatchers.IO)

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun dest(): File = File(tmp.root, "1.src")
    private fun ok(r: Outcome<FetchResult>): FetchResult = (r as Outcome.Ok).value

    @Test
    fun cancellationDuringAStalledBodyReturnsPromptly() = runBlocking {
        // Code review M1 S-6: 1 KB, then the server stalls for 60 s; the read must not hold the worker.
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(64 * 1024)).throttleBody(1024, 60, TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.IO) { fetcher.fetch(FetchRequest(server.url("/big.xml").toString()), dest()) }
        delay(500)
        val t0 = System.nanoTime()
        withTimeout(5_000) { job.cancel(); job.join() }
        assertTrue(job.isCancelled)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 3_000_000_000L)
        assertFalse(File(dest().path + ".part").exists())
        assertFalse(dest().exists())
    }

    @Test
    fun downloadsToPartThenRenamesAndReportsValidators() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body("#EXTM3U\n")
                .addHeader("ETag", "\"v1\"").addHeader("Last-Modified", "Tue, 13 Oct 2026 10:00:00 GMT")
                .addHeader("Content-Type", "audio/x-mpegurl; charset=windows-1251").build(),
        )
        val r = ok(fetcher.fetch(FetchRequest(server.url("/list.m3u").toString(), headers = mapOf("User-Agent" to "UA/1")), dest()))
        assertFalse(r.notModified)
        assertEquals(8L, r.bytes)
        assertEquals("\"v1\"", r.etag)
        assertEquals("Tue, 13 Oct 2026 10:00:00 GMT", r.lastModified)
        assertEquals("audio/x-mpegurl", r.contentType)
        assertEquals("windows-1251", r.charset)
        assertEquals("#EXTM3U\n", dest().readText())
        assertFalse(File(dest().path + ".part").exists())
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("UA/1", req.headers["User-Agent"])
        assertNull(req.headers["If-None-Match"])
    }

    @Test
    fun defaultUserAgentWhenNoneGiven() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("x").build())
        fetcher.fetch(FetchRequest(server.url("/a").toString()), dest())
        assertEquals(HttpModule.DEFAULT_USER_AGENT, server.takeRequest(1, TimeUnit.SECONDS)!!.headers["User-Agent"])
    }

    @Test
    fun conditionalGet304LeavesTheFileAlone() = runBlocking {
        dest().writeText("old")
        server.enqueue(MockResponse.Builder().code(304).build())
        val r = ok(fetcher.fetch(FetchRequest(server.url("/a").toString(), etag = "\"v1\"", lastModified = "Mon"), dest()))
        assertTrue(r.notModified)
        assertEquals("\"v1\"", r.etag)
        assertEquals("old", dest().readText())
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("\"v1\"", req.headers["If-None-Match"])
        assertEquals("Mon", req.headers["If-Modified-Since"])
    }

    @Test
    fun maxBytesAbortsAndDeletesThePart() = runBlocking {
        dest().writeText("previous good copy")
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(10_000)).build())
        val r = fetcher.fetch(FetchRequest(server.url("/big").toString(), maxBytes = 1_000), dest())
        assertTrue((r as Outcome.Err).error is SourceError.Unsupported)
        assertFalse(File(dest().path + ".part").exists())
        assertEquals("previous good copy", dest().readText())
    }

    // ---- performance review H2: gzip bodies are stored compressed; readers decide by magic bytes

    private fun gzip(b: Buffer): Buffer = Buffer().also { out -> GzipSink(out).buffer().use { it.writeAll(b) } }
    private fun gzip(text: String): Buffer = gzip(Buffer().writeUtf8(text))
    private fun stored(): String = Compression.decompress(dest().source().buffer()).use { it.readUtf8() }
    private fun storedKind(): Compression.Kind = dest().source().buffer().use { Compression.sniff(it) }

    @Test
    fun acceptEncodingGzipIsSentUnlessTheCallerSetsOne() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("x").build())
        fetcher.fetch(FetchRequest(server.url("/a").toString()), dest())
        assertEquals("gzip", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Accept-Encoding"])
        server.enqueue(MockResponse.Builder().code(200).body("x").build())
        fetcher.fetch(FetchRequest(server.url("/a").toString(), headers = mapOf("accept-encoding" to "identity")), dest())
        assertEquals("identity", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Accept-Encoding"])
    }

    @Test
    fun gzipContentEncodingIsStoredCompressedAndReadsBack() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(gzip("#EXTM3U gz\n")).addHeader("Content-Encoding", "gzip").build())
        val r = ok(fetcher.fetch(FetchRequest(server.url("/g").toString()), dest()))
        assertEquals(Compression.Kind.GZIP, storedKind())
        assertEquals(dest().length(), r.bytes)
        assertEquals("#EXTM3U gz\n", stored())
    }

    @Test
    fun aGzFileSentWithContentEncodingGzipIsTwoLayersAndReadsBack() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(gzip(gzip("<tv></tv>\n"))).addHeader("Content-Encoding", "gzip").build())
        ok(fetcher.fetch(FetchRequest(server.url("/epg.xml.gz").toString()), dest()))
        assertEquals("<tv></tv>\n", stored())
    }

    @Test
    fun aGzFileWithoutContentEncodingAndAPlainBodyBothReadBack() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(gzip("<tv/>\n")).build())
        ok(fetcher.fetch(FetchRequest(server.url("/epg.xml.gz").toString()), dest()))
        assertEquals("<tv/>\n", stored())
        // A server that ignores Accept-Encoding.
        server.enqueue(MockResponse.Builder().code(200).body("#EXTM3U plain\n").build())
        ok(fetcher.fetch(FetchRequest(server.url("/list.m3u").toString()), dest()))
        assertEquals(Compression.Kind.NONE, storedKind())
        assertEquals("#EXTM3U plain\n", stored())
    }

    @Test
    fun streamedSizeCapWithoutContentLength() = runBlocking {
        // A chunked body has no length, so only the streamed byte count can enforce the cap (it counts stored bytes).
        server.enqueue(MockResponse.Builder().code(200).chunkedBody("z".repeat(50_000), 1_000).build())
        val r = fetcher.fetch(FetchRequest(server.url("/g").toString(), maxBytes = 10_000), dest())
        assertTrue((r as Outcome.Err).error is SourceError.Unsupported)
        assertFalse(dest().exists())
        assertFalse(File(dest().path + ".part").exists())
    }

    @Test
    fun httpErrorsMapToSourceErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(Outcome.Err(SourceError.Http(404)), fetcher.fetch(FetchRequest(server.url("/a").toString()), dest()))
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(Outcome.Err(SourceError.Auth(AuthReason.BAD_CREDENTIALS)), fetcher.fetch(FetchRequest(server.url("/a").toString()), dest()))
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(Outcome.Err(SourceError.Http(503)), fetcher.fetch(FetchRequest(server.url("/a").toString()), dest()))
        assertFalse(dest().exists())
    }

    @Test
    fun connectFailureIsANetworkError() = runBlocking {
        val url = server.url("/a").toString()
        server.close()
        val e = (fetcher.fetch(FetchRequest(url), dest()) as Outcome.Err).error
        assertEquals(NetworkKind.CONNECT, (e as SourceError.Network).kind)
        assertTrue(NetErrors.isTransient(e))
        assertFalse(NetErrors.isTransient(SourceError.Http(404)))
        assertTrue(NetErrors.isTransient(SourceError.Http(429)))
    }

    @Test
    fun badUrlIsUnsupported() = runBlocking {
        assertTrue((fetcher.fetch(FetchRequest("not a url"), dest()) as Outcome.Err).error is SourceError.Unsupported)
    }
}
