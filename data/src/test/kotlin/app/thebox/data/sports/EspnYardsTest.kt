package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FollowExtras
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.NflYardsFixtures
import app.thebox.core.sports.SportsConstants
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * (NFL YDS §1.7, §4.2; S3, S4, S5) The yards step of [EspnScoreSource] against a scripted server: the statistics path for
 * both teams in parallel, attached only to stoppage snapshots, the retry / stall / block rules, the catch-all, and the
 * board's answer untouched by any of it. Boards are the 2026-11-16 live2 fixtures (LAC 24 @ BAL 33, event 401873065).
 */
class EspnYardsTest {
    private lateinit var server: MockWebServer
    /** (arrival ms, path) of every statistics request. */
    private val stats = CopyOnWriteArrayList<Pair<Long, String>>()
    private val boards = CopyOnWriteArrayList<String>()

    /** The board body every scoreboard request gets (dated board, `{"events":[…]}`). */
    @Volatile private var board: String = NflYardsFixtures.text(NflYardsFixtures.OFFICIAL_TIMEOUT)
    /** The statistics answer per team id. */
    @Volatile private var statsAnswer: (String) -> MockResponse = { team -> total(if (team == AWAY) 142 else 51) }

    /** Real elapsed time plus [jump], so the planner's 15 s / 30 s / 10 min steps can be skipped and `sideMs` is real. */
    @Volatile private var jump = 0L
    private val t0 = System.nanoTime()
    private val mono = MonoClock { jump + (System.nanoTime() - t0) / 1_000_000L }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.startsWith(STATS_PREFIX) -> {
                        stats += System.nanoTime() / 1_000_000L to path
                        val team = path.removePrefix(STATS_PREFIX).substringBefore('/')
                        statsAnswer(team)
                    }
                    path.startsWith("/apis/site/v2/sports/football/nfl/scoreboard") -> {
                        boards += path
                        json(board)
                    }
                    else -> json("""{"code":404}""", 404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun origin() = server.url("/").toString().trimEnd('/')

    private fun source(statsYards: ((app.thebox.core.sports.EspnStatsDto) -> Int?)? = null): EspnScoreSource {
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
        val endpoints = { EspnEndpoints.fromOverride(origin())!! }
        return if (statsYards == null) {
            EspnScoreSource(client, mono, endpoints, callTimeoutMs = 10_000L)
        } else {
            EspnScoreSource(client, mono, endpoints, callTimeoutMs = 10_000L, statsYards = statsYards)
        }
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun total(v: Int): MockResponse = json(
        """{"splits":{"categories":[{"name":"general","stats":[{"name":"fumbles","value":1.0,"displayValue":"1"}]},""" +
            """{"name":"passing","stats":[{"name":"totalYards","value":$v.0,"displayValue":"$v"}]}]}}""",
    )

    private val start = Instant.parse("2026-11-17T01:15:00Z").toEpochMilli()
    private val on = FollowExtras(nflYards = true)

    private fun poll(s: EspnScoreSource, extras: FollowExtras = on): Fetch<GameSnapshot> =
        runBlocking { s.game(NflYardsFixtures.LAC_AT_BAL, start, extras) }

    private fun yards(f: Fetch<GameSnapshot>): Pair<Int?, Int?> =
        ((f as Fetch.Ok).value.detail as LeagueDetail.Nfl).let { it.awayYards to it.homeYards }

    private val none = null to null

    // ---------------------------------------------------------------- the path, the overlap, the snapshot

    @Test fun bothTeamsAreFetchedInParallelAndAttachedToTheStoppageSnapshot() {
        // (Code review S2) 1 s delays and wide bounds: the overlap must hold on a loaded runner too.
        statsAnswer = { team -> total(if (team == AWAY) 142 else 51).newBuilder().headersDelay(1_000, TimeUnit.MILLISECONDS).build() }
        val f = poll(source())
        assertTrue(f is Fetch.Ok)
        assertEquals(142 to 51, yards(f))
        assertEquals(
            setOf("$STATS_PREFIX$AWAY/statistics", "$STATS_PREFIX$HOME/statistics"),
            stats.map { it.second }.toSet(),
        )
        assertEquals(2, stats.size)
        val (a, b) = stats.map { it.first }
        assertTrue("the second call arrived before the first's 1 s delay ended", kotlin.math.abs(a - b) < 1_000)
        val side = (f as Fetch.Ok).sideMs
        assertTrue("in parallel (≥ 1 s, not 2 × 1 s): $side", side in 1_000 until 1_900)

        // wireBytes is the board's only: the same poll without the extras reports the same bytes.
        val plain = poll(source(), FollowExtras.NONE) as Fetch.Ok
        assertEquals(plain.wireBytes, f.wireBytes)
        assertEquals(0L, plain.sideMs)
    }

    @Test fun theRealStatisticsBodyIsRead() {
        val body = NflYardsFixtures.text(NflYardsFixtures.STATS_FINAL)
        val neg = NflYardsFixtures.text(NflYardsFixtures.STATS_NEGATIVE)
        statsAnswer = { team -> json(if (team == AWAY) body else neg) }
        assertEquals(123 to -2, yards(poll(source())))
    }

    @Test fun noRequestWithoutTheExtrasOutsideAStoppageOrWithoutIds() {
        val s = source()
        val off = poll(s, FollowExtras.NONE)
        assertEquals(none, yards(off))
        assertTrue(stats.isEmpty())

        board = NflYardsFixtures.text(NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS) // in play: no key
        assertEquals(none, yards(poll(s)))
        assertTrue(stats.isEmpty())

        // Competitor ids that are not 1–6 digits: nothing is fetched.
        board = NflYardsFixtures.text(NflYardsFixtures.OFFICIAL_TIMEOUT).replace("\"id\":\"24\"", "\"id\":\"x24\"").replace("\"id\":\"33\"", "\"id\":\"x33\"")
        val noIds = source()
        val f = poll(noIds)
        assertTrue(f is Fetch.Ok)
        assertEquals(none, yards(f))
        assertTrue(stats.isEmpty())
    }

    @Test fun yardsAttachOnlyToStoppageSnapshotsAndTheHeldStoppageIsNotRefetched() {
        val s = source()
        assertEquals(142 to 51, yards(poll(s)))
        board = NflYardsFixtures.text(NflYardsFixtures.OFFICIAL_TIMEOUT_HELD)
        jump += 10_000
        assertEquals("the same 74, carried", 142 to 51, yards(poll(s)))
        assertEquals(2, stats.size)
        board = NflYardsFixtures.text(NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS)
        jump += 10_000
        assertEquals("the next play has none", none, yards(poll(s)))
        assertEquals(2, stats.size)
    }

    @Test fun theChangeOfEndsIsOneStoppageWithTheEndPeriodSample() {
        val s = source()
        board = NflYardsFixtures.text(NflYardsFixtures.END_PERIOD)
        assertEquals(142 to 51, yards(poll(s)))
        board = NflYardsFixtures.text(NflYardsFixtures.CHANGE_OF_ENDS)
        jump += 10_000
        assertEquals(142 to 51, yards(poll(s)))
        assertEquals("E1 both times: one fetch", 2, stats.size)
    }

    @Test fun theRefreshIsUsedOnlyFromAPollTwentySecondsAfterIt() {
        val s = source()
        assertEquals(142 to 51, yards(poll(s)))
        statsAnswer = { team -> total(if (team == AWAY) 150 else 51) }
        jump += SportsConstants.NFL_YDS_REFRESH_MS
        assertEquals("refresh read, not yet used", 142 to 51, yards(poll(s)))
        assertEquals(4, stats.size)
        jump += SportsConstants.NFL_YDS_CONFIRM_MS - 2_000
        assertEquals("19 s later: still not used", 142 to 51, yards(poll(s)))
        jump += 3_000
        assertEquals("≥ 20 s later in the same stoppage: used", 150 to 51, yards(poll(s)))
        jump += 60_000
        poll(s)
        assertEquals("one fetch and one refresh only", 4, stats.size)
    }

    /**
     * (Integration ruling, live2 2026-11-16 21:25–21:45) At a team timeout (`"T"` key) the refresh is due 12 s after the
     * first good fetch and used from the first poll at least 5 s after it, still in the timeout; the official timeout
     * above keeps 30 s / 20 s.
     */
    @Test fun aTeamTimeoutRefreshesAtTwelveSecondsAndUsesItFiveSecondsLater() {
        // The live2 official-timeout board re-typed as a team timeout (type 21, `"T"` key).
        board = NflYardsFixtures.text(NflYardsFixtures.OFFICIAL_TIMEOUT).replace("\"id\":\"74\"", "\"id\":\"21\"")
        val s = source()
        statsAnswer = { team -> total(if (team == AWAY) 110 else 51) }
        assertEquals(110 to 51, yards(poll(s)))
        assertEquals(2, stats.size)
        statsAnswer = { team -> total(if (team == AWAY) 118 else 51) }
        jump += SportsConstants.NFL_YDS_TIMEOUT_REFRESH_MS - 1_000
        assertEquals("11 s: no refresh yet", 110 to 51, yards(poll(s)))
        assertEquals(2, stats.size)
        jump += 1_500
        assertEquals("refresh read at ≥ 12 s, not yet used", 110 to 51, yards(poll(s)))
        assertEquals(4, stats.size)
        jump += SportsConstants.NFL_YDS_TIMEOUT_CONFIRM_MS - 1_000
        assertEquals("4 s later: still not used", 110 to 51, yards(poll(s)))
        jump += 1_500
        assertEquals("≥ 5 s later in the same timeout: used", 118 to 51, yards(poll(s)))
        jump += 60_000
        poll(s)
        assertEquals("one fetch and one refresh only", 4, stats.size)
    }

    // ---------------------------------------------------------------- failures (S4)

    @Test fun retryableFailuresGiveNoYardsAndRetryAtFifteenSeconds() {
        val answers = listOf<Pair<String, (String) -> MockResponse>>(
            "404 JSON" to { _ -> json("""{"error":{"code":404,"message":"no instance found"}}""", 404) },
            "404 HTML" to { _ -> MockResponse.Builder().code(404).body("<html>nope</html>").addHeader("Content-Type", "text/html").build() },
            "500" to { _ -> json("{}", 500) },
            "bad body" to { _ -> json("""{"splits":[1,2""") },
            "no total" to { team -> if (team == AWAY) total(10) else json(NflYardsFixtures.text(NflYardsFixtures.STATS_NO_TOTAL)) },
        )
        for ((name, answer) in answers) {
            stats.clear()
            statsAnswer = answer
            val s = source()
            val f = poll(s)
            assertTrue(name, f is Fetch.Ok)
            assertEquals(name, none, yards(f))
            assertEquals(name, 2, stats.size)
            jump += SportsConstants.NFL_YDS_RETRY_MS - 1_000
            assertEquals(name, none, yards(poll(s)))
            assertEquals("$name: not before 15 s", 2, stats.size)
            jump += 1_000
            statsAnswer = { team -> total(if (team == AWAY) 142 else 51) }
            assertEquals("$name: retried at 15 s", 142 to 51, yards(poll(s)))
            assertEquals(name, 4, stats.size)
        }
    }

    @Test fun aStallEndsTheStoppageAndTwoInARowBlock() {
        statsAnswer = { team -> total(if (team == AWAY) 142 else 51).newBuilder().headersDelay(3, TimeUnit.SECONDS).build() }
        val s = source()
        val t = System.nanoTime()
        val f = poll(s)
        val took = (System.nanoTime() - t) / 1_000_000L
        assertTrue(f is Fetch.Ok)
        assertEquals(none, yards(f))
        // (Code review S2) Cut by the 2.5 s statistics call timeout, not the 10 s one; the upper bound leaves room for a load.
        assertTrue("cut at ≈ 2.5 s: $took ms", took in 2_400L..3_500L)
        assertEquals(2, stats.size)
        jump += 60_000
        poll(s)
        assertEquals("no retry in that stoppage", 2, stats.size)

        // A second stoppage stalls too: blocked for 10 min.
        board = NflYardsFixtures.text(NflYardsFixtures.END_PERIOD)
        jump += 60_000
        assertEquals(none, yards(poll(s)))
        assertEquals(4, stats.size)
        board = NflYardsFixtures.text(NflYardsFixtures.CHANGE_OF_ENDS).replace("\"period\":2", "\"period\":4") // E3
        statsAnswer = { team -> total(if (team == AWAY) 142 else 51) }
        jump += 60_000
        val blocked = poll(s)
        assertTrue(blocked is Fetch.Ok)
        assertEquals(none, yards(blocked))
        assertEquals("blocked", 4, stats.size)
        jump += SportsConstants.NFL_YDS_BLOCK_MS
        assertEquals("after 10 min", 142 to 51, yards(poll(s)))
        assertEquals(6, stats.size)
    }

    @Test fun a429OrA403BlocksTheYardsButNeverTheBoard() {
        for (code in listOf(429, 403)) {
            stats.clear()
            boards.clear()
            board = NflYardsFixtures.text(NflYardsFixtures.OFFICIAL_TIMEOUT)
            statsAnswer = { _ -> json("{}", code) }
            val s = source()
            val f = poll(s)
            assertTrue("$code: the board's Ok, not a 429 (the follow is not slowed)", f is Fetch.Ok)
            assertEquals(none, yards(f))
            assertEquals(2, stats.size)
            statsAnswer = { team -> total(if (team == AWAY) 142 else 51) }
            board = NflYardsFixtures.text(NflYardsFixtures.END_PERIOD)
            for (i in 1..5) {
                jump += 60_000
                assertTrue(poll(s) is Fetch.Ok)
            }
            assertEquals("$code: no statistics request for 10 min", 2, stats.size)
            assertEquals("$code: the board polled every time", 6, boards.size)
            jump += SportsConstants.NFL_YDS_BLOCK_MS
            assertEquals(142 to 51, yards(poll(s)))
        }
    }

    @Test fun aThrowableInTheStepNeverEscapesAndTheBoardIsDelivered() {
        for (boom in listOf<(app.thebox.core.sports.EspnStatsDto) -> Int?>({ error("mapper bug") }, { throw StackOverflowError() })) {
            stats.clear()
            val s = source(statsYards = boom)
            val f = poll(s)
            assertTrue(f is Fetch.Ok)
            assertEquals("LAC", (f as Fetch.Ok).value.away.abbrev)
            assertEquals(none, yards(f))
            assertEquals(2, stats.size)
        }
    }

    // ---------------------------------------------------------------- endpoints, ids, logs

    @Test fun theOverrideMapsTheStatisticsBase() {
        assertEquals("http://10.0.2.2:8080/v2/sports/football/leagues/nfl/", EspnEndpoints.fromOverride("http://10.0.2.2:8080/x?y=1")?.statsBase)
        assertEquals("https://sports.core.api.espn.com/v2/sports/football/leagues/nfl/", EspnEndpoints.DEFAULT.statsBase)
    }

    @Test fun thePerEventFollowCapturesTheIds() {
        val event = Json.parseToJsonElement(NflYardsFixtures.text(NflYardsFixtures.OFFICIAL_TIMEOUT)).jsonObject["events"]!!.jsonArray.single().toString()
        board = event // the per-event endpoint answers the bare event
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
        val s = EspnScoreSource.production(client, mono) { EspnEndpoints.fromOverride(origin())!! }
        assertEquals(142 to 51, yards(poll(s)))
        assertTrue(boards.single().endsWith("/scoreboard/401873065"))
    }

    @Test fun theLogsCarryNoIds() {
        val logs = CopyOnWriteArrayList<String>()
        val saved = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { _, tag, message, error -> logs += "$tag $message ${error?.message.orEmpty()}" }
        try {
            val s = source()
            poll(s) // ok
            statsAnswer = { _ -> json("{}", 500) }
            board = NflYardsFixtures.text(NflYardsFixtures.END_PERIOD)
            jump += 60_000
            poll(s) // 5xx
            source(statsYards = { error("boom") }).let { poll(it) }
            board = board.replace("\"id\":\"24\"", "\"id\":\"x24\"").replace("\"id\":\"33\"", "\"id\":\"x33\"")
            poll(source()) // no ids
        } finally {
            BoxLog.sink = saved
        }
        assertTrue(logs.any { "statistics" in it })
        for (line in logs) {
            assertFalse(line, "401873065" in line)
            assertFalse(line, "competitors/24" in line || "competitors/33" in line)
            assertFalse(line, "http" in line)
        }
        assertNull(logs.firstOrNull { "x24" in it || "x33" in it })
    }

    private companion object {
        const val AWAY = "24"
        const val HOME = "33"
        const val STATS_PREFIX = "/v2/sports/football/leagues/nfl/events/401873065/competitions/401873065/competitors/"
    }
}
