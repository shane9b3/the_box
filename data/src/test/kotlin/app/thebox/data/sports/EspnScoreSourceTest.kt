package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.EspnBoardDto
import app.thebox.core.sports.EspnGameDto
import app.thebox.core.sports.EspnMapper
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The ESPN transport against a scripted server (docs/design/m6-nfl.md §1.4, §1.5, §7.2, §10.3). The mapper is a fake
 * that reads only what the DTO decode yields (WP-1 owns the real one); the last case runs WP-1's mapper when it exists.
 */
class EspnScoreSourceTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    /** Scripted answers by the scoreboard's query ("" = none); default `{"events":[]}`. */
    private val answers = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()
    private var nowMono = 5_000L
    private val mono = MonoClock { nowMono }
    private val boards = CopyOnWriteArrayList<EspnBoardDto>()
    private val games = CopyOnWriteArrayList<EspnGameDto>()

    /** A stand-in for WP-1's board mapper: one NFL fixture game per decoded event id, with a foreign logo to rewrite. */
    private val fakeBoard: (EspnBoardDto, Long?, String) -> DayBoard? = { dto, _, _ ->
        boards += dto
        dto.events?.let { evs ->
            DayBoard("w2026-2-9", "w2026-2-8", "w2026-2-10", evs.mapNotNull { e -> EspnScoreSource.idOf(e)?.let { game(it) } })
        }
    }
    private val fakeGame: (EspnGameDto, GameId, Long?, String) -> GameSnapshot? = { dto, id, _, _ ->
        games += dto
        dto.events?.firstOrNull { EspnScoreSource.idOf(it) == id.id }?.let { game(id.id) }
    }
    /** A stand-in for WP-1's `parseWeekKey`: `w{year}-{type}-{week}`. */
    private val fakeWeek: (String) -> Triple<Int, Int, Int>? = { key ->
        Regex("""w(\d{4})-(\d)-(\d{1,2})""").matchEntire(key)?.destructured?.let { (y, t, w) -> Triple(y.toInt(), t.toInt(), w.toInt()) }
    }

    private fun game(id: String): GameSnapshot = SportsFixtures.nflGame(id = id).let {
        it.copy(away = it.away.copy(logo = "https://evil.test/kc.png"), home = it.home.copy(logo = null))
    }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.url.encodedPath != PATH) return json("""{"code":404}""", 404)
                return answers[request.url.encodedQuery.orEmpty()]?.invoke(request) ?: json("""{"events":[]}""")
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun origin() = server.url("/").toString().trimEnd('/')

    private fun source(
        mapBoard: (EspnBoardDto, Long?, String) -> DayBoard? = fakeBoard,
        mapGame: (EspnGameDto, GameId, Long?, String) -> GameSnapshot? = fakeGame,
        parseWeek: (String) -> Triple<Int, Int, Int>? = fakeWeek,
        callTimeoutMs: Long = 10_000L,
    ) = EspnScoreSource(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
        mono,
        endpoints = { EspnEndpoints.fromOverride(origin())!! },
        mapBoard = mapBoard,
        mapGame = mapGame,
        parseWeekKey = parseWeek,
        callTimeoutMs = callTimeoutMs,
    )

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private fun events(vararg idDate: Pair<String, String>): String =
        """{"events":[${idDate.joinToString(",") { (id, d) -> """{"id":"$id","date":"$d","competitions":[]}""" }}]}"""

    private fun queries() = requests.map { it.url.encodedQuery.orEmpty() }

    private val kcBuf = GameId(League.NFL, "401899001")

    // ---------------------------------------------------------------- paths (§1.5, §7.2)

    @Test fun todayIsTheUndatedWeekBoard() {
        answers[""] = { json(events("401899001" to "2026-11-08T18:00Z")) }
        val f = runBlocking { source().today() } as Fetch.Ok
        assertEquals(listOf(PATH), requests.map { it.url.encodedPath })
        assertEquals(listOf(""), queries())
        assertEquals("application/json", requests.single().headers["Accept"])
        assertEquals(listOf("401899001"), f.value.games.map { it.id.id })
    }

    @Test fun dayTakesAWeekKeyOrADateKeyAndNothingElse() {
        val s = source()
        assertTrue(runBlocking { s.day("w2026-2-9") } is Fetch.Ok)
        assertTrue(runBlocking { s.day("2026-11-09") } is Fetch.Ok)
        assertEquals(listOf("seasontype=2&week=9", "dates=20261109"), queries())
        requests.clear()
        for (bad in listOf("../admin", "w2026-x-9", "20261109", "", "2026-11-09&x=1", "now")) {
            assertEquals(bad, FailKind.HTTP_OTHER, (runBlocking { s.day(bad) } as Fetch.Failed).kind)
        }
        assertTrue("never sent", requests.isEmpty())
    }

    @Test fun malformedOrForeignGameIdsAreNeverSent() {
        val s = source()
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.game(GameId(League.NFL, "1/../x")) } as Fetch.Failed).kind)
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.game(GameId(League.NHL, "401899001")) } as Fetch.Failed).kind)
        assertTrue(requests.isEmpty())
    }

    @Test fun gameWithNothingRememberedReadsTheWeekBoardThenItsRememberedEtDate() {
        // Monday night's 01:15Z kick-off is 20:15 ET on Monday the 9th: `dates=20261109` (§1.1).
        answers[""] = { json(events("401899001" to "2026-11-10T01:15Z", "401873038" to "2026-11-08T14:30Z")) }
        answers["dates=20261109"] = { json(events("401899001" to "2026-11-10T01:15Z")) }
        val s = source()
        val first = runBlocking { s.game(kcBuf) } as Fetch.Ok
        assertEquals("401899001", first.value.id.id)
        assertEquals("after a restart: the week board first", listOf(""), queries())
        val second = runBlocking { s.game(kcBuf) } as Fetch.Ok
        assertEquals("401899001", second.value.id.id)
        assertEquals("then the ET date remembered from it", listOf("", "dates=20261109"), queries())
        assertEquals("game() decodes the smaller DTO", 2, games.size)
        assertTrue(boards.isEmpty())
    }

    @Test fun gameUsesTheDateRememberedFromAnyBoard() {
        answers["seasontype=2&week=9"] = { json(events("401873038" to "2026-11-08T14:30Z", "401899001" to "2026-11-08T18:00:00Z")) }
        answers["dates=20261108"] = { json(events("401873038" to "2026-11-08T14:30Z", "401899001" to "2026-11-08T18:00Z")) }
        val s = source()
        runBlocking { s.day("w2026-2-9") }
        val g = runBlocking { s.game(kcBuf) } as Fetch.Ok
        assertEquals("the event filter picks the id", "401899001", g.value.id.id)
        assertEquals(listOf("seasontype=2&week=9", "dates=20261108"), queries())
    }

    @Test fun anEventMissingFromItsDatedBoardIsRetriedOnceAgainstTheWeekBoard() {
        answers["dates=20261108"] = { json(events("401873038" to "2026-11-08T14:30Z")) }
        val s = source()
        // Remember KC @ BUF on the 8th from a dated board, then re-date it (a weather move, N7).
        answers["dates=20261107"] = { json(events("401899001" to "2026-11-08T18:00Z")) }
        runBlocking { s.day("2026-11-07") }
        requests.clear()

        answers[""] = { json(events("401899001" to "2026-11-09T18:00Z")) }
        val moved = runBlocking { s.game(kcBuf) } as Fetch.Ok
        assertEquals("401899001", moved.value.id.id)
        assertEquals(listOf("dates=20261108", ""), queries())
        requests.clear()
        answers["dates=20261109"] = { json(events("401899001" to "2026-11-09T18:00Z")) }
        runBlocking { s.game(kcBuf) }
        assertEquals("the new date is remembered", listOf("dates=20261109"), queries())

        requests.clear()
        answers["dates=20261109"] = { json(events()) }
        answers[""] = { json(events("401873038" to "2026-11-08T14:30Z")) }
        assertEquals("absent from both: NotFound", Fetch.NotFound, runBlocking { s.game(kcBuf) })
        assertEquals("one retry, no more", listOf("dates=20261109", ""), queries())
    }

    @Test fun aFailedDatedBoardIsNotRetriedAndAFailedRetryIsNotNotFound() {
        val s = source()
        answers["dates=20261107"] = { json(events("401899001" to "2026-11-08T18:00Z")) }
        runBlocking { s.day("2026-11-07") }
        requests.clear()
        answers["dates=20261108"] = { json("{}", 503) }
        assertEquals(FailKind.HTTP_5XX, (runBlocking { s.game(kcBuf) } as Fetch.Failed).kind)
        assertEquals(listOf("dates=20261108"), queries())

        requests.clear()
        answers["dates=20261108"] = { json(events()) }
        answers[""] = { json("{}", 429, "Retry-After" to "30") }
        val f = runBlocking { s.game(kcBuf) } as Fetch.Failed
        assertEquals(FailKind.HTTP_429, f.kind)
        assertEquals(30, f.retryAfterS)
    }

    @Test fun aListedEventThatFailsToMapIsBadBodyNotNotFound() {
        answers[""] = { json(events("401899001" to "2026-11-08T18:00Z")) }
        val s = source(mapGame = { _, _, _, _ -> null })
        assertEquals(FailKind.BAD_BODY, (runBlocking { s.game(kcBuf) } as Fetch.Failed).kind)
        val throwing = source(mapGame = { _, _, _, _ -> throw IllegalStateException("mapper bug") })
        assertEquals(FailKind.BAD_BODY, (runBlocking { throwing.game(kcBuf) } as Fetch.Failed).kind)
        val nullBoard = source(mapBoard = { _, _, _ -> null })
        assertEquals(FailKind.BAD_BODY, (runBlocking { nullBoard.today() } as Fetch.Failed).kind)
    }

    // ---------------------------------------------------------------- Date / Age

    @Test fun noAgeIsZeroAndDateIsRead() {
        nowMono = 42_000L
        answers[""] = { json(events(), 200, "Date" to "Sun, 08 Nov 2026 15:11:33 GMT", "Cache-Control" to "max-age=7") }
        val f = runBlocking { source().today() } as Fetch.Ok
        assertEquals("ESPN sends no Age", 0, f.ageS)
        assertEquals(1_794_150_693_000L, f.serverDateMs)
        assertEquals(42_000L, f.receivedMono)
        answers[""] = { json(events(), 200, "Age" to "99") }
        assertEquals(SportsConstants.AGE_MAX_S, (runBlocking { source().today() } as Fetch.Ok).ageS)
    }

    @Test fun noConditionalHeadersAreEverSent() {
        val s = source()
        runBlocking { s.today(); s.today() }
        for (r in requests) {
            assertNull(r.headers["If-None-Match"])
            assertNull(r.headers["If-Modified-Since"])
        }
    }

    // ---------------------------------------------------------------- failures (§1.4)

    @Test fun statusCodesAndHtml404s() {
        val s = source()
        answers["dates=20261109"] = { json("""{"code":400,"message":"Failed to get events endpoint."}""", 400) }
        assertEquals("a 400 (a bad dates value)", FailKind.HTTP_OTHER, (runBlocking { s.day("2026-11-09") } as Fetch.Failed).kind)
        answers["dates=20261109"] = {
            MockResponse.Builder().code(404).addHeader("Content-Type", "text/html").body("<html>gone</html>").build()
        }
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.day("2026-11-09") } as Fetch.Failed).kind)
        answers["dates=20261109"] = {
            MockResponse.Builder().code(404).addHeader("Content-Type", "application/octet-stream").body("  <!doctype html>").build()
        }
        assertEquals("HTML without the type is HTML", FailKind.HTTP_OTHER, (runBlocking { s.day("2026-11-09") } as Fetch.Failed).kind)
        answers["dates=20261109"] = { json("""{"message":"nope"}""", 404) }
        assertEquals(Fetch.NotFound, runBlocking { s.day("2026-11-09") })
        answers["dates=20261109"] = { json("{}", 502) }
        assertEquals(FailKind.HTTP_5XX, (runBlocking { s.day("2026-11-09") } as Fetch.Failed).kind)
        answers["dates=20261109"] = { json("{}", 403, "Retry-After" to "99999") }
        val o = runBlocking { s.day("2026-11-09") } as Fetch.Failed
        assertEquals(FailKind.HTTP_OTHER, o.kind)
        assertEquals(300, o.retryAfterS)
    }

    @Test fun aJson404OnBothBoardsIsNotFound() {
        answers[""] = { json("""{"message":"nope"}""", 404) }
        assertEquals(Fetch.NotFound, runBlocking { source().game(kcBuf) })
    }

    @Test fun badBodiesAreBadBodyAndReTypedLeavesStillDecode() {
        val s = source()
        for (bad in listOf("<html>captive portal</html>", "[1,2,3]", "", """{"events":{"id":"1"}}""", """{"events":[1,2]}""", """{"events":[""")) {
            answers[""] = { json(bad) }
            assertEquals(bad, FailKind.BAD_BODY, (runBlocking { s.today() } as Fetch.Failed).kind)
        }
        // B3: leaves are JsonElement?, so a number for a string, an array for an object or a null cannot fail the body.
        answers[""] = {
            json(
                """{"events":[{"id":401899001,"date":"2026-11-08T18:00Z","competitions":[{"status":[1],"situation":"x",""" +
                    """"competitors":[{"id":1,"homeAway":null,"score":7,"team":[]}]}],"odds":{"a":[1,{"b":2}]}}],""" +
                    """"leagues":[{"calendar":"none"}],"season":2026,"week":null}""",
            )
        }
        val f = runBlocking { s.today() } as Fetch.Ok
        assertEquals("a numeric id still reads", listOf("401899001"), f.value.games.map { it.id.id })
        assertEquals(1, boards.last().events!!.single().competitions!!.single().competitors!!.size)
    }

    @Test fun theOneMebibyteCapIsEnforcedWhileStreaming() {
        val s = source()
        val cap = SportsConstants.ESPN_MAX_BODY_BYTES.toInt()
        assertEquals(1_048_576, cap)
        val prefix = """{"events":[{"id":"401899001","date":"2026-11-08T18:00Z"}],"pad_bytes":""""
        val suffix = "\"}"
        val atCap = prefix + "x".repeat(cap - prefix.length - suffix.length) + suffix
        assertEquals(cap, atCap.length)
        val over = atCap.replaceFirst("\"pad_bytes\":\"", "\"pad_bytes\":\"y")

        answers[""] = { json(atCap) }
        assertTrue("exactly the cap is fine", runBlocking { s.today() } is Fetch.Ok)
        boards.clear()
        answers[""] = { json(over) }
        assertEquals("by Content-Length", FailKind.BAD_BODY, (runBlocking { s.today() } as Fetch.Failed).kind)
        answers[""] = {
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").chunkedBody(over, 8_192).build()
        }
        assertEquals("chunked: counted while streaming", FailKind.BAD_BODY, (runBlocking { s.today() } as Fetch.Failed).kind)
        assertTrue("never mapped", boards.isEmpty())
        // The pad in the middle (before events) is counted as it streams past, too.
        val padFirst = """{"pad_bytes":"${"z".repeat(cap)}","events":[]}"""
        answers[""] = {
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").chunkedBody(padFirst, 8_192).build()
        }
        assertEquals(FailKind.BAD_BODY, (runBlocking { s.today() } as Fetch.Failed).kind)
        assertTrue(boards.isEmpty())
    }

    @Test fun timeoutsAndConnectionFailures() {
        answers[""] = { json(events()).newBuilder().headersDelay(2, TimeUnit.SECONDS).build() }
        assertEquals(FailKind.TIMEOUT, (runBlocking { source(callTimeoutMs = 300).today() } as Fetch.Failed).kind)
        val dead = EspnScoreSource(
            OkHttpClient(), mono, endpoints = { EspnEndpoints.fromOverride("http://127.0.0.1:1")!! },
            mapBoard = fakeBoard, mapGame = fakeGame, parseWeekKey = fakeWeek,
        )
        assertEquals(FailKind.IO, (runBlocking { dead.today() } as Fetch.Failed).kind)
    }

    // ---------------------------------------------------------------- the real bodies, streamed into the DTO

    @Test fun theFullWeekBodyStreamsIntoTheBoardDto() {
        val body = paddedWeekBody(272_000)
        assertTrue("a real-size body: ${body.length} B", body.length in 272_000..SportsConstants.ESPN_MAX_BODY_BYTES.toInt())
        answers[""] = { json(body, 200, "Date" to "Sun, 08 Nov 2026 14:28:00 GMT") }
        val f = runBlocking { source().today() } as Fetch.Ok
        val dto = boards.single()
        assertEquals(15, dto.events!!.size)
        assertEquals(15, f.value.games.size)
        assertNotNull("the calendar is kept for prev/next", dto.leagues!!.single().calendar)
        assertNotNull(dto.season)
        assertNotNull(dto.week)
        val comp = dto.events!!.first().competitions!!.single()
        assertEquals(2, comp.competitors!!.size)
        assertNotNull(comp.status)
        assertTrue(comp.competitors!!.all { it.homeAway != null && it.team != null })

        // game() decodes the same bytes into the calendar-free DTO and finds its event (dates remembered from today()).
        val id = EspnScoreSource.idOf(dto.events!!.last())!!
        val date = EspnScoreSource.etDate(dto.events!!.last().date)!!
        answers["dates=$date"] = { json(body) }
        val g = runBlocking { source().let { s -> s.today(); requests.clear(); s.game(GameId(League.NFL, id)) } } as Fetch.Ok
        assertEquals(id, g.value.id.id)
        assertEquals(listOf("dates=$date"), queries())
        assertEquals(15, games.last().events!!.size)
    }

    @Test fun theLiveFixturesStreamIntoTheGameDto() {
        for (name in LIVE_FIXTURES) {
            val text = SportsFixtures.text("sports/nfl/$name")
            answers[""] = { json(text) }
            val id = Json.parseToJsonElement(text).jsonObject["events"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
            val g = runBlocking { source().game(GameId(League.NFL, id)) }
            assertTrue(name, g is Fetch.Ok)
            val comp = games.last().events!!.first { EspnScoreSource.idOf(it) == id }.competitions!!.single()
            if (name != "scoreboard-day-pre-2026-11-08.json") assertNotNull("$name: situation decoded", comp.situation)
        }
    }

    // ---------------------------------------------------------------- logos (A-N2) and endpoints (A-N3)

    @Test fun logosAreTheDarkCombinerUrlUnderTheBase() {
        val s = source()
        val base = EspnEndpoints.fromOverride(origin())!!.logoBase
        val kc = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs")
        assertEquals(base + "combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/kc.png&h=200&w=200", s.logoUrl(kc))
        assertEquals("the feed's logo is never used",
            base + "combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/wsh.png&h=200&w=200",
            s.logoUrl(kc.copy(abbrev = "WSH", logo = "https://evil.test/x.png")))
        for (bad in listOf("K", "KCCC", "../x", "K C", "", "KC.png", "kç")) {
            assertEquals(bad, "", s.logoUrl(kc.copy(abbrev = bad)))
        }
        assertEquals(
            "https://a.espncdn.com/combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/buf.png&h=200&w=200",
            EspnScoreSource.combinerLogo("BUF", EspnEndpoints.LOGO_BASE),
        )

        // Every mapped snapshot carries the derived URL, whatever the mapper put there.
        answers[""] = { json(events("401899001" to "2026-11-08T18:00Z")) }
        val g = (runBlocking { s.today() } as Fetch.Ok).value.games.single()
        assertEquals(base + "combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/kc.png&h=200&w=200", g.away.logo)
        assertEquals(base + "combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/buf.png&h=200&w=200", g.home.logo)
        val game = (runBlocking { s.game(kcBuf) } as Fetch.Ok).value
        assertEquals(g.away.logo, game.away.logo)
    }

    @Test fun endpointOverrideParsing() {
        assertEquals(
            EspnEndpoints("http://10.0.2.2:8080/apis/site/v2/sports/football/nfl/", "http://10.0.2.2:8080/"),
            EspnEndpoints.fromOverride(" http://10.0.2.2:8080/\n"),
        )
        assertEquals("http://panel.test/apis/site/v2/sports/football/nfl/", EspnEndpoints.fromOverride("http://panel.test/x?y=1")?.apiBase)
        assertNull(EspnEndpoints.fromOverride(""))
        assertNull(EspnEndpoints.fromOverride(null))
        assertNull(EspnEndpoints.fromOverride("ftp://x"))
        assertNull(EspnEndpoints.fromOverride("not a url"))
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/football/nfl/", EspnEndpoints.DEFAULT.apiBase)
        assertEquals("https://a.espncdn.com/", EspnEndpoints.DEFAULT.logoBase)
    }

    @Test fun etDates() {
        assertEquals("20261108", EspnScoreSource.etDate(JsonPrimitive("2026-11-08T14:30Z")))
        assertEquals("Monday night is Monday ET", "20261109", EspnScoreSource.etDate(JsonPrimitive("2026-11-10T01:15Z")))
        assertEquals("20261109", EspnScoreSource.etDate(JsonPrimitive("2026-11-10T04:59:59Z")))
        assertEquals("20261110", EspnScoreSource.etDate(JsonPrimitive("2026-11-10T05:00:00Z")))
        assertEquals("EDT in September", "20260913", EspnScoreSource.etDate(JsonPrimitive("2026-09-14T03:59Z")))
        assertNull(EspnScoreSource.etDate(JsonPrimitive("soon")))
        assertNull(EspnScoreSource.etDate(JsonArray(emptyList())))
        assertNull(EspnScoreSource.etDate(null))
    }

    // ---------------------------------------------------------------- with WP-1's mapper

    @Test fun recordedFixturesThroughTheRealMapper() {
        answers[""] = { json(SportsFixtures.text("sports/nfl/scoreboard-week9-pre-2026-11-08.json")) }
        // The week query answers the same recorded week (an empty `{"events":[]}` with no calendar and no `Date` is not a
        // board the real mapper can key, so it is BAD_BODY by design).
        answers["seasontype=2&week=9"] = answers.getValue("")
        val s = source(mapBoard = EspnMapper::board, mapGame = EspnMapper::game, parseWeek = EspnMapper::parseWeekKey)
        val d = runBlocking { s.today() } as Fetch.Ok
        assertEquals("w2026-2-9", d.value.dayKey)
        assertTrue(d.value.games.isNotEmpty())
        assertTrue(d.value.games.all { it.away.logo!!.contains("/500-dark/scoreboard/") })
        assertEquals(Triple(2026, 2, 9), EspnMapper.parseWeekKey("w2026-2-9"))
        assertTrue(runBlocking { s.day("w2026-2-9") } is Fetch.Ok)
        assertEquals("seasontype=2&week=9", queries().last())
        assertEquals(FailKind.HTTP_OTHER, (runBlocking { s.day("x2026") } as Fetch.Failed).kind)
    }

    /** The trimmed week fixture padded back to about [target] bytes, as ESPN's `leaders`/`odds`/`broadcasts` make it. */
    private fun paddedWeekBody(target: Int): String {
        val root = Json.parseToJsonElement(SportsFixtures.text("sports/nfl/scoreboard-week9-pre-2026-11-08.json")).jsonObject
        val evs = root["events"]!!.jsonArray
        val base = root.toString().length
        val per = ((target - base) / evs.size).coerceAtLeast(0)
        val padded = evs.map { e ->
            val o = e.jsonObject
            val leaders = JsonArray(List(per / 100 + 1) { i -> JsonObject(mapOf("name" to JsonPrimitive("leader$i"), "value" to JsonPrimitive("v".repeat(70)))) })
            JsonObject(o + ("leaders" to leaders) + ("odds" to JsonArray(listOf(JsonObject(mapOf("details" to JsonPrimitive("KC -2.5")))))))
        }
        return JsonObject(root + ("events" to JsonArray(padded))).toString()
    }

    private companion object {
        const val PATH = "/apis/site/v2/sports/football/nfl/scoreboard"
        val LIVE_FIXTURES = listOf(
            "scoreboard-day-pre-2026-11-08.json",
            "scoreboard-live-coin-toss-2026-11-08.json",
            "scoreboard-live-end-period-2026-11-08.json",
            "scoreboard-live-fg-dead-ball-2026-11-08.json",
            "scoreboard-live-first-down-2026-11-08.json",
            "scoreboard-live-kickoff-2026-11-08.json",
            "scoreboard-live-kickoff-after-fg-2026-11-08.json",
            "scoreboard-live-no-possession-after-kickoff-2026-11-08.json",
            "scoreboard-live-plus7-away-kickoff-2026-11-08.json",
            "scoreboard-live-td-score-first-2026-11-08.json",
            "scoreboard-live-try-2026-11-08.json",
        )
    }
}
