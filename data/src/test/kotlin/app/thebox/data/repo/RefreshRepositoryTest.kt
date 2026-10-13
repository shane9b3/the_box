package app.thebox.data.repo

import app.thebox.core.common.AuthReason
import app.thebox.core.common.ImportGate
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.refresh.DetectedCredentials
import app.thebox.core.refresh.PlaylistDifferImpl
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamCatalogDecoder
import app.thebox.core.xtream.XtreamServerInfo
import app.thebox.data.contracts.XtreamAction
import app.thebox.data.contracts.XtreamLogin
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.testutil.FakeDiffer
import app.thebox.data.testutil.FakeXtreamDecoder
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §3.10 refresh orchestration with fakes for A/B/C; refresh_log rows on every outcome. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefreshRepositoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph

    private val url = "http://panel.example:8080/get.php?username=alice&password=SECRETPW&type=m3u_plus"
    private val detected = DetectedCredentials("http://panel.example:8080", "alice", "SECRETPW")

    private fun m3u(vararg names: String, epg: String = "http://panel.example:8080/xmltv.php?username=alice&password=SECRETPW") = buildString {
        append("#EXTM3U url-tvg=\"$epg\"\n")
        names.forEachIndexed { i, n ->
            append("#EXTINF:-1 tvg-id=\"$n.uk\" group-title=\"News\",$n\n")
            append("http://panel.example:8080/live/alice/SECRETPW/${i + 1}.ts\n")
        }
    }

    @Before
    fun setUp() {
        db = TestDb.create()
        g = TestGraph(db)
    }

    @After fun tearDown() { db.close() }

    private suspend fun addPlaylist(): Long = (g.playlists.addM3uUrl("My list", url) as Outcome.Ok).value

    @Test
    fun m3uRefreshEndToEndWithB1LoginAndNoCredentialAnywhere() = runBlocking {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV")
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), 0))
        val r = g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL)

        assertNull(r.error)
        assertEquals(RefreshOutcome.SUCCESS, r.report!!.outcome)
        assertEquals(3, r.report!!.added)
        assertEquals(3L, db.scalar("SELECT COUNT(*) FROM channel"))
        val log = db.refreshLogDao().latest("PLAYLIST", id).single()
        assertEquals("SUCCESS", log.outcome)
        assertEquals("MANUAL", log.trigger)
        // Detected credentials went into the secret; B1 login used them and stored the panel zone.
        val secret = g.playlists.secret(db.playlistDao().get(id)!!) as Outcome.Ok
        assertEquals("alice", secret.value.username)
        assertEquals("http://panel.example:8080", g.xtream.logins.single().baseUrls.single())
        assertEquals("Asia/Tokyo", db.playlistDao().get(id)!!.serverZone)
        assertNotNull(db.xtreamAccountDao().get(id))
        // One PLAYLIST_HEADER source; new sources trigger an import.
        val src = db.epgSourceDao().forPlaylist(id).single()
        assertEquals("PLAYLIST_HEADER", src.origin)
        assertEquals("panel.example:8080", src.displaySource)
        assertEquals(listOf(Trigger.PLAYLIST_CHANGED), g.requester.imports)
        // B3: no TEXT column of any table contains the password.
        assertNoSecretInDatabase("SECRETPW")
        assertNoSecretInDatabase("alice")
        val header = db.playlistDao().get(id)!!.headerJson!!
        assertTrue(header, header.contains("xmltv.php"))
    }

    @Test
    fun notModifiedKeepsChannelsAndLogs() = runBlocking {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC")
        g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL)
        g.fetcher.notModified += url
        val r = g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC)
        assertEquals(RefreshOutcome.NOT_MODIFIED, r.report!!.outcome)
        assertNotNull(g.fetcher.requests.last().etag)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM channel"))
        assertEquals(listOf("NOT_MODIFIED", "SUCCESS"), db.refreshLogDao().latest("PLAYLIST", id, 5).map { it.outcome })
        assertEquals("NOT_MODIFIED", db.playlistDao().get(id)!!.lastStatus)
    }

    @Test
    fun networkAndAuthErrorsAreLoggedAndReturned() = runBlocking {
        val id = addPlaylist()
        g.fetcher.errors[url] = SourceError.Network(NetworkKind.CONNECT)
        val r = g.refresh().refresh(id, Trigger.PERIODIC)
        assertEquals(SourceError.Network(NetworkKind.CONNECT), r.error)
        assertEquals("FAILED", db.refreshLogDao().latest("PLAYLIST", id).single().outcome)
        assertEquals("net.connect", db.playlistDao().get(id)!!.lastStatus)
        g.fetcher.errors[url] = SourceError.Auth(AuthReason.BAD_CREDENTIALS)
        assertEquals("auth.bad_credentials", g.refresh().refresh(id, Trigger.PERIODIC).error!!.code)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM refresh_log WHERE error_code = 'auth.bad_credentials'"))
    }

    @Test
    fun suspiciousShrinkFailsWithoutTouchingChannels() = runBlocking {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u(*Array(60) { "C$it" })
        g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL)
        assertEquals(60L, db.scalar("SELECT live_count FROM playlist"))
        g.fetcher.bodies[url] = m3u("C1", "C2")
        val r = g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC)
        assertEquals("shrink", r.error!!.code)
        assertEquals(RefreshOutcome.FAILED, r.report!!.outcome)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE missing_since IS NOT NULL"))
        assertEquals("shrink", db.refreshLogDao().latest("PLAYLIST", id).single().errorCode)
        // "Accept anyway".
        val forced = g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL, forceAcceptShrink = true)
        assertNull(forced.error)
        assertEquals(58L, db.scalar("SELECT COUNT(*) FROM channel WHERE missing_since IS NOT NULL"))
    }

    @Test
    fun credentialErrorsStopBeforeTheDownload() = runBlocking {
        val id = addPlaylist()
        g.playlists.clearSecretCache()
        g.creds.failGet = SourceError.Credentials(CredentialFailure.UNAVAILABLE)
        val r = g.refresh().refresh(id, Trigger.PERIODIC)
        assertEquals("cred.unavailable", r.error!!.code)
        assertTrue(g.fetcher.requests.isEmpty())
        assertFalse("transient: no re-auth flag", db.playlistDao().get(id)!!.needsReauth)
        g.creds.failGet = SourceError.Credentials(CredentialFailure.KEY_INVALIDATED)
        g.refresh().refresh(id, Trigger.PERIODIC)
        assertTrue(db.playlistDao().get(id)!!.needsReauth)
    }

    @Test
    fun xtvgUrlChangeKeepsSourceIdAndMappings() = runBlocking {
        // S14
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC", epg = "http://epg.one/guide.xml?token=abc")
        g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL)
        val src = db.epgSourceDao().forPlaylist(id).single()
        db.epgMappingDao().upsert(EpgMappingEntity(id, "tvg:BBC.uk", src.id, "bbc", 1))

        g.fetcher.bodies[url] = m3u("BBC", epg = "http://epg.two/guide.xml?token=xyz")
        g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL)
        val after = db.epgSourceDao().forPlaylist(id).single()
        assertEquals(src.id, after.id)
        assertEquals(src.credentialRef, after.credentialRef)
        assertTrue(src.urlHash != after.urlHash)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM epg_mapping"))
        val secret = (g.playlists.secret(after.credentialRef) as Outcome.Ok).value
        assertEquals("http://epg.two/guide.xml?token=xyz", secret.urls.single())
        // Dropping the header URL removes the source.
        g.fetcher.bodies[url] = m3u("BBC", epg = "")
        g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL)
        assertTrue(db.epgSourceDao().forPlaylist(id).isEmpty())
    }

    @Test
    fun xtreamRefreshStoresAccountAndBaseIndex() = runBlocking {
        val id = (g.playlists.addXtream("X", listOf("http://a.example", "http://b.example"), "bob", "pw123") as Outcome.Ok).value
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), baseIndexUsed = 1))
        g.xtream.downloads[XtreamAction.LIVE_CATEGORIES] = "1|News\n2|Sport\n"
        g.xtream.downloads[XtreamAction.LIVE_STREAMS] = "10|BBC|1\n11|ESPN|2\n"
        val r = g.refresh().refresh(id, Trigger.ONBOARDING)
        assertNull(r.error)
        assertEquals(2L, db.scalar("SELECT COUNT(*) FROM channel"))
        assertEquals(listOf("xcat:1", "xcat:2"), db.strings("SELECT group_key FROM channel_group ORDER BY group_key"))
        // Xtream channel URLs arrive templated from the decoder and are stored untouched.
        assertEquals("{{base}}/live/{{u}}/{{p}}/10.{{ext}}", db.strings("SELECT url FROM channel ORDER BY sort_index").first())
        assertEquals(1, db.playlistDao().get(id)!!.activeBaseIndex)
        assertNotNull(db.xtreamAccountDao().get(id))
        assertNoSecretInDatabase("pw123")
    }

    // ---- amended 2026-10-13: templating happens exactly once, gate is the shared import gate ----------------

    @Test
    fun xtreamUrlsAreNeverTemplatedTwice() = runBlocking {
        // Credentials that collide with the template's literal words: any second pass would rewrite them.
        val id = (g.playlists.addXtream("X", listOf("http://a.example"), "live", "base1") as Outcome.Ok).value
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), baseIndexUsed = 0))
        g.xtream.downloads[XtreamAction.LIVE_CATEGORIES] = "1|News\n"
        g.xtream.downloads[XtreamAction.LIVE_STREAMS] = "10|BBC|1\n11|ITV|1\n"
        val differ = PlaylistDifferImpl(StableKeyDeriverImpl())
        assertNull(g.refresh(differ = differ).refresh(id, Trigger.MANUAL).error)
        val first = db.strings("SELECT url FROM channel ORDER BY sort_index")
        assertEquals(listOf("{{base}}/live/{{u}}/{{p}}/10.{{ext}}", "{{base}}/live/{{u}}/{{p}}/11.{{ext}}"), first)
        val again = g.refresh(differ = differ).refresh(id, Trigger.PERIODIC).report!!
        assertEquals(2, again.unchanged)
        assertEquals(0, again.updated)
        assertEquals(first, db.strings("SELECT url FROM channel ORDER BY sort_index"))
    }

    @Test
    fun anUntemplatedXtreamUrlIsRefusedNotStored() = runBlocking {
        val id = (g.playlists.addXtream("X", listOf("http://a.example"), "bob", "pw123") as Outcome.Ok).value
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), baseIndexUsed = 0))
        g.xtream.downloads[XtreamAction.LIVE_CATEGORIES] = "1|News\n"
        g.xtream.downloads[XtreamAction.LIVE_STREAMS] = "10|BBC|1\n"
        val fake = FakeXtreamDecoder()
        val raw = object : XtreamCatalogDecoder by fake {
            override fun liveChannels(input: java.io.InputStream, categories: List<app.thebox.core.xtream.XtreamCategory>) =
                fake.liveChannels(input, categories).map { it.copy(url = "http://a.example/live/bob/pw123/10.ts") }
        }
        val r = g.refresh(differ = PlaylistDifferImpl(StableKeyDeriverImpl()), decoder = raw).refresh(id, Trigger.MANUAL)
        assertEquals("internal", r.error!!.code)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel"))
        assertNoSecretInDatabase("pw123")
    }

    @Test
    fun xtreamShapedM3uIsTemplatedOnceAcrossRefreshes() = runBlocking {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV")
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), 0))
        val differ = PlaylistDifferImpl(StableKeyDeriverImpl())
        assertNull(g.refresh(differ = differ).refresh(id, Trigger.MANUAL).error)
        val stored = db.strings("SELECT url FROM channel ORDER BY sort_index")
        assertEquals((1..3).map { "http://panel.example:8080/live/{{u}}/{{p}}/$it.ts" }, stored)
        val again = g.refresh(differ = differ).refresh(id, Trigger.PERIODIC).report!!
        assertEquals(3, again.unchanged)
        assertEquals(stored, db.strings("SELECT url FROM channel ORDER BY sort_index"))
        assertNoSecretInDatabase("SECRETPW")
    }

    @Test
    fun theMergeAwaitsTheSharedImportGate() = runBlocking {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC", "CNN")
        var waits = 0
        g.importMutex.gate = ImportGate { waits++ }
        assertNull(g.refresh(differ = PlaylistDifferImpl(StableKeyDeriverImpl())).refresh(id, Trigger.MANUAL).error)
        assertTrue("merge batches pause on ImportMutex.gate", waits >= 1)
        // EpgRepository exposes the same gate.
        assertTrue(g.epg.gate === g.importMutex.gate)
    }

    private fun account() = XtreamAccountInfo(
        auth = true, status = AccountStatus.ACTIVE, maxConnections = 2,
        server = XtreamServerInfo(timezone = "Asia/Tokyo"), measuredAtDeviceMs = 1,
    )

    private suspend fun assertNoSecretInDatabase(secret: String) {
        val tables = db.strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'channel_fts_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'")
        for (t in tables) {
            if (t == "credential") continue // AEAD blobs
            for (c in db.strings("SELECT name FROM pragma_table_info('$t')")) {
                val hits = db.scalar("SELECT COUNT(*) FROM $t WHERE CAST($c AS TEXT) LIKE ?", "%$secret%")
                assertEquals("$t.$c contains the secret", 0L, hits)
            }
        }
    }
}
