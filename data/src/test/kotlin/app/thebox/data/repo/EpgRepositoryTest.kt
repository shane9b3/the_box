package app.thebox.data.repo

import app.thebox.core.common.NetworkKind
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.match.MatchMethod
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.epgChannel
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** EPG build/patch orchestration with FakeEpgStore (§3.10 EpgImportWorker), resolvedRefs (§2.2), ImportMutex (S9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpgRepositoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private var p = 0L
    private val xml = "http://epg.example/guide.xml"

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create()
        g = TestGraph(db)
        p = db.newPlaylist()
        db.newChannel(p, "bbc", "BBC One", tvgId = "bbc.uk", sortIndex = 0)
        db.newChannel(p, "cnn", "CNN", sortIndex = 1)
        Unit
    }

    @After fun tearDown() { db.close() }

    private suspend fun userSource(): Long {
        val id = (g.epg.addUserSource("Guide", xml) as Outcome.Ok).value
        g.fetcher.bodies[xml] = "<tv/>"
        g.store.catalogue[id] = listOf(epgChannel(id, "bbc.uk", "BBC One"), epgChannel(id, "cnn", "CNN"), epgChannel(id, "x", "X"))
        return id
    }

    @Test
    fun buildFetchesBuildsPromotesAndLogs() = runBlocking {
        val s = userSource()
        assertEquals(listOf(Trigger.MANUAL), g.requester.imports)
        val r = g.epg.build(Trigger.MANUAL)
        assertNull(r.error)
        assertEquals(1, g.store.builds.get())
        assertEquals(1, g.store.recovers.get())
        assertEquals(setOf("bbc.uk", "cnn"), g.store.imported[s])
        val src = File(g.cacheRoot, "epg/$s.src")
        assertTrue(src.exists())
        assertTrue(!File(g.cacheRoot, "epg/$s.new").exists())
        val row = db.epgSourceDao().get(s)!!
        assertTrue(row.etag != null && row.programmeCount == 20 && row.channelCount == 3)
        assertEquals("SUCCESS", db.refreshLogDao().latest("EPG_BUILD", null).single().outcome)
        assertEquals(2, db.epgMatchDao().autos().size)
        assertEquals(1, db.epgMatchDao().autos().count { it.hasData && it.xmltvId == "bbc.uk" })
    }

    @Test
    fun notModifiedEverywhereSkipsTheBuild() = runBlocking {
        userSource()
        g.epg.build(Trigger.MANUAL)
        g.fetcher.notModified += xml
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(r.skipped)
        assertEquals(1, g.store.builds.get())
        assertTrue(g.fetcher.requests.last().etag != null)
        // MANUAL forces a rebuild from the cached file.
        g.epg.build(Trigger.MANUAL, force = true)
        assertEquals(2, g.store.builds.get())
    }

    @Test
    fun failedSourceFallsBackToTheLastGoodFile() = runBlocking {
        val s = userSource()
        g.epg.build(Trigger.MANUAL)
        g.fetcher.bodies[xml] = "<tv>new but broken</tv>"
        g.store.failSource = s
        val r = g.epg.build(Trigger.PERIODIC)
        assertNull(r.error)
        assertEquals(3, g.store.builds.get())
        assertTrue(g.store.lastBuild!!.sources.single().file.name.endsWith(".src"))
    }

    @Test
    fun downloadErrorWithoutCacheFailsAndIsLogged() = runBlocking {
        userSource()
        g.fetcher.errors[xml] = SourceError.Network(NetworkKind.DNS)
        val r = g.epg.build(Trigger.PERIODIC)
        assertEquals("net.dns", r.error!!.code)
        assertEquals(0, g.store.builds.get())
        assertEquals("FAILED", db.refreshLogDao().latest("EPG_BUILD", null).single().outcome)
    }

    @Test
    fun transientFailureOfASourceWithoutCachedFileKeepsTheCurrentGuide() = runBlocking {
        // Code review M1 S-10: without a last-good file a rebuild would publish the guide without that source.
        val a = userSource()
        val xml2 = "http://epg2.example/guide.xml"
        val b = (g.epg.addUserSource("Guide 2", xml2) as Outcome.Ok).value
        g.fetcher.bodies[xml2] = "<tv/>"
        g.store.catalogue[b] = listOf(epgChannel(b, "cnn", "CNN"))
        assertNull(g.epg.build(Trigger.MANUAL).error)
        assertEquals(1, g.store.builds.get())
        File(g.cacheRoot, "epg/$b.src").delete() // KeepCachedXmltv policy dropped it
        g.fetcher.bodies[xml] = "<tv>changed</tv>"
        g.fetcher.errors[xml2] = SourceError.Network(NetworkKind.TIMEOUT)
        val r = g.epg.build(Trigger.PERIODIC)
        assertEquals("net.timeout", r.error!!.code)
        assertEquals("no rebuild without source $b", 1, g.store.builds.get())
        assertTrue(File(g.cacheRoot, "epg/$a.src").exists())
    }

    @Test
    fun runtimeErrorDuringBuildIsLoggedAsFailure() = runBlocking {
        // Code review M1 S-13: e.g. SQLITE_FULL escaped the worker with no refresh_log row.
        userSource()
        g.store.duringBuild = { throw IllegalStateException("disk full") }
        val r = g.epg.build(Trigger.PERIODIC)
        assertEquals("internal", r.error!!.code)
        assertEquals("FAILED", db.refreshLogDao().latest("EPG_BUILD", null).single().outcome)
    }

    @Test
    fun concurrentBuildsDoNotDownloadAtTheSameTime() = runBlocking {
        // Code review M1 S-2: epg-periodic and epg-import are different unique works and both run on first start.
        val s = userSource()
        g.fetcher.delayMs = 150
        val a = async(Dispatchers.IO) { g.epg.build(Trigger.PERIODIC) }
        val b = async(Dispatchers.IO) { g.epg.build(Trigger.APP_START) }
        assertNull(a.await().error)
        assertNull(b.await().error)
        assertEquals(1, g.fetcher.maxInFlight.get())
        assertTrue(File(g.cacheRoot, "epg/$s.src").exists())
    }

    @Test
    fun noSourcesIsANoOp() = runBlocking {
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        assertTrue(g.epg.patch().skipped)
    }

    @Test
    fun patchImportsPendingMappedRefs() = runBlocking {
        val s = userSource()
        g.epg.build(Trigger.MANUAL)
        db.epgMappingDao().upsert(EpgMappingEntity(p, "cnn", s, "x", 1))
        val r = g.epg.patch()
        assertNull(r.error)
        assertEquals(listOf(EpgChannelRef(s, "x")), g.store.lastPatch!!.refs)
        assertTrue("x" in g.store.imported[s]!!)
        assertEquals("SUCCESS", db.refreshLogDao().latest("EPG_PATCH", null).single().outcome)
        assertTrue("nothing left to patch", g.epg.patch().skipped)
    }

    @Test
    fun resolvedRefsFollowMappingPriorityAndShift() = runBlocking {
        val a = db.epgSourceDao().insert(EpgSourceEntity(name = "a", origin = "USER", credentialRef = "r1", urlHash = 1, displaySource = "h", priority = 0, shiftMin = 30, createdAt = 1))
        val b = db.epgSourceDao().insert(EpgSourceEntity(name = "b", origin = "USER", credentialRef = "r2", urlHash = 2, displaySource = "h", priority = 1, createdAt = 1))
        db.epgMatchDao().insertAll(
            listOf(
                EpgMatchEntity(p, "bbc", a, "bbc.a", 0, "ID_EXACT", 1f, hasData = false, inputsHash = 1, matchedAt = 1),
                EpgMatchEntity(p, "bbc", b, "bbc.b", 0, "ID_EXACT", 1f, hasData = true, inputsHash = 1, matchedAt = 1),
                EpgMatchEntity(p, "cnn", a, "cnn.a", 0, "FUZZY", 0.95f, hasData = false, inputsHash = 1, matchedAt = 1),
            ),
        )
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "cnn", epgShiftMin = -60, updatedAt = 1))
        g.settings.update { it.copy(globalShiftMin = 5) }
        var refs = g.epg.resolvedRefs(listOf(p))
        val bbc = refs.getValue(ChannelIdentity(p, StableKey("bbc")))
        assertEquals(EpgChannelRef(b, "bbc.b"), bbc.ref) // first source WITH data wins
        assertEquals(5, bbc.shiftMin)
        val cnn = refs.getValue(ChannelIdentity(p, StableKey("cnn")))
        assertEquals(EpgChannelRef(a, "cnn.a"), cnn.ref) // no data anywhere: first AUTO
        assertEquals(30 - 60 + 5, cnn.shiftMin)
        assertEquals(MatchMethod.FUZZY, cnn.method)
        // An explicit "no guide data" mapping wins over matches.
        db.epgMappingDao().upsert(EpgMappingEntity(p, "bbc", null, null, 1))
        refs = g.epg.resolvedRefs(listOf(p))
        assertNull(refs.getValue(ChannelIdentity(p, StableKey("bbc"))).ref)
        assertEquals(MatchMethod.OVERRIDE, refs.getValue(ChannelIdentity(p, StableKey("bbc"))).method)
    }

    @Test
    fun playlistMergeAndEpgBuildSerialiseOnImportMutex() = runBlocking {
        // S9
        userSource()
        val events = Collections.synchronizedList(ArrayList<String>())
        g.store.duringBuild = {
            events += "build-start"
            Thread.sleep(300)
            events += "build-end"
        }
        val build = async(Dispatchers.IO) { g.epg.build(Trigger.MANUAL) }
        while (events.isEmpty()) delay(10)
        val merge = async(Dispatchers.IO) { g.importMutex.withLock { events += "merge" } }
        build.await(); merge.await()
        assertEquals(listOf("build-start", "build-end", "merge"), events.toList())
    }
}
