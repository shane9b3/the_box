package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.EspnDetailMapper
import app.thebox.core.sports.hub.EspnSummaryDto
import app.thebox.core.sports.hub.NflHubFixtures
import app.thebox.core.sports.hub.NflHubPipeline
import app.thebox.data.sports.EspnHubTestSupport.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * (NFL hub integration, docs/design/m6-nfl-hub.md §18) The real pipeline with no stand-in: each [NflHubPipeline] case's
 * summary served by a MockWebServer → `EspnDetailSource` (the real `EspnDetailMapper`, the debug-override http rule, the
 * source's skew and `Date`) → `DetailFetcher.of` → `GameDetailRepositoryImpl` (real time; no rest) → `DetailState.Ready`
 * equal to [NflHubPipeline.expected] (which the `:ui` pipeline test publishes into the view model), and the box score
 * through the loop's `Input.Box` from the same single parse. Also: the catalog the resolver plays from, the fake panel's
 * summary through the real DTO and mapper (§8.4), and the real pipeline's allocation for the largest fixture at 600 KB.
 */
class NflHubPipelineDataTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var case: NflHubPipeline.Case = NflHubPipeline.FINAL_HIGHLIGHT
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    return json(case.body, 200, "Date" to httpDate(case.serverDateMs))
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun httpDate(ms: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    private val mono = MonoClock { System.nanoTime() / 1_000_000L }
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
    private fun endpoints() = EspnEndpoints.fromOverride(server.url("/").toString())!!

    private fun source(catalog: EspnClipCatalog) =
        EspnDetailSource(client, mono, endpoints = ::endpoints, catalog = catalog, wallNow = { case.wallMs })

    private class Signals : SportsSignals {
        override val foreground = MutableStateFlow(true)
        override val online = MutableStateFlow(true)
    }

    private class Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    /** The app's router (`DetailFetcher.of`'s NFL branch) over the real source; the NHL source is not involved. */
    private fun fetcher(espn: EspnDetailSource) = object : DetailFetcher {
        override suspend fun detail(id: GameId, phaseHint: GamePhase?): DetailAnswer = espn.detailAnswer(id, phaseHint)
        override suspend fun boxScore(id: GameId) = espn.boxScore(id)
    }

    @Test fun everyCaseThroughTheSourceAndTheRepositoryEqualsTheExpectedDetail() = runBlocking {
        for (c in NflHubPipeline.CASES + NflHubPipeline.NAMES_AND_INJURIES) {
            case = c
            requests.clear()
            val catalog = EspnClipCatalog()
            val espn = source(catalog)
            val store = FakeSettingsStore()
            store.update(SportsDomain) { SportsPrefs() }
            store.update(SportsFollowDomain) { SportsFollowDoc(pollPhaseMs = 1_500L) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repo = GameDetailRepositoryImpl(fetcher(espn), store, Awake(), Signals(), scope, mono, { c.wallMs }, dwellMs = 0L, onTrim = espn::trimMemory)
                val game = NflHubPipeline.game(c)
                repo.show(game)
                val ready = withTimeout(10_000L) { repo.detail.first { it is DetailState.Ready } } as DetailState.Ready
                val want = NflHubPipeline.expected(c) ?: error("$c does not map")
                assertEquals("$c: the repository publishes exactly the real mapper's detail with the source's skew", want, ready.detail)
                assertEquals("$c: skew", c.skewMs, ready.detail.clockSkewMs)
                assertEquals("$c: server date", c.serverDateMs, ready.detail.serverDateMs)
                // The box score from the same parse: the loop's slot, no second request.
                val box = repo.boxScore(c.id)
                assertEquals("$c: box", NflHubPipeline.box(c), (box as Fetch.Ok).value)
                assertEquals("$c: one summary request for detail and box", 1, requests.size)
                assertEquals("/apis/site/v2/sports/football/nfl/summary", requests.single().url.encodedPath)
                assertEquals("event=${c.id.id}", requests.single().url.encodedQuery)
                // The catalog the resolver plays from holds exactly the offered clips, URLs included (http: the override).
                val offered = ready.detail.clips.map { it.videoId }.toSet()
                for (id in offered) assertNotNull("$c: catalog $id", catalog.get(id))
                assertEquals("$c: catalog skew", c.skewMs, catalog.skewMs)
            } finally {
                scope.cancel()
            }
        }
        assertEquals("one parse at a time with the real mapper", 1, maxInFlight.get())
    }

    /** The facts the panel and the sheets depend on, as the real pipeline produces them for each case. */
    @Test fun theRealPipelinesFactsPerCase() {
        val pre = NflHubPipeline.expected(NflHubPipeline.PRE)!!
        assertEquals(GamePhase.SCHEDULED, pre.fetchedPhase)
        assertEquals(6, pre.seasonStats.size)
        assertTrue("an Analysis clip is never offered", pre.clips.isEmpty())
        assertTrue(pre.recent.isNotEmpty())
        val live = NflHubPipeline.expected(NflHubPipeline.LIVE)!!
        assertEquals("the real Q2 capture", GamePhase.LIVE, live.fetchedPhase)
        assertEquals(4, live.plays.size)
        assertTrue("no in-game clip yet", live.clips.isEmpty())
        assertEquals(GamePhase.LIVE, NflHubPipeline.game(NflHubPipeline.LIVE).phase)
        val half = NflHubPipeline.expected(NflHubPipeline.LIVE_HALFTIME_PROVISIONAL)!!
        assertEquals(GamePhase.INTERMISSION, half.fetchedPhase)
        assertEquals(listOf(ClipKind.PLAY), half.clips.map { it.kind })
        val fin = NflHubPipeline.expected(NflHubPipeline.FINAL_HIGHLIGHT)!!
        assertEquals(listOf(ClipKind.RECAP, ClipKind.PLAY, ClipKind.PLAY, ClipKind.PLAY), fin.clips.map { it.kind })
        assertEquals(10, fin.plays.size)
        val expired = NflHubPipeline.expected(NflHubPipeline.FINAL_EXPIRED)!!
        assertTrue("the expired highlight is not offered by the detail", expired.clips.isEmpty())
        assertEquals("…nor by the board at its server date", null, NflHubPipeline.game(NflHubPipeline.FINAL_EXPIRED).media)
        assertNotNull("the MNF board offers its highlight at its date", NflHubPipeline.game(NflHubPipeline.FINAL_HIGHLIGHT).media?.recapId)
        val none = NflHubPipeline.expected(NflHubPipeline.NO_CLIP)!!
        assertTrue(none.clips.isEmpty())
        assertEquals("J. Cook III", none.leaders.first { it.category == "rushingYards" }.let { it.home ?: it.away }!!.name)
        val names = NflHubPipeline.expected(NflHubPipeline.NAMES_AND_INJURIES)!!
        assertEquals("A. St. Brown", names.leaders.first { it.category == "receivingYards" }.away!!.name)
        assertEquals("St. Brown", EspnDetailMapper.leaderSurname("A. St. Brown"))
        assertEquals("Cook III", EspnDetailMapper.leaderSurname("J. Cook III"))
        assertEquals(6, names.injuries.count { it.side == app.thebox.core.sports.Side.AWAY })
        assertEquals(17, names.injuryCounts[app.thebox.core.sports.Side.AWAY])
    }

    /** Production never offers an http clip: the same fixture with plain-http media through ESPN's own endpoints. */
    @Test fun productionEndpointsNeverOfferAnHttpClip() = runBlocking {
        assertTrue(!EspnEndpoints.DEFAULT.httpMediaOk)
        assertTrue(endpoints().httpMediaOk)
        case = NflHubPipeline.FINAL_HIGHLIGHT.copy(body = NflHubPipeline.FINAL_HIGHLIGHT.body.replace("https://", "http://"))
        val catalog = EspnClipCatalog()
        // ESPN's own endpoints, but the request still goes to the scripted server (the client rewrites the host).
        val prod = EspnDetailSource(
            client.newBuilder().addInterceptor { ch ->
                val u = ch.request().url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                ch.proceed(ch.request().newBuilder().url(u).build())
            }.build(),
            mono, endpoints = { EspnEndpoints.DEFAULT }, catalog = catalog, wallNow = { case.wallMs },
        )
        val d = (prod.detailAnswer(case.id, null).fetch as Fetch.Ok).value
        assertTrue("no http clip offered in production", d.clips.isEmpty())
        assertTrue("no http headshot either", d.leaders.all { p -> listOfNotNull(p.away, p.home).all { it.headshot == null } })
        assertEquals(0, catalog.size)
        // The override (the fake panel) offers them.
        val dbg = (source(EspnClipCatalog()).detailAnswer(case.id, null).fetch as Fetch.Ok).value
        assertEquals(4, dbg.clips.size)
    }

    /** The resolver plays a summary clip from the catalog the real pipeline filled: no record lookup. */
    @Test fun aClipFromTheRealSummaryStreamsFromTheCatalog() = runBlocking {
        case = NflHubPipeline.FINAL_HIGHLIGHT
        val catalog = EspnClipCatalog()
        val d = (source(catalog).detailAnswer(case.id, null).fetch as Fetch.Ok).value
        val resolver = EspnClipResolver(client, mono, endpoints = ::endpoints, catalog = catalog, wallNow = { case.wallMs })
        val before = requests.size
        val r = resolver.stream(ClipRef(d.clips.first().videoId, ClipKind.RECAP, case.id))
        assertTrue(r is ClipResult.Ok)
        assertEquals("no record lookup", before, requests.size)
        // The kill switch: the router answers NotFound for NFL refs without a call.
        val off = ClipResolvers(resolver, resolver, espnVideo = false)
        assertEquals(ClipResult.NotFound, off.stream(ClipRef(d.clips.first().videoId, ClipKind.RECAP, case.id)))
    }

    // ---------------------------------------------------------------- the fake panel's summary (§8.4, integration 2k)

    /**
     * `fakepanel-summary-*.json` are written by `tools/fakepanel/test_nfl_summary_golden.py` from `sports_nfl.summary`
     * (which asserts the live fake panel still produces their shape); here they go through the real DTO and mapper.
     */
    @Test fun theFakePanelsSummariesDecodeAndMapThroughTheRealPipeline() = runBlocking {
        val goldens = listOf("pre" to GamePhase.SCHEDULED, "live" to GamePhase.LIVE, "final" to GamePhase.FINAL)
        for ((name, phase) in goldens) {
            val body = SportsFixtures.text("sports/nfl/hub/fakepanel-summary-$name.json")
            val id = NflHubFixtures.JSON.parseToJsonElement(body).let { (it as kotlinx.serialization.json.JsonObject)["header"]!! }
                .let { (it as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"') }
            val videos = NflHubFixtures.JSON.decodeFromString(EspnSummaryDto.serializer(), body).videos.orEmpty()
            val published = videos.mapNotNull { v -> (v.originalPublishDate)?.let { Instant.parse(it).toEpochMilli() } }.maxOrNull()
            val wall = published?.plus(60_000L) ?: Instant.parse("2026-11-08T17:30:00Z").toEpochMilli()
            case = NflHubPipeline.Case("fakepanel $name", GameId(League.NFL, id), body, wall, wall)
            val a = source(EspnClipCatalog()).detailAnswer(case.id, null)
            val d = (a.fetch as? Fetch.Ok)?.value
            assertNotNull("fakepanel $name decodes and maps (not BAD_BODY): ${a.fetch}", d)
            d!!
            assertEquals(name, phase, d.fetchedPhase)
            assertNotNull("$name box", a.box)
            assertEquals("$name: venue", true, d.venue?.isNotBlank())
            when (name) {
                "pre" -> {
                    assertEquals(6, d.seasonStats.size)
                    assertTrue(d.recent.isNotEmpty())
                    assertTrue(d.injuries.isNotEmpty())
                    assertEquals("5.5", d.leaders.first { it.category == "sacks" }.home!!.line)
                }
                "live" -> {
                    assertTrue(d.plays.isNotEmpty())
                    assertTrue(d.gameStats.size >= 5)
                    assertTrue(a.box!!.value.away.tables.isNotEmpty())
                }
                "final" -> {
                    assertEquals(listOf(ClipKind.RECAP, ClipKind.PLAY, ClipKind.PLAY, ClipKind.PLAY), d.clips.map { it.kind })
                    assertNotNull(d.recapHeadline)
                }
            }
        }
    }

    // ---------------------------------------------------------------- memory (§5.4, integration 4)

    /**
     * The real pipeline's allocation per parse (the transport's capped decode + `EspnDetailMapper.detail` + `box` +
     * the catalog's video mapping) for the largest fixture (MNF) padded to a 600 KB body. Recorded for the device check
     * and bounded at the design's churn order (§5.3: 1–2 MB per parse; bound 6 MB).
     */
    @Test fun theRealPipelinesAllocationForTheLargestFixtureAt600Kb() {
        val base = SportsFixtures.text(NflHubFixtures.FINAL_MNF)
        val padKb = (600 * 1024 - base.length) / 1024
        val body = EspnHubTestSupport.padded(base, padKb)
        val bytes = body.encodeToByteArray()
        assertTrue("≈600 KB: ${bytes.size}", bytes.size in 590_000..640_000)
        val id = GameId(League.NFL, "401873078")
        val at = NflHubPipeline.FINAL_HIGHLIGHT.wallMs
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun once(): Int {
            val d = EspnHttp.decodeCapped(java.io.ByteArrayInputStream(bytes), EspnSummaryDto.serializer(), app.thebox.core.sports.SportsConstants.ESPN_SUMMARY_MAX_BYTES)
            val dto = (d as EspnHttp.Decoded.Ok).value
            val detail = EspnDetailMapper.detail(dto, id, at, httpOk = false)!!
            val box = EspnDetailMapper.box(dto)!!
            val entries = dto.videos.orEmpty().mapNotNull { app.thebox.core.sports.hub.EspnVideoMapper.entry(it, at, false) }
            return detail.clips.size + box.away.tables.size + entries.size
        }
        repeat(3) { once() }
        val me = Thread.currentThread().id
        val samples = List(5) {
            val before = threads.getThreadAllocatedBytes(me)
            once()
            threads.getThreadAllocatedBytes(me) - before
        }.sorted()
        val median = samples[2]
        val decodeOnly = List(5) {
            val before = threads.getThreadAllocatedBytes(me)
            EspnHttp.decodeCapped(java.io.ByteArrayInputStream(bytes), EspnSummaryDto.serializer(), app.thebox.core.sports.SportsConstants.ESPN_SUMMARY_MAX_BYTES)
            threads.getThreadAllocatedBytes(me) - before
        }.sorted()[2]
        val line = "NflHubPipelineDataTest allocation: ${bytes.size} B body; decode+map+box+catalog ${median} B (decode alone ${decodeOnly} B; mapping ${median - decodeOnly} B)"
        println(line)
        File(System.getProperty("java.io.tmpdir"), "nfl-hub-pipeline-alloc.txt").writeText(line + "\n")
        assertTrue(line, median < 6L * 1024 * 1024)
    }
}
