package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.hub.EspnClipRecordDto
import app.thebox.data.sports.EspnHubTestSupport.html
import app.thebox.data.sports.EspnHubTestSupport.json
import app.thebox.data.sports.EspnHubTestSupport.wafChallenge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * (NFL hub P2, docs/design/m6-nfl-hub.md §5.3, §8.2 `EspnHttpTest`, arch M6) The shared ESPN transport: the cap is a
 * per-call parameter, the body is drained (the wire meter counts it whole), an HTML 404 is not "no such thing", and the
 * WAF rule (403, and 202 with `x-amzn-waf-action`, → `HTTP_429`) holds for the board, the summary and the clip records.
 */
class EspnHttpTest {
    @Serializable private class Small(val a: Int? = null, val pad: String? = null)

    private lateinit var server: MockWebServer
    @Volatile private var answer: (RecordedRequest) -> MockResponse = { json("""{"a":1}""") }
    private val mono = MonoClock { 1_000L }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    @After fun stop() = server.close()

    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()

    private fun call(cap: Long): EspnHttp.Answer<Small> {
        val http = EspnHttp(WireMeter.install(client.newBuilder(), client).build(), mono, "EspnHttpTest")
        return runBlocking { http.call(server.url("/x"), Small.serializer(), cap) }
    }

    @Test fun theCapIsPerCall() {
        val body = """{"a":1,"pad":"${"x".repeat(5_000)}"}"""
        answer = { json(body) }
        assertTrue(call(cap = 10_000).fetch is Fetch.Ok)
        assertEquals(FailKind.BAD_BODY, (call(cap = 4_000).fetch as Fetch.Failed).kind)
    }

    @Test fun theBodyIsDrainedSoTheWireMeterCountsItWhole() {
        answer = { json("""{"a":1}""" + " ".repeat(20_000)) }
        val a = call(cap = 100_000)
        val ok = a.fetch as Fetch.Ok
        assertEquals(1, ok.value.a)
        assertEquals("every decoded byte read", 20_007L, a.length)
        assertEquals("the wire bytes are the whole body", 20_007L, ok.wireBytes)
    }

    @Test fun anHtml404IsNotNotFound() {
        answer = { html(404) }
        assertEquals(FailKind.HTTP_OTHER, (call(10_000).fetch as Fetch.Failed).kind)
        answer = { json("""{"code":404}""", 404) }
        assertEquals(Fetch.NotFound, call(10_000).fetch)
    }

    @Test fun theWafRuleOnAPlainCall() {
        answer = { html(403, "Retry-After" to "30") }
        val f = call(10_000)
        assertEquals(FailKind.HTTP_429, (f.fetch as Fetch.Failed).kind)
        assertEquals(30, (f.fetch as Fetch.Failed).retryAfterS)
        assertEquals("the real status is kept for the callers' shape rules", 403, f.code)
        answer = { wafChallenge() }
        assertEquals(FailKind.HTTP_429, (call(10_000).fetch as Fetch.Failed).kind)
        answer = { html(202) }
        assertEquals("a 202 without the header is just odd", FailKind.HTTP_OTHER, (call(10_000).fetch as Fetch.Failed).kind)
        answer = { json("{}", 401) }
        assertEquals("401 is the resolver's rule, not the transport's", FailKind.HTTP_OTHER, (call(10_000).fetch as Fetch.Failed).kind)
    }

    @Test fun theWafRuleOnTheBoard() {
        val board: (app.thebox.core.sports.EspnBoardDto, Long?, String) -> DayBoard? = { _, _, _ -> DayBoard("k", null, null, emptyList()) }
        val s = EspnScoreSource(client, mono, { EspnEndpoints.fromOverride(server.url("/").toString())!! }, mapBoard = board)
        for (r in listOf({ html(403) }, { wafChallenge() })) {
            answer = { r() }
            assertEquals(FailKind.HTTP_429, (runBlocking { s.today() } as Fetch.Failed).kind)
        }
        answer = { json("""{"events":[]}""") }
        assertTrue(runBlocking { s.today() } is Fetch.Ok)
    }

    @Test fun theWafRuleOnTheClipRecord() {
        val http = EspnHttp(client, mono, "EspnHttpTest")
        for (r in listOf({ html(403) }, { wafChallenge() })) {
            answer = { r() }
            val a = runBlocking { http.call(server.url("/espn-core/v1/video/clips/1"), EspnClipRecordDto.serializer(), 65_536) }
            assertEquals(FailKind.HTTP_429, (a.fetch as Fetch.Failed).kind)
        }
    }
}
