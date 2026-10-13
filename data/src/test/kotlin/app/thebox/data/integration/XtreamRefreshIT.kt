package app.thebox.data.integration

import app.thebox.core.catchup.TimeBaseFactoryImpl
import app.thebox.core.common.Outcome
import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.refresh.PlaylistDifferImpl
import app.thebox.core.xtream.XtreamCatalogDecoderImpl
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.net.HttpModule
import app.thebox.data.repo.EpgMatchRepository
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.RefreshRepository
import app.thebox.data.repo.Trigger
import app.thebox.data.testutil.FakeM3uParser
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.scalar
import app.thebox.data.xtream.XtreamClient
import app.thebox.data.net.ContentCopier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * §5.6 `XtreamRefreshIT`: login, categories and streams from MockWebServer (the `xtream/` fixtures, clean and
 * quirks) through WP-C's client and decoder and WP-A's differ into library.db; account card; active_base_index.
 */
@Ignore("needs WP-A, WP-C")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XtreamRefreshIT {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var server: MockWebServer

    private fun fixture(name: String) = requireNotNull(javaClass.classLoader!!.getResource("xtream/$name")) { "missing xtream/$name" }.readText()

    @Before
    fun setUp() {
        db = TestDb.create()
        g = TestGraph(db)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.url.queryParameter("action")
                val body = when (action) {
                    null -> fixture("login_ok.json")
                    "get_live_categories" -> fixture("live_categories.json")
                    "get_live_streams" -> fixture("live_streams.json")
                    else -> "[]"
                }
                return MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "application/json").build()
            }
        }
        server.start()
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun repo(): RefreshRepository {
        val client = XtreamClient(HttpModule.apiClient(HttpModule.baseClient()), XtreamCatalogDecoderImpl(), Dispatchers.IO)
        val playlists = PlaylistRepository(db.playlistDao(), db.xtreamAccountDao(), db.epgSourceDao(), g.creds, TimeBaseFactoryImpl(), g.clock)
        val matches = EpgMatchRepository(db, db.channelDao(), db.epgSourceDao(), db.epgMappingDao(), db.epgMatchDao(), g.settings, g.requester, g.clock)
        return RefreshRepository(
            db, db.playlistDao(), db.epgSourceDao(), db.refreshLogDao(), playlists, matches, g.store, FakeM3uParser(),
            PlaylistDifferImpl(StableKeyDeriverImpl()), XtreamCatalogDecoderImpl(), client, g.fetcher,
            ContentCopier(TestDb.context, Dispatchers.IO), TimeBaseFactoryImpl(), g.importMutex, g.requester,
            File(g.cacheRoot, "playlists"), Dispatchers.IO, g.clock,
        )
    }

    @Test
    fun loginCategoriesStreamsAccountCardAndBaseRotation() = runBlocking {
        val dead = "http://127.0.0.1:1"
        val base = server.url("/").toString().trimEnd('/')
        val id = (g.playlists.addXtream("X", listOf(dead, base), "user", "pass") as Outcome.Ok).value
        val r = repo().refresh(id, Trigger.ONBOARDING)
        assertNull(r.error)
        assertEquals(20L, db.scalar("SELECT COUNT(*) FROM channel WHERE playlist_id = ?", id))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE url LIKE '%pass%'"))
        assertEquals(1, db.playlistDao().get(id)!!.activeBaseIndex)
        assertNotNull(g.playlists.account(id).first())
        assertEquals(1, db.epgSourceDao().forPlaylist(id).count { it.origin == "XTREAM" })
    }
}
