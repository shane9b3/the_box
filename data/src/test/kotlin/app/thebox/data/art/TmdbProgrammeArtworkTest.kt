package app.thebox.data.art

import app.thebox.core.art.ArtworkHint
import app.thebox.core.art.ArtworkStatus
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.GuideDomain
import app.thebox.data.di.WallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** The TMDB lookup against a scripted server (docs/decisions/2026-10-18-tmdb-programme-posters.md). */
class TmdbProgrammeArtworkTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val requests = ArrayList<RecordedRequest>()
    /** Scripted answers by path prefix; default = an empty result list. */
    private val answers = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()
    private val store = FakeSettingsStore()
    private var nowMs = 1_000_000L
    private val clock = WallClock { nowMs }
    private lateinit var art: TmdbProgrammeArtwork

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                val f = answers.entries.firstOrNull { path.startsWith(it.key) }?.value
                return f?.invoke(request) ?: json(RESULTS_EMPTY)
            }
        }
        server.start()
        art = build()
    }

    @After fun stop() = server.close()

    private fun build(dir: File = tmp.root, builtIn: String? = null) = TmdbProgrammeArtwork(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build(),
        store, dir, Dispatchers.Unconfined, clock, CoroutineScope(Dispatchers.Unconfined), builtIn,
        apiBase = server.url("/3/").toString(), imageBase = "https://image.test/w342", language = "en-GB",
    )

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    private fun results(vararg r: String) = """{"page":1,"results":[${r.joinToString(",")}]}"""
    private fun hit(name: String, poster: String?, type: String? = null, popularity: Double? = null) =
        """{"id":1,"name":${q(name)},"poster_path":${poster?.let { q(it) } ?: "null"}${type?.let { ""","media_type":"$it"""" } ?: ""}${popularity?.let { ""","popularity":$it""" } ?: ""}}"""
    private fun q(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    /** [art] becomes an instance whose build carries [key] (the only key there is: owner ruling 2026-10-19). */
    private fun keyOn(key: String = V3_KEY) {
        art = build(builtIn = key)
        requests.clear()
    }

    private fun last() = requests.last()

    // ---------------------------------------------------------------- off / the app's key (owner rulings 2026-10-18/19)

    @Test fun aBuildWithoutAKeyIsOffAndMakesNoRequest() {
        assertTrue("posters are on by default", store.flow(GuideDomain).value.posters)
        assertEquals(ArtworkStatus.OFF, art.status.value)
        assertNull(runBlocking { art.poster("Doctor Who") })
        assertNull(runBlocking { art.poster("Heat", ArtworkHint.MOVIE) })
        assertTrue(requests.isEmpty())
        assertEquals(0, art.requests)
        assertEquals(ArtworkStatus.OFF, art.status.value)
    }

    @Test fun theAppsOwnKeyWorksWithNoSetup() {
        val a = build(builtIn = " $APP_KEY ")
        assertEquals(ArtworkStatus.ON, a.status.value)
        answers["/3/search/multi"] = { json(results(hit("Doctor Who", "/dw.jpg", "tv"))) }
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { a.poster("Doctor Who") })
        assertEquals(APP_KEY, last().url.queryParameter("api_key"))
        assertNull(last().headers["Authorization"])
        assertEquals("the viewer's language, so a localised poster is preferred", "en-GB", last().url.queryParameter("language"))
    }

    @Test fun aBlankBuiltInKeyIsNoKey() {
        val a = build(builtIn = "  ")
        assertEquals(ArtworkStatus.OFF, a.status.value)
        assertNull(runBlocking { a.poster("Doctor Who") })
        assertTrue(requests.isEmpty())
    }

    @Test fun aV4TokenGoesAsABearerHeader() {
        keyOn(V4_TOKEN)
        answers["/3/search/multi"] = { json(results(hit("Doctor Who", "/dw.jpg", "tv"))) }
        runBlocking { art.poster("Doctor Who") }
        assertEquals("Bearer $V4_TOKEN", last().headers["Authorization"])
        assertNull(last().url.queryParameter("api_key"))
    }

    @Test fun switchingPostersOffStopsLookupsAndEmptiesTheCache() {
        val a = build(builtIn = APP_KEY)
        answers["/3/search/multi"] = { json(results(hit("Doctor Who", "/dw.jpg", "tv"))) }
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { a.poster("Doctor Who") })
        assertEquals(1, a.cache.size)
        store.update(GuideDomain) { it.copy(posters = false) }
        assertEquals(ArtworkStatus.OFF, a.status.value)
        assertEquals("a list of focused titles does not outlive the switch", 0, a.cache.size)
        assertNull(runBlocking { a.poster("Doctor Who") })
        assertEquals(1, requests.size)
        store.update(GuideDomain) { it.copy(posters = true) }
        assertEquals(ArtworkStatus.ON, a.status.value)
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { a.poster("Doctor Who") })
        assertEquals(2, requests.size)
    }

    @Test fun theOldCacheFileIsDroppedOnStart() {
        val legacy = File(tmp.root, TmdbProgrammeArtwork.LEGACY_CACHE_FILE)
        legacy.writeText("""{"ocean's 11":["/takarazuka.jpg",1]}""")
        build(builtIn = APP_KEY)
        assertFalse(legacy.exists())
    }

    // ---------------------------------------------------------------- lookups

    @Test fun theQueryIsTheCleanedTitleAndTheHintPicksTheEndpoint() {
        keyOn()
        answers["/3/search/"] = { json(results(hit("Doctor Who", "/dw.jpg", "tv"))) }
        runBlocking { art.poster("New: Doctor Who (2023)") }
        assertEquals("/3/search/multi", last().url.encodedPath)
        assertEquals("Doctor Who", last().url.queryParameter("query"))
        assertEquals("false", last().url.queryParameter("include_adult"))
        runBlocking { art.poster("Casualty - S38 E12", ArtworkHint.SERIES) }
        assertEquals("/3/search/tv", last().url.encodedPath)
        assertEquals("Casualty", last().url.queryParameter("query"))
        runBlocking { art.poster("Heat (R)", ArtworkHint.MOVIE) }
        assertEquals("/3/search/movie", last().url.encodedPath)
        assertEquals("Heat", last().url.queryParameter("query"))
    }

    @Test fun anExactTitleMatchWithAPosterBeatsTheFirstResultAndPeopleAreSkipped() {
        keyOn()
        answers["/3/search/multi"] = {
            json(results(hit("Doctor Who", "/person.jpg", "person"), hit("Doctor Who Confidential", "/conf.jpg", "tv", 40.0), hit("doctor who", "/dw.jpg", "tv", 30.0)))
        }
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { art.poster("Doctor Who") })
        answers["/3/search/multi"] = { json(results(hit("Something", null, "tv"), hit("Something Else", "/else.jpg", "movie"))) }
        assertEquals("https://image.test/w342/else.jpg", runBlocking { art.poster("Something") })
    }

    /** Owner finding 2026-10-18: "Ocean's 11" showed the Takarazuka stage musical, the only entry with that exact title. */
    @Test fun aFringeExactMatchLosesToThePopularFirstResult() {
        keyOn()
        answers["/3/search/multi"] = {
            json(results(hit("Ocean's Eleven", "/clooney.jpg", "movie", 52.0), hit("Ocean's Eleven", "/sinatra.jpg", "movie", 9.0), hit("Ocean's 11", "/takarazuka.jpg", "movie", 1.2)))
        }
        assertEquals("https://image.test/w342/clooney.jpg", runBlocking { art.poster("Ocean's 11") })
        // At a tenth of the first result's popularity the exact match is still the programme.
        answers["/3/search/multi"] = { json(results(hit("Heat Wave", "/wave.jpg", "movie", 20.0), hit("Heat", "/heat.jpg", "movie", 2.0))) }
        assertEquals("https://image.test/w342/heat.jpg", runBlocking { art.poster("Heat") })
    }

    @Test fun theTitleHeadIsTriedWhenTheFullTitleMatchesNothing() {
        keyOn()
        answers["/3/search/multi"] = { r ->
            if (r.url.queryParameter("query") == "Doctor Who") json(results(hit("Doctor Who", "/dw.jpg", "tv"))) else json(RESULTS_EMPTY)
        }
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { art.poster("Doctor Who: The Giggle") })
        assertEquals(listOf("Doctor Who: The Giggle", "Doctor Who"), requests.map { it.url.queryParameter("query") })
    }

    @Test fun hitsAndMissesAreCachedAcrossInstances() {
        keyOn()
        answers["/3/search/multi"] = { r ->
            if (r.url.queryParameter("query") == "Doctor Who") json(results(hit("Doctor Who", "/dw.jpg", "tv"))) else json(RESULTS_EMPTY)
        }
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { art.poster("Doctor Who") })
        assertNull(runBlocking { art.poster("Nothing Known") })
        assertEquals(2, requests.size)
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { art.poster("DOCTOR WHO (2005)") })
        assertNull(runBlocking { art.poster("Nothing Known") })
        assertEquals(2, requests.size)

        val fresh = build(builtIn = V3_KEY)
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { fresh.poster("Doctor Who") })
        assertEquals(2, requests.size)
        assertEquals(2, fresh.cache.size)
    }

    @Test fun a401OnTheAppsKeyStopsLookupsAndIsReportedAsTheAppsProblem() {
        keyOn(APP_KEY)
        answers["/3/search/multi"] = { json("""{"status_code":7}""", 401) }
        assertNull(runBlocking { art.poster("Doctor Who") })
        assertEquals(ArtworkStatus.APP_KEY_REJECTED, art.status.value)
        assertNull(runBlocking { art.poster("Other") })
        assertEquals(1, requests.size)
        assertNull(art.cache.get("any:doctor who"))
    }

    @Test fun failuresBackOffAndAreNotCached() {
        keyOn()
        answers["/3/search/multi"] = { json("", 503) }
        assertNull(runBlocking { art.poster("Doctor Who") })
        assertNull(runBlocking { art.poster("Doctor Who") })
        assertEquals(1, requests.size)
        nowMs += TmdbProgrammeArtwork.FAILURE_BACKOFF_MS + 1
        answers["/3/search/multi"] = { json(results(hit("Doctor Who", "/dw.jpg", "tv"))) }
        assertEquals("https://image.test/w342/dw.jpg", runBlocking { art.poster("Doctor Who") })
        assertEquals(2, requests.size)
    }

    @Test fun rateLimitingBacksOffBriefly() {
        keyOn()
        answers["/3/search/multi"] = { json("", 429) }
        assertNull(runBlocking { art.poster("Alpha") })
        assertNull(runBlocking { art.poster("Bravo") })
        assertEquals(1, requests.size)
        nowMs += TmdbProgrammeArtwork.RATE_LIMIT_BACKOFF_MS + 1
        runBlocking { art.poster("Charlie") }
        assertEquals(2, requests.size)
    }

    @Test fun aDeadServerIsNotAnError() {
        keyOn()
        server.close()
        assertNull(runBlocking { art.poster("Doctor Who") })
        assertEquals(ArtworkStatus.ON, art.status.value)
    }

    @Test fun nothingSearchableMakesNoRequest() {
        keyOn()
        assertNull(runBlocking { art.poster(" ") })
        assertNull(runBlocking { art.poster("(R)") })
        assertTrue(requests.isEmpty())
    }

    companion object {
        const val V3_KEY = "0123456789abcdef0123456789abcdef"
        const val APP_KEY = "fedcba9876543210fedcba9876543210"
        const val V4_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ0ZXN0In0.sig"
        const val RESULTS_EMPTY = """{"page":1,"results":[]}"""
    }
}
