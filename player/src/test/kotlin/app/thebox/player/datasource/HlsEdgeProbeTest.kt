package app.thebox.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.thebox.core.common.BoxLog
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * (HLS edge hand-over, stage 0) [HlsEdgeProbe] and [HlsEdgeDataSource]: transparent pass-through, redaction of every
 * line (fed real redirects, credentials and tokens through OkHttp), volume over a simulated 2 h run, boundary summary.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HlsEdgeProbeTest {
    private val lines = CopyOnWriteArrayList<String>()
    private val now = AtomicLong(1_000_000)
    private val savedLevel = BoxLog.minLevel
    private val savedOut = HlsEdgeProbe.out
    private val savedClock = HlsEdgeProbe.clock

    @Before fun setUp() {
        BoxLog.minLevel = BoxLog.DEBUG
        HlsEdgeProbe.resetForTest()
        HlsEdgeProbe.out = { lines += it }
    }

    @After fun tearDown() {
        BoxLog.minLevel = savedLevel
        HlsEdgeProbe.out = savedOut
        HlsEdgeProbe.clock = savedClock
        HlsEdgeProbe.resetForTest()
    }

    private fun fakeClock() { HlsEdgeProbe.clock = { now.get() } }

    // ------------------------------------------------------------ transparency

    /** Scripted upstream: [body] in uneven reads, then end; or the given exceptions at open / after [failAfter] bytes. */
    private class Scripted(
        private val body: ByteArray,
        private val openError: IOException? = null,
        private val readError: IOException? = null,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : BaseDataSource(true) {
        private var pos = 0
        private var reads = 0
        var closed = 0
        override fun open(dataSpec: DataSpec): Long {
            openError?.let { throw it }
            pos = 0
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= failAfter) readError?.let { throw it }
            if (pos >= body.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, body.size - pos, 1 + (reads++ * 7) % 13)
            System.arraycopy(body, pos, buffer, offset, n)
            pos += n
            return n
        }
        override fun getUri(): Uri = Uri.parse("http://edge.example/x/final.m3u8")
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Type" to listOf("application/vnd.apple.mpegurl"))
        override fun close() { closed++ }
    }

    private fun drain(ds: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64)
        while (true) {
            val n = ds.read(buf, 5, 40)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buf, 5, n)
        }
    }

    private fun edgeSession(tuneId: Long = 7, url: String = "http://panel.example:8080/live/u/p/1.m3u8"): HlsEdgeSession =
        HlsEdgeProbe.begin(sessionCtx(url, tuneId = tuneId))!!

    @Test
    fun wrapperIsTransparentForBytesUriHeadersAndExceptions() {
        val body = ("#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n#EXT-X-MEDIA-SEQUENCE:5\r\n" + (5..9).joinToString("") { "#EXTINF:6,\r\ns$it.ts\r\n" })
            .toByteArray()
        val spec = DataSpec(Uri.parse("http://panel.example/live/u/p/1.m3u8"))
        val session = edgeSession()
        for (type in listOf(C.DATA_TYPE_MANIFEST, C.DATA_TYPE_MEDIA, C.DATA_TYPE_DRM)) {
            val plain = Scripted(body)
            plain.open(spec)
            val expected = drain(plain)
            val inner = Scripted(body)
            val wrapped = HlsEdgeDataSource(inner, type, session)
            assertEquals(C.LENGTH_UNSET.toLong(), wrapped.open(spec))
            assertArrayEquals("type $type", expected, drain(wrapped))
            assertEquals(inner.uri, wrapped.uri)
            assertEquals(inner.responseHeaders, wrapped.responseHeaders)
            wrapped.close()
            assertEquals(1, inner.closed)

            val openErr = HttpDataSource.HttpDataSourceException(spec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            val w2 = HlsEdgeDataSource(Scripted(body, openError = openErr), type, session)
            try { w2.open(spec); fail() } catch (e: IOException) { assertSame(openErr, e) }
            w2.close()

            val readErr = IOException("cut")
            val w3 = HlsEdgeDataSource(Scripted(body, readError = readErr, failAfter = 20), type, session)
            w3.open(spec)
            try { drain(w3); fail() } catch (e: IOException) { assertSame(readErr, e) }
            w3.close()
        }
        // The open failure and the body failure were logged with their phase.
        assertTrue(lines.any { it.startsWith("req ") && it.contains("err=HttpDataSourceException@local") })
        assertTrue(lines.any { it.startsWith("req ") && it.contains("err=IOException@body") })
    }

    // ------------------------------------------------------------ redaction, end to end through OkHttp

    @Test
    fun noLineCarriesAPathQueryCredentialTokenOrIp() {
        val server = MockWebServer()
        server.start()
        try {
            val port = server.port
            val origin = "http://localhost:$port/live/user1/pass1/77.m3u8?token=tok1"
            val edge = "http://127.0.0.1:$port/hls/secretpathtok"
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "$edge/index.m3u8?t=qtok").build())
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-KEY:METHOD=AES-128,URI=\"key?k=keytok\"\n" +
                        "#EXT-X-PROGRAM-DATE-TIME:2026-11-25T12:02:30.000Z\n#EXTINF:6,\nseg_100.ts?sig=abcsig\n#EXTINF:6,\n$edge/seg_101.ts\n",
                ).build(),
            )
            server.enqueue(MockResponse.Builder().code(200).body("x".repeat(3000)).build())
            server.enqueue(MockResponse.Builder().code(404).addHeader("Location", "http://other.example/secret2/a?b=tok3").body("nope").build())

            val ctx = sessionCtx(origin, tuneId = 31)
            val session = HlsEdgeProbe.begin(ctx)!!
            val http = StreamHttpDataSourceFactory(ctx, OkHttpClient(), SessionTelemetry(REAL_CLOCK), StandaloneScope(), REAL_CLOCK)
            val factory = HlsEdgeDataSourceFactory(http, session)

            val pl = factory.createDataSource(C.DATA_TYPE_MANIFEST)
            pl.open(DataSpec(Uri.parse(origin)))
            val plBody = String(drain(pl))
            assertTrue("the playlist reaches Media3 unchanged", plBody.contains("seg_100.ts?sig=abcsig"))
            assertEquals("getUri is the final hop", "$edge/index.m3u8?t=qtok", pl.uri.toString())
            pl.close()

            val seg = factory.createDataSource(C.DATA_TYPE_MEDIA)
            seg.open(DataSpec(Uri.parse("$edge/seg_100.ts?sig=abcsig")))
            assertEquals(3000, drain(seg).size)
            seg.close()

            val missing = factory.createDataSource(C.DATA_TYPE_MEDIA)
            try { missing.open(DataSpec(Uri.parse("$edge/seg_101.ts"))); fail() } catch (e: HttpDataSource.InvalidResponseCodeException) { assertEquals(404, e.responseCode) }
            missing.close()

            val closedPort = java.net.ServerSocket(0).use { it.localPort }
            val dead = factory.createDataSource(C.DATA_TYPE_MEDIA)
            try { dead.open(DataSpec(Uri.parse("http://127.0.0.1:$closedPort/hls/secretpathtok/seg_102.ts?sig=zz"))); fail() } catch (_: IOException) { }
            dead.close()

            HlsEdgeProbe.onPlayerFailure(31, "Network", HlsEdgeProbe.PlayerFacts(11_800, 14_200, 3, true))
            HlsEdgeProbe.onDecision(31, "ReResolve", "Network", null)
            HlsEdgeProbe.end(31)
        } finally {
            server.close()
        }

        assertTrue(lines.size >= 6)
        val first = lines.first { it.startsWith("req ") && it.contains(" hops=1") }
        assertTrue(first, first.contains("rq=localhost:"))
        assertTrue(first, first.contains("fin=ip#"))
        assertTrue(first, first.contains(" k=media") && first.contains(" st=200") && first.contains("conn=N"))
        assertTrue(lines.any { it.startsWith("pl ") && it.contains("msn=100") && it.contains("key=AES-128") && it.contains("pdt0=2026-11-25T12:02:30.000Z") })
        assertTrue(lines.any { it.startsWith("req ") && it.contains("st=404") && it.contains("err=InvalidResponseCodeException@status") })
        assertTrue(lines.any { it.startsWith("req ") && it.contains("@connect") })
        assertTrue(lines.any { it.startsWith("fail t=31 sig=Network state=READY pwr=1 buf=11800 off=14200") })
        val forbidden = listOf(
            "/", "?", "user1", "pass1", "tok1", "qtok", "secretpathtok", "seg_10", "77.m3u8", "index.m3u8", "abcsig", "keytok",
            "secret2", "tok3", "other.example", "127.0.0.1", "Location", "live", "hls",
        )
        val ipv4 = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
        for (line in lines) {
            for (f in forbidden) assertFalse("'$f' in: $line", line.contains(f))
            assertNull("IP in: $line", ipv4.find(line))
        }
    }

    @Test
    fun hostsAreHostOnlyAndIpLiteralsAreHashed() {
        val v4 = okhttp3.HttpUrl.Builder().scheme("http").host("10.1.2.3").port(8000).addPathSegment("a").build()
        val v6 = okhttp3.HttpUrl.Builder().scheme("http").host("2001:db8::1").addPathSegment("a").build()
        val name = okhttp3.HttpUrl.Builder().scheme("https").host("Edge-7.CDN.example").addPathSegment("p").query("t=1").build()
        assertTrue(HlsEdgeProbe.hostLabel(v4).matches(Regex("""ip#[0-9a-f]{6}:8000""")))
        assertTrue(HlsEdgeProbe.hostLabel(v6).matches(Regex("""ip#[0-9a-f]{6}""")))
        assertEquals("edge-7.cdn.example", HlsEdgeProbe.hostLabel(name))
        assertEquals(HlsEdgeProbe.hash("x"), HlsEdgeProbe.hash("x"))
        assertFalse(HlsEdgeProbe.hash("x") == HlsEdgeProbe.hash("y"))
    }

    // ------------------------------------------------------------ volume and boundary

    private fun playlist(msn: Long, names: (Long) -> String = { "seg$it.ts" }, n: Int = 5, pdtBaseMs: Long? = null): HlsPlaylistScanner.Summary {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$msn\n")
        for (i in 0 until n) {
            if (pdtBaseMs != null) sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(java.time.Instant.ofEpochMilli(pdtBaseMs + (msn + i) * 6_000)).append('\n')
            sb.append("#EXTINF:6,\n").append(names(msn + i)).append('\n')
        }
        val sc = HlsPlaylistScanner("edge.example", HlsEdgeProbe::hash)
        val b = sb.toString().toByteArray()
        sc.feed(b, 0, b.size)
        return sc.finish()
    }

    private val plSpec by lazy { DataSpec(Uri.parse("http://edge.example/hls/tok/index.m3u8")) }
    private val segSpec by lazy { DataSpec(Uri.parse("http://edge.example/hls/tok/seg.ts")) }

    private fun plLoad(s: HlsEdgeSession, summary: HlsPlaylistScanner.Summary) {
        val t = s.newTrace(C.DATA_TYPE_MANIFEST, plSpec)
        s.onDone(t, plSpec, 600, true, summary)
    }

    private fun segLoad(s: HlsEdgeSession) {
        val t = s.newTrace(C.DATA_TYPE_MEDIA, segSpec)
        s.onDone(t, segSpec, 1_500_000, true, null)
    }

    @Test
    fun aTwoHourRunWithFourBoundariesStaysUnderTwoThousandLines() {
        fakeClock()
        var tune = 100L
        var s = edgeSession(tune)
        var msn = 1_000L
        val end = now.get() + 2 * 3_600_000L
        val boundaries = (1..4).map { now.get() + it * 1_500_000L }.toMutableList()
        while (now.get() < end) {
            plLoad(s, playlist(msn))
            segLoad(s)
            // Media3 reloads an unchanged playlist at TD/2.
            now.addAndGet(3_000)
            plLoad(s, playlist(msn))
            now.addAndGet(3_000)
            msn++
            if (boundaries.isNotEmpty() && now.get() >= boundaries.first()) {
                boundaries.removeAt(0)
                HlsEdgeProbe.onPlayerFailure(tune, "Network", HlsEdgeProbe.PlayerFacts(12_000, 15_000, 3, true))
                HlsEdgeProbe.onDecision(tune, "ReResolve", "Network", null)
                HlsEdgeProbe.end(tune)
                now.addAndGet(1_000)
                tune++
                s = edgeSession(tune)
                plLoad(s, playlist(msn))
                HlsEdgeProbe.onFirstFrame(tune)
            }
        }
        assertEquals(4, lines.count { it.startsWith("boundary ") })
        assertTrue("lines=${lines.size}", lines.size < 2_000)
        assertTrue(lines.count { it.startsWith("agg ") } >= 100)
        // Routine requests before each failure come back from the flight recorder.
        assertTrue(lines.any { it.startsWith("fr ago=") && it.contains(" req ") })
    }

    @Test
    fun boundaryCompareOldEdgesLastPlaylistWithTheNewEdgesFirst() {
        fakeClock()
        val pdt = java.time.Instant.parse("2026-11-25T12:00:00Z").toEpochMilli()
        val old = edgeSession(1)
        plLoad(old, playlist(100, pdtBaseMs = pdt))
        now.addAndGet(20_000)
        HlsEdgeProbe.onPlayerFailure(1, "Network", null)
        HlsEdgeProbe.end(1)
        now.addAndGet(900)
        val new = edgeSession(2)
        // Aligned: same names at the same MSNs, PDT 0.5 s later on the new edge.
        plLoad(new, playlist(102, pdtBaseMs = pdt + 500))
        now.addAndGet(1_300)
        HlsEdgeProbe.onFirstFrame(2)
        val b = lines.single { it.startsWith("boundary ") }
        assertTrue(b, b.contains("from=1 to=2 sameChan=y sig=Network fails=1"))
        assertTrue(b, b.contains("old=msn100..104(n5)") && b.contains("new=msn102..106(n5)"))
        assertTrue(b, b.contains("oldLastInNew=msn104 msnShift=+0 pdtShift=+500"))
        assertTrue(b, b.contains("atOldLastMsn=name:same,pdt:+500"))
        assertTrue(b, b.contains("newFirstInOld=msn102"))
        assertTrue(b, b.contains("failToFrame=2200"))
        assertTrue(b, b.contains("failToNewPl=900"))

        // Misaligned: an independent segmenter (other names) on the next edge.
        lines.clear()
        HlsEdgeProbe.onPlayerFailure(2, "Network", null)
        HlsEdgeProbe.end(2)
        val third = edgeSession(3)
        plLoad(third, playlist(5, names = { "other$it.ts" }))
        HlsEdgeProbe.onFirstFrame(3)
        val m = lines.single { it.startsWith("boundary ") }
        assertTrue(m, m.contains("oldLastInNew=absent") && m.contains("atOldLastMsn=absent") && m.contains("newFirstInOld=absent"))
    }

    @Test
    fun aZapToAnotherChannelAbandonsThePendingBoundary() {
        fakeClock()
        val a = edgeSession(1, "http://panel.example/live/u/p/1.m3u8")
        plLoad(a, playlist(10))
        HlsEdgeProbe.onPlayerFailure(1, "Network", null)
        HlsEdgeProbe.end(1)
        edgeSession(2, "http://panel.example/live/u/p/2.m3u8")
        HlsEdgeProbe.onFirstFrame(2)
        assertNotNull(lines.singleOrNull { it.startsWith("boundary from=1 to=- result=abandoned") })
        assertEquals(1, lines.count { it.startsWith("boundary ") })
    }

    @Test
    fun linesReachBoxLogAtDebugUnderTheHlsEdgeTag() {
        val savedSink = BoxLog.sink
        val seen = CopyOnWriteArrayList<Triple<Int, String, String>>()
        try {
            BoxLog.sink = BoxLog.Sink { level, tag, message, _ -> seen += Triple(level, tag, message) }
            HlsEdgeProbe.out = savedOut
            HlsEdgeProbe.begin(sessionCtx("http://panel.example/live/u/p/1.m3u8", tuneId = 8))
            val hit = seen.single()
            assertEquals(BoxLog.DEBUG, hit.first)
            assertEquals("HlsEdge", hit.second)
            assertTrue(hit.third, hit.third.startsWith("start t=8 origin=panel.example chan=#"))
        } finally {
            BoxLog.sink = savedSink
        }
    }

    @Test
    fun disabledInReleaseLevel() {
        BoxLog.minLevel = BoxLog.INFO
        assertNull(HlsEdgeProbe.begin(sessionCtx("http://panel.example/live/u/p/1.m3u8", tuneId = 9)))
        assertFalse(HlsEdgeProbe.isTracked(9))
        HlsEdgeProbe.onPlayerFailure(9, "Network", null)
        HlsEdgeProbe.onFirstFrame(9)
        assertTrue(lines.isEmpty())
    }
}
