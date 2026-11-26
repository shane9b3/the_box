package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipFormat
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.EspnVideoMapper
import app.thebox.core.sports.hub.NflHubFixtures
import app.thebox.core.sports.hub.VideoEntry
import app.thebox.data.sports.EspnHubTestSupport.html
import app.thebox.data.sports.EspnHubTestSupport.json
import app.thebox.data.sports.EspnHubTestSupport.text
import app.thebox.data.sports.EspnHubTestSupport.wafChallenge
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * (NFL hub P2, docs/design/m6-nfl-hub.md §4.2, §4.3, §4.7, §8.2 `EspnClipResolverTest`; arch M2, M3, M5, M6) The ESPN
 * resolver against a scripted record host: `info` and a catalog hit make no request; a miss makes one lookup that fills
 * the catalog; `fresh` makes exactly one lookup (404, the same URL, a new URL, a failure); the WAF / 401 classification;
 * the offer window at `now + skew` (expired, embargoed, clock skew); records that cannot play here; no URL in a log line
 * beyond its host.
 */
class EspnClipResolverTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var answer: (RecordedRequest) -> MockResponse = { json(text(NflHubFixtures.RECORD_50578630)) }
    private val mono = MonoClock { 1_000L }
    /** 2026-11-25T00:00Z: clip 50578630 is offered (embargo 24th 02:30:57Z, expiry 26th 02:30:00Z). */
    private var wall = EspnHubTestSupport.NOV25
    private val catalog = EspnClipCatalog()
    private val logs = CopyOnWriteArrayList<String>()
    private var oldSink: BoxLog.Sink? = null
    private val game = GameId(League.NFL, "401873078")
    private val burrow = ClipRef(50578630L, ClipKind.PLAY, game)
    private val expiry = Instant.parse("2026-11-26T02:30:00Z").toEpochMilli()

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
        oldSink = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { _, tag, message, _ -> logs += "$tag: $message" }
    }

    @After fun stop() {
        BoxLog.sink = oldSink!!
        server.close()
    }

    private fun resolver() = EspnClipResolver(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
        mono,
        endpoints = { EspnEndpoints.fromOverride(server.url("/").toString())!! },
        catalog = catalog,
        wallNow = { wall },
    )

    /** The catalog entry the summary would have written: the fixture's record, with a URL of our own. */
    private fun cataloged(url: String = "https://clips.invalid/50578630/playlist.m3u8"): VideoEntry {
        val dto = NflHubFixtures.record(NflHubFixtures.RECORD_50578630).videos!!.single()
        val e = EspnVideoMapper.entry(dto, null, httpOk = false)!!
        return e.copy(hlsUrl = url).also { catalog.put(listOf(it)) }
    }

    private fun recordWith(url: String) = text(NflHubFixtures.RECORD_50578630).replace("https://clips.invalid/50578630/playlist.m3u8", url)

    private fun httpDate(ms: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    // ---------------------------------------------------------------- info and the catalog (arch M2)

    @Test fun infoIsCatalogOnlyAndNeverAsks() {
        val r = resolver()
        assertEquals(ClipResult.NotFound, runBlocking { r.info(burrow) })
        cataloged()
        val info = (runBlocking { r.info(burrow) } as ClipResult.Ok).value
        assertEquals("Joe Burrow heaves a beauty to Tee Higgins for a Bengals TD", info.title)
        assertEquals(23_000L, info.durationMs)
        assertEquals("https://img.invalid/50578630.jpg", info.thumbnailUrl)
        assertNull(info.posterUrl)
        assertTrue("never a request", requests.isEmpty())
    }

    @Test fun aCatalogHitStreamsWithoutARequest() {
        cataloged()
        val (info, stream) = (runBlocking { resolver().stream(burrow) } as ClipResult.Ok).value
        assertEquals(burrow, info.ref)
        assertEquals("https://clips.invalid/50578630/playlist.m3u8", stream.url)
        assertEquals(ClipFormat.HLS, stream.format)
        assertNull("captions come from the master", stream.captionsUrl)
        assertTrue(requests.isEmpty())
        assertEquals(50578630L, catalog.lastServed)
    }

    @Test fun aMissIsOneLookupThatFillsTheCatalog() {
        val r = resolver()
        val ok = runBlocking { r.stream(burrow) } as ClipResult.Ok
        assertEquals("https://clips.invalid/50578630/playlist.m3u8", ok.value.second.url)
        assertEquals(1, requests.size)
        assertEquals("/espn-core/v1/video/clips/50578630", requests.single().url.encodedPath)
        assertNull(requests.single().url.encodedQuery)
        // Now a hit: no second request.
        runBlocking { r.stream(burrow) }
        assertEquals(1, requests.size)
        assertTrue(runBlocking { r.info(burrow) } is ClipResult.Ok)
    }

    // ---------------------------------------------------------------- fresh (arch M3)

    @Test fun freshBypassesTheCatalogWithOneLookup() {
        cataloged("https://clips.invalid/OLD/playlist.m3u8")
        answer = { json(recordWith("https://clips.invalid/NEW/playlist.m3u8")) }
        val ok = runBlocking { resolver().stream(burrow, fresh = true) } as ClipResult.Ok
        assertEquals("a new URL plays", "https://clips.invalid/NEW/playlist.m3u8", ok.value.second.url)
        assertEquals(1, requests.size)
        assertEquals("the catalog is updated", "https://clips.invalid/NEW/playlist.m3u8", catalog.get(50578630L)!!.hlsUrl)
    }

    /**
     * (Code review 2026-11-26, data #1) ESPN's URLs are stable: Try again after a transient failure (5xx, Wi-Fi) gets the
     * same URL back, and it plays. A really dead URL then fails once more as `CLIP_EXPIRED`, and `ClipSession` stops
     * after its one re-resolve (`ClipSessionTest.aStableUrlThatIsReallyDeadEndsAfterOneReResolve`).
     */
    @Test fun freshWithTheSameUrlPlaysIt() {
        cataloged()
        val ok = runBlocking { resolver().stream(burrow, fresh = true) } as ClipResult.Ok
        assertEquals("https://clips.invalid/50578630/playlist.m3u8", ok.value.second.url)
        assertEquals("never two lookups for one retry", 1, requests.size)
        assertEquals(50578630L, catalog.lastServed)
        // Pressed again: one more lookup each time, never a loop inside the resolver.
        runBlocking { resolver().stream(burrow, fresh = true) }
        assertEquals(2, requests.size)
    }

    @Test fun freshNotFoundAndFailures() {
        cataloged()
        val cases = listOf<Pair<() -> MockResponse, ClipResult<*>>>(
            { json("""{"code":404}""", 404) } to ClipResult.NotFound,
            { json("{}", 503, "Retry-After" to "12") } to ClipResult.Failed(FailKind.HTTP_5XX, 12),
            { json("{}", 429, "Retry-After" to "60") } to ClipResult.Failed(FailKind.HTTP_429, 60),
            { html(403) } to ClipResult.Failed(FailKind.HTTP_429),
            { wafChallenge() } to ClipResult.Failed(FailKind.HTTP_429),
            { json("{}", 401) } to ClipResult.Failed(FailKind.HTTP_429),
        )
        for ((a, want) in cases) {
            requests.clear()
            answer = { a() }
            assertEquals(want, runBlocking { resolver().stream(burrow, fresh = true) })
            assertEquals("exactly one lookup: $want", 1, requests.size)
        }
        assertFalse("a block never answers Refused (NFL video stays on)", cases.any { it.second == ClipResult.Refused })
    }

    @Test fun freshThatFindsTheClipExpiredIsExpired() {
        cataloged()
        answer = { json(recordWith("https://clips.invalid/NEW/playlist.m3u8")) }
        wall = expiry - SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS
        assertEquals(ClipResult.Expired, runBlocking { resolver().stream(burrow, fresh = true) })
        assertEquals(1, requests.size)
    }

    @Test fun anIoFailureIsAFailedKindWithoutARetry() {
        server.close()
        val r = runBlocking { resolver().stream(burrow) }
        assertTrue("$r", r is ClipResult.Failed && (r.kind == FailKind.IO || r.kind == FailKind.TIMEOUT))
    }

    // ---------------------------------------------------------------- the offer window (§4.3)

    @Test fun theOfferWindowIsCheckedAtNowPlusSkew() {
        cataloged()
        val r = resolver()
        // 5 min margin: offered until expiry − margin, exclusive.
        wall = expiry - SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS - 1
        assertTrue(runBlocking { r.stream(burrow) } is ClipResult.Ok)
        wall += 1
        assertEquals(ClipResult.Expired, runBlocking { r.stream(burrow) })
        // A device clock 10 min slow: the server's Date (from an answer) moves the reference forward.
        wall = expiry - 10 * 60_000L
        assertTrue("by the device clock it is fine", runBlocking { r.stream(burrow) } is ClipResult.Ok)
        catalog.noteServerDate(serverDateMs = wall + 6 * 60_000L, wallMs = wall)
        assertEquals("by the server's clock it is inside the margin", ClipResult.Expired, runBlocking { r.stream(burrow) })
        assertTrue(requests.isEmpty())
    }

    @Test fun theSkewComesFromTheRecordAnswerToo() {
        wall = expiry - 10 * 60_000L
        answer = { json(text(NflHubFixtures.RECORD_50578630), 200, "Date" to httpDate(wall + 6 * 60_000L)) }
        assertEquals(ClipResult.Expired, runBlocking { resolver().stream(burrow) })
        assertEquals(6 * 60_000L, catalog.skewMs)
    }

    @Test fun anEmbargoedClipIsExpired() {
        wall = Instant.parse("2026-11-24T02:30:00Z").toEpochMilli() // 57 s before the embargo lifts
        assertEquals(ClipResult.Expired, runBlocking { resolver().stream(burrow) })
        wall = Instant.parse("2026-11-24T02:31:00Z").toEpochMilli()
        assertTrue(runBlocking { resolver().stream(burrow) } is ClipResult.Ok)
    }

    @Test fun aRecordThatCannotPlayHereIsBadBody() {
        val base = text(NflHubFixtures.RECORD_50578630)
        val noSettop = base.replace("\"settop\",", "")
        val httpOnly = base.replace("https://clips.invalid/50578630/playlist.m3u8", "http://clips.invalid/50578630/playlist.m3u8")
        val otherKind = base.replace("\"OnePlay\"", "\"Analysis\"")
        val noHls = base.replace("\"HLS\"", "\"HDS\"")
        val prod = EspnClipResolver(OkHttpClient(), mono, { EspnEndpoints.DEFAULT.copy(clipBase = server.url("/c/").toString()) }, catalog, { wall })
        for ((label, body) in listOf("no settop" to noSettop, "http (not the override)" to httpOnly, "Analysis" to otherKind, "no HLS" to noHls)) {
            answer = { json(body) }
            assertEquals(label, ClipResult.Failed(FailKind.BAD_BODY), runBlocking { prod.stream(burrow) })
        }
        answer = { json("""{"videos":[]}""") }
        assertEquals(ClipResult.Failed(FailKind.BAD_BODY), runBlocking { prod.stream(burrow) })
        answer = { json("""{"videos":{"id":1}}""") }
        assertEquals(ClipResult.Failed(FailKind.BAD_BODY), runBlocking { prod.stream(burrow) })
        answer = { json("""{"videos":[{"id":1}]}""" + " ".repeat(SportsConstants.ESPN_CLIP_RECORD_MAX.toInt())) }
        assertEquals("over the record cap", ClipResult.Failed(FailKind.BAD_BODY), runBlocking { prod.stream(burrow) })
        assertEquals(0, catalog.size)
    }

    // ---------------------------------------------------------------- logs (§4.7, arch M5)

    @Test fun noLogLineCarriesAUrlBeyondItsHost() {
        cataloged("https://media.clips.invalid/secret-path/abc/playlist.m3u8")
        runBlocking { resolver().stream(burrow) }
        answer = { json(recordWith("https://media.clips.invalid/other-path/def/playlist.m3u8")) }
        runBlocking { resolver().stream(burrow, fresh = true) }
        answer = { json("{}", 503) }
        runBlocking { resolver().stream(burrow, fresh = true) }
        assertTrue(logs.toString(), logs.isNotEmpty())
        for (line in logs) {
            assertFalse(line, line.contains("secret-path") || line.contains("other-path") || line.contains("playlist.m3u8"))
            assertFalse("no clip id: $line", line.contains("50578630"))
        }
        assertTrue(logs.toString(), logs.any { it.contains("media.clips.invalid") })
    }
}
