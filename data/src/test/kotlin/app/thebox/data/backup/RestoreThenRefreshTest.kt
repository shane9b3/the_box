package app.thebox.data.backup

import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.Outcome
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SetupDomain
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.repo.Trigger
import app.thebox.data.testutil.FakeDiffer
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.strings
import kotlinx.coroutines.Dispatchers
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
import java.io.OutputStream
import java.nio.file.Files

/**
 * Placeholder review S4 (docs/design/m5-restore-placeholder-guides.md): restore onto a new TV, then that TV's first
 * refresh, on one library. The only test that proves `BackupRestorer` and `RefreshRepository.syncSources` agree on the
 * placeholder's shape: the refresh fills the restored rows in place, so mappings and overrides survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreThenRefreshTest {
    private lateinit var dir: File
    private val dbs = ArrayList<LibraryDatabase>()
    private val listUrl = "http://lists.example/list.m3u?token=abc"
    private val epgA = "http://epg.one/a.xml?key=1"
    private val epgB = "http://epg.two/b.xml?key=2"
    private val body = "#EXTM3U url-tvg=\"$epgA,$epgB\"\n" +
        "#EXTINF:-1 tvg-id=\"bbc.uk\" group-title=\"News\",BBC\nhttp://lists.example/1.ts\n" +
        "#EXTINF:-1 tvg-id=\"cnn.us\" group-title=\"News\",CNN\nhttp://lists.example/2.ts\n"

    @Before fun setUp() { dir = Files.createTempDirectory("restore-refresh").toFile() }
    @After fun tearDown() { dbs.forEach { it.close() }; dir.deleteRecursively() }

    private fun library(): Pair<TestGraph, BackupServiceImpl> {
        val db = TestDb.create().also { dbs += it }
        val g = TestGraph(db)
        val settings = FakeSettingsStore().apply { register(SetupDomain) }
        val storage = object : BackupStorage {
            override fun downloadsWritable() = false
            override suspend fun write(name: String, body: suspend (OutputStream) -> Unit) = error("unused")
            override fun list() = BackupListing(emptyList(), false)
            override fun copyTo(location: String, dest: File) = false
            override fun delete(location: String) = Unit
        }
        val env = object : BackupEnvironment { override val versionName = "1.0-test"; override val versionCode = 7L }
        val service = BackupServiceImpl(
            db, g.creds, RestoreMergeTest.MapBlobs(), settings, g.importMutex, storage, env, RestoreMergeTest.RecordingFollowUp(), g.clock,
            Dispatchers.IO, File(dir, "state-${dbs.size}"), 1_000,
        )
        return g to service
    }

    /** `ordinal|name|priority|enabled|shift|zone|placeholder` for the playlist's header guides. */
    private suspend fun guides(db: LibraryDatabase, pid: Long): List<String> = db.epgSourceDao().forPlaylist(pid).map {
        "${it.ordinal}|${it.name}|${it.priority}|${it.enabled}|${it.shiftMin}|${it.assumeZone}|${it.isPlaceholder}"
    }

    @Test
    fun theFirstRefreshAfterARestoreFillsThePlaceholdersAndKeepsMappingsAndOverrides() = runBlocking {
        // The old TV: refreshed, two header guides with overrides, one mapping to each.
        val (old, oldService) = library()
        val oldId = (old.playlists.addM3uUrl("My list", listUrl) as Outcome.Ok).value
        old.fetcher.bodies[listUrl] = body
        assertNull(old.refresh(FakeDiffer()).refresh(oldId, Trigger.MANUAL).error)
        val (g0, g1) = old.db.epgSourceDao().forPlaylist(oldId)
        old.db.epgSourceDao().update(g0.copy(priority = 5, shiftMin = 60, assumeZone = "Europe/Oslo"))
        old.db.epgSourceDao().update(g1.copy(priority = 2, enabled = false, shiftMin = -30))
        old.db.epgMappingDao().upsert(EpgMappingEntity(oldId, "tvg:bbc.uk", g0.id, "bbc.one", 1))
        old.db.epgMappingDao().upsert(EpgMappingEntity(oldId, "tvg:cnn.us", g1.id, "cnn.intl", 1))
        val file = File(dir, "old.thebox")
        file.outputStream().use { assertTrue(oldService.export(it, "pw".toCharArray()) is Outcome.Ok) }

        // The new TV: one restore creates placeholders; the mappings land on them.
        val (tv, service) = library()
        assertTrue(service.restore(file, "pw".toCharArray(), RestoreMode.MERGE) is RestoreResult.Done)
        val pid = tv.db.playlistDao().all().single().id
        val restored = tv.db.epgSourceDao().forPlaylist(pid)
        assertEquals(
            listOf("0|My list (1)|5|true|60|Europe/Oslo|true", "1|My list (2)|2|false|-30|null|true"),
            guides(tv.db, pid),
        )
        val mappings = "SELECT m.stable_key || '|' || s.ordinal || '|' || m.source_id || '|' || m.xmltv_id FROM epg_mapping m JOIN epg_source s ON s.id = m.source_id ORDER BY 1"
        val before = tv.db.strings(mappings)
        assertEquals(listOf("tvg:bbc.uk|0|${restored[0].id}|bbc.one", "tvg:cnn.us|1|${restored[1].id}|cnn.intl"), before)
        assertTrue(tv.db.epgSourceDao().active().isEmpty())

        // Its first refresh: the same rows are filled in, nothing is inserted or dropped.
        tv.fetcher.bodies[listUrl] = body
        assertNull(tv.refresh(FakeDiffer()).refresh(pid, Trigger.APP_START).error)
        val after = tv.db.epgSourceDao().forPlaylist(pid)
        assertEquals(restored.map { it.id }, after.map { it.id })
        assertEquals(
            listOf("0|My list (1)|5|true|60|Europe/Oslo|false", "1|My list (2)|2|false|-30|null|false"),
            guides(tv.db, pid),
        )
        assertEquals(before, tv.db.strings(mappings))
        assertEquals(restored.map { it.credentialRef }, after.map { it.credentialRef })
        assertEquals(listOf(epgA, epgB), after.map { (tv.creds.get(it.credentialRef) as Outcome.Ok).value.urls.single() })
        // The hashes match the old TV's rows: an export from either TV keys the guides the same way.
        assertEquals(listOf(g0.urlHash, g1.urlHash), after.map { it.urlHash })
        assertEquals(listOf(Trigger.PLAYLIST_CHANGED), tv.requester.imports)
        assertEquals("only the enabled guide is active", listOf(after[0].id), tv.db.epgSourceDao().active().map { it.id })
    }
}
