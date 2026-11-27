package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChlHubFixtures
import app.thebox.core.sports.hub.ChlRanks
import app.thebox.core.sports.hub.GameDetail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * (CHL hub P2, docs/design/m6-chl-hub.md §4.1, §4.2, §6 P2) [ChlDetailSource] against a scripted `lscluster` (the fake
 * panel's keys only) with stand-in mappers (P1's bodies are not needed): which views each phase reads and in what order
 * (the preview first, then the goalies and the tables), the set of 5 and of 3 with the ranks cached, at most 3 in flight,
 * partial answers (goalies / tables failing: Ready-able `Ok`, ranks reused however old), the playoffs (no tables), the
 * error mapping (REFUSED, 404, cap, wrong JSON type, mapper null / throw), the final's single `gamesummary` with its box,
 * no request outside pre-game / final, the trim, and that no log line carries a URL, key, id or body.
 */
class ChlDetailSourceTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var now = 10_000L
    private val mono = MonoClock { now }
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    @Volatile private var holdMs = 0L

    /** Per view (`preview`, `gamesummary`, `topgoalies`, `standard`, `special`) → the answer; default the fixtures. */
    private val answers = HashMap<String, (RecordedRequest) -> MockResponse>()

    private val ldnPre = GameId(League.OHL, "29246")
    private val ottFinal = GameId(League.OHL, "29239")

    // ---- the stand-in mappers (record what they were given)
    private class PreviewCall(
        val id: GameId, val body: JsonObject, val away: JsonObject?, val home: JsonObject?, val ranks: ChlRanks?, val playoff: Boolean,
        val headshot: (String) -> String?,
    )

    private val previewCalls = CopyOnWriteArrayList<PreviewCall>()
    private val rankCalls = CopyOnWriteArrayList<Pair<JsonArray?, JsonArray?>>()
    private var previewOut: () -> GameDetail? = { ChlHubFixtures.preOhl29246() }
    private var summaryOut: () -> Pair<GameDetail, BoxScore>? = { ChlHubFixtures.finalOhl29239() to ChlHubFixtures.boxOhl29239() }
    private var ranksOut: (JsonArray?, JsonArray?) -> ChlRanks? = { s, p ->
        ChlRanks(if (s != null) mapOf("14" to 8) else emptyMap(), emptyMap(), if (p != null) mapOf("14" to 9) else emptyMap(), emptyMap())
    }

    private val logs = CopyOnWriteArrayList<String>()
    private var oldSink: BoxLog.Sink? = null

    @Before fun start() {
        oldSink = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { _, tag, message, t -> logs += "$tag $message ${t?.javaClass?.simpleName ?: ""}" }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val n = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                try {
                    if (holdMs > 0 && view(request) != "preview") Thread.sleep(holdMs)
                    return (answers[view(request)] ?: ::fixture)(request)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        server.close()
        BoxLog.sink = oldSink!!
    }

    private fun view(r: RecordedRequest): String {
        val u = r.url
        return when (u.queryParameter("feed")) {
            "gc" -> u.queryParameter("tab") ?: "?"
            "modulekit" -> u.queryParameter("type") ?: "?"
            "statviewfeed" -> if (u.queryParameter("special") == "true") "special" else "standard"
            else -> "?"
        }
    }

    private fun json(text: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").addHeader("Date", "Fri, 27 Nov 2026 21:10:00 GMT").body(text).build()

    private fun fixture(r: RecordedRequest): MockResponse = json(
        ChlHubFixtures.text(
            when (view(r)) {
                "preview" -> ChlHubFixtures.PREVIEW_OHL_29246
                "gamesummary" -> ChlHubFixtures.SUMMARY_OHL_29239
                "topgoalies" -> ChlHubFixtures.GOALIES_OHL_T14
                "standard" -> ChlHubFixtures.TABLE_OHL
                "special" -> ChlHubFixtures.TABLE_SPECIAL_OHL
                else -> error("unexpected view")
            },
        ),
    )

    private fun endpoints() = HockeyTechEndpoints.fromOverride(server.url("/").toString())!!

    private fun source() = ChlDetailSource(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
        mono,
        { endpoints() },
        mapPreview = { id, body, away, home, ranks, playoff, headshot ->
            previewCalls += PreviewCall(id, body, away, home, ranks, playoff, headshot)
            previewOut()
        },
        mapSummary = { _, _, _ -> summaryOut() },
        mapRanks = { s, p ->
            rankCalls += s to p
            ranksOut(s, p)
        },
        callTimeoutMs = 10_000L,
    )

    private fun views() = requests.map(::view)

    private fun DetailAnswer.ok(): Fetch.Ok<GameDetail> = fetch as Fetch.Ok<GameDetail>

    private fun DetailAnswer.kind(): FailKind = (fetch as Fetch.Failed).kind

    // ---------------------------------------------------------------- pre-game: the set of 5, then 3

    @Test fun aFirstPreGameReadIsThePreviewThenGoaliesAndTablesFive() = runBlocking {
        val a = source().detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(5, requests.size)
        assertEquals("preview", views().first())
        assertEquals(setOf("topgoalies", "standard", "special"), views().drop(1).toSet())
        assertEquals(2, views().count { it == "topgoalies" })
        // The goalie reads use the preview's team ids and season.
        val goalies = requests.filter { view(it) == "topgoalies" }
        assertEquals(setOf("20", "14"), goalies.map { it.url.queryParameter("team_id") }.toSet())
        assertTrue(goalies.all { it.url.queryParameter("season_id") == "88" })
        assertTrue(a.fetch is Fetch.Ok)
        assertFalse(a.partial)
        assertFalse(a.landingFailed)
        assertNull(a.box)
        val c = previewCalls.single()
        assertEquals(ldnPre, c.id)
        assertTrue(c.body.containsKey("GC"))
        assertNotNull(c.away)
        assertNotNull(c.home)
        assertEquals(mapOf("14" to 8), c.ranks!!.gf)
        assertFalse(c.playoff)
        // The headshot builder is the endpoints' (the fake panel's leaguestat route under the override).
        assertEquals(server.url("/leaguestat/ohl/240x240/9924.jpg").toString(), c.headshot("9924"))
        assertNull(c.headshot("../x"))
        // Tables: the decoded arrays (JSONP stripped).
        assertNotNull(rankCalls.single().first)
        assertNotNull(rankCalls.single().second)
        // The detail's server date is the preview's Date; the wire sums every read.
        val ok = a.ok()
        assertEquals(1_795_813_800_000L, ok.serverDateMs)
        assertEquals(ok.serverDateMs, ok.value.serverDateMs)
        assertTrue(ok.wireBytes!! > 0)
    }

    @Test fun theRequestsAreTheCapturedOnes() = runBlocking {
        source().detailAnswer(ldnPre, GamePhase.SCHEDULED)
        val byView = requests.associateBy(::view)
        val names = { v: String -> byView.getValue(v).url.queryParameterNames.toList() }
        assertEquals(listOf("feed", "tab", "game_id", "fmt", "lang_code", "key", "client_code"), names("preview"))
        assertEquals("29246", byView.getValue("preview").url.queryParameter("game_id"))
        assertEquals(
            listOf("feed", "view", "type", "season_id", "team_id", "first", "limit", "fmt", "lang", "key", "client_code"),
            names("topgoalies"),
        )
        assertEquals(listOf("feed", "view", "groupTeamsBy", "context", "season", "special", "sort", "lang", "key", "client_code"), names("standard"))
        assertEquals("points", byView.getValue("standard").url.queryParameter("sort"))
        assertEquals("power_play_pct", byView.getValue("special").url.queryParameter("sort"))
        assertEquals("88", byView.getValue("special").url.queryParameter("season"))
        for (r in requests) {
            assertEquals("/hockeytech/feed/index.php", r.url.encodedPath)
            assertEquals(HockeyTechEndpoints.fakeKey(League.OHL), r.url.queryParameter("key"))
            assertEquals("ohl", r.url.queryParameter("client_code"))
        }
    }

    @Test fun cachedRanksMakeTheNextReadThreeUntilTheyAreThirtyMinutesOld() = runBlocking {
        val s = source()
        s.detailAnswer(ldnPre, GamePhase.PRE)
        requests.clear()
        now += SportsConstants.HUB_CHL_TABLES_TTL_MS - 1
        val a = s.detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(listOf("preview", "topgoalies", "topgoalies"), views())
        assertFalse(a.partial)
        assertEquals(mapOf("14" to 8), previewCalls.last().ranks!!.gf) // the cached ranks
        requests.clear()
        now += 1
        s.detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(5, requests.size)
        assertEquals(1, s.cachedRanksCount)
    }

    @Test fun atMostThreeReadsAreInFlight() = runBlocking {
        holdMs = 300L
        source().detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(5, requests.size)
        assertTrue("in flight ${maxInFlight.get()}", maxInFlight.get() <= ChlDetailSource.MAX_IN_FLIGHT)
        assertTrue("the reads after the preview run in parallel", maxInFlight.get() >= 2)
    }

    // ---------------------------------------------------------------- partial answers (§4.1, §4.4 a)

    @Test fun aFailedGoalieReadIsAPartialAnswerWithTheNamesOnly() = runBlocking {
        answers["topgoalies"] = { r -> if (r.url.queryParameter("team_id") == "20") json("", 503) else fixture(r) }
        val a = source().detailAnswer(ldnPre, GamePhase.PRE)
        assertTrue(a.fetch is Fetch.Ok)
        assertTrue(a.partial)
        assertFalse(a.landingFailed)
        val c = previewCalls.single()
        assertNull(c.away)
        assertNotNull(c.home)
        assertTrue(logs.any { it.contains("chl-detail → partial") })
    }

    @Test fun aGoalieBodyOfTheWrongShapeIsPartialToo() = runBlocking {
        answers["topgoalies"] = { json("([1,2])") }
        val a = source().detailAnswer(ldnPre, GamePhase.PRE)
        assertTrue(a.partial)
        assertNull(previewCalls.single().away)
        assertNull(previewCalls.single().home)
    }

    @Test fun failedTablesReuseTheLastGoodRanksHoweverOld() = runBlocking {
        val s = source()
        s.detailAnswer(ldnPre, GamePhase.PRE)
        val good = previewCalls.single().ranks
        now += 10 * SportsConstants.HUB_CHL_TABLES_TTL_MS
        answers["standard"] = { json("", 500) }
        answers["special"] = { json("", 500) }
        val a = s.detailAnswer(ldnPre, GamePhase.PRE)
        assertTrue(a.fetch is Fetch.Ok)
        assertTrue(a.partial)
        assertSame(good, previewCalls.last().ranks)
        // The stale cache is kept, so the next read asks for the tables again.
        requests.clear()
        answers.clear()
        val b = s.detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(5, requests.size)
        assertFalse(b.partial)
    }

    @Test fun oneTableFailingWithNothingCachedGivesTheRanksOfTheOtherUncached() = runBlocking {
        answers["special"] = { json("Invalid key.") }
        val s = source()
        val a = s.detailAnswer(ldnPre, GamePhase.PRE)
        assertTrue(a.partial)
        val r = previewCalls.single().ranks!!
        assertEquals(mapOf("14" to 8), r.gf)
        assertTrue(r.pp.isEmpty())
        assertNull(rankCalls.last().second)
        assertEquals(0, s.cachedRanksCount)
    }

    @Test fun bothTablesFailingWithNothingCachedGivesNoRanks() = runBlocking {
        answers["standard"] = { json("", 503) }
        answers["special"] = { json("{\"not\":\"an array\"}") }
        val a = source().detailAnswer(ldnPre, GamePhase.PRE)
        assertTrue(a.partial)
        assertNull(previewCalls.single().ranks)
        assertTrue(rankCalls.isEmpty())
    }

    @Test fun unmappableTablesArePartialAndNotCached() = runBlocking {
        ranksOut = { _, _ -> null }
        val s = source()
        val a = s.detailAnswer(ldnPre, GamePhase.PRE)
        assertTrue(a.partial)
        assertEquals(0, s.cachedRanksCount)
    }

    // ---------------------------------------------------------------- playoffs (§2.5)

    @Test fun thePlayoffsReadNoTablesAndPassNoRanks() = runBlocking {
        val playoff = ChlHubFixtures.text(ChlHubFixtures.PREVIEW_OHL_29246).replace("\"playoff\": \"0\"", "\"playoff\": \"1\"")
        answers["preview"] = { json(playoff) }
        val a = source().detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(listOf("preview", "topgoalies", "topgoalies"), views())
        assertFalse(a.partial)
        assertTrue(previewCalls.single().playoff)
        assertNull(previewCalls.single().ranks)
    }

    // ---------------------------------------------------------------- errors (§4.1)

    @Test fun aFailedPreviewFailsTheAnswerWithoutFurtherReads() = runBlocking {
        fun kind(r: MockResponse): FailKind {
            requests.clear()
            answers["preview"] = { r }
            val a = runBlocking { source().detailAnswer(ldnPre, GamePhase.PRE) }
            assertEquals(listOf("preview"), views())
            assertFalse(a.partial)
            return a.kind()
        }
        assertEquals(FailKind.REFUSED, kind(json("Invalid key.")))
        assertEquals(FailKind.REFUSED, kind(json("Client access denied.")))
        assertEquals(FailKind.HTTP_OTHER, kind(json("{\"error\": \"No such game\"}", 404)))
        assertEquals(FailKind.HTTP_429, kind(json("", 429)))
        assertEquals(FailKind.HTTP_5XX, kind(json("", 502)))
        assertEquals(FailKind.BAD_BODY, kind(json("<html>busy</html>")))
        assertEquals(FailKind.BAD_BODY, kind(json("([{\"sections\":[]}])"))) // an array where an object belongs
        assertEquals(FailKind.BAD_BODY, kind(json("{\"GC\":{}}"))) // no Preview
        assertEquals(FailKind.BAD_BODY, kind(json("{\"a\":\"" + "x".repeat(SportsConstants.MAX_BODY_BYTES.toInt()) + "\"}"))) // over the cap
        assertTrue(previewCalls.isEmpty())
    }

    @Test fun aMapperThatAnswersNullOrThrowsIsABadBody() = runBlocking {
        previewOut = { null }
        assertEquals(FailKind.BAD_BODY, source().detailAnswer(ldnPre, GamePhase.PRE).kind())
        previewOut = { throw IllegalStateException("bug") }
        assertEquals(FailKind.BAD_BODY, source().detailAnswer(ldnPre, GamePhase.PRE).kind())
        summaryOut = { null }
        assertEquals(FailKind.BAD_BODY, source().detailAnswer(ottFinal, GamePhase.FINAL).kind())
        summaryOut = { throw IndexOutOfBoundsException() }
        assertEquals(FailKind.BAD_BODY, source().detailAnswer(ottFinal, GamePhase.FINAL).kind())
    }

    // ---------------------------------------------------------------- the final

    @Test fun theFinalIsOneGameSummaryWithTheBoxOfTheSameParse() = runBlocking {
        val a = source().detailAnswer(ottFinal, GamePhase.FINAL)
        assertEquals(listOf("gamesummary"), views())
        val r = requests.single().url
        assertEquals(listOf("feed", "tab", "game_id", "fmt", "lang_code", "key", "client_code"), r.queryParameterNames.toList())
        assertEquals("29239", r.queryParameter("game_id"))
        val ok = a.ok()
        assertEquals(ChlHubFixtures.finalOhl29239().copy(serverDateMs = ok.serverDateMs), ok.value)
        assertEquals(ChlHubFixtures.boxOhl29239(), a.box!!.value)
        assertEquals(1_795_813_800_000L, a.box!!.serverDateMs)
        assertFalse(a.partial)
        assertTrue(previewCalls.isEmpty())
        assertTrue(rankCalls.isEmpty())
    }

    @Test fun theFinalsErrorsMapAsThePreviews() = runBlocking {
        answers["gamesummary"] = { json("{\"error\": \"No such game\"}", 404) }
        assertEquals(FailKind.HTTP_OTHER, source().detailAnswer(ottFinal, GamePhase.FINAL).kind())
        answers["gamesummary"] = { json("Invalid key.") }
        assertEquals(FailKind.REFUSED, source().detailAnswer(ottFinal, GamePhase.FINAL).kind())
    }

    // ---------------------------------------------------------------- no request outside pre-game and final (D5, R6)

    @Test fun liveBreaksPostponedAndNoHintMakeNoRequest() = runBlocking {
        val s = source()
        for (p in listOf(GamePhase.LIVE, GamePhase.INTERMISSION, GamePhase.POSTPONED, null)) {
            assertEquals(FailKind.HTTP_OTHER, s.detailAnswer(ldnPre, p).kind())
        }
        assertEquals(FailKind.HTTP_OTHER, (s.detail(ldnPre) as Fetch.Failed).kind)
        assertEquals(FailKind.HTTP_OTHER, (s.boxScore(ottFinal) as Fetch.Failed).kind)
        // Not a CHL game, or not a usable id.
        assertEquals(FailKind.HTTP_OTHER, s.detailAnswer(GameId(League.NHL, "2026020999"), GamePhase.PRE).kind())
        assertEquals(FailKind.HTTP_OTHER, s.detailAnswer(GameId(League.OHL, "29246&x=1"), GamePhase.PRE).kind())
        assertTrue(requests.isEmpty())
    }

    @Test fun trimDropsTheCachedRanks() = runBlocking {
        val s = source()
        s.detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(1, s.cachedRanksCount)
        s.trimMemory()
        assertEquals(0, s.cachedRanksCount)
        requests.clear()
        s.detailAnswer(ldnPre, GamePhase.PRE)
        assertEquals(5, requests.size)
    }

    // ---------------------------------------------------------------- logs (§4.1, m6-chl-leagues §2.2)

    @Test fun logsAreOneLinePerStateChangeWithNoUrlKeyIdOrBody() = runBlocking {
        val s = source()
        s.detailAnswer(ldnPre, GamePhase.PRE)
        s.detailAnswer(ldnPre, GamePhase.PRE)
        answers["preview"] = { json("Invalid key.") }
        s.detailAnswer(ldnPre, GamePhase.PRE)
        answers["preview"] = { json("{\"GC\":{\"Parameters\":{\"key\":\"" + HockeyTechEndpoints.fakeKey(League.OHL) + "\"}") }
        s.detailAnswer(ldnPre, GamePhase.PRE)
        answers["gamesummary"] = { MockResponse.Builder().code(200).body("{\"GC\":").build() }
        s.detailAnswer(ottFinal, GamePhase.FINAL)
        assertEquals(listOf("OHL chl-detail → ok", "OHL chl-detail → REFUSED", "OHL chl-detail → BAD_BODY"), logs.map { it.substringAfter(' ').trim() })
        for (l in logs) {
            assertFalse(l, l.contains("index.php"))
            assertFalse(l, l.contains("fakepanel"))
            assertFalse(l, l.contains("29246") || l.contains("29239"))
            assertFalse(l, l.contains("GC"))
            assertFalse(l, l.contains(server.hostName + ":" + server.port))
        }
    }
}
