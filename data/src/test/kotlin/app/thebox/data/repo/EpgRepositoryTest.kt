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
import app.thebox.data.testutil.scalar
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

    private companion object {
        const val HOUR = 3_600_000L
    }

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

    // ---- performance review M6 (2026-11-10): no validators, identical file, generation max age

    private fun logOutcomes() = runBlocking { db.refreshLogDao().latest("EPG_BUILD", null, 10).map { it.outcome } }

    @Test
    fun identicalFileWithoutValidatorsSkipsTheBuildAsNotModified() = runBlocking {
        val s = userSource()
        g.fetcher.noValidators += xml
        g.fetcher.bodies[xml] = "<tv>same</tv>"
        assertNull(g.epg.build(Trigger.MANUAL).error)
        assertEquals(1, g.store.builds.get())
        val src = File(g.cacheRoot, "epg/$s.src")
        val before = src.lastModified()
        g.clock.advance(12 * HOUR)
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(r.skipped)
        assertNull(r.error)
        assertEquals("no validators were sent", null, g.fetcher.requests.last().etag)
        assertEquals("no rebuild", 1, g.store.builds.get())
        assertTrue("the new download is dropped", !File(g.cacheRoot, "epg/$s.new").exists())
        assertEquals("<tv>same</tv>", src.readText())
        assertEquals(before, src.lastModified())
        assertEquals(listOf("NOT_MODIFIED", "SUCCESS"), logOutcomes())
    }

    @Test
    fun notModifiedResolvesAnEarlierBuildFailure() = runBlocking {
        userSource()
        g.epg.build(Trigger.MANUAL)
        g.store.duringBuild = { throw IllegalStateException("disk full") }
        g.fetcher.bodies[xml] = "<tv>changed</tv>"
        assertEquals("internal", g.epg.build(Trigger.PERIODIC).error!!.code)
        g.store.duringBuild = {}
        g.fetcher.notModified += xml
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        assertEquals(listOf("NOT_MODIFIED", "FAILED", "SUCCESS"), logOutcomes())
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM refresh_log WHERE outcome = 'FAILED' AND acknowledged = 0"))
    }

    @Test
    fun oneByteDifferentWithTheSameLengthRebuilds() = runBlocking {
        val s = userSource()
        g.fetcher.noValidators += xml
        g.fetcher.bodies[xml] = "<tv>aaaa</tv>"
        g.epg.build(Trigger.MANUAL)
        g.fetcher.bodies[xml] = "<tv>aaab</tv>"
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(!r.skipped)
        assertEquals(2, g.store.builds.get())
        assertTrue(g.store.lastBuild!!.sources.single().file.name.endsWith(".new"))
        assertEquals("<tv>aaab</tv>", File(g.cacheRoot, "epg/$s.src").readText())
        assertEquals(listOf("SUCCESS", "SUCCESS"), logOutcomes())
    }

    @Test
    fun differentLengthRebuilds() = runBlocking {
        val s = userSource()
        g.fetcher.noValidators += xml
        g.fetcher.bodies[xml] = "<tv>aaaa</tv>"
        g.epg.build(Trigger.MANUAL)
        g.fetcher.bodies[xml] = "<tv>aaaa </tv>"
        assertTrue(!g.epg.build(Trigger.PERIODIC).skipped)
        assertEquals(2, g.store.builds.get())
        assertEquals("<tv>aaaa </tv>", File(g.cacheRoot, "epg/$s.src").readText())
    }

    // Review 2026-11-10 N1: with the default 12 h schedule an unchanged guide skips while shrunk + 12 h ≤ 26 h.

    @Test
    fun anIdenticalFileRebuildsFromTheCacheOnceTheWindowWouldShrinkTooFar() = runBlocking {
        val s = userSource()
        g.fetcher.noValidators += xml
        g.epg.build(Trigger.MANUAL)
        g.clock.advance(13 * HOUR)
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        assertEquals(1, g.store.builds.get())
        g.clock.advance(2 * HOUR)
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(!r.skipped)
        assertNull(r.error)
        assertEquals(2, g.store.builds.get())
        assertEquals("built from the kept file", "$s.src", g.store.lastBuild!!.sources.single().file.name)
        assertEquals("window moves to now", g.clock.now(), g.store.lastBuild!!.nowMs)
        assertTrue(!File(g.cacheRoot, "epg/$s.new").exists())
        // The new generation covers the window again: the next identical file is skipped.
        g.clock.advance(12 * HOUR)
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        assertEquals(2, g.store.builds.get())
    }

    @Test
    fun notModifiedRebuildsFromTheCacheOnceTheWindowWouldShrinkTooFar() = runBlocking {
        val s = userSource()
        g.epg.build(Trigger.MANUAL)
        g.fetcher.notModified += xml
        g.clock.advance(13 * HOUR)
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        g.clock.advance(2 * HOUR)
        val requests = g.fetcher.requests.size
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(!r.skipped)
        assertEquals("one conditional GET, no second download", requests + 1, g.fetcher.requests.size)
        assertEquals(2, g.store.builds.get())
        assertEquals("$s.src", g.store.lastBuild!!.sources.single().file.name)
        assertTrue(db.epgSourceDao().get(s)!!.etag != null)
        assertEquals(listOf("SUCCESS", "NOT_MODIFIED", "SUCCESS"), logOutcomes())
    }

    @Test
    fun sixHourScheduleRebuildsAnUnchangedGuideEveryFourthRun() = runBlocking {
        userSource()
        g.settings.update { it.copy(epgRefreshIntervalH = 6, epgFutureDays = 3) }
        g.epg.build(Trigger.MANUAL)
        g.fetcher.notModified += xml
        val built = ArrayList<Boolean>()
        repeat(8) {
            g.clock.advance(6 * HOUR)
            built += !g.epg.build(Trigger.PERIODIC).skipped
        }
        assertEquals(listOf(false, false, false, true, false, false, false, true), built)
    }

    @Test
    fun dailyScheduleRebuildsEveryRunEvenAfterAMidCycleBuild() = runBlocking {
        // N1: the old 20 h age rule skipped the 24 h run after a manual build at 5 h, so the window lost ~43 h.
        userSource()
        g.settings.update { it.copy(epgRefreshIntervalH = 24, epgFutureDays = 3) }
        g.epg.build(Trigger.MANUAL)
        g.fetcher.notModified += xml
        g.clock.advance(5 * HOUR)
        g.epg.build(Trigger.MANUAL, force = true)
        assertEquals(2, g.store.builds.get())
        g.clock.advance(19 * HOUR)
        assertTrue(!g.epg.build(Trigger.PERIODIC).skipped)
        g.clock.advance(24 * HOUR)
        assertTrue(!g.epg.build(Trigger.PERIODIC).skipped)
        assertEquals(4, g.store.builds.get())
    }

    // ---- review 2026-11-10 S1: the skip also requires the build inputs to match

    @Test
    fun aChangedNextImportSettingRebuildsAnIdenticalFileFromTheCache() = runBlocking {
        val s = userSource()
        g.fetcher.noValidators += xml
        g.epg.build(Trigger.MANUAL)
        g.settings.update { it.copy(epgFutureDays = 14) }
        g.clock.advance(1 * HOUR)
        val requests = g.fetcher.requests.size
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(!r.skipped)
        assertEquals("one download, no second one", requests + 1, g.fetcher.requests.size)
        assertEquals(2, g.store.builds.get())
        assertEquals("$s.src", g.store.lastBuild!!.sources.single().file.name)
        assertEquals(14, g.store.lastBuild!!.futureDays)
        // Inputs unchanged now: skipped again.
        g.clock.advance(1 * HOUR)
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        // Descriptions, languages and matching are build inputs too.
        for (change in listOf<(DataSettingsSnapshot) -> DataSettingsSnapshot>(
            { it.copy(storeDescriptions = false) },
            { it.copy(preferredLanguages = listOf("fr")) },
            { it.copy(match = it.match.copy(caseInsensitiveIds = false)) },
        )) {
            val before = g.store.builds.get()
            g.settings.update(change)
            assertTrue(!g.epg.build(Trigger.PERIODIC).skipped)
            assertEquals(before + 1, g.store.builds.get())
            assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        }
    }

    @Test
    fun aGenerationWithoutAFingerprintIsRebuiltOnce() = runBlocking {
        // A generation built before S1 has no build_inputs: one rebuild from the cache stores them, then skips resume.
        userSource()
        g.epg.build(Trigger.MANUAL)
        g.store.dropBuildInputs()
        g.fetcher.notModified += xml
        assertTrue(!g.epg.build(Trigger.PERIODIC).skipped)
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        assertEquals(2, g.store.builds.get())
    }

    @Test
    fun aChangedSourceZoneRebuilds() = runBlocking {
        val s = userSource()
        g.epg.build(Trigger.MANUAL)
        g.fetcher.notModified += xml
        assertTrue(g.epg.build(Trigger.PERIODIC).skipped)
        db.epgSourceDao().update(db.epgSourceDao().get(s)!!.copy(assumeZone = "Europe/Berlin"))
        assertTrue(!g.epg.build(Trigger.PERIODIC).skipped)
    }

    // ---- review 2026-11-10 S4

    @Test
    fun aFailedSourceNextToAnIdenticalOneLogsNoNotModifiedRow() = runBlocking {
        val a = userSource()
        val xml2 = "http://epg2.example/guide.xml"
        val b = (g.epg.addUserSource("Guide 2", xml2) as Outcome.Ok).value
        g.fetcher.bodies[xml2] = "<tv>b</tv>"
        g.fetcher.noValidators += xml2
        g.store.catalogue[b] = listOf(epgChannel(b, "cnn", "CNN"))
        assertNull(g.epg.build(Trigger.MANUAL).error)
        // An earlier failed build row.
        g.store.duringBuild = { throw IllegalStateException("disk full") }
        g.fetcher.bodies[xml] = "<tv>changed</tv>"
        assertEquals("internal", g.epg.build(Trigger.PERIODIC).error!!.code)
        g.store.duringBuild = {}
        g.fetcher.bodies[xml] = "<tv/>"
        // A answers 500 (its cached .src is used), B is byte-identical.
        g.fetcher.errors[xml] = SourceError.Http(500)
        assertTrue(File(g.cacheRoot, "epg/$a.src").exists())
        val rows = logOutcomes().size
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(r.skipped)
        assertEquals("no new row", rows, logOutcomes().size)
        assertEquals(listOf("FAILED", "SUCCESS"), logOutcomes())
        assertEquals("A's failure is still shown", 1L, db.scalar("SELECT COUNT(*) FROM refresh_log WHERE outcome = 'FAILED' AND acknowledged = 0"))
    }

    @Test
    fun manualForceWithAnIdenticalFileRebuildsFromTheKeptFile() = runBlocking {
        val s = userSource()
        g.fetcher.noValidators += xml
        g.epg.build(Trigger.MANUAL)
        val r = g.epg.build(Trigger.MANUAL, force = true)
        assertTrue(!r.skipped)
        assertNull(r.error)
        assertTrue(!File(g.cacheRoot, "epg/$s.new").exists())
        assertEquals("$s.src", g.store.lastBuild!!.sources.single().file.name)
        assertEquals(listOf("SUCCESS", "SUCCESS"), logOutcomes())
    }

    @Test
    fun aGenerationBuiltInTheFutureIsRebuilt() = runBlocking {
        // The device clock moved back after the build: the age is negative and would otherwise never reach the limit.
        userSource()
        g.epg.build(Trigger.MANUAL)
        g.fetcher.notModified += xml
        g.clock.advance(-2 * HOUR)
        assertTrue(!g.epg.build(Trigger.PERIODIC).skipped)
        assertEquals(2, g.store.builds.get())
    }

    @Test
    fun sameFileBytesComparesLengthAndContent() {
        val dir = File(g.cacheRoot, "cmp").apply { mkdirs() }
        val big = ByteArray(200_000) { (it * 31).toByte() }
        val a = File(dir, "a").apply { writeBytes(big) }
        val b = File(dir, "b").apply { writeBytes(big) }
        assertTrue(sameFileBytes(a, b))
        b.writeBytes(big.copyOf().also { it[199_999] = (it[199_999] + 1).toByte() })
        assertTrue("last byte differs", !sameFileBytes(a, b))
        b.writeBytes(big.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertTrue("first byte differs", !sameFileBytes(a, b))
        b.writeBytes(big.copyOf(199_999))
        assertTrue("shorter", !sameFileBytes(a, b))
        assertTrue("missing file", !sameFileBytes(a, File(dir, "none")))
        assertTrue(sameFileBytes(File(dir, "e1").apply { writeBytes(ByteArray(0)) }, File(dir, "e2").apply { writeBytes(ByteArray(0)) }))
        // Review 2026-11-10 N4: a cancelled worker stops the compare at the next buffer.
        assertTrue(runCatching { sameFileBytes(a, a) { true } }.exceptionOrNull() is kotlin.coroutines.cancellation.CancellationException)
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

    /** A restore placeholder (docs/design/m5-restore-placeholder-guides.md): hash 0, a ref with no secret. */
    private suspend fun placeholder(): Long = db.epgSourceDao().insert(
        EpgSourceEntity(
            name = "Restored", origin = EpgSourceEntity.ORIGIN_PLAYLIST_HEADER, playlistId = p, credentialRef = g.creds.newRef(),
            urlHash = EpgSourceEntity.PLACEHOLDER_HASH, displaySource = "", priority = 0, createdAt = 1,
        ),
    )

    @Test
    fun aPlaceholderAloneIsNeitherBuiltNorReportedAsAFailure() = runBlocking {
        val ph = placeholder()
        val r = g.epg.build(Trigger.PERIODIC)
        assertTrue(r.skipped)
        assertNull(r.error)
        assertEquals(0, g.store.builds.get())
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM refresh_log WHERE outcome = 'FAILED'"))
        val row = db.epgSourceDao().get(ph)!!
        assertNull("no setStatus on a placeholder", row.lastStatus)
        assertNull(row.lastImportAt)
        assertTrue(g.fetcher.requests.isEmpty())
    }

    @Test
    fun aPlaceholderNextToAWorkingGuideStaysUntouched() = runBlocking {
        val ph = placeholder()
        val s = userSource()
        val r = g.epg.build(Trigger.MANUAL)
        assertNull(r.error)
        assertEquals(listOf(s), g.store.lastBuild!!.sources.map { it.sourceId })
        assertEquals("SUCCESS", db.refreshLogDao().latest("EPG_BUILD", null).single().outcome)
        assertNull(db.epgSourceDao().get(ph)!!.lastStatus)
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
