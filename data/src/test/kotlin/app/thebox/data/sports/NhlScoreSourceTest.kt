package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlMapper
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The NHL transport against a scripted server (docs/design/m6-score-bug.md §2.1, §2.2, §8 `:data`). */
class NhlScoreSourceTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    /** Scripted answers by exact path; default 404 JSON. */
    private val answers = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()
    private var nowMono = 5_000L
    private val mono = MonoClock { nowMono }
    private val mappedBodies = CopyOnWriteArrayList<JsonObject>()

    /** A stand-in for WP-1's mapper: the day key is `currentDate`, the board has no games. */
    private val fakeDay: (JsonObject, String) -> DayBoard? = { body, _ ->
        mappedBodies += body
        body["currentDate"]?.jsonPrimitive?.content?.let { DayBoard(it, null, null, emptyList()) }
    }
    private val fakeLanding: (JsonObject, String) -> GameSnapshot? = { body, base ->
        mappedBodies += body
        body["id"]?.jsonPrimitive?.content?.let { SportsFixtures.game(id = it, away = SportsFixtures.team("UTA", "Mammoth").copy(logo = base + "UTA_dark.svg")) }
    }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answers[request.url.encodedPath]?.invoke(request) ?: json("""{}""", 404)
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun origin() = server.url("/").toString().trimEnd('/')

    private fun source(
        mapDay: (JsonObject, String) -> DayBoard? = fakeDay,
        mapLanding: (JsonObject, String) -> GameSnapshot? = fakeLanding,
        callTimeoutMs: Long = 10_000L,
    ) = NhlScoreSource(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
        mono,
        endpoints = { SportsEndpoints.fromOverride(origin())!! },
        mapDay = mapDay,
        mapLanding = mapLanding,
        deriveLogo = { abbrev, base -> "$base${abbrev}_dark.svg" },
        callTimeoutMs = callTimeoutMs,
    )

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private val day = """{"prevDate":"2026-11-04","currentDate":"2026-11-05","nextDate":"2026-11-06","games":[]}"""

    // ---------------------------------------------------------------- URLs (§2.1)

    @Test fun todayFollowsTheScoreNowRedirectToTheDatedUrl() {
        answers["/v1/score/now"] = {
            MockResponse.Builder().code(307).addHeader("Location", "/v1/score/2026-11-05").addHeader("Age", "17").build()
        }
        answers["/v1/score/2026-11-05"] = { json(day, 200, "Age" to "5", "Date" to "Fri, 06 Nov 2026 00:52:25 GMT") }
        val f = runBlocking { source().today() }
        assertEquals(listOf("/v1/score/now", "/v1/score/2026-11-05"), requests.map { it.url.encodedPath })
        f as Fetch.Ok
        assertEquals("2026-11-05", f.value.dayKey)
        assertEquals("the final response's Age, not the redirect's", 5, f.ageS)
        assertEquals(1_793_926_345_000L, f.serverDateMs)
    }

    @Test fun dayAndGameUseTheDatedAndLandingPaths() {
        answers["/v1/score/2026-11-04"] = { json(day.replace("2026-11-05", "2026-11-04")) }
        answers["/v1/gamecenter/2026020251/landing"] = { json("""{"id":2026020251}""") }
        val s = source()
        val d = runBlocking { s.day("2026-11-04") }
        val g = runBlocking { s.game(GameId(League.NHL, "2026020251")) }
        assertEquals("2026-11-04", (d as Fetch.Ok).value.dayKey)
        assertEquals("2026020251", (g as Fetch.Ok).value.id.id)
        assertEquals(listOf("/v1/score/2026-11-04", "/v1/gamecenter/2026020251/landing"), requests.map { it.url.encodedPath })
        assertEquals("application/json", requests.first().headers["Accept"])
    }

    @Test fun malformedKeysAndIdsAreNeverSent() {
        val s = source()
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.day("../admin") } as Fetch.Failed).kind)
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.game(GameId(League.NHL, "1/../x")) } as Fetch.Failed).kind)
        assertTrue(requests.isEmpty())
    }

    @Test fun noConditionalHeadersAreEverSent() {
        answers["/v1/score/2026-11-05"] = { json(day, 200, "ETag" to "W/\"6aed24d8--gzip\"", "Last-Modified" to "Fri, 06 Nov 2026 00:52:25 GMT") }
        val s = source()
        runBlocking { s.day("2026-11-05"); s.day("2026-11-05") }
        assertEquals(2, requests.size)
        for (r in requests) {
            assertNull("A-S1: no If-None-Match", r.headers["If-None-Match"])
            assertNull(r.headers["If-Modified-Since"])
        }
    }

    // ---------------------------------------------------------------- Date / Age (A-S2, A-S12)

    @Test fun dateAndAgeAreReadAndAgeIsClamped() {
        val s = source()
        fun ok(vararg h: Pair<String, String>): Fetch.Ok<DayBoard> {
            answers["/v1/score/2026-11-05"] = { json(day, 200, *h) }
            return runBlocking { s.day("2026-11-05") } as Fetch.Ok
        }
        nowMono = 42_000L
        val a = ok("Date" to "Fri, 06 Nov 2026 00:52:38 GMT", "Age" to "18")
        assertEquals(18, a.ageS)
        assertEquals(1_793_926_358_000L, a.serverDateMs)
        assertEquals("stamped from the injected MonoClock", 42_000L, a.receivedMono)
        assertEquals(SportsConstants.AGE_MAX_S, ok("Age" to "99").ageS)
        assertEquals(0, ok("Age" to "-3").ageS)
        assertEquals(0, ok("Age" to "soon").ageS)
        val bare = ok()
        assertEquals(0, bare.ageS)
        // MockWebServer adds no Date by default; a missing Date is null (the machine falls back to received + 20 s).
        assertNull(bare.serverDateMs)
    }

    // ---------------------------------------------------------------- failures (§2.4, A-S12)

    @Test fun notFoundNeedsAJsonOrEmptyBody() {
        val s = source()
        val id = GameId(League.NHL, "2026020999")
        answers["/v1/gamecenter/2026020999/landing"] = { json("""{"message":"Not Found"}""", 404) }
        assertEquals(Fetch.NotFound, runBlocking { s.game(id) })
        answers["/v1/gamecenter/2026020999/landing"] = { MockResponse.Builder().code(404).build() }
        assertEquals(Fetch.NotFound, runBlocking { s.game(id) })
        answers["/v1/gamecenter/2026020999/landing"] = {
            MockResponse.Builder().code(404).addHeader("Content-Type", "text/html").body("<html>gone</html>").build()
        }
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.game(id) } as Fetch.Failed).kind)
        answers["/v1/gamecenter/2026020999/landing"] = {
            MockResponse.Builder().code(404).addHeader("Content-Type", "application/octet-stream").body("  <!doctype html>").build()
        }
        assertEquals("an HTML body without the type is still HTML", FailKind.HTTP_OTHER, (runBlocking { s.game(id) } as Fetch.Failed).kind)
    }

    @Test fun statusCodesMapToFailKindsWithRetryAfter() {
        val s = source()
        nowMono = 7_777L
        answers["/v1/score/2026-11-05"] = { json("{}", 429, "Retry-After" to "30") }
        val r = runBlocking { s.day("2026-11-05") } as Fetch.Failed
        assertEquals(FailKind.HTTP_429, r.kind)
        assertEquals(30, r.retryAfterS)
        assertEquals(7_777L, r.receivedMono)
        answers["/v1/score/2026-11-05"] = { json("{}", 503) }
        assertEquals(Fetch.Failed(FailKind.HTTP_5XX, null, 7_777L), runBlocking { s.day("2026-11-05") })
        answers["/v1/score/2026-11-05"] = { json("{}", 403, "Retry-After" to "99999") }
        val o = runBlocking { s.day("2026-11-05") } as Fetch.Failed
        assertEquals(FailKind.HTTP_OTHER, o.kind)
        assertEquals("clamped to the back-off cap", 300, o.retryAfterS)
    }

    @Test fun badBodiesAreBadBody() {
        val s = source()
        answers["/v1/score/2026-11-05"] = { json("<html>captive portal</html>") }
        assertEquals(FailKind.BAD_BODY, (runBlocking { s.day("2026-11-05") } as Fetch.Failed).kind)
        answers["/v1/score/2026-11-05"] = { json("[1,2,3]") }
        assertEquals(FailKind.BAD_BODY, (runBlocking { s.day("2026-11-05") } as Fetch.Failed).kind)
        answers["/v1/score/2026-11-05"] = { json("""{"games":[]}""") } // the fake mapper needs currentDate
        assertEquals(FailKind.BAD_BODY, (runBlocking { s.day("2026-11-05") } as Fetch.Failed).kind)
        val throwing = source(mapDay = { _, _ -> throw IllegalStateException("mapper bug") })
        answers["/v1/score/2026-11-05"] = { json(day) }
        assertEquals(FailKind.BAD_BODY, (runBlocking { throwing.day("2026-11-05") } as Fetch.Failed).kind)
    }

    @Test fun theBodyCapIsEnforcedBeforeDecoding() {
        val s = source()
        val cap = SportsConstants.MAX_BODY_BYTES.toInt()
        val prefix = """{"currentDate":"2026-11-05","pad":""""
        val suffix = "\"}"
        val atCap = prefix + "x".repeat(cap - prefix.length - suffix.length) + suffix
        assertEquals(cap, atCap.length)
        answers["/v1/score/2026-11-05"] = { json(atCap) }
        assertTrue("exactly the cap is fine", runBlocking { s.day("2026-11-05") } is Fetch.Ok)
        mappedBodies.clear()
        answers["/v1/score/2026-11-05"] = { json(atCap.replaceFirst("\"pad\":\"", "\"pad\":\"y")) }
        assertEquals(FailKind.BAD_BODY, (runBlocking { s.day("2026-11-05") } as Fetch.Failed).kind)
        assertTrue("A-N5: never decoded", mappedBodies.isEmpty())
    }

    @Test fun timeoutsAndConnectionFailures() {
        answers["/v1/score/2026-11-05"] = { json(day).newBuilder().headersDelay(2, TimeUnit.SECONDS).build() }
        assertEquals(FailKind.TIMEOUT, (runBlocking { source(callTimeoutMs = 300).day("2026-11-05") } as Fetch.Failed).kind)
        val dead = NhlScoreSource(
            OkHttpClient(), mono, endpoints = { SportsEndpoints.fromOverride("http://127.0.0.1:1")!! },
            mapDay = fakeDay, mapLanding = fakeLanding,
        )
        assertEquals(FailKind.IO, (runBlocking { dead.day("2026-11-05") } as Fetch.Failed).kind)
    }

    // ---------------------------------------------------------------- logos (A-N2) and endpoints (A-N3)

    @Test fun logoUrlKeepsOnlyADarkLogoUnderTheBase() {
        val s = source()
        val base = SportsEndpoints.fromOverride(origin())!!.logoBase
        assertEquals(base + "MTL_dark.svg", s.logoUrl(SportsFixtures.team("MTL", "Canadiens").copy(logo = base + "MTL_dark.svg")))
        assertEquals(base + "MTL_dark.svg", s.logoUrl(SportsFixtures.team("MTL", "Canadiens").copy(logo = "https://evil.test/MTL_dark.svg")))
        assertEquals(base + "MTL_dark.svg", s.logoUrl(SportsFixtures.team("MTL", "Canadiens").copy(logo = null)))
    }

    @Test fun endpointOverrideParsing() {
        assertEquals(
            SportsEndpoints("http://10.0.2.2:8080/v1/", "http://10.0.2.2:8080/logos/nhl/svg/"),
            SportsEndpoints.fromOverride(" http://10.0.2.2:8080/\n"),
        )
        assertEquals("a path is dropped: the panel serves the NHL's own paths",
            "http://panel.test/v1/", SportsEndpoints.fromOverride("http://panel.test/whatever?x=1")?.apiBase)
        assertNull(SportsEndpoints.fromOverride(""))
        assertNull(SportsEndpoints.fromOverride(null))
        assertNull(SportsEndpoints.fromOverride("ftp://x"))
        assertNull(SportsEndpoints.fromOverride("not a url"))
        assertEquals("https://api-web.nhle.com/v1/", SportsEndpoints.DEFAULT.apiBase)
        assertEquals("https://assets.nhle.com/logos/nhl/svg/", SportsEndpoints.DEFAULT.logoBase)
    }

    // ---------------------------------------------------------------- with WP-1's mapper (skipped while it is a stub)

    @Test fun recordedFixturesThroughTheRealMapper() {
        assumeTrue("NhlMapper is still a stub (WP-1)", mapperImplemented())
        answers["/v1/score/2026-11-05"] = { json(SportsFixtures.text(SportsFixtures.SCORE_2026_11_05), 200, "Age" to "18") }
        answers["/v1/gamecenter/2026020251/landing"] = { json(SportsFixtures.text(SportsFixtures.LANDING_LIVE_INT)) }
        val s = source(mapDay = NhlMapper::day, mapLanding = NhlMapper::landing)
        val d = runBlocking { s.day("2026-11-05") } as Fetch.Ok
        assertEquals("2026-11-05", d.value.dayKey)
        assertEquals(11, d.value.games.size)
        val g = runBlocking { s.game(GameId(League.NHL, "2026020251")) } as Fetch.Ok
        assertEquals("UTA", g.value.away.abbrev)
    }

    private fun mapperImplemented(): Boolean = try {
        NhlMapper.darkLogoUrl("MTL", SportsEndpoints.LOGO_BASE)
        true
    } catch (e: NotImplementedError) {
        false
    }
}
