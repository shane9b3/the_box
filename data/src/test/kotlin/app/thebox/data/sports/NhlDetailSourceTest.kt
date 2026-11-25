package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.HubFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** docs/design/m6-sports-hub.md §4.2, §10.2: parallel calls, a failed landing, caps, cancel, the box score. */
class NhlDetailSourceTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val answers = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()
    private val mono = MonoClock { 7_000L }
    private val uta = GameId(League.NHL, "2026020382")

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

    private fun source(callTimeoutMs: Long = 10_000L) = NhlDetailSource(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
        mono,
        endpoints = { SportsEndpoints.fromOverride(server.url("/").toString().trimEnd('/'))!! },
        callTimeoutMs = callTimeoutMs,
    )

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private val rrPath = "/v1/gamecenter/2026020382/right-rail"
    private val landingPath = "/v1/gamecenter/2026020382/landing"

    @Test fun bothCallsGoOutTogetherAndTheNewerDateWins() {
        answers[rrPath] = { json(SportsFixtures.text(HubFixtures.GC_382_RIGHT_RAIL), 200, "Date" to "Tue, 24 Nov 2026 03:00:00 GMT", "Age" to "4") }
        answers[landingPath] = {
            json(SportsFixtures.text(HubFixtures.GC_382_LANDING), 200, "Date" to "Tue, 24 Nov 2026 03:00:10 GMT").newBuilder()
                .headersDelay(300, TimeUnit.MILLISECONDS).build()
        }
        val a = runBlocking { source().detailAnswer(uta, GamePhase.FINAL) }
        assertEquals(setOf(rrPath, landingPath), requests.map { it.url.encodedPath }.toSet())
        val ok = a.fetch as Fetch.Ok
        assertFalse(a.landingFailed)
        assertEquals(1_795_489_210_000L, ok.value.serverDateMs)
        assertEquals(ok.value.serverDateMs, ok.serverDateMs)
        assertEquals(4, ok.ageS)
        assertEquals(7_000L, ok.receivedMono)
        assertEquals(6, ok.value.goals.size)
        assertEquals("Canada Life Centre", ok.value.venue)
        assertTrue(requests.all { it.headers["Accept"] == "application/json" && it.headers["Cookie"] == null })
    }

    @Test fun parallelNotSequential() = runBlocking {
        answers[rrPath] = { json(SportsFixtures.text(HubFixtures.GC_382_RIGHT_RAIL)).newBuilder().headersDelay(700, TimeUnit.MILLISECONDS).build() }
        answers[landingPath] = { json(SportsFixtures.text(HubFixtures.GC_382_LANDING)).newBuilder().headersDelay(700, TimeUnit.MILLISECONDS).build() }
        val t0 = System.nanoTime()
        val a = source().detailAnswer(uta, null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(a.fetch is Fetch.Ok)
        assertTrue("both calls overlap ($ms ms)", ms < 1_300)
    }

    @Test fun aFailedLandingKeepsTheDetailAndIsReported() {
        answers[rrPath] = { json(SportsFixtures.text(HubFixtures.GC_382_RIGHT_RAIL)) }
        answers[landingPath] = { json("""{"message":"x"}""", 503) }
        val a = runBlocking { source().detailAnswer(uta, GamePhase.FINAL) }
        assertTrue(a.landingFailed)
        val d = (a.fetch as Fetch.Ok).value
        assertTrue(d.goals.isEmpty() && d.stars.isEmpty())
        assertEquals(8, d.gameStats.size)
        assertEquals(GamePhase.FINAL, d.fetchedPhase)
    }

    @Test fun aFailedRightRailIsTheResult() {
        answers[rrPath] = { json("""{}""", 429, "Retry-After" to "30") }
        answers[landingPath] = { json(SportsFixtures.text(HubFixtures.GC_382_LANDING)) }
        val f = runBlocking { source().detail(uta) }
        assertEquals(FailKind.HTTP_429, (f as Fetch.Failed).kind)
        assertEquals(30, f.retryAfterS)

        answers[rrPath] = { json("""{"message":"Not Found"}""", 404) }
        assertEquals(Fetch.NotFound, runBlocking { source().detail(uta) })

        answers[rrPath] = { json("""{"nothing":"known"}""") }
        assertEquals(FailKind.BAD_BODY, (runBlocking { source().detail(uta) } as Fetch.Failed).kind)

        answers[rrPath] = { json("<html>", 200) }
        assertEquals(FailKind.BAD_BODY, (runBlocking { source().detail(uta) } as Fetch.Failed).kind)
    }

    @Test fun bodiesAreCapped() {
        val pad = "x".repeat((SportsConstants.MAX_BODY_BYTES + 10).toInt())
        answers[rrPath] = { json("""{"linescore":{},"pad":"$pad"}""") }
        answers[landingPath] = { json(SportsFixtures.text(HubFixtures.GC_382_LANDING)) }
        assertEquals(FailKind.BAD_BODY, (runBlocking { source().detail(uta) } as Fetch.Failed).kind)
    }

    @Test fun cancellingTheCallerCancelsBothCalls() = runBlocking {
        answers[rrPath] = { json(SportsFixtures.text(HubFixtures.GC_382_RIGHT_RAIL)).newBuilder().headersDelay(5, TimeUnit.SECONDS).build() }
        answers[landingPath] = { json(SportsFixtures.text(HubFixtures.GC_382_LANDING)).newBuilder().headersDelay(5, TimeUnit.SECONDS).build() }
        val s = source()
        val t0 = System.nanoTime()
        val job = async(Dispatchers.IO) { s.detailAnswer(uta, null) }
        delay(300)
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertTrue("cancel returns at once", (System.nanoTime() - t0) / 1_000_000 < 3_000)
    }

    @Test fun invalidIdsMakeNoRequest() {
        val s = source()
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.detail(GameId(League.NFL, "401873049")) } as Fetch.Failed).kind)
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.detail(GameId(League.NHL, "../x")) } as Fetch.Failed).kind)
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.boxScore(GameId(League.NHL, "1/2")) } as Fetch.Failed).kind)
        assertTrue(requests.isEmpty())
    }

    @Test fun boxScore() {
        answers["/v1/gamecenter/2026020382/boxscore"] = { json(SportsFixtures.text(HubFixtures.GC_382_BOXSCORE)) }
        val b = (runBlocking { source().boxScore(uta) } as Fetch.Ok).value
        assertEquals(18, b.away.skaters.size)
        assertEquals(listOf("/v1/gamecenter/2026020382/boxscore"), requests.map { it.url.encodedPath })
        answers["/v1/gamecenter/2026020382/boxscore"] = { json("{}", 500) }
        assertEquals(FailKind.HTTP_5XX, (runBlocking { source().boxScore(uta) } as Fetch.Failed).kind)
    }
}
