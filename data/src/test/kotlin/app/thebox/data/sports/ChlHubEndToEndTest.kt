package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChlHubFixtures
import app.thebox.core.sports.hub.DetailCadence
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.Ranked
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import kotlin.random.Random

/**
 * (CHL hub integration, docs/design/m6-chl-hub.md "Integration record") The whole data path with nothing stood in: the
 * six fixture games' real bodies served over HTTP (MockWebServer, the fake panel's key) → the real [ChlDetailSource]
 * with its **default** mappers (`ChlDetailMapper`'s, as the `@Inject` constructor builds it) and the real headshot
 * builder (`HockeyTechEndpoints.headshot`) → the real [GameDetailRepositoryImpl] loop → [DetailState]. The states equal the
 * frozen `ChlHubFixtures` values (headshots on the endpoints' host instead of the fakes' test host), the final's box
 * comes from the same parse, the tables failing gives a partial `Ready` without ranks (not failing), and a
 * postponed-looking game (the feed's status 1: `HockeyTechMapper` has no POSTPONED for the CHL) is read as a scheduled one
 * at the 30 min cadence, never in a loop.
 *
 * The source is called with `runBlocking` inside the fetcher so the repository's virtual clock stays deterministic while
 * the HTTP is real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChlHubEndToEndTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var tablesFail = false

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = route(request) ?: return MockResponse.Builder().code(404).body("not found").build()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(ChlHubFixtures.text(path)).build()
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    /** The captured bodies by the request's own parameters; anything not captured is a 404 (a failed secondary read). */
    private fun route(r: RecordedRequest): String? {
        val u = r.url
        val client = u.queryParameter("client_code")
        assertEquals("the fake panel's key only", "fakepanel-$client", u.queryParameter("key"))
        return when (u.queryParameter("feed")) {
            "gc" -> {
                assertEquals("json", u.queryParameter("fmt"))
                when (u.queryParameter("tab") to u.queryParameter("game_id")) {
                    "preview" to "29246" -> ChlHubFixtures.PREVIEW_OHL_29246
                    "preview" to "1023348" -> ChlHubFixtures.PREVIEW_WHL_1023348
                    "preview" to "32953" -> ChlHubFixtures.PREVIEW_QMJHL_32953
                    "gamesummary" to "29239" -> ChlHubFixtures.SUMMARY_OHL_29239
                    "gamesummary" to "1023337" -> ChlHubFixtures.SUMMARY_WHL_1023337
                    "gamesummary" to "32952" -> ChlHubFixtures.SUMMARY_OT_QMJHL_32952
                    else -> null
                }
            }
            "modulekit" -> when (client to u.queryParameter("team_id")) {
                "ohl" to "14" -> ChlHubFixtures.GOALIES_OHL_T14
                "whl" to "206" -> ChlHubFixtures.GOALIES_WHL_T206
                "lhjmq" to "2" -> ChlHubFixtures.GOALIES_QMJHL_T2
                else -> null
            }
            "statviewfeed" -> {
                // As captured: no fmt (the JSONP array answer).
                assertEquals(null, u.queryParameter("fmt"))
                if (tablesFail) return null
                when (client to (u.queryParameter("special") == "true")) {
                    "ohl" to false -> ChlHubFixtures.TABLE_OHL
                    "ohl" to true -> ChlHubFixtures.TABLE_SPECIAL_OHL
                    "whl" to true -> ChlHubFixtures.TABLE_SPECIAL_WHL
                    "lhjmq" to false -> ChlHubFixtures.TABLE_QMJHL
                    else -> null
                }
            }
            else -> null
        }
    }

    private class Signals : SportsSignals {
        override val foreground = MutableStateFlow(true)
        override val online = MutableStateFlow(true)
    }

    private class Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private val wallBase = 1_795_813_800_000L
    private val dwell = SportsConstants.HUB_FETCH_DWELL_MS

    private class H(val repo: GameDetailRepositoryImpl, val calls: MutableList<Pair<GameId, GamePhase?>>, val times: MutableList<Long>) {
        val state: DetailState get() = repo.detail.value
        val ready: DetailState.Ready get() = state as DetailState.Ready
    }

    private fun endpoints() = HockeyTechEndpoints.fromOverride(server.url("/").toString())!!

    /** The real source (default mappers, real headshot builder) behind the repository's real loop. */
    private fun TestScope.harness(): H {
        val mono = MonoClock { testScheduler.currentTime }
        val source = ChlDetailSource(OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(), mono, { endpoints() })
        val calls = CopyOnWriteArrayList<Pair<GameId, GamePhase?>>()
        val times = CopyOnWriteArrayList<Long>()
        val fetcher = object : DetailFetcher {
            override suspend fun detail(id: GameId, phaseHint: GamePhase?): DetailAnswer {
                calls += id to phaseHint
                times += mono.nowMs()
                return runBlocking { source.detailAnswer(id, phaseHint) }
            }

            override suspend fun boxScore(id: GameId): Fetch<BoxScore> = runBlocking { source.boxScore(id) }
        }
        val store = FakeSettingsStore()
        store.update(SportsDomain) { SportsPrefs() }
        store.update(SportsFollowDomain) { SportsFollowDoc(pollPhaseMs = 1_500L) }
        val repo = GameDetailRepositoryImpl(
            fetcher, store, Awake(), Signals(), backgroundScope, mono, { wallBase + testScheduler.currentTime }, Random(3),
            onTrim = { source.trimMemory() }, chlHub = SportsConstants.CHL_HUB_ENABLED,
        )
        runCurrent()
        return H(repo, calls, times)
    }

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun game(league: League, id: String, phase: GamePhase, startUtcMs: Long = wallBase + 3_600_000L): GameSnapshot =
        SportsFixtures.game(id = id, phase = phase, league = league, startUtcMs = startUtcMs)

    /** The fakes' test host → the endpoints' headshot URL (`{logoBase}{client}/240x240/{id}.jpg`). */
    private fun onEndpoints(fake: Any): String =
        fake.toString().replace(Regex("""https://headshots\.test/(\w+)/(\d+)\.jpg""")) { m -> "${endpoints().logoBase}${m.groupValues[1]}/240x240/${m.groupValues[2]}.jpg" }

    private fun assertDetail(expected: GameDetail, got: GameDetail) = assertEquals(onEndpoints(expected), got.copy(serverDateMs = null).toString())

    @Test fun theBuildSwitchIsOn() {
        assertTrue(SportsConstants.CHL_HUB_ENABLED)
        assertTrue(GameDetailRepositoryImpl.detailed(League.OHL))
    }

    @Test fun theThreePreGamesFromHttpToReadyEqualTheFixtureValues() = runTest {
        val h = harness()
        val cases = listOf(
            game(League.OHL, "29246", GamePhase.SCHEDULED) to ChlHubFixtures.preOhl29246(),
            game(League.WHL, "1023348", GamePhase.SCHEDULED) to ChlHubFixtures.preWhl1023348(),
            game(League.QMJHL, "32953", GamePhase.PRE) to ChlHubFixtures.preQmjhl32953(),
        )
        for ((g, expected) in cases) {
            h.repo.show(g)
            settle(dwell)
            val r = h.ready
            assertDetail(expected, r.detail)
            assertFalse(r.failing)
            // Every captured set lacks one side's goalies (and the WHL / QMJHL one table): a partial answer, no error.
            assertTrue("${g.id}: one goalie read or table was not captured", r.partial)
        }
        assertEquals(listOf(GamePhase.SCHEDULED, GamePhase.SCHEDULED, GamePhase.PRE), h.calls.map { it.second })
    }

    @Test fun theThreeFinalsAndTheirBoxScoresFromOneParse() = runTest {
        val h = harness()
        val cases = listOf(
            Triple(game(League.OHL, "29239", GamePhase.FINAL, wallBase - DetailCadence.CHL.gameLengthMs), ChlHubFixtures.finalOhl29239(), ChlHubFixtures.boxOhl29239()),
            Triple(game(League.WHL, "1023337", GamePhase.FINAL, wallBase - DetailCadence.CHL.gameLengthMs), ChlHubFixtures.finalWhl1023337(), ChlHubFixtures.boxWhl1023337()),
            Triple(game(League.QMJHL, "32952", GamePhase.FINAL, wallBase - DetailCadence.CHL.gameLengthMs), ChlHubFixtures.finalOtQmjhl32952(), ChlHubFixtures.boxOtQmjhl32952()),
        )
        for ((g, expected, box) in cases) {
            val before = requests.size
            h.repo.show(g)
            settle(dwell)
            assertDetail(expected, h.ready.detail)
            assertFalse(h.ready.partial || h.ready.failing)
            val b = async { h.repo.boxScore(g.id) }
            runCurrent()
            assertEquals(onEndpoints(box), (b.await() as Fetch.Ok).value.toString())
            assertEquals("one gamesummary, the box from the same parse", 1, requests.size - before)
            assertEquals("gamesummary", requests.last().url.queryParameter("tab"))
        }
        // The stars are in: each final is read once in the hour.
        settle(SportsConstants.HUB_CHL_STARS_WAIT_MS)
        assertEquals(3, h.calls.size)
    }

    @Test fun tablesFailingIsAPartialReadyWithoutRanksThenTheReReadFillsThem() = runTest {
        tablesFail = true
        val h = harness()
        val g = game(League.OHL, "29246", GamePhase.SCHEDULED)
        h.repo.show(g)
        settle(dwell)
        val fake = ChlHubFixtures.preOhl29246()
        val s = fake.season!!
        fun Ranked?.bare() = this?.copy(rank = null)
        val unranked = fake.copy(
            season = s.copy(
                awayGf = s.awayGf.bare(), homeGf = s.homeGf.bare(), awayGa = s.awayGa.bare(), homeGa = s.homeGa.bare(),
                awayPp = s.awayPp.bare(), homePp = s.homePp.bare(), awayPk = s.awayPk.bare(), homePk = s.homePk.bare(),
            ),
        )
        assertDetail(unranked, h.ready.detail)
        assertTrue(h.ready.partial)
        assertFalse("a partial answer is not an error", h.ready.failing)
        // The tables come back: the 120 s re-read fills the ranks.
        tablesFail = false
        settle(SportsConstants.HUB_CHL_PARTIAL_RETRY_MS)
        assertEquals(2, h.calls.size)
        assertDetail(fake, h.ready.detail)
    }

    @Test fun aPostponedLookingGameReadsAsScheduledAtTheThirtyMinuteCadence() = runTest {
        // The CHL marks a postponed game only with status 1 and game_status "Postponed" (the fake panel's hub_postponed);
        // HockeyTechMapper.classify maps status 1 to SCHEDULED, so the board never says POSTPONED (no captured sample).
        // Its start long past, the game must read as an ordinary pre-game: Ready, no crash, no request loop.
        // The fixtures' set always lacks NIAG's goalies (a partial answer): the 120 s re-read runs HUB_CHL_PARTIAL_RETRIES
        // times, then the 30 min cadence.
        val h = harness()
        val g = game(League.OHL, "29246", GamePhase.SCHEDULED, startUtcMs = wallBase - 6 * 3_600_000L)
        h.repo.show(g)
        settle(dwell + 3 * SportsConstants.HUB_NFL_PRE_MS)
        val p = SportsConstants.HUB_CHL_PARTIAL_RETRY_MS
        val pre = SportsConstants.HUB_NFL_PRE_MS
        assertEquals(3, SportsConstants.HUB_CHL_PARTIAL_RETRIES)
        assertEquals(listOf(0L, p, 2 * p, 3 * p, 3 * p + pre, 3 * p + 2 * pre).map { dwell + it }, h.times)
        assertTrue(h.calls.all { it.second == GamePhase.SCHEDULED })
        assertTrue(h.ready.partial)
        assertFalse(h.ready.failing)
        assertDetail(ChlHubFixtures.preOhl29246(), h.ready.detail)
        val box = async { h.repo.boxScore(g.id) }
        runCurrent()
        assertEquals(FailKind.HTTP_OTHER, (box.await() as Fetch.Failed).kind)
        // Preview + 2 goalie reads (one 404s) + the 2 tables when the 30 min ranks are due: never more than 5 a read.
        assertTrue("${requests.size} requests for 6 reads", requests.size <= 6 * 5)
    }
}
