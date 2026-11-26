package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipOffer
import app.thebox.core.sports.hub.NflHubFixtures
import app.thebox.data.sports.EspnHubTestSupport.NOV25
import app.thebox.data.sports.EspnHubTestSupport.gzipJson
import app.thebox.data.sports.EspnHubTestSupport.html
import app.thebox.data.sports.EspnHubTestSupport.json
import app.thebox.data.sports.EspnHubTestSupport.text
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * (NFL hub P2, docs/design/m6-nfl-hub.md §5.3, §5.5, §8.2 `EspnDetailSourceTest`) The summary source against a scripted
 * server, with a stand-in mapper (P1's is not needed): the path and query, every status class (404 JSON / HTML, 429 with
 * `Retry-After`, 5xx, the WAF's 403 and 202), the decoded cap (no partial detail), malformed JSON, gzip, the catalog
 * filled with offered entries only, the skew from `Date`, the box in the same answer, and a cancel mid-body.
 */
class EspnDetailSourceTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var answer: (RecordedRequest) -> MockResponse = { json(text(NflHubFixtures.FINAL_MNF)) }
    private val mono = MonoClock { 5_000L }
    private var wall = NOV25
    private val mapper = EspnHubTestSupport.FakeMapper()
    private val catalog = EspnClipCatalog()
    private val mnf = GameId(League.NFL, "401873078")

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun source(callTimeoutMs: Long = 10_000L) = EspnDetailSource(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
        mono,
        endpoints = { EspnEndpoints.fromOverride(server.url("/").toString())!! },
        catalog = catalog,
        mapDetail = mapper.detail,
        mapBox = mapper.box,
        wallNow = { wall },
        callTimeoutMs = callTimeoutMs,
    )

    private fun fetch(id: GameId = mnf) = runBlocking { source().detailAnswer(id, null) }

    private fun httpDate(ms: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    @Test fun theSummaryPathAndQueryCarryTheEventIdOnly() {
        val a = fetch()
        assertTrue(a.fetch is Fetch.Ok)
        val r = requests.single()
        assertEquals("/apis/site/v2/sports/football/nfl/summary", r.url.encodedPath)
        assertEquals("event=401873078", r.url.encodedQuery)
        assertEquals("application/json", r.headers["Accept"])
        assertNull("no cookies, no auth", r.headers["Authorization"] ?: r.headers["Cookie"])
    }

    @Test fun aBadIdOrAnotherLeagueMakesNoRequest() {
        for (id in listOf(GameId(League.NFL, "../summary"), GameId(League.NFL, ""), GameId(League.NHL, "2026020385"))) {
            assertEquals(FailKind.HTTP_OTHER, (fetch(id).fetch as Fetch.Failed).kind)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun everyFixtureDecodesAndMapsWithItsBox() {
        for (path in NflHubFixtures.SUMMARIES) {
            answer = { json(text(path)) }
            val a = fetch()
            val ok = a.fetch as Fetch.Ok
            assertEquals(path, mnf, ok.value.id)
            assertNotNull("the box from the same parse: $path", a.box)
            assertEquals(ok.receivedMono, a.box!!.receivedMono)
        }
        assertEquals("one parse per summary: the box is not a second read", NflHubFixtures.SUMMARIES.size, requests.size)
        assertEquals(NflHubFixtures.SUMMARIES.size, mapper.boxCalls.get())
    }

    @Test fun statusClasses() {
        val cases = listOf<Triple<String, () -> MockResponse, Any>>(
            Triple("404 JSON", { json("""{"code":404,"message":"no event"}""", 404) }, Fetch.NotFound),
            Triple("404 HTML", { html(404) }, FailKind.HTTP_OTHER),
            Triple("400", { json("""{"code":400}""", 400) }, FailKind.HTTP_OTHER),
            Triple("429", { json("{}", 429, "Retry-After" to "40") }, FailKind.HTTP_429),
            Triple("503", { json("{}", 503) }, FailKind.HTTP_5XX),
            Triple("WAF 403", { html(403) }, FailKind.HTTP_429),
            Triple("WAF 202", { EspnHubTestSupport.wafChallenge() }, FailKind.HTTP_429),
            Triple("202 without the WAF header", { html(202) }, FailKind.HTTP_OTHER),
        )
        for ((label, a, want) in cases) {
            answer = { a() }
            val f = fetch().fetch
            if (want == Fetch.NotFound) assertEquals(label, Fetch.NotFound, f) else assertEquals(label, want, (f as Fetch.Failed).kind)
        }
        answer = { json("{}", 429, "Retry-After" to "40") }
        assertEquals(40, (fetch().fetch as Fetch.Failed).retryAfterS)
        assertEquals("no mapper call for a failure", 0, mapper.detailCalls.get())
        assertEquals("nothing reached the catalog", 0, catalog.size)
    }

    @Test fun overTheDecodedCapIsBadBodyWithNoPartialDetail() {
        val body = EspnHubTestSupport.padded(text(NflHubFixtures.FINAL_MNF), kb = 2_100, first = false)
        assertTrue(body.length > SportsConstants.ESPN_SUMMARY_MAX_BYTES)
        for (r in listOf({ json(body) }, { gzipJson(body) })) {
            answer = { r() }
            val a = fetch()
            assertEquals(FailKind.BAD_BODY, (a.fetch as Fetch.Failed).kind)
            assertNull(a.box)
        }
        assertEquals("the mapper never saw a partial DTO", 0, mapper.detailCalls.get())
        assertEquals("the catalog got nothing from the partial body", 0, catalog.size)
    }

    @Test fun aPaddedSummaryUnderTheCapDecodesGzipped() {
        val body = EspnHubTestSupport.padded(text(NflHubFixtures.FINAL_MNF), kb = 600)
        answer = { gzipJson(body) }
        val a = fetch()
        assertTrue(a.fetch is Fetch.Ok)
        assertEquals("gzip asked for by OkHttp, not by the app", "gzip", requests.single().headers["Accept-Encoding"])
        assertEquals(4, (a.fetch as Fetch.Ok).value.clips.size)
    }

    @Test fun malformedJsonOrAWrongSpineIsBadBody() {
        for (bad in listOf("""{"header":""", "<html>", """{"videos":{"id":1}}""", """{"header":[1,2]}""", "[]")) {
            answer = { json(bad) }
            assertEquals(bad, FailKind.BAD_BODY, (fetch().fetch as Fetch.Failed).kind)
        }
        assertEquals(0, mapper.detailCalls.get())
    }

    @Test fun aMapperThatFailsIsBadBody() {
        mapper.answerNull = true
        assertEquals(FailKind.BAD_BODY, (fetch().fetch as Fetch.Failed).kind)
        mapper.answerNull = false
        mapper.throwOnMap = true
        val a = fetch()
        assertEquals(FailKind.BAD_BODY, (a.fetch as Fetch.Failed).kind)
        assertNull(a.box)
        assertEquals(0, catalog.size)
    }

    @Test fun theCatalogGetsTheOfferedEntriesOnly() {
        // MNF: the highlight and three one-plays, all offered on the 25th.
        fetch()
        assertEquals(4, catalog.size)
        assertEquals(ClipKind.RECAP, catalog.get(50579688L)!!.clip.kind)
        assertEquals("https://clips.invalid/50578630/playlist.m3u8", catalog.get(50578630L)!!.hlsUrl)
        // Pre-game: an Analysis clip is never offered.
        val pre = EspnClipCatalog()
        EspnDetailSource(
            OkHttpClient(), mono, { EspnEndpoints.fromOverride(server.url("/").toString())!! }, pre, mapper.detail, mapper.box, { wall },
        ).let { s -> answer = { json(text(NflHubFixtures.PRE_GB_LAR)) }; runBlocking { s.detailAnswer(GameId(League.NFL, "401873079"), null) } }
        assertEquals(0, pre.size)
        // Two days later every MNF clip is past its expiry margin: none is added.
        val late = EspnClipCatalog()
        wall = Instant.parse("2026-11-26T04:30:00Z").toEpochMilli()
        answer = { json(text(NflHubFixtures.FINAL_MNF)) }
        EspnDetailSource(OkHttpClient(), mono, { EspnEndpoints.fromOverride(server.url("/").toString())!! }, late, mapper.detail, mapper.box, { wall })
            .let { s -> runBlocking { s.detailAnswer(mnf, null) } }
        assertEquals(0, late.size)
    }

    @Test fun theSkewComesFromTheDateHeaderAndIsClamped() {
        // The server is 90 s ahead of the device: the mapper is asked at now + 90 s, and the detail carries the skew.
        answer = { json(text(NflHubFixtures.FINAL_MNF), 200, "Date" to httpDate(wall + 90_000)) }
        val ok = fetch().fetch as Fetch.Ok
        assertEquals(90_000L, ok.value.clockSkewMs)
        assertEquals(wall + 90_000, mapper.lastAt)
        assertEquals(wall + 90_000, ok.value.serverDateMs)
        assertEquals(90_000L, catalog.skewMs)
        // A device clock a day off: the skew is clamped to an hour.
        answer = { json(text(NflHubFixtures.FINAL_MNF), 200, "Date" to httpDate(wall - 86_400_000)) }
        assertEquals(-SportsConstants.HUB_CLOCK_SKEW_MAX_MS, (fetch().fetch as Fetch.Ok).value.clockSkewMs)
    }

    // ---------------------------------------------------------------- code review 2026-11-26 (data #2, #3, #5)

    /** (#2) The seam has the production signature: the stand-in receives the same `httpOk` (the override: true). */
    @Test fun theStandInMapperReceivesTheHttpFlag() {
        fetch()
        assertEquals(true, mapper.lastHttpOk)
    }

    /** The real mapper through the default seam (no stand-in). */
    private fun realSource(cat: EspnClipCatalog) = EspnDetailSource(
        OkHttpClient(), mono, { EspnEndpoints.fromOverride(server.url("/").toString())!! }, cat, wallNow = { wall },
    )

    /** MNF's body with its `videos[]` replaced by [videos]. */
    private fun mnfWith(videos: List<JsonObject>): String {
        val root = Json.parseToJsonElement(text(NflHubFixtures.FINAL_MNF)).jsonObject
        return JsonObject(root + ("videos" to JsonArray(videos))).toString()
    }

    private fun mnfVideos(): List<JsonObject> =
        Json.parseToJsonElement(text(NflHubFixtures.FINAL_MNF)).jsonObject["videos"]!!.jsonArray.map { it.jsonObject }

    /** [v] with a new id and publish date (one second apart per [n]). */
    private fun copyOf(v: JsonObject, id: Long, n: Int): JsonObject =
        JsonObject(v + ("id" to JsonPrimitive(id)) + ("originalPublishDate" to JsonPrimitive("2026-11-24T02:${(31 + n / 60).toString().padStart(2, '0')}:${(n % 60).toString().padStart(2, '0')}Z")))

    /**
     * (#5) More than `HUB_NFL_MAX_CLIPS` offered videos with the highlight listed last: the detail and the catalog come
     * from one selection, so the highlight is in both and they hold the same ids.
     */
    @Test fun theCatalogAndTheDetailShareOneSelection() {
        val vs = mnfVideos()
        val highlight = vs.first { it["tracking"]!!.jsonObject["coverageType"]!!.jsonPrimitive.content == "Final Game Highlight" }
        val play = vs.first { it["tracking"]!!.jsonObject["coverageType"]!!.jsonPrimitive.content == "OnePlay" }
        val many = (1..30).map { copyOf(play, 60_000_000L + it, it) } + highlight
        answer = { json(mnfWith(many)) }
        val cat = EspnClipCatalog()
        val d = (runBlocking { realSource(cat).detailAnswer(mnf, null) }.fetch as Fetch.Ok).value
        assertEquals(SportsConstants.HUB_NFL_MAX_CLIPS, d.clips.size)
        assertEquals("the highlight first in the detail", 50579688L, d.clips.first().videoId)
        assertNotNull("and in the catalog", cat.get(50579688L))
        assertEquals(d.clips.size, cat.size)
        for (c in d.clips) assertNotNull("catalog has ${c.videoId}", cat.get(c.videoId))
    }

    /**
     * (#3) A clip embargoed for another ten minutes is kept (detail and catalog), so the view model's offer tick can
     * reveal it when the embargo lifts; it is not offered until then (the UI and the resolver check `ClipOffer.offered`).
     */
    @Test fun anEmbargoedClipIsKeptForTheEmbargoLift() {
        val vs = mnfVideos()
        val play = vs.first { it["id"]!!.jsonPrimitive.content == "50578630" }
        val tr = play["timeRestrictions"]!!.jsonObject
        val lift = wall + 600_000L
        val embargoed = JsonObject(play + ("timeRestrictions" to JsonObject(tr + ("embargoDate" to JsonPrimitive(Instant.ofEpochMilli(lift).toString())))))
        answer = { json(mnfWith(vs.map { if (it === play) embargoed else it })) }
        val cat = EspnClipCatalog()
        val d = (runBlocking { realSource(cat).detailAnswer(mnf, null) }.fetch as Fetch.Ok).value
        val c = d.clips.first { it.videoId == 50578630L }
        assertEquals(lift, c.embargoMs)
        assertTrue("not offered yet", !ClipOffer.offered(c.embargoMs, c.expiresMs, wall))
        assertEquals("the lift is the next change", lift, ClipOffer.changesAt(c.embargoMs, c.expiresMs, wall))
        assertTrue("offered at the lift", ClipOffer.offered(c.embargoMs, c.expiresMs, lift))
        assertNotNull(cat.get(50578630L))
        // An expired clip is still never listed.
        wall = Instant.parse("2026-11-26T02:30:00Z").toEpochMilli()
        val late = (runBlocking { realSource(EspnClipCatalog()).detailAnswer(mnf, null) }.fetch as Fetch.Ok).value
        assertTrue(late.clips.none { it.videoId == 50578630L })
    }

    @Test fun boxScoreIsOneReadMappedToTheBox() {
        val s = source()
        assertTrue(runBlocking { s.boxScore(mnf) } is Fetch.Ok)
        answer = { json("{}", 503) }
        assertEquals(FailKind.HTTP_5XX, (runBlocking { s.boxScore(mnf) } as Fetch.Failed).kind)
        answer = { json("""{"header":{"id":"401873078"}}""") } // no boxscore section: the mapper gives no box
        assertEquals(FailKind.BAD_BODY, (runBlocking { s.boxScore(mnf) } as Fetch.Failed).kind)
    }

    /** A move to another game cancels the call: the 600 KB body stops at once and its partial DTO is dropped. */
    @Test fun aCancelMidBodyDropsTheAnswer() {
        val body = EspnHubTestSupport.padded(text(NflHubFixtures.FINAL_MNF), kb = 600)
        answer = {
            MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "application/json")
                .throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS).build()
        }
        val s = source()
        runBlocking {
            val job = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) { s.detailAnswer(mnf, null) }
            delay(400) // a few throttled chunks in
            job.cancel()
            val outcome = runCatching { withTimeout(2_000) { job.await() } }
            assertTrue("cancelled, not answered: $outcome", outcome.exceptionOrNull() is CancellationException)
        }
        Thread.sleep(300)
        assertEquals("the mapper never saw the partial body", 0, mapper.detailCalls.get())
        assertEquals(0, catalog.size)
    }

    @Test fun aSlowBodyTimesOut() {
        answer = {
            MockResponse.Builder().code(200).body(text(NflHubFixtures.FINAL_MNF)).addHeader("Content-Type", "application/json")
                .throttleBody(1024, 200, TimeUnit.MILLISECONDS).build()
        }
        val f = runBlocking { source(callTimeoutMs = 500).detailAnswer(mnf, null) }.fetch as Fetch.Failed
        assertTrue(f.kind.toString(), f.kind == FailKind.TIMEOUT || f.kind == FailKind.IO)
    }
}
