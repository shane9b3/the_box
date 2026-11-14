package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.TeamLine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The CHL transport against a scripted HockeyTech (docs/design/m6-chl-leagues.md §2, §7.2): the request plan, the 304
 * reuse, partial failures, every §2.6 mapping, S5, the board fallbacks, the overlay, logo validation and the redaction
 * rules (no key, URL, body or exception text in any log line). The real `HockeyTechMapper` maps the recorded fixtures.
 */
class HockeyTechScoreSourceTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Firebase nodes by `"{client}/{node}/{game id}"`; absent = JSON `null`. */
    private val nodes = HashMap<String, String>()
    /** A scripted answer per Firebase node name (`penalties`, …) replacing the node. */
    private val nodeFaults = HashMap<String, () -> MockResponse>()
    /** The date-query answers by node name; absent = `{}`. */
    private val overlays = HashMap<String, () -> MockResponse>()
    /** `lscluster` answers by `view`; absent = the recorded fixture (or an empty board for another date). */
    private val feedFaults = HashMap<String, () -> MockResponse>()
    /** Edits applied to a recorded `gamesbydate` body before it is served. */
    private var boardEdit: (JsonObject) -> JsonObject = { it }

    private var nowMono = 1_000_000L
    private val mono = MonoClock { nowMono }
    /** 2026-11-13 21:00 Toronto (18:00 Vancouver): "today" is 2026-11-13 for all three leagues. */
    private var wallMs = at("2026-11-13T21:00", "America/Toronto")

    private val logs = CopyOnWriteArrayList<String>()
    private val logThrowables = CopyOnWriteArrayList<Throwable>()
    private var oldSink: BoxLog.Sink? = null

    @Before fun start() {
        oldSink = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { _, tag, message, t ->
            logs += "$tag $message"
            if (t != null) logThrowables += t
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    @After fun stop() {
        server.close()
        BoxLog.sink = oldSink!!
    }

    private fun at(local: String, zone: String): Long = LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun origin() = server.url("/").toString().trimEnd('/')

    private fun source(
        league: League = League.OHL,
        callTimeoutMs: Long = 10_000L,
        endpoints: () -> HockeyTechEndpoints = { HockeyTechEndpoints.fromOverride(origin())!! },
    ) = HockeyTechScoreSource(
        league,
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
        mono,
        endpoints = endpoints,
        wall = { wallMs },
        callTimeoutMs = callTimeoutMs,
    )

    // ---------------------------------------------------------------- the scripted server

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json; charset=utf-8")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private fun text(body: String, code: Int = 200, type: String = "text/html; charset=UTF-8"): MockResponse =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", type).build()

    private fun answer(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath
        return when {
            path == "/hockeytech/feed/index.php" -> feed(r)
            path.startsWith("/firebase/svf/") -> firebase(r, path.removePrefix("/firebase/svf/"))
            else -> text("<html>404</html>", 404)
        }
    }

    private fun clientOf(league: League) = HockeyTechEndpoints.client(league)

    private fun feed(r: RecordedRequest): MockResponse {
        val q = { k: String -> r.url.queryParameter(k) }
        val client = q("client_code") ?: return text("Invalid key.")
        val key = q("key")
        // The fake panel's rule: the key of the client, else "Client access denied." for another client's key.
        val valid = listOf(League.OHL, League.WHL, League.QMJHL).associateBy { HockeyTechEndpoints.fakeKey(it) }
        val owner = valid[key] ?: return text("Invalid key.")
        if (clientOf(owner) != client) return text("Client access denied.")
        val view = q("view") ?: return text("Invalid key.")
        feedFaults[view]?.let { return it() }
        return when (view) {
            "gamesbydate" -> if (q("fetch_date") == "2026-11-13") {
                val board = Json.parseToJsonElement(SportsFixtures.text("sports/chl/lscluster/gamesbydate-$client-2026-11-13.json")).jsonObject
                json(boardEdit(board).toString())
            } else {
                json("""{"SiteKit":{"Parameters":{},"Gamesbydate":[]}}""")
            }
            "teamsbyseason" -> json(
                SportsFixtures.text("sports/chl/lscluster/teamsbyseason-$client-2026-27.json")
                    .replace("https:\\/\\/assets.leaguestat.com\\/", "${origin()}/leaguestat/"),
            )
            "seasons" -> json(SportsFixtures.text("sports/chl/lscluster/seasons-$client.json"))
            else -> text("Invalid key.")
        }
    }

    private fun firebase(r: RecordedRequest, rest: String): MockResponse {
        val client = rest.substringBefore('/')
        val sub = rest.substringAfter('/')
        val node = sub.substringBefore('/')
        if (r.url.queryParameter("orderBy") != null) {
            if (r.headers["X-Firebase-ETag"] != null) {
                return json("""{"error" : "Mixing querying parameters and ETag requests is not supported"}""", 400)
            }
            return overlays[node]?.invoke() ?: json("{}")
        }
        nodeFaults[node]?.let { return it() }
        val id = sub.substringAfterLast('/').removeSuffix(".json")
        val body = nodes["$client/$node/$id"] ?: "null"
        val etag = "e" + Integer.toHexString(body.hashCode())
        val wantsTag = r.headers["X-Firebase-ETag"] == "true"
        if (wantsTag && r.headers["If-None-Match"] == etag) {
            return MockResponse.Builder().code(304).addHeader("ETag", etag).build()
        }
        return if (wantsTag) json(body, 200, "ETag" to etag) else json(body)
    }

    // ---------------------------------------------------------------- node bodies

    private fun rc(period: Int = 2, min: String = "10", sec: String = "30", running: Boolean = true, status: Int = 2) =
        """{"Clock":{"Minutes":"$min","Running":$running,"Seconds":"$sec","period":$period},"DatePlayed":"2026-11-13","status_id":$status}"""

    private fun pc(period: Int = 2, status: Int = 2, final: Boolean = false) =
        """{"ClockMinutes":"10","ClockSeconds":"30","DatePlayed":"2026-11-13","Final":$final,"PeriodId":$period,"Started":true,"StatusId":$status}"""

    private fun gs(away: Int = 1, home: Int = 2, awayAtt: Int = 1, homeAtt: Int = 1, shootout: Boolean = false) =
        """{"DatePlayed":"2026-11-13","VisitorGoalTotal":$away,"HomeGoalTotal":$home,"VisitorPowerPlayAttempts":$awayAtt,"HomePowerPlayAttempts":$homeAtt,"Shootout":$shootout}"""

    private fun ss(away: Int = 11, home: Int = 14) = """{"DatePlayed":"2026-11-13","VisitorShotTotal":$away,"HomeShotTotal":$home}"""

    /** Goals as `(home, period, "mm:ss")`, ids 1, 2, … */
    private fun goals(vararg g: Triple<Boolean, Int, String>): String = g.mapIndexed { i, (home, p, t) ->
        """"${i + 1}":{"IsHome":${if (home) 1 else 0},"Period":$p,"Time":"$t","PowerPlay":0,"ShortHanded":0,"EmptyNet":0,"PenaltyShot":0,"LSEventId":${i + 1}}"""
    }.joinToString(",", """{"GameGoals":{""", "}}")

    private val threeGoals = goals(Triple(false, 1, "05:00"), Triple(true, 1, "08:00"), Triple(true, 2, "03:00"))

    /** An away two-minute power-play minor at period 2, 09:00 elapsed (HOME has the advantage until 11:00). */
    private val awayMinor =
        """{"DatePlayed":"","GamePenalties":{"10":{"Bench":0,"Home":0,"LSEventId":10,"Minutes":2,"Offence":1,"PenaltyShot":0,"Period":2,"PowerPlay":1,"Time":"09:00","TimeOn":"","TimeOnSet":0,"TimeStart":"09:00","TimeStartSet":1,"PenalizedPlayerId":55,"ServedPlayerId":55}}}"""

    private val noPenalties = """{"DatePlayed":"","GamePenalties":{}}"""

    /** OHL 29191 PBO @ OSH (the board says status 2) live in period 2 at 10:30 remaining, 1–2, an away minor serving. */
    private fun liveGame(client: String = "ohl", id: String = "29191", penalties: String? = awayMinor, clock: String = rc()) {
        nodes["$client/runningclock/$id"] = clock
        nodes["$client/publishedclock/$id"] = pc()
        nodes["$client/goalssummary/$id"] = gs()
        nodes["$client/shotssummary/$id"] = ss()
        penalties?.let { nodes["$client/penalties/$id"] = it }
        nodes["$client/goals/$id"] = threeGoals
    }

    private val ohl29191 = GameId(League.OHL, "29191")
    private val start29191 = at("2026-11-13T19:05", "America/Toronto")

    private fun fb(): List<RecordedRequest> = requests.filter { it.url.encodedPath.startsWith("/firebase/") }
    private fun fbNodes(): List<RecordedRequest> = fb().filter { it.url.queryParameter("orderBy") == null }
    private fun feeds(view: String): List<RecordedRequest> =
        requests.filter { it.url.encodedPath == "/hockeytech/feed/index.php" && it.url.queryParameter("view") == view }
    private fun nodeName(r: RecordedRequest) = r.url.encodedPath.removePrefix("/firebase/svf/").substringAfter('/').substringBefore('/')

    private fun ok(f: Fetch<GameSnapshot>): GameSnapshot {
        assertTrue("expected Ok, was $f", f is Fetch.Ok)
        return (f as Fetch.Ok).value
    }

    private fun failed(f: Fetch<*>): FailKind {
        assertTrue("expected Failed, was $f", f is Fetch.Failed)
        return (f as Fetch.Failed).kind
    }

    private fun board(f: Fetch<DayBoard>): DayBoard {
        assertTrue("expected Ok, was $f", f is Fetch.Ok)
        return (f as Fetch.Ok).value
    }

    // ---------------------------------------------------------------- the request plan (§2.4, S4)

    @Test fun aLivePollRequestsTheSixPerGameNodesInParallelWithEtagHeaders() {
        liveGame()
        val s = source()
        val snap = ok(runBlocking { s.game(ohl29191, start29191) })
        val n = fbNodes()
        assertEquals(
            setOf("runningclock/games/29191", "publishedclock/1/games/29191", "goalssummary/1/games/29191",
                "shotssummary/1/games/29191", "penalties/1/games/29191", "goals/1/games/29191"),
            n.map { it.url.encodedPath.removePrefix("/firebase/svf/ohl/").removeSuffix(".json") }.toSet(),
        )
        assertEquals(6, n.size)
        assertTrue(n.all { it.headers["X-Firebase-ETag"] == "true" && it.headers["If-None-Match"] == null })
        // Mapped from the per-game objects: the score, the shots, the derived power play, the full names (P10).
        assertEquals(GamePhase.LIVE, snap.phase)
        assertEquals(1 to 2, snap.away.score to snap.home.score)
        assertEquals(11 to 14, snap.away.sog to snap.home.sog)
        assertEquals("Peterborough Petes", snap.away.name)
        assertEquals("OSH", snap.home.abbrev)
        assertEquals(Side.HOME, snap.powerPlay?.side)
        assertEquals(PowerPlayKind.ONE_MAN, snap.powerPlay?.kind)
        assertEquals(90, snap.powerPlay?.secondsRemaining)
        assertFalse(snap.powerPlayUnknown)
        assertFalse(snap.startTbd)
        assertEquals(PollMeter.CHL_LIVE, s.followEndpoint(ohl29191))
        // Once per process: the board, its teams and the seasons; no Firebase date query for a followed game.
        assertEquals(1, feeds("gamesbydate").size)
        assertEquals(1, feeds("teamsbyseason").size)
        assertEquals(1, feeds("seasons").size)
    }

    @Test fun theNextPollSendsIfNoneMatchAndReusesEach304Body() {
        liveGame()
        val s = source()
        ok(runBlocking { s.game(ohl29191, start29191) })
        val first = fbNodes().associate { nodeName(it) to it }
        requests.clear()
        nowMono += 5_000
        nodes["ohl/runningclock/29191"] = rc(min = "10", sec = "25")
        val snap = ok(runBlocking { s.game(ohl29191, start29191) })
        val second = fbNodes()
        assertEquals(6, second.size)
        assertTrue(second.all { it.headers["If-None-Match"]?.startsWith("e") == true })
        assertEquals(6, first.size)
        // Five 304s: the bodies they confirmed are still mapped (penalties: the power play, now 85 s; shots; score).
        assertEquals(Side.HOME, snap.powerPlay?.side)
        assertEquals(85, snap.powerPlay?.secondsRemaining)
        assertEquals(11 to 14, snap.away.sog to snap.home.sog)
        assertEquals(1 to 2, snap.away.score to snap.home.score)
        assertEquals(625, snap.clock?.secondsRemaining)
    }

    @Test fun wireBytesAreTheSumOfThePollsBodiesAndA304AddsNone() {
        liveGame()
        val s = source()
        val first = runBlocking { s.game(ohl29191, start29191) } as Fetch.Ok
        val expected = listOf(rc(), pc(), gs(), ss(), awayMinor, threeGoals).sumOf { it.toByteArray().size.toLong() }
        assertEquals(expected, first.wireBytes)
        nowMono += 5_000
        val second = runBlocking { s.game(ohl29191, start29191) } as Fetch.Ok
        assertEquals(0L, second.wireBytes)
        assertNull(second.maxAgeS)
        assertEquals(0, second.ageS)
    }

    @Test fun thePollIsParallel() {
        liveGame()
        val s = source()
        runBlocking { s.game(ohl29191, start29191) } // warm: the board, teams, seasons
        for (n in listOf("runningclock", "publishedclock", "goalssummary", "shotssummary", "penalties", "goals")) {
            val body = nodes.getValue("ohl/$n/29191")
            nodeFaults[n] = { json(body).newBuilder().headersDelay(400, TimeUnit.MILLISECONDS).build() }
        }
        val t0 = System.nanoTime()
        ok(runBlocking { s.game(ohl29191, start29191) })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("six 400 ms answers took $ms ms: not parallel", ms < 1_800)
    }

    @Test fun shootoutsIsAddedForTheWhlAfterAPossibleShootoutAndResetWithTheFollow() {
        val whl = GameId(League.WHL, "1023288")
        val tied = rc(period = 4, min = "00", sec = "00", running = false)
        liveGame("whl", "1023288", penalties = noPenalties, clock = tied)
        nodes["whl/goalssummary/1023288"] = gs(away = 2, home = 2)
        nodes["whl/goals/1023288"] = goals(Triple(false, 1, "01:00"), Triple(false, 2, "01:00"), Triple(true, 2, "02:00"), Triple(true, 3, "03:00"))
        nodes["whl/publishedclock/1023288"] = pc(period = 4)
        val s = source(League.WHL)
        val start = at("2026-11-13T19:00", "America/Edmonton")
        ok(runBlocking { s.game(whl, start) })
        assertFalse(fbNodes().any { nodeName(it) == "shootouts" })
        requests.clear()
        nowMono += 5_000
        ok(runBlocking { s.game(whl, start) })
        assertTrue("the previous snapshot was a possible shootout", fbNodes().any { nodeName(it) == "shootouts" })
        assertEquals(7, fbNodes().size)

        // A new follow forgets the previous snapshot: its first poll has no shootouts node.
        requests.clear()
        val other = GameId(League.WHL, "1023287")
        liveGame("whl", "1023287", penalties = noPenalties)
        ok(runBlocking { s.game(other, start) })
        assertFalse(fbNodes().any { nodeName(it) == "shootouts" })
    }

    @Test fun theOhlNeverRequestsShootouts() {
        liveGame(penalties = noPenalties, clock = rc(period = 4, min = "00", sec = "00", running = false))
        nodes["ohl/goalssummary/29191"] = gs(away = 2, home = 2)
        nodes["ohl/goals/29191"] = goals(Triple(false, 1, "01:00"), Triple(false, 2, "01:00"), Triple(true, 2, "02:00"), Triple(true, 3, "03:00"))
        val s = source()
        repeat(3) {
            ok(runBlocking { s.game(ohl29191, start29191) })
            nowMono += 5_000
        }
        assertFalse(fbNodes().any { nodeName(it) == "shootouts" })
    }

    // ---------------------------------------------------------------- the three node states and partial failures

    @Test fun aGenuineNullPenaltiesNodeIsNotAFailure() {
        liveGame(penalties = null) // JSON null
        nodes["ohl/goalssummary/29191"] = gs(awayAtt = 0, homeAtt = 0)
        val snap = ok(runBlocking { source().game(ohl29191, start29191) })
        assertNull(snap.powerPlay)
        assertFalse("null penalties with no attempts is 'none' (E27), not unknown", snap.powerPlayUnknown)
    }

    @Test fun aFailedPenaltiesRequestIsUnknownAndA304AfterItReusesTheBody() {
        liveGame()
        val s = source()
        ok(runBlocking { s.game(ohl29191, start29191) })
        nowMono += 5_000
        nodeFaults["penalties"] = { json("""{"error":"x"}""", 503) }
        val failedPoll = ok(runBlocking { s.game(ohl29191, start29191) })
        assertTrue("H8", failedPoll.powerPlayUnknown)
        assertNull(failedPoll.powerPlay)
        // The node's cached (ETag, body) survived the failure: the next 304 answers it.
        nodeFaults.clear()
        requests.clear()
        nowMono += 5_000
        val back = ok(runBlocking { s.game(ohl29191, start29191) })
        assertTrue(fbNodes().first { nodeName(it) == "penalties" }.headers["If-None-Match"] != null)
        assertEquals(Side.HOME, back.powerPlay?.side)
        assertFalse(back.powerPlayUnknown)
    }

    @Test fun eachOptionalNodeFailingAloneKeepsThePoll() {
        for (n in listOf("publishedclock", "shotssummary", "penalties", "goals")) {
            nodes.clear()
            nodeFaults.clear()
            liveGame()
            nodeFaults[n] = { json("""{"error":"x"}""", 503) }
            val snap = ok(runBlocking { source().game(ohl29191, start29191) })
            assertEquals(n, 1 to 2, snap.away.score to snap.home.score)
            when (n) {
                "shotssummary" -> assertNull(n, snap.away.sog)
                "penalties", "goals" -> assertTrue(n, snap.powerPlayUnknown)
                "publishedclock" -> {
                    assertEquals(n, GamePhase.LIVE, snap.phase) // status from runningclock
                    assertEquals(n, Side.HOME, snap.powerPlay?.side)
                }
            }
            if (n == "goals") assertNull(snap.lastGoal)
        }
    }

    @Test fun eachRequiredNodeFailingAloneFailsThePoll() {
        for (n in listOf("runningclock", "goalssummary")) {
            nodes.clear()
            nodeFaults.clear()
            liveGame()
            nodeFaults[n] = { json("""{"error":"x"}""", 503) }
            assertEquals(n, FailKind.HTTP_5XX, failed(runBlocking { source().game(ohl29191, start29191) }))
        }
    }

    // ---------------------------------------------------------------- §2.6 error mappings

    @Test fun firebasePermissionDeniedIsRefused() {
        liveGame()
        nodeFaults["runningclock"] = { json(SportsFixtures.text("sports/chl/errors/firebase-permission-denied.json"), 401) }
        assertEquals(FailKind.REFUSED, failed(runBlocking { source().game(ohl29191, start29191) }))
        nodeFaults["runningclock"] = { json("""{"error":"Permission denied"}""", 403) }
        assertEquals(FailKind.REFUSED, failed(runBlocking { source().game(ohl29191, start29191) }))
        // A refusal wins over another required node's failure in the same poll.
        nodeFaults["goalssummary"] = { json("x", 500) }
        assertEquals(FailKind.REFUSED, failed(runBlocking { source().game(ohl29191, start29191) }))
    }

    @Test fun aRefusalCarriesALongRetryAfterUnclamped() {
        liveGame()
        nodeFaults["runningclock"] = { json("""{"error":"Permission denied"}""", 401, "Retry-After" to "900") }
        val f = runBlocking { source().game(ohl29191, start29191) } as Fetch.Failed
        assertEquals(FailKind.REFUSED, f.kind)
        assertEquals(900, f.retryAfterS)
    }

    @Test fun a429CarriesRetryAfter() {
        liveGame()
        nodeFaults["runningclock"] = { json("{}", 429, "Retry-After" to "42") }
        val f = runBlocking { source().game(ohl29191, start29191) } as Fetch.Failed
        assertEquals(FailKind.HTTP_429, f.kind)
        assertEquals(42, f.retryAfterS)
    }

    @Test fun aTimeoutIsTimeout() {
        liveGame()
        val s = source(callTimeoutMs = 400)
        runBlocking { s.game(ohl29191, start29191) } // the board etc. answer fast
        nodeFaults["runningclock"] = { json(rc()).newBuilder().headersDelay(2, TimeUnit.SECONDS).build() }
        assertEquals(FailKind.TIMEOUT, failed(runBlocking { s.game(ohl29191, start29191) }))
    }

    @Test fun anOversizeMalformedOrWrongShapeNodeIsBadBody() {
        liveGame()
        val big = """{"Clock":{"Minutes":"10","Seconds":"00","Running":true,"period":2},"pad":"${"x".repeat(140_000)}"}"""
        for (body in listOf(big, "{not json", "[1,2]", "<html>oops</html>")) {
            nodeFaults["runningclock"] = { json(body) }
            assertEquals(body.take(12), FailKind.BAD_BODY, failed(runBlocking { source().game(ohl29191, start29191) }))
        }
        // An HTML 404 from the database host is not "no such game".
        nodeFaults["runningclock"] = { text("<html>404</html>", 404) }
        assertEquals(FailKind.HTTP_OTHER, failed(runBlocking { source().game(ohl29191, start29191) }))
    }

    @Test fun lsclusterRefusalsAreRefusedAndOtherFailuresMapAsUsual() {
        feedFaults["gamesbydate"] = { text(SportsFixtures.text("sports/chl/errors/lscluster-invalid-key.txt")) }
        assertEquals(FailKind.REFUSED, failed(runBlocking { source().day("2026-11-13") }))
        feedFaults["gamesbydate"] = { text(SportsFixtures.text("sports/chl/errors/lscluster-client-denied.txt")) }
        assertEquals(FailKind.REFUSED, failed(runBlocking { source().day("2026-11-13") }))
        feedFaults["gamesbydate"] = { text("oops", 502) }
        assertEquals(FailKind.HTTP_5XX, failed(runBlocking { source().day("2026-11-13") }))
        feedFaults["gamesbydate"] = { text("<html>maintenance</html>") }
        assertEquals(FailKind.BAD_BODY, failed(runBlocking { source().day("2026-11-13") }))
        feedFaults["gamesbydate"] = { json("""{"SiteKit":{"Gamesbydate":[],"pad":"${"x".repeat(530_000)}"}}""") }
        assertEquals(FailKind.BAD_BODY, failed(runBlocking { source().day("2026-11-13") }))
        feedFaults["gamesbydate"] = { json("""{"nothing":1}""") }
        assertEquals(FailKind.BAD_BODY, failed(runBlocking { source().day("2026-11-13") }))
        feedFaults["gamesbydate"] = { json("{}", 429, "Retry-After" to "30") }
        assertEquals(FailKind.HTTP_429, failed(runBlocking { source().day("2026-11-13") }))
        // No request at all for an unusable key.
        val before = requests.size
        assertEquals(FailKind.HTTP_OTHER, failed(runBlocking { source().day("2026-13-45") }))
        assertEquals(FailKind.HTTP_OTHER, failed(runBlocking { source().game(GameId(League.WHL, "1"), null) }))
        assertEquals(FailKind.HTTP_OTHER, failed(runBlocking { source().game(GameId(League.OHL, "29a"), null) }))
        assertEquals(before, requests.size)
    }

    @Test fun anLsclusterRefusalLeavesALiveFollowWorkingOnceItsFactsAreKnown() {
        liveGame()
        val s = source()
        ok(runBlocking { s.game(ohl29191, start29191) })
        feedFaults["gamesbydate"] = { text("Invalid key.") }
        nowMono += HockeyTechScoreSource.BOARD_CACHE_MS + 1
        assertEquals(FailKind.REFUSED, failed(runBlocking { s.day("2026-11-13") }))
        nowMono += 300_000
        ok(runBlocking { s.game(ohl29191, start29191) })
    }

    @Test fun anUnreachableHostIsIo() {
        liveGame()
        val s = source()
        runBlocking { s.game(ohl29191, start29191) }
        server.close()
        val k = failed(runBlocking { s.game(ohl29191, start29191) })
        assertTrue("$k", k == FailKind.IO || k == FailKind.TIMEOUT)
    }

    // ---------------------------------------------------------------- S5 and board mode

    @Test fun aLiveModeNullClockOnALiveBoardIsAVisibleFailure() {
        liveGame()
        nodes.remove("ohl/runningclock/29191") // null
        val s = source()
        assertEquals(FailKind.BAD_BODY, failed(runBlocking { s.game(ohl29191, start29191) }))
        assertEquals(PollMeter.CHL_LIVE, s.followEndpoint(ohl29191))
    }

    @Test fun aScheduledGameAheadIsReadFromTheBoardWithoutFirebase() {
        // WHL 1023286 VIC @ KAM, status 1, 19:00 Vancouver; now 18:00 Vancouver.
        val id = GameId(League.WHL, "1023286")
        val s = source(League.WHL)
        val snap = ok(runBlocking { s.game(id, at("2026-11-13T19:00", "America/Vancouver")) })
        assertEquals(GamePhase.SCHEDULED, snap.phase)
        assertEquals("Victoria Royals", snap.away.name) // the facts' full name, not the card's "Royals"
        assertTrue(fb().isEmpty())
        assertNull("a board-mode answer is no live meter sample", s.followEndpoint(id))
    }

    @Test fun aScheduledGamePastItsStartWithANullClockIsBoardModeForThatPoll() {
        wallMs = at("2026-11-13T19:30", "America/Vancouver")
        val id = GameId(League.WHL, "1023286")
        val s = source(League.WHL)
        val snap = ok(runBlocking { s.game(id, at("2026-11-13T19:00", "America/Vancouver")) })
        assertEquals(GamePhase.SCHEDULED, snap.phase)
        assertEquals("Kamloops Blazers", snap.home.name)
        assertTrue("live mode was tried first", fbNodes().any { nodeName(it) == "runningclock" })
        assertNull(s.followEndpoint(id))
    }

    @Test fun aFinalGameFirebaseNoLongerListsIsReadFromTheBoard() {
        // QMJHL 32721 NFL @ CAP, board status 4 / final 1; Firebase answers null for every node.
        val id = GameId(League.QMJHL, "32721")
        val snap = ok(runBlocking { source(League.QMJHL).game(id, at("2026-11-13T19:00", "America/Halifax")) })
        assertEquals(GamePhase.FINAL, snap.phase)
        assertEquals("NFL", snap.away.abbrev) // a team code, never a league (L2)
        assertEquals("Newfoundland Regiment", snap.away.name)
        assertNotNull(snap.away.score)
    }

    @Test fun aForfeitWithoutAScoreSummaryUsesTheFinalBoard() {
        val id = GameId(League.QMJHL, "32721")
        nodes["lhjmq/runningclock/32721"] = rc(period = 3, min = "00", sec = "00", running = false, status = 5)
        nodes["lhjmq/publishedclock/32721"] = pc(period = 3, status = 5, final = true)
        val snap = ok(runBlocking { source(League.QMJHL).game(id, at("2026-11-13T19:00", "America/Halifax")) })
        assertEquals(GamePhase.FINAL, snap.phase)
    }

    @Test fun aLiveGameWithoutAScoreSummaryIsBadBody() {
        liveGame()
        nodes.remove("ohl/goalssummary/29191")
        assertEquals(FailKind.BAD_BODY, failed(runBlocking { source().game(ohl29191, start29191) }))
    }

    @Test fun aGameNoBoardListsIsNotFound() {
        val s = source()
        val f = runBlocking { s.game(GameId(League.OHL, "99999"), start29191) }
        assertEquals(Fetch.NotFound, f)
        assertTrue(fb().isEmpty())
        assertNull(s.followEndpoint(GameId(League.OHL, "99999")))
    }

    @Test fun aFollowedSnapshotKeepsTheBoardsTimeTbdFlag() {
        boardEdit = { b -> editGame(b, "29191") { it + ("time_tbd" to JsonPrimitive("1")) } }
        liveGame()
        val snap = ok(runBlocking { source().game(ohl29191, null) })
        assertTrue(snap.startTbd)
    }

    @Test fun eachDayRefreshUpdatesTheFollowedGamesFacts() {
        liveGame()
        val s = source()
        assertEquals(start29191, ok(runBlocking { s.game(ohl29191, start29191) }).startUtcMs)
        boardEdit = { b -> editGame(b, "29191") { it + ("schedule_time" to JsonPrimitive("19:35:00")) } }
        nowMono += HockeyTechScoreSource.BOARD_CACHE_MS + 1
        board(runBlocking { s.day("2026-11-13") })
        assertEquals(start29191 + 30 * 60_000L, ok(runBlocking { s.game(ohl29191, start29191) }).startUtcMs)
    }

    @Test fun anOvertimeFollowRereadsTheBoardForTheOtLength() {
        liveGame(penalties = noPenalties, clock = rc(period = 4, min = "03", sec = "00"))
        nodes["ohl/goalssummary/29191"] = gs(away = 2, home = 2)
        nodes["ohl/goals/29191"] = goals(Triple(false, 1, "01:00"), Triple(false, 2, "01:00"), Triple(true, 2, "02:00"), Triple(true, 3, "03:00"))
        val s = source()
        ok(runBlocking { s.game(ohl29191, start29191) })
        assertEquals(1, feeds("gamesbydate").size)
        nowMono += HockeyTechScoreSource.BOARD_CACHE_MS + 1
        ok(runBlocking { s.game(ohl29191, start29191) })
        assertEquals("period 4 has no listed length: the board is re-read once per 240 s", 2, feeds("gamesbydate").size)
        nowMono += 5_000
        ok(runBlocking { s.game(ohl29191, start29191) })
        assertEquals(2, feeds("gamesbydate").size)
    }

    private fun editGame(board: JsonObject, id: String, edit: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        val kit = board.getValue("SiteKit").jsonObject
        val games = kit.getValue("Gamesbydate").jsonArray.map { g ->
            val o = g.jsonObject
            if ((o["game_id"] as JsonPrimitive).content == id) JsonObject(edit(o)) else o
        }
        return JsonObject(board + ("SiteKit" to JsonObject(kit + ("Gamesbydate" to kotlinx.serialization.json.JsonArray(games)))))
    }

    // ---------------------------------------------------------------- the day list (§2.3)

    @Test fun theBoardIsCachedFor240sAndTeamsAndSeasonsLoadOnce() {
        val s = source()
        val b = board(runBlocking { s.day("2026-11-12") }) // another date: an empty board
        assertEquals(League.OHL, b.league)
        assertTrue(b.games.isEmpty())
        board(runBlocking { s.day("2026-11-13") })
        nowMono += 239_000
        board(runBlocking { s.day("2026-11-13") })
        assertEquals(2, feeds("gamesbydate").size)
        nowMono += 2_000
        board(runBlocking { s.day("2026-11-13") })
        assertEquals(3, feeds("gamesbydate").size)
        assertEquals(1, feeds("seasons").size)
        assertEquals(1, feeds("teamsbyseason").size)
        assertEquals("88", feeds("teamsbyseason").single().url.queryParameter("season_id"))
        val q = feeds("gamesbydate").last().url
        assertEquals("modulekit", q.queryParameter("feed"))
        assertEquals("2026-11-13", q.queryParameter("fetch_date"))
        assertEquals("ohl", q.queryParameter("client_code"))
        assertEquals("json", q.queryParameter("fmt"))
        assertEquals("en", q.queryParameter("lang"))
    }

    @Test fun aTeamsFailureShowsCodePlatesAndRetriesOnTheNextBoard() {
        feedFaults["teamsbyseason"] = { text("x", 500) }
        val s = source()
        val first = board(runBlocking { s.day("2026-11-13") })
        assertTrue(first.games.all { it.away.logo == null })
        feedFaults.clear()
        nowMono += 241_000
        val second = board(runBlocking { s.day("2026-11-13") })
        assertEquals(2, feeds("teamsbyseason").size)
        assertTrue(second.games.any { it.away.logo != null })
    }

    @Test fun todayWithLiveGamesGetsTheOverlayWithoutEtags() {
        overlays["runningclock"] = { json("""{"29191":${rc(period = 3, min = "04", sec = "00")}}""") }
        overlays["goalssummary"] = { json("""{"29191":${gs(away = 6, home = 3)}}""") }
        val s = source()
        val b = board(runBlocking { s.today() })
        assertEquals("2026-11-13", b.dayKey)
        val ov = fb()
        assertEquals(2, ov.size)
        assertTrue(ov.all { it.headers["X-Firebase-ETag"] == null && it.headers["If-None-Match"] == null })
        assertEquals("\"DatePlayed\"", ov.first().url.queryParameter("orderBy"))
        assertEquals("\"2026-11-13\"", ov.first().url.queryParameter("equalTo"))
        val g = b.games.first { it.id.id == "29191" }
        assertEquals(6 to 3, g.away.score to g.home.score)
        assertEquals(240, g.clock?.secondsRemaining)
        // Another date: no overlay.
        requests.clear()
        board(runBlocking { s.day("2026-11-12") })
        assertTrue(fb().isEmpty())
    }

    @Test fun aFailedOverlayKeepsTheBoardAndARefusedOneIsSkippedFor300s() {
        overlays["runningclock"] = { json("x", 503) }
        val s = source()
        // No overlaid board yet: the plain board.
        assertTrue(runBlocking { s.day("2026-11-13") } is Fetch.Ok)
        overlays.clear()
        assertTrue(runBlocking { s.day("2026-11-13") } is Fetch.Ok) // overlaid now
        overlays["runningclock"] = { json("x", 503) }
        assertEquals("the repository keeps its board, failing", FailKind.HTTP_5XX, failed(runBlocking { s.day("2026-11-13") }))
        // A refused database never fails the board, and is not asked again inside 300 s.
        overlays["runningclock"] = { json("""{"error":"Permission denied"}""", 401) }
        assertTrue(runBlocking { s.day("2026-11-13") } is Fetch.Ok)
        requests.clear()
        nowMono += 299_000
        assertTrue(runBlocking { s.day("2026-11-13") } is Fetch.Ok)
        assertTrue(fb().isEmpty())
        nowMono += 2_000
        runBlocking { s.day("2026-11-13") }
        assertEquals(2, fb().size)
    }

    // ---------------------------------------------------------------- logos (§3.5)

    @Test fun logosAreValidatedUnderTheLeaguesFolder() {
        val s = source()
        val b = board(runBlocking { s.day("2026-11-13") })
        val prefix = "${origin()}/leaguestat/ohl/logos/"
        val bram = b.games.first { it.away.abbrev == "BRAM" || it.home.abbrev == "BRAM" }.let { if (it.away.abbrev == "BRAM") it.away else it.home }
        assertEquals("the JPEG swap (P6)", "${prefix}18.png", bram.logo)
        assertEquals(bram.logo, s.logoUrl(bram))
        fun line(url: String?) = TeamLine("OTT", "Ottawa 67's", null, url)
        assertEquals("${prefix}5_88.png", s.logoUrl(line("${prefix}5_88.png")))
        for (bad in listOf(
            null, "", "https://assets.leaguestat.com/ohl/logos/5.png", "${origin()}/leaguestat/whl/logos/5.png",
            "${prefix}5.jpg", "${prefix}../5.png", "${prefix}5.png?x=1", "${prefix}50x50/5.png", "${prefix}abc.png",
        )) {
            assertEquals("$bad", "", s.logoUrl(line(bad)))
        }
    }

    // ---------------------------------------------------------------- redaction (§2.2, L6)

    /** Endpoints with the real keys against the scripted server (which accepts only the fake ones). */
    private fun realKeyEndpoints(): HockeyTechEndpoints {
        val o = HockeyTechEndpoints.fromOverride(origin())!!
        return HockeyTechEndpoints(o.feedUrl, o.firebaseBase, o.logoBase, HockeyTechKeys::of)
    }

    private val keys get() = listOf(HockeyTechKeys.OHL, HockeyTechKeys.WHL, HockeyTechKeys.QMJHL)

    @Test fun noLogLineOrLoggedThrowableCarriesAKeyAUrlOrABody() {
        val s = source(endpoints = ::realKeyEndpoints)
        // The real key is sent (the scripted server refuses it, and its refusal body is never logged).
        assertEquals(FailKind.REFUSED, failed(runBlocking { s.day("2026-11-13") }))
        assertEquals(HockeyTechKeys.OHL, feeds("gamesbydate").single().url.queryParameter("key"))
        // A body echoing the key that does not decode: the decoder's message would quote it.
        feedFaults["gamesbydate"] = { json("""{"SiteKit":{"Parameters":{"key":"${HockeyTechKeys.OHL}"}, oops""") }
        assertEquals(FailKind.BAD_BODY, failed(runBlocking { source().day("2026-11-13") }))
        // Live failures of every kind, then the host gone (OkHttp's messages name the host and port).
        feedFaults.clear()
        liveGame()
        val fake = source()
        ok(runBlocking { fake.game(ohl29191, start29191) })
        nodeFaults["runningclock"] = { json("""{"error":"${HockeyTechKeys.OHL}"""", 200) }
        failed(runBlocking { fake.game(ohl29191, start29191) })
        nodeFaults["runningclock"] = { json("{}", 401) }
        failed(runBlocking { fake.game(ohl29191, start29191) })
        server.close()
        failed(runBlocking { fake.game(ohl29191, start29191) })
        failed(runBlocking { source(endpoints = ::realKeyEndpoints).day("2026-11-14") })

        assertTrue("some transitions were logged", logs.size >= 4)
        assertTrue("no throwable is ever handed to the log", logThrowables.isEmpty())
        for (line in logs) {
            for (k in keys) assertFalse(line, line.contains(k))
            for (bad in listOf("http", "://", "?", "index.php", "firebase", "leaguestat", "localhost", "127.0.0.1", "SiteKit", "Clock", "{")) {
                assertFalse("\"$bad\" in: $line", line.contains(bad, ignoreCase = true))
            }
        }
        assertTrue(logs.any { it.contains("OHL board → REFUSED") })
        assertTrue(logs.any { it.contains("OHL live → REFUSED") })
    }

    @Test fun theEndpointsNeverPrintAKey() {
        val e = realKeyEndpoints()
        for (k in keys) assertFalse(e.toString().contains(k))
        assertFalse(HockeyTechEndpoints.DEFAULT.toString().contains(HockeyTechKeys.WHL))
        assertEquals("https://lscluster.hockeytech.com/feed/index.php", HockeyTechEndpoints.DEFAULT.feedUrl)
        assertEquals("https://leaguestat-b9523.firebaseio.com/svf/lhjmq/", HockeyTechEndpoints.DEFAULT.firebase(League.QMJHL))
        assertEquals("https://assets.leaguestat.com/whl/logos/", HockeyTechEndpoints.DEFAULT.logoPrefix(League.WHL))
        val o = HockeyTechEndpoints.fromOverride("http://10.0.2.2:8080/")!!
        assertEquals("http://10.0.2.2:8080/hockeytech/feed/index.php", o.feedUrl)
        assertEquals("http://10.0.2.2:8080/firebase/svf/", o.firebaseBase)
        assertEquals("http://10.0.2.2:8080/leaguestat/", o.logoBase)
        assertEquals("fakepanel-ohl", o.key(League.OHL))
        assertNull(HockeyTechEndpoints.fromOverride("ftp://x"))
        assertNull(HockeyTechEndpoints.fromOverride("  "))
    }

    @Test fun theKeysAreRegisteredWithRedactOnceTheSourceClassLoads() {
        source() // the companion touches HockeyTechKeys
        for (k in keys) {
            assertEquals("key ***", app.thebox.core.common.Redact.text("key $k"))
        }
        assertFalse(app.thebox.core.common.Redact.text("${HockeyTechEndpoints.FEED_URL}?view=seasons&key=${HockeyTechKeys.OHL}").contains(HockeyTechKeys.OHL))
    }

    @Test fun nhlAndNflAreRejected() {
        val e = runCatching { source(League.NHL) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
