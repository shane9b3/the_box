package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.ClipFormat
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.HubFixtures
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * docs/design/m6-sports-hub.md §6.2, §6.3, §10.2 `NhlClipResolverTest`: one read per process, 401 → one re-read → retry,
 * a second 401 → Refused, the hold after not-found and after a script failure, geo 403, 404, the metadata cache, `stream()`
 * uncached, https only, no `Origin` / `Referer`, and a log capture that finds neither the key nor a signed token. Every key
 * here is an obviously fake one.
 */
class NhlClipResolverTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var nowMono = 10_000L
    private val mono = MonoClock { nowMono }

    /** What the fake script serves (a fake key after an empty default), and the key the fake service accepts. */
    @Volatile private var scriptKey: String? = KEY_A
    @Volatile private var accepted: String = KEY_A
    @Volatile private var scriptStatus = 200
    @Volatile private var gzipScript = false
    @Volatile private var scriptRetryAfterS: Int? = null
    @Volatile private var scriptDisconnect = false
    /** Per video id: a scripted answer that overrides the default (a fixture body when the key is accepted). */
    private val answers = HashMap<String, (RecordedRequest) -> MockResponse>()

    private val logs = CopyOnWriteArrayList<String>()
    private var oldSink: BoxLog.Sink? = null

    @Before fun start() {
        oldSink = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { _, tag, message, error ->
            logs += "$tag $message ${error?.toString().orEmpty()} ${error?.cause?.toString().orEmpty()}"
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                if (path == NhlVideoEndpoints.FAKE_SCRIPT_PATH) return script()
                if (path.startsWith(NhlVideoEndpoints.FAKE_PLAYBACK_PATH)) {
                    val id = path.removePrefix(NhlVideoEndpoints.FAKE_PLAYBACK_PATH)
                    val accept = request.headers["Accept"].orEmpty()
                    answers[id]?.let { return it(request) }
                    if (accept != "application/json;pk=$accepted") return json("""[{"error_code":"INVALID_POLICY_KEY"}]""", 401)
                    return json(clip(id))
                }
                return json("{}", 404)
            }
        }
        server.start()
    }

    @After fun stop() {
        server.close()
        oldSink?.let { BoxLog.sink = it }
    }

    private fun origin() = server.url("/").toString().trimEnd('/')

    private fun endpoints(debug: Boolean = true) =
        NhlVideoEndpoints(origin() + NhlVideoEndpoints.FAKE_SCRIPT_PATH, origin() + NhlVideoEndpoints.FAKE_PLAYBACK_PATH, debugOverride = debug)

    private fun client() = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()

    private fun resolver(ep: NhlVideoEndpoints = endpoints()): Pair<NhlClipResolver, NhlVideoKeySource> {
        val keys = NhlVideoKeySource(client(), { ep }, mono)
        return NhlClipResolver(client(), keys, mono) to keys
    }

    private fun script(): MockResponse {
        if (scriptDisconnect) return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        if (scriptStatus != 200) {
            return MockResponse.Builder().code(scriptStatus).body("busy")
                .apply { scriptRetryAfterS?.let { addHeader("Retry-After", it.toString()) } }.build()
        }
        val text = "/*! fake player */!function(){var c={policyKey:\"\",accountId:\"0\"};" +
            (scriptKey?.let { "window.cfg={\"policyKey\" : \"$it\",x:1};" } ?: "") + "}();"
        if (!gzipScript) return MockResponse.Builder().code(200).addHeader("Content-Type", "application/javascript").body(text).build()
        val gz = Buffer()
        GzipSink(gz).buffer().use { it.writeUtf8(text) }
        return MockResponse.Builder().code(200).addHeader("Content-Type", "application/javascript").addHeader("Content-Encoding", "gzip").body(gz).build()
    }

    /** The recorded goal answer with fake signed queries (never real tokens). */
    private fun clip(id: String): String =
        SportsFixtures.text(HubFixtures.CLIP_GOAL).replace("?redacted=1", "?fastly_token=$TOKEN").replace("6408791393112", id)

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun ref(id: Long = 6408791393112) = ClipRef(id, ClipKind.GOAL, GameId(League.NHL, "2026020382"))

    private fun scriptReads() = requests.count { it.url.encodedPath == NhlVideoEndpoints.FAKE_SCRIPT_PATH }
    private fun lookups() = requests.count { it.url.encodedPath.startsWith(NhlVideoEndpoints.FAKE_PLAYBACK_PATH) }

    @Test fun theKeyIsReadOncePerProcessAndSentWithoutOriginOrReferer() = runBlocking {
        val (r, _) = resolver()
        val a = r.info(ref(1001))
        val b = r.info(ref(1002))
        val c = r.stream(ref(1003))
        assertTrue(a is ClipResult.Ok && b is ClipResult.Ok && c is ClipResult.Ok)
        assertEquals(1, scriptReads())
        for (q in requests) {
            assertNull(q.headers["Origin"])
            assertNull(q.headers["Referer"])
        }
        val lookup = requests.last { it.url.encodedPath.startsWith(NhlVideoEndpoints.FAKE_PLAYBACK_PATH) }
        assertEquals("application/json;pk=$KEY_A", lookup.headers["Accept"])
    }

    @Test fun parallelFirstLookupsShareOneRead() = runBlocking {
        val (r, _) = resolver()
        val results = (1..4).map { i -> async { r.info(ref(2000L + i)) } }.awaitAll()
        assertTrue(results.all { it is ClipResult.Ok })
        assertEquals(1, scriptReads())
    }

    @Test fun aGzipScriptIsScannedDecoded() = runBlocking {
        gzipScript = true
        val (r, _) = resolver()
        assertTrue(r.info(ref()) is ClipResult.Ok)
        assertEquals(1, scriptReads())
    }

    @Test fun aRotatedKeyIsReReadOnceAndTheLookupRetried() = runBlocking {
        val (r, _) = resolver()
        assertTrue(r.info(ref(1)) is ClipResult.Ok)
        accepted = KEY_B
        scriptKey = KEY_B
        val s = r.stream(ref(2))
        assertTrue(s is ClipResult.Ok)
        assertEquals(2, scriptReads())
        assertEquals(3, lookups())
        assertEquals("application/json;pk=$KEY_B", requests.last().headers["Accept"])
    }

    @Test fun aSecondRefusalIsRefusedAndHoldsForTenMinutes() = runBlocking {
        val (r, _) = resolver()
        accepted = "SOMETHING-ELSE-ENTIRELY-0000"
        assertEquals(ClipResult.Refused, r.info(ref(1)))
        assertEquals(2, scriptReads())
        assertEquals(2, lookups())
        // Inside the hold: no request at all.
        nowMono += SportsConstants.HUB_VIDEO_KEY_REREAD_MS - 1
        assertEquals(ClipResult.Refused, r.stream(ref(2)))
        assertEquals(2, scriptReads())
        assertEquals(2, lookups())
        // After it: the script is read again.
        nowMono += 1
        accepted = KEY_A
        assertTrue(r.stream(ref(2)) is ClipResult.Ok)
        assertEquals(3, scriptReads())
    }

    @Test fun a403WithoutGeoIsAKeyRefusalToo() = runBlocking {
        answers["7"] = { json("""[{"error_code":"ACCESS_DENIED","error_subcode":"DOMAIN_RESTRICTED"}]""", 403) }
        val (r, _) = resolver()
        assertEquals(ClipResult.Refused, r.info(ref(7)))
        assertEquals(2, scriptReads())
    }

    @Test fun geoIsGeoAndKeepsTheKey() = runBlocking {
        answers["8"] = { json("""[{"error_code":"ACCESS_DENIED","error_subcode":"CLIENT_GEO","message":"blocked"}]""", 403) }
        val (r, _) = resolver()
        assertEquals(ClipResult.Geo, r.stream(ref(8)))
        assertTrue(r.info(ref(9)) is ClipResult.Ok)
        assertEquals(1, scriptReads())
    }

    @Test fun notFoundAndServerErrors() = runBlocking {
        answers["404"] = { json("""[{"error_code":"VIDEO_NOT_FOUND"}]""", 404) }
        answers["503"] = { json("busy", 503) }
        answers["429"] = { json("slow down", 429) }
        answers["999"] = { json("not json at all") }
        answers["998"] = { json("""{"name":"x","sources":[{"src":"https://a.test/v.mpd","type":"application/dash+xml"}]}""") }
        answers["997"] = { json("""{"name":"${"x".repeat(70_000)}"}""") }
        val (r, _) = resolver()
        assertEquals(ClipResult.NotFound, r.info(ref(404)))
        assertEquals(ClipResult.Failed(FailKind.HTTP_5XX), r.info(ref(503)))
        assertEquals(ClipResult.Failed(FailKind.HTTP_429), r.stream(ref(429)))
        assertEquals(ClipResult.Failed(FailKind.BAD_BODY), r.info(ref(999)))
        assertEquals("nothing playable", ClipResult.Failed(FailKind.BAD_BODY), r.stream(ref(998)))
        assertTrue("the metadata of an unplayable video still maps", r.info(ref(998)) is ClipResult.Ok)
        assertEquals("over the 64 KB cap", ClipResult.Failed(FailKind.BAD_BODY), r.info(ref(997)))
        assertEquals(1, scriptReads())
    }

    /** (Sports Hub code review 2026-11-25, L2) A lookup redirect, even https → https to another host, is not followed. */
    @Test fun aLookupRedirectIsNeverFollowed() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            answers["301"] = { MockResponse.Builder().code(302).addHeader("Location", other.url("/elsewhere/301").toString()).build() }
            val (r, _) = resolver()
            assertEquals(ClipResult.Failed(FailKind.HTTP_OTHER), r.info(ref(301)))
            assertEquals("the other host never saw the key header", 0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test fun holdAfterAScriptWithoutAKey() = runBlocking {
        scriptKey = null
        val (r, _) = resolver()
        assertEquals(ClipResult.Refused, r.info(ref(1)))
        assertEquals(ClipResult.Refused, r.stream(ref(1)))
        assertEquals(1, scriptReads())
        assertEquals(0, lookups())
        nowMono += SportsConstants.HUB_VIDEO_KEY_REREAD_MS
        scriptKey = KEY_A
        assertTrue(r.info(ref(1)) is ClipResult.Ok)
        assertEquals(2, scriptReads())
    }

    /**
     * (Sports Hub code review 2026-11-25, M1) A transient script failure backs off from 30 s, doubling to the 1 min cap,
     * instead of holding video off for 10 minutes; the toast's "Try again in a minute" is then true.
     */
    @Test fun aTransientScriptFailureBacksOffShortly() = runBlocking {
        scriptStatus = 500
        val (r, _) = resolver()
        assertEquals(ClipResult.Failed(FailKind.HTTP_5XX), r.info(ref(1)))
        assertEquals(ClipResult.Failed(FailKind.HTTP_5XX), r.info(ref(1)))
        assertEquals("inside the backoff: no read", 1, scriptReads())
        nowMono += SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS
        assertEquals(ClipResult.Failed(FailKind.HTTP_5XX), r.info(ref(1)))
        assertEquals("30 s later it is read again", 2, scriptReads())
        nowMono += SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS
        assertEquals(ClipResult.Failed(FailKind.HTTP_5XX), r.info(ref(1)))
        assertEquals("the second wait doubled", 2, scriptReads())
        nowMono += SportsConstants.HUB_VIDEO_KEY_BACKOFF_MAX_MS - SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS
        r.info(ref(1))
        assertEquals(3, scriptReads())
        nowMono += SportsConstants.HUB_VIDEO_KEY_BACKOFF_MAX_MS
        r.info(ref(1))
        assertEquals("never more than a minute", 4, scriptReads())
        scriptStatus = 200
        nowMono += SportsConstants.HUB_VIDEO_KEY_BACKOFF_MAX_MS
        assertTrue(r.info(ref(1)) is ClipResult.Ok)
        assertEquals(5, scriptReads())
    }

    /** (M1) A script read that breaks off (offline after standby) is IO and backs off; it never turns video off for 10 min. */
    @Test fun aBrokenScriptReadBacksOffAsIo() = runBlocking {
        scriptDisconnect = true
        val (r, _) = resolver()
        val first = r.info(ref(1))
        assertTrue("$first", first is ClipResult.Failed && first.kind in setOf(FailKind.IO, FailKind.TIMEOUT))
        val reads = scriptReads()
        assertTrue(reads >= 1)
        assertEquals(first, r.stream(ref(1)))
        assertEquals("inside the backoff: no read", reads, scriptReads())
        scriptDisconnect = false
        nowMono += SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS
        assertTrue("30 s later the network is back and video works", r.stream(ref(1)) is ClipResult.Ok)
    }

    /** (M1) `Retry-After` is the backoff's floor; a key that is absent still holds for 10 minutes. */
    @Test fun retryAfterIsTheBackoffFloor() = runBlocking {
        scriptStatus = 429
        scriptRetryAfterS = 120
        val (r, _) = resolver()
        assertEquals(ClipResult.Failed(FailKind.HTTP_429), r.info(ref(1)))
        nowMono += SportsConstants.HUB_VIDEO_KEY_BACKOFF_MAX_MS
        r.info(ref(1))
        assertEquals("Retry-After 120 s outlasts the 1 min cap", 1, scriptReads())
        nowMono += 60_000
        scriptStatus = 200
        assertTrue(r.info(ref(1)) is ClipResult.Ok)
        assertEquals(2, scriptReads())
    }

    @Test fun infoIsCachedTenMinutesAndStreamIsNot() = runBlocking {
        val (r, _) = resolver()
        val first = r.info(ref(5)) as ClipResult.Ok
        val again = r.info(ref(5).copy(kind = ClipKind.RECAP)) as ClipResult.Ok
        assertEquals(1, lookups())
        assertEquals(first.value.title, again.value.title)
        assertEquals("the cached info answers with the asked ref", ClipKind.RECAP, again.value.ref.kind)
        nowMono += SportsConstants.HUB_CLIP_META_TTL_MS
        assertTrue(r.info(ref(5)) is ClipResult.Ok)
        assertEquals(2, lookups())
        r.stream(ref(5))
        r.stream(ref(5))
        assertEquals(4, lookups())
    }

    @Test fun theStreamIsTheHttpsHlsMasterWithCaptions() = runBlocking {
        val (r, _) = resolver()
        val (info, stream) = (r.stream(ref()) as ClipResult.Ok).value
        assertEquals("Vilardi strikes first", info.title)
        assertEquals(ClipFormat.HLS, stream.format)
        assertTrue(stream.url.startsWith("https://manifest.prod.boltdns.net/"))
        assertTrue(stream.captionsUrl!!.startsWith("https://"))
        assertFalse(stream.toString().contains(TOKEN))
    }

    @Test fun httpsOnlyWithoutTheDebugOverride() = runBlocking {
        val (r, _) = resolver(endpoints(debug = false))
        assertEquals(ClipResult.Refused, r.info(ref()))
        assertEquals(ClipResult.Refused, r.stream(ref()))
        assertEquals(0, requests.size)
        assertTrue(NhlVideoEndpoints.DEFAULT.allowed(NhlVideoEndpoints.PLAYER_SCRIPT))
        assertTrue(NhlVideoEndpoints.DEFAULT.allowed(NhlVideoEndpoints.PLAYBACK_BASE + "1"))
        assertFalse(NhlVideoEndpoints.DEFAULT.allowed("http://edge.api.brightcove.com/x"))
    }

    @Test fun theDebugOverrideRedirectsBothVideoHosts() {
        val ep = SportsEndpoints.videoFromOverride(" http://10.0.2.2:8080/ \n")!!
        assertEquals("http://10.0.2.2:8080/bc/player/index.min.js", ep.playerScript)
        assertEquals("http://10.0.2.2:8080/bc/playback/v1/accounts/6415718365001/videos/", ep.playbackBase)
        assertTrue(ep.debugOverride)
        assertNull(SportsEndpoints.videoFromOverride("ftp://x"))
        assertNull(SportsEndpoints.videoFromOverride(""))
        assertFalse(NhlVideoEndpoints.DEFAULT.debugOverride)
    }

    @Test fun aLogCaptureFindsNoKeyAndNoToken() = runBlocking {
        val (r, _) = resolver()
        r.info(ref(1))
        accepted = KEY_B
        scriptKey = KEY_B
        val s = (r.stream(ref(2)) as ClipResult.Ok).value.second
        answers["3"] = { json("""[{"error_code":"ACCESS_DENIED","error_subcode":"CLIENT_GEO"}]""", 403) }
        r.stream(ref(3))
        scriptKey = null
        accepted = "NOBODY-HAS-THIS-KEY-000000"
        r.stream(ref(4))
        // Something else logs the stream URL and a throwable carrying the key: both come out masked.
        BoxLog.w("Elsewhere", "opening ${s.url} token $TOKEN", IllegalStateException("bad pk=$KEY_B"))
        assertTrue(logs.any { it.contains("video key: read") })
        assertTrue(logs.any { it.contains("video key: not found") || it.contains("video key: refused") })
        for (line in logs) {
            assertFalse(line, line.contains(TOKEN))
            assertFalse(line, Redact.text(line).contains(KEY_A) || Redact.text(line).contains(KEY_B))
        }
        // Messages are redacted by BoxLog; throwable text by the export (Redact.text over the whole line).
        assertFalse(Redact.text("e: bad pk=$KEY_B").contains(KEY_B))
        assertFalse(Redact.text("x $TOKEN y").contains(TOKEN))
        assertTrue(logs.none { it.contains("video") && it.contains(KEY_A) })
    }

    private companion object {
        /** Obviously fake keys (the scan wants 20–400 characters of `[A-Za-z0-9_\-.]`). */
        const val KEY_A = "FAKE-KEY-FOR-TESTS-ONLY-AAAA"
        const val KEY_B = "FAKE-KEY-FOR-TESTS-ONLY-BBBB"
        const val TOKEN = "FAKETOKEN0123456789abcdef"
    }
}
