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
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.testutil.FakeDiffer
import app.thebox.data.testutil.FakeXtreamDecoder
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    /** [epg] null writes a bare `#EXTM3U` line (no guide-link attribute at all). */
    private fun m3u(vararg names: String, epg: String? = "http://panel.example:8080/xmltv.php?username=alice&password=SECRETPW") = buildString {
        append(if (epg == null) "#EXTM3U\n" else "#EXTM3U url-tvg=\"$epg\"\n")
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
    fun headerCatchupSourceIsTemplated() = runBlocking {
        // Code review M1 B-1: the header-level catchup-source reached playlist.header_json with the password.
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC").replaceFirst(
            "#EXTM3U ",
            "#EXTM3U catchup=\"default\" catchup-source=\"http://panel.example:8080/timeshift/alice/SECRETPW/{duration:60}/{Y}-{m}-{d}:{H}-{M}/1.ts\" ",
        )
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), 0))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL).error)
        assertNoSecretInDatabase("SECRETPW")
        val header = db.playlistDao().get(id)!!.headerJson!!
        assertTrue(header, header.contains("/timeshift/{{u}}/{{p}}/{duration:60}/"))
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
    fun concurrentRefreshesOfOnePlaylistAreSerialised() = runBlocking {
        // Code review M1 S-1: playlist-periodic-<id> and playlist-refresh-<id> can run at once.
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC", "CNN")
        g.fetcher.delayMs = 150
        val repo = g.refresh(FakeDiffer(detected))
        val a = async(Dispatchers.IO) { repo.refresh(id, Trigger.PERIODIC) }
        val b = async(Dispatchers.IO) { repo.refresh(id, Trigger.APP_START) }
        assertNull(a.await().error)
        assertNull(b.await().error)
        assertEquals("one download at a time per playlist", 1, g.fetcher.maxInFlight.get())
        assertEquals(2L, db.scalar("SELECT COUNT(*) FROM channel"))
    }

    @Test
    fun unexpectedExceptionIsLoggedAsFailure() = runBlocking {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC")
        val boom = object : app.thebox.core.refresh.PlaylistDiffer {
            override fun merge(
                entries: app.thebox.core.refresh.EntryProducer, existing: app.thebox.core.refresh.ExistingChannels,
                sink: app.thebox.core.refresh.MergeSink, ctx: app.thebox.core.refresh.MergeContext, cancel: app.thebox.core.common.CancelCheck,
            ): app.thebox.core.refresh.RefreshReport = throw IllegalStateException("http://panel.example:8080/live/alice/SECRETPW/1.ts")
        }
        val r = g.refresh(boom).refresh(id, Trigger.PERIODIC)
        assertEquals("internal", r.error!!.code)
        val log = db.refreshLogDao().latest("PLAYLIST", id).single()
        assertEquals("FAILED", log.outcome)
        assertFalse(log.errorDetail.orEmpty().contains("SECRETPW"))
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
        // A header with an empty url-tvg keeps the last known link (decision 2026-11-11; this used to delete it).
        g.fetcher.bodies[url] = m3u("BBC", epg = "")
        g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL)
        assertEquals(listOf(after), db.epgSourceDao().forPlaylist(id))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM epg_mapping"))
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

    // ---- restore placeholders (docs/design/m5-restore-placeholder-guides.md §2) ------------------------------

    /** What `BackupRestorer` writes for a never-refreshed playlist: overrides, hash 0, a ref with no secret. */
    private suspend fun placeholder(pid: Long, ordinal: Int, origin: String = EpgSourceEntity.ORIGIN_PLAYLIST_HEADER): EpgSourceEntity {
        val id = db.epgSourceDao().insert(
            EpgSourceEntity(
                name = "Old box guide $ordinal", origin = origin, playlistId = pid, ordinal = ordinal, credentialRef = g.creds.newRef(),
                urlHash = EpgSourceEntity.PLACEHOLDER_HASH, displaySource = "", priority = 7 + ordinal, enabled = true, shiftMin = 60,
                assumeZone = "Europe/Oslo", createdAt = 1,
            ),
        )
        db.epgMappingDao().upsert(EpgMappingEntity(pid, "map-$ordinal", id, "x$ordinal.uk", 1))
        return db.epgSourceDao().get(id)!!.also { assertTrue(it.isPlaceholder) }
    }

    private fun epgs(vararg urls: String) = urls.joinToString(",")

    @Test
    fun theFirstRefreshFillsAPlaceholderInPlace() = runBlocking {
        val id = addPlaylist()
        val ph = placeholder(id, 0)
        assertTrue("inert before the refresh", db.epgSourceDao().active().isEmpty())
        g.fetcher.bodies[url] = m3u("BBC", epg = "http://epg.one/guide.xml?token=abc")
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL).error)
        val after = db.epgSourceDao().forPlaylist(id).single()
        assertEquals(ph.id, after.id)
        assertFalse(after.isPlaceholder)
        assertTrue(after.urlHash != 0L)
        assertEquals("epg.one", after.displaySource)
        assertEquals(ph.credentialRef, after.credentialRef)
        assertEquals("http://epg.one/guide.xml?token=abc", (g.playlists.secret(after.credentialRef) as Outcome.Ok).value.urls.single())
        assertEquals(listOf(ph.name, "${ph.priority}", "60", "Europe/Oslo", "true"), listOf(after.name, "${after.priority}", "${after.shiftMin}", after.assumeZone, "${after.enabled}"))
        assertEquals(listOf("map-0|${ph.id}"), db.strings("SELECT stable_key || '|' || source_id FROM epg_mapping"))
        assertEquals(listOf(Trigger.PLAYLIST_CHANGED), g.requester.imports)
        assertEquals(listOf(ph.id), db.epgSourceDao().active().map { it.id })
    }

    @Test
    fun aProviderListingFewerGuidesDropsTheExtraPlaceholderAndListingMoreAddsRows() = runBlocking {
        val id = addPlaylist()
        val p0 = placeholder(id, 0)
        placeholder(id, 1)
        g.fetcher.bodies[url] = m3u("BBC", epg = "http://epg.one/a.xml")
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL).error)
        assertEquals(listOf(p0.id), db.epgSourceDao().forPlaylist(id).map { it.id })
        assertEquals("the dropped guide's mapping cascades", listOf("map-0"), db.strings("SELECT stable_key FROM epg_mapping"))

        val id2 = (g.playlists.addM3uUrl("Two", "http://lists.example/two.m3u") as Outcome.Ok).value
        val q0 = placeholder(id2, 0)
        g.fetcher.bodies["http://lists.example/two.m3u"] = m3u("CNN", epg = epgs("http://epg.two/a.xml", "http://epg.two/b.xml"))
        assertNull(g.refresh(FakeDiffer()).refresh(id2, Trigger.MANUAL).error)
        val rows = db.epgSourceDao().forPlaylist(id2)
        assertEquals(listOf(0, 1), rows.map { it.ordinal })
        assertEquals(q0.id, rows[0].id)
        assertTrue(rows.none { it.isPlaceholder })
    }

    @Test
    fun aFailedDownloadLeavesThePlaceholderAlone() = runBlocking {
        val id = addPlaylist()
        val ph = placeholder(id, 0)
        g.fetcher.errors[url] = SourceError.Network(NetworkKind.CONNECT)
        assertTrue(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL).error != null)
        assertEquals(ph, db.epgSourceDao().get(ph.id))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM epg_mapping"))
        assertTrue(db.epgSourceDao().active().isEmpty())
    }

    @Test
    fun anXtreamPlaceholderIsFilledInPlace() = runBlocking {
        val id = (g.playlists.addXtream("X", listOf("http://a.example"), "bob", "pw123") as Outcome.Ok).value
        val ph = placeholder(id, 0, EpgSourceEntity.ORIGIN_XTREAM)
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), baseIndexUsed = 0))
        g.xtream.downloads[XtreamAction.LIVE_CATEGORIES] = "1|News\n"
        g.xtream.downloads[XtreamAction.LIVE_STREAMS] = "10|BBC|1\n"
        assertNull(g.refresh().refresh(id, Trigger.MANUAL).error)
        val after = db.epgSourceDao().forPlaylist(id).single()
        assertEquals(ph.id, after.id)
        assertFalse(after.isPlaceholder)
        assertEquals(listOf(60, 7), listOf(after.shiftMin, after.priority))
        assertTrue((g.playlists.secret(after.credentialRef) as Outcome.Ok).value.urls.single().contains("xmltv.php"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM epg_mapping WHERE source_id = ?", ph.id))
        assertEquals(listOf(Trigger.PLAYLIST_CHANGED), g.requester.imports)
        assertNoSecretInDatabase("pw123")
    }

    @Test
    fun aGuideLoginThatCannotBeStoredKeepsThePlaceholderAndAddsNoRow() = runBlocking {
        // Placeholder review S1: a live row without its secret would fail every guide build with cred.missing.
        val id = addPlaylist()
        val ph = placeholder(id, 0)
        g.fetcher.bodies[url] = m3u("BBC", epg = epgs("http://epg.one/a.xml", "http://epg.one/b.xml"))
        g.creds.failPut = SourceError.Credentials(CredentialFailure.UNAVAILABLE)
        assertNull(g.refresh(FakeDiffer()).refresh(id, Trigger.MANUAL).error)
        assertEquals(listOf(ph), db.epgSourceDao().forPlaylist(id))
        assertTrue(db.epgSourceDao().active().isEmpty())
        assertTrue(g.requester.imports.isEmpty())
        // The next refresh retries and fills it.
        g.creds.failPut = null
        assertNull(g.refresh(FakeDiffer()).refresh(id, Trigger.MANUAL).error)
        val rows = db.epgSourceDao().forPlaylist(id)
        assertEquals(listOf(ph.id), rows.filter { it.ordinal == 0 }.map { it.id })
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.isPlaceholder })
    }

    // ---- header guide links across refreshes (docs/decisions/2026-11-11-keep-header-guide-links.md) ----------

    private val linkA = "http://epg.a.example/guide.xml?token=one"
    private val linkB = "http://epg.b.example/guide.xml?token=two"
    private val linkC = "http://epg.c.example/guide.xml"

    /** A playlist whose header lists [links], refreshed once, with a manual match on each guide row (in order). */
    private suspend fun playlistWithMatchedLinks(vararg links: String): Pair<Long, List<EpgSourceEntity>> {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = epgs(*links))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL).error)
        val rows = db.epgSourceDao().forPlaylist(id)
        assertEquals(links.size, rows.size)
        rows.forEachIndexed { i, s -> db.epgMappingDao().upsert(EpgMappingEntity(id, "key-$i", s.id, "x$i.uk", 1)) }
        return id to rows
    }

    private suspend fun mappings() = db.strings("SELECT stable_key || '|' || source_id FROM epg_mapping ORDER BY stable_key")

    private suspend fun urlOf(s: EpgSourceEntity) = (g.playlists.secret(s.credentialRef) as Outcome.Ok).value.urls.single()

    @Test
    fun caseA_aHeaderWithTheSameLinksChangesNothing() = runBlocking {
        val (id, rows) = playlistWithMatchedLinks(linkA, linkB)
        val before = mappings()
        val imports = g.requester.imports.size
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        assertEquals(rows, db.epgSourceDao().forPlaylist(id))
        assertEquals(before, mappings())
        assertEquals("no guide rebuild for an unchanged set", imports, g.requester.imports.size)
    }

    @Test
    fun caseB_aHeaderWithNoLinksKeepsTheSourcesAndTheManualMatches() = runBlocking {
        val (id, rows) = playlistWithMatchedLinks(linkA, linkB)
        val userId = (g.epg.addUserSource("Mine", "http://user.example/epg.xml") as Outcome.Ok).value
        db.epgMappingDao().upsert(EpgMappingEntity(id, "key-user", userId, "u.uk", 1))
        val before = mappings()
        val imports = g.requester.imports.size
        // A bare #EXTM3U line: the attribute is missing altogether.
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = null)
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        assertEquals(rows, db.epgSourceDao().forPlaylist(id))
        assertEquals(listOf(linkA, linkB), rows.map { urlOf(it) })
        assertEquals(before, mappings())
        assertNotNull(db.epgSourceDao().get(userId))
        assertEquals(imports, g.requester.imports.size)
        // The attribute present but empty is the same case.
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = "")
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        assertEquals(rows, db.epgSourceDao().forPlaylist(id))
        assertEquals(before, mappings())
        // The links coming back is case (a): nothing changes.
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = epgs(linkA, linkB))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        assertEquals(rows, db.epgSourceDao().forPlaylist(id))
        assertEquals(before, mappings())
    }

    @Test
    fun caseC_aDroppedLinkRemovesOnlyItsOwnRowAndTheRemainingLinkKeepsItsMatches() = runBlocking {
        val (id, rows) = playlistWithMatchedLinks(linkA, linkB)
        val (a, b) = rows
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = epgs(linkB))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        val after = db.epgSourceDao().forPlaylist(id).single()
        assertEquals("B keeps its row", b.id, after.id)
        assertEquals(0, after.ordinal)
        assertEquals(linkB, urlOf(after))
        assertEquals(b.urlHash, after.urlHash)
        assertNull(db.epgSourceDao().get(a.id))
        // A's match goes with A (the provider withdrew that guide); B's match is untouched.
        assertEquals(listOf("key-1|${b.id}"), mappings())
    }

    @Test
    fun caseC_aReorderedHeaderKeepsEachLinksRowAndMatches() = runBlocking {
        val (id, rows) = playlistWithMatchedLinks(linkA, linkB)
        val (a, b) = rows
        val before = mappings()
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = epgs(linkB, linkA))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        val after = db.epgSourceDao().forPlaylist(id).associateBy { it.id }
        assertEquals(setOf(a.id, b.id), after.keys)
        assertEquals(listOf(1, 0), listOf(after.getValue(a.id).ordinal, after.getValue(b.id).ordinal))
        assertEquals(listOf(linkA, linkB), listOf(urlOf(after.getValue(a.id)), urlOf(after.getValue(b.id))))
        assertEquals(before, mappings())
    }

    @Test
    fun caseC_anAddedLinkAddsARowAndKeepsTheOthers() = runBlocking {
        val (id, rows) = playlistWithMatchedLinks(linkA)
        val before = mappings()
        val imports = g.requester.imports.size
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = epgs(linkC, linkA))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        val after = db.epgSourceDao().forPlaylist(id)
        assertEquals(2, after.size)
        val kept = after.single { it.id == rows[0].id }
        assertEquals(listOf(1, linkA), listOf(kept.ordinal, urlOf(kept)))
        assertEquals(listOf(0, linkC), after.single { it.id != rows[0].id }.let { listOf(it.ordinal, urlOf(it)) })
        assertEquals(before, mappings())
        assertEquals(imports + 1, g.requester.imports.size)
    }

    @Test
    fun regression_aChangedLinkStillUpdatesTheSourceInPlace() = runBlocking {
        // S14 still holds when the header is not empty: a link that is replaced (new host) takes over the free row.
        val (id, rows) = playlistWithMatchedLinks(linkA, linkB)
        val before = mappings()
        val imports = g.requester.imports.size
        g.fetcher.bodies[url] = m3u("BBC", "CNN", "ITV", epg = epgs(linkA, linkC))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error)
        val after = db.epgSourceDao().forPlaylist(id)
        assertEquals(rows.map { it.id }, after.map { it.id })
        assertEquals(listOf(linkA, linkC), after.map { urlOf(it) })
        assertEquals("epg.c.example", after[1].displaySource)
        assertTrue(after[1].urlHash != rows[1].urlHash)
        assertEquals(before, mappings())
        assertEquals(imports + 1, g.requester.imports.size)
    }

    @Test
    fun caseD_aFailedOrRejectedDownloadLeavesTheHeaderSourcesAlone() = runBlocking {
        val id = addPlaylist()
        g.fetcher.bodies[url] = m3u(*Array(60) { "C$it" }, epg = epgs(linkA, linkB))
        assertNull(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.MANUAL).error)
        val rows = db.epgSourceDao().forPlaylist(id)
        rows.forEachIndexed { i, s -> db.epgMappingDao().upsert(EpgMappingEntity(id, "key-$i", s.id, "x$i.uk", 1)) }
        val before = mappings()
        // Network failure.
        g.fetcher.errors[url] = SourceError.Network(NetworkKind.CONNECT)
        assertEquals("net.connect", g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error!!.code)
        g.fetcher.errors.remove(url)
        // Shrink guard, with a header that lost its links too.
        g.fetcher.bodies[url] = m3u("C1", epg = null)
        assertEquals("shrink", g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error!!.code)
        // Empty playlist (header line only).
        g.fetcher.bodies[url] = "#EXTM3U\n"
        assertTrue(g.refresh(FakeDiffer(detected)).refresh(id, Trigger.PERIODIC).error != null)
        assertEquals(rows, db.epgSourceDao().forPlaylist(id))
        assertEquals(before, mappings())
    }

    @Test
    fun xtreamGuideSourceAndMatchesSurviveRefreshes() = runBlocking {
        val id = (g.playlists.addXtream("X", listOf("http://a.example"), "bob", "pw123") as Outcome.Ok).value
        g.xtream.login = Outcome.Ok(XtreamLogin(account(), baseIndexUsed = 0))
        g.xtream.downloads[XtreamAction.LIVE_CATEGORIES] = "1|News\n"
        g.xtream.downloads[XtreamAction.LIVE_STREAMS] = "10|BBC|1\n"
        assertNull(g.refresh().refresh(id, Trigger.MANUAL).error)
        val src = db.epgSourceDao().forPlaylist(id).single()
        assertEquals(EpgSourceEntity.ORIGIN_XTREAM, src.origin)
        db.epgMappingDao().upsert(EpgMappingEntity(id, "key-0", src.id, "x.uk", 1))
        assertNull(g.refresh().refresh(id, Trigger.PERIODIC).error)
        assertEquals(listOf(src), db.epgSourceDao().forPlaylist(id))
        assertEquals(listOf("key-0|${src.id}"), mappings())
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
