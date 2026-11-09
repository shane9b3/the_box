package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * docs/design/m6-sports-update-interval.md §4.2 (review S3, S4, S11): the NFL follow on ESPN's per-event endpoint
 * `scoreboard/{id}`, its board fallback, `max-age` and the wire bytes, against a scripted server and the real mapper.
 */
class EspnEventSourceTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private var nowMono = 5_000L
    private val mono = MonoClock { nowMono }

    /** The per-event answer; default 404 JSON. */
    private var event: (String) -> MockResponse = { id -> json("""{"code":404,"message":"No event found for eventId: $id"}""", 404) }
    /** The dated / week board answer; default no events. */
    private var board: (String) -> MockResponse = { json("""{"events":[]}""") }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val q = request.url.encodedQuery.orEmpty()
                return when {
                    path == BOARD -> { requests += "board?$q"; board(q) }
                    path.startsWith("$BOARD/") -> { val id = path.removePrefix("$BOARD/"); requests += "event:$id"; event(id) }
                    else -> html404()
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun origin() = server.url("/").toString().trimEnd('/')
    private fun client() = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
    private fun endpoints() = EspnEndpoints.fromOverride(origin())!!

    private fun perEvent() = EspnScoreSource.production(client(), mono) { endpoints() }
    private fun boardOnly() = EspnScoreSource(client(), mono, { endpoints() })

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private fun gzip(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(body) }
        val b = MockResponse.Builder().code(code).body(out).addHeader("Content-Type", "application/json").addHeader("Content-Encoding", "gzip")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private fun html404() = MockResponse.Builder().code(404).body("<html><body>404</body></html>").addHeader("Content-Type", "text/html").build()

    private fun fixture(name: String) = SportsFixtures.text("sports/nfl/$name")
    private fun idOf(text: String) = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content
    private val live = "sbEvent.json"
    private val pre = "ev_401873050.json"
    private val final = "ev_401873045.json"

    private fun game(id: String) = GameId(League.NFL, id)
    private val startHint = 1_794_172_000_000L // 2026-11-08 ~21:46 UTC: ET 2026-11-08

    // ---------------------------------------------------------------- decode and map

    @Test fun theLivePreAndFinalFixturesMapLikeTheBoardsEvent() {
        for ((name, phase) in listOf(live to GamePhase.LIVE, pre to GamePhase.SCHEDULED, final to GamePhase.FINAL)) {
            val text = fixture(name)
            val id = idOf(text)
            event = { json(text, 200, "Cache-Control" to "max-age=1") }
            board = { json("""{"events":[$text]}""") }
            requests.clear()
            val ev = runBlocking { perEvent().game(game(id), startHint) } as Fetch.Ok
            assertEquals(name, listOf("event:$id"), requests)
            val viaBoard = runBlocking { boardOnly().game(game(id), startHint) } as Fetch.Ok
            assertEquals(name, viaBoard.value, ev.value)
            assertTrue(name, ev.value.phase == phase || (phase == GamePhase.SCHEDULED && ev.value.phase == GamePhase.PRE))
            assertEquals(1, ev.maxAgeS)
        }
    }

    @Test fun theProductionSourceReadsPerEventAndTheTransportTestsDefaultKeepsTheBoard() {
        val text = fixture(live)
        val id = idOf(text)
        event = { json(text) }
        board = { json("""{"events":[$text]}""") }
        runBlocking { boardOnly().game(game(id), startHint) }
        assertTrue(requests.all { it.startsWith("board") })
        requests.clear()
        val s = perEvent()
        runBlocking { s.game(game(id), startHint) }
        assertEquals(listOf("event:$id"), requests)
        assertEquals(PollMeter.NFL_EVENT, s.followEndpoint(game(id)))
        assertEquals(PollMeter.NFL_BOARD, boardOnly().followEndpoint(game(id)))
    }

    @Test fun anotherEventsBodyIsABadBody() {
        val text = fixture(live)
        event = { json(text) }
        val f = runBlocking { perEvent().game(game("401899001"), startHint) } as Fetch.Failed
        assertEquals(FailKind.BAD_BODY, f.kind)
    }

    // ---------------------------------------------------------------- 404 (S4)

    @Test fun aJson404IsCheckedAgainstTheBoardOnce() {
        val text = fixture(live)
        val id = idOf(text)
        // Absent from the board too: NotFound (the two-NotFound rule then removes the follow).
        val s = perEvent()
        assertEquals(Fetch.NotFound, runBlocking { s.game(game(id), startHint) })
        assertEquals("event:$id", requests.first())
        assertTrue(requests.drop(1).all { it.startsWith("board") })
        // Present on the board: its snapshot.
        board = { json("""{"events":[$text]}""") }
        requests.clear()
        val f = runBlocking { perEvent().game(game(id), startHint) }
        assertTrue(f is Fetch.Ok)
        assertEquals(listOf("event:$id", "board?dates=20261108"), requests)
    }

    // ---------------------------------------------------------------- the fallback (S3)

    @Test fun twoJson400sSwitchToTheBoardAndItIsRetriedEveryTenMinutes() {
        val text = fixture(live)
        val id = idOf(text)
        event = { json("""{"code":400,"message":"bad"}""", 400) }
        board = { json("""{"events":[$text]}""") }
        val s = perEvent()
        val first = runBlocking { s.game(game(id), startHint) } as Fetch.Failed
        assertEquals(FailKind.HTTP_OTHER, first.kind)
        assertEquals(PollMeter.NFL_EVENT, s.followEndpoint(game(id)))
        requests.clear()
        val second = runBlocking { s.game(game(id), startHint) }
        assertTrue("the switch reads the board at once", second is Fetch.Ok)
        assertEquals(listOf("event:$id", "board?dates=20261108"), requests)
        assertEquals(PollMeter.NFL_BOARD, s.followEndpoint(game(id)))
        requests.clear()
        nowMono += SportsConstants.PER_EVENT_RETRY_MS - 1
        runBlocking { s.game(game(id), startHint) }
        assertEquals(listOf("board?dates=20261108"), requests)
        // Ten minutes on: per-event once more; it answers, so the follow is back on it.
        event = { json(text) }
        nowMono += 1
        requests.clear()
        assertTrue(runBlocking { s.game(game(id), startHint) } is Fetch.Ok)
        assertEquals(listOf("event:$id"), requests)
        assertEquals(PollMeter.NFL_EVENT, s.followEndpoint(game(id)))
    }

    @Test fun aRetryThatFailsOnceGoesBackToTheBoard() {
        val text = fixture(live)
        val id = idOf(text)
        event = { json("not json") }
        board = { json("""{"events":[$text]}""") }
        val s = perEvent()
        runBlocking { s.game(game(id), startHint); s.game(game(id), startHint) }
        assertEquals(PollMeter.NFL_BOARD, s.followEndpoint(game(id)))
        nowMono += SportsConstants.PER_EVENT_RETRY_MS
        requests.clear()
        assertTrue(runBlocking { s.game(game(id), startHint) } is Fetch.Ok)
        assertEquals(listOf("event:$id", "board?dates=20261108"), requests)
        assertEquals(PollMeter.NFL_BOARD, s.followEndpoint(game(id)))
    }

    @Test fun badBodiesSwitchButNetworkProblemsNeverDo() {
        val text = fixture(live)
        val id = idOf(text)
        board = { json("""{"events":[$text]}""") }
        for ((label, answer) in listOf<Pair<String, () -> MockResponse>>(
            "HTML 404" to { html404() },
            "429" to { json("{}", 429) },
            "503" to { json("{}", 503) },
            "403" to { json("{}", 403) },
            "302" to { MockResponse.Builder().code(302).addHeader("Location", "/nowhere").build() },
        )) {
            event = { answer() }
            val s = perEvent()
            requests.clear()
            repeat(3) { runBlocking { s.game(game(id), startHint) } }
            assertTrue(label, requests.none { it.startsWith("board") })
            assertEquals(label, PollMeter.NFL_EVENT, s.followEndpoint(game(id)))
        }
        // A 403 (a WAF block of a hot client) is reported as a 429: the slow-down, not the fallback.
        event = { json("{}", 403, "Retry-After" to "40") }
        val f = runBlocking { perEvent().game(game(id), startHint) } as Fetch.Failed
        assertEquals(FailKind.HTTP_429, f.kind)
        assertEquals(40, f.retryAfterS)
        // A 200 that does not decode, twice: the board.
        event = { json("""{"competitions":7}""") }
        val s = perEvent()
        requests.clear()
        runBlocking { s.game(game(id), startHint); s.game(game(id), startHint) }
        assertEquals(PollMeter.NFL_BOARD, s.followEndpoint(game(id)))
    }

    @Test fun aNewFollowStartsOnPerEvent() {
        val text = fixture(live)
        event = { json("""{"code":400}""", 400) }
        board = { json("""{"events":[$text]}""") }
        val s = perEvent()
        runBlocking { s.game(game(idOf(text)), startHint); s.game(game(idOf(text)), startHint) }
        assertEquals(PollMeter.NFL_BOARD, s.followEndpoint(game(idOf(text))))
        requests.clear()
        runBlocking { s.game(game("401873050"), startHint) }
        assertEquals("event:401873050", requests.first())
    }

    // ---------------------------------------------------------------- headers and the wire

    @Test fun maxAgeIsSMaxageFirst() {
        val text = fixture(live)
        val id = idOf(text)
        event = { json(text, 200, "Cache-Control" to "no-transform, max-age=5, s-maxage=19") }
        assertEquals(19, (runBlocking { perEvent().game(game(id), startHint) } as Fetch.Ok).maxAgeS)
        event = { json(text, 200, "Cache-Control" to "public, max-age=7") }
        assertEquals(7, (runBlocking { perEvent().game(game(id), startHint) } as Fetch.Ok).maxAgeS)
        event = { json(text) }
        assertNull((runBlocking { perEvent().game(game(id), startHint) } as Fetch.Ok).maxAgeS)
    }

    @Test fun wireBytesAreTheCompressedSize() {
        val text = fixture(live)
        val id = idOf(text)
        val zipped = Buffer().also { b -> GzipSink(b).buffer().use { it.writeUtf8(text) } }.size
        event = { gzip(text, 200, "Cache-Control" to "max-age=1") }
        val f = runBlocking { perEvent().game(game(id), startHint) } as Fetch.Ok
        assertEquals(zipped, f.wireBytes)
        assertTrue("gzip is smaller: $zipped < ${text.length}", zipped < text.length)
        event = { json(text) }
        assertEquals(text.encodeToByteArray().size.toLong(), (runBlocking { perEvent().game(game(id), startHint) } as Fetch.Ok).wireBytes)
    }

    @Test fun aGzip400IsReadAsJsonAndCountsAsAShapeFailure() {
        val text = fixture(live)
        val id = idOf(text)
        event = { gzip("""{"code":400,"message":"Invalid eventId"}""", 400, "Cache-Control" to "max-age=3019") }
        board = { json("""{"events":[$text]}""") }
        val s = perEvent()
        runBlocking { s.game(game(id), startHint); s.game(game(id), startHint) }
        assertEquals(PollMeter.NFL_BOARD, s.followEndpoint(game(id)))
    }

    private companion object {
        const val BOARD = "/apis/site/v2/sports/football/nfl/scoreboard"
    }
}
