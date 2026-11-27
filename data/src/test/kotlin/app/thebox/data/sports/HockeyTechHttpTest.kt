package app.thebox.data.sports

import app.thebox.core.common.Redact
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * (CHL hub commit 0, docs/design/m6-chl-hub.md §4.4 b, §6 C0) The shared HockeyTech transport's hub additions: the
 * JSONP-array and object decode ([HockeyTechHttp.element]) on the real fixtures, the body cap, the `lscluster` URL
 * (the caller's parameters, then only `key` and `client_code`), the error mapping of [HockeyTechHttp.json], and that
 * nothing it prints carries a key. Only the fake panel's keys are used.
 */
class HockeyTechHttpTest {
    private lateinit var server: MockWebServer
    private val mono = MonoClock { 5_000L }

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.close()

    private fun endpoints() = HockeyTechEndpoints.fromOverride(server.url("/").toString())!!

    private fun http() = HockeyTechHttp(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(), mono)

    private fun url() = HockeyTechHttp.lscluster(endpoints(), League.OHL) {
        addQueryParameter("feed", "gc").addQueryParameter("tab", "preview").addQueryParameter("game_id", "29246")
            .addQueryParameter("fmt", "json").addQueryParameter("lang_code", "en")
    }

    private fun body(text: String, code: Int = 200) = MockResponse.Builder().code(code).body(text).build()

    // ---------------------------------------------------------------- element

    @Test fun theJsonpLeagueTableDecodesToItsArray() {
        val t = SportsFixtures.text("sports/chl/hub/svf-teams-ohl.json")
        assertTrue(t.trimStart().startsWith("(")) // the fixture keeps the wrapper
        val e = HockeyTechHttp.element(t)
        assertTrue(e is JsonArray)
        val rows = (((e as JsonArray)[0].jsonObject["sections"] as JsonArray)[0].jsonObject["data"] as JsonArray)
        assertEquals(20, rows.size)
        assertTrue(HockeyTechHttp.element(SportsFixtures.text("sports/chl/hub/svf-teams-special-whl.json")) is JsonArray)
    }

    @Test fun aPlainObjectDecodesUnchanged() {
        val e = HockeyTechHttp.element(SportsFixtures.text("sports/chl/hub/gc-preview-pre-ohl-29246.json"))
        assertTrue(e is JsonObject)
        assertTrue((e as JsonObject).containsKey("GC"))
        assertTrue(HockeyTechHttp.element(" \n({\"a\":1})\n ") is JsonObject)
    }

    @Test fun oneWrapperOnlyAndBrokenTextIsNull() {
        assertNull(HockeyTechHttp.element("(([1]))")) // two wrappers: the inner "(" is not JSON
        assertNull(HockeyTechHttp.element("([1]")) // unbalanced
        assertNull(HockeyTechHttp.element("()"))
        assertNull(HockeyTechHttp.element(""))
        assertNull(HockeyTechHttp.element("Invalid key."))
        assertNull(HockeyTechHttp.element("<html>"))
    }

    // ---------------------------------------------------------------- lscluster

    @Test fun lsclusterAddsOnlyKeyAndClientCodeAfterTheCallersParameters() {
        val u = url()
        assertEquals("/hockeytech/feed/index.php", u.encodedPath)
        assertEquals(listOf("feed", "tab", "game_id", "fmt", "lang_code", "key", "client_code"), u.queryParameterNames.toList())
        assertEquals(HockeyTechEndpoints.fakeKey(League.OHL), u.queryParameter("key"))
        assertEquals("ohl", u.queryParameter("client_code"))
        assertNull(u.queryParameter("lang")) // gc takes lang_code; nothing is added for the caller
        val q = HockeyTechHttp.lscluster(endpoints(), League.QMJHL) { addQueryParameter("feed", "statviewfeed") }
        assertEquals(listOf("feed", "key", "client_code"), q.queryParameterNames.toList())
        assertEquals("lhjmq", q.queryParameter("client_code"))
    }

    @Test fun theDefaultEndpointsUseTheLeaguesRealKeyWhichIsRedacted() {
        val u = HockeyTechHttp.lscluster(HockeyTechEndpoints.DEFAULT, League.WHL) { addQueryParameter("feed", "gc") }
        val key = u.queryParameter("key")!!
        assertEquals(HockeyTechKeys.of(League.WHL), key)
        assertFalse(Redact.text(u.toString()).contains(key))
    }

    // ---------------------------------------------------------------- json

    @Test fun anArrayAnswerIsOkWithWireBytes() = runBlocking {
        server.enqueue(body(SportsFixtures.text("sports/chl/hub/svf-teams-special-ohl.json")))
        val a = http().json(url())
        assertTrue(a is HockeyTechHttp.JsonAnswer.Ok)
        a as HockeyTechHttp.JsonAnswer.Ok
        assertTrue(a.element is JsonArray)
        assertEquals(5_000L, a.receivedMono)
        assertTrue(a.wire > 0)
    }

    @Test fun overTheCapIsBadBody() = runBlocking {
        val big = "[" + "1,".repeat(40_000) + "1]" // ≈ 80 KB
        server.enqueue(body(big))
        val f = http().json(url(), cap = 65_536L) as HockeyTechHttp.JsonAnswer.Failed
        assertEquals(FailKind.BAD_BODY, f.fetch.kind)
        assertEquals("over cap", f.cause)
        server.enqueue(body(big))
        assertTrue(http().json(url()) is HockeyTechHttp.JsonAnswer.Ok) // under MAX_BODY_BYTES
        assertEquals(524_288L, SportsConstants.MAX_BODY_BYTES)
    }

    @Test fun theErrorMappingFollowsTheScoreSource() = runBlocking {
        fun kind(r: MockResponse): FailKind? {
            server.enqueue(r)
            return (runBlocking { http().json(url()) } as? HockeyTechHttp.JsonAnswer.Failed)?.fetch?.kind
        }
        assertEquals(FailKind.REFUSED, kind(body("Invalid key.")))
        assertEquals(FailKind.REFUSED, kind(body("  Client access denied.")))
        assertEquals(FailKind.REFUSED, kind(body("", 403)))
        assertEquals(FailKind.HTTP_OTHER, kind(body("{\"error\":\"No such game\"}", 404)))
        assertEquals(FailKind.HTTP_429, kind(body("", 429)))
        assertEquals(FailKind.HTTP_5XX, kind(body("", 503)))
        assertEquals(FailKind.BAD_BODY, kind(body("<html>maintenance</html>")))
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "9999").build())
        val f = http().json(url()) as HockeyTechHttp.JsonAnswer.Failed
        assertEquals((SportsConstants.BACKOFF_MAX_MS / 1000).toInt(), f.fetch.retryAfterS)
    }

    @Test fun nothingPrintedCarriesAKeyOrTheUrl() = runBlocking {
        val key = HockeyTechEndpoints.fakeKey(League.OHL)
        server.enqueue(body("{\"GC\":{}}"))
        val ok = http().json(url())
        server.enqueue(body("Invalid key."))
        val refused = http().json(url())
        for (s in listOf(ok.toString(), refused.toString(), http().toString(), endpoints().toString(), HockeyTechEndpoints.DEFAULT.toString())) {
            assertFalse(s, s.contains(key))
            assertFalse(s, s.contains("index.php"))
            for (l in League.entries.filter { it != League.NHL && it != League.NFL }) assertFalse(s, s.contains(HockeyTechKeys.of(l)))
        }
    }

    // ---------------------------------------------------------------- headshot (§4.4 d)

    @Test fun headshotsAreBuiltForDigitIdsUnderTheLogoBase() {
        assertEquals("https://assets.leaguestat.com/ohl/240x240/9924.jpg", HockeyTechEndpoints.DEFAULT.headshot(League.OHL, "9924"))
        assertEquals("https://assets.leaguestat.com/lhjmq/240x240/20480.jpg", HockeyTechEndpoints.DEFAULT.headshot(League.QMJHL, "20480"))
        assertEquals(server.url("/leaguestat/whl/240x240/30291.jpg").toString(), endpoints().headshot(League.WHL, "30291"))
        assertNull(HockeyTechEndpoints.DEFAULT.headshot(League.OHL, ""))
        assertNull(HockeyTechEndpoints.DEFAULT.headshot(League.OHL, "../x"))
        assertNull(HockeyTechEndpoints.DEFAULT.headshot(League.OHL, "12a"))
        assertNull(HockeyTechEndpoints.DEFAULT.headshot(League.OHL, "1234567890123"))
    }
}
