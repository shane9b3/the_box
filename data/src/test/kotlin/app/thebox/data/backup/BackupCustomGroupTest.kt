package app.thebox.data.backup

import app.thebox.core.backup.MergePlanner
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.Outcome
import app.thebox.core.settings.Domains
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SetupDomain
import app.thebox.core.source.PlaylistType
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.guide.exec
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.FakeCredentialStore
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.strings
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/** docs/design/m6-channel-editor.md §6.2 `BackupCustomGroupTest` (§3.6 "Backup", purge c). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupCustomGroupTest {
    private val clock = FakeClock(1_800_000_000_000L)
    private lateinit var dir: File
    private val dbs = ArrayList<LibraryDatabase>()

    private class Graph(val db: LibraryDatabase, val service: BackupServiceImpl)

    private fun graph(): Graph {
        val settings = FakeSettingsStore()
        settings.register(SettingsDomain(Domains.GUIDE, GuidePrefs.serializer(), GuidePrefs()))
        settings.register(SetupDomain)
        val db = TestDb.create().also { dbs += it }
        val storage = object : BackupStorage {
            override fun downloadsWritable() = false
            override suspend fun write(name: String, body: suspend (OutputStream) -> Unit) = error("unused")
            override fun list() = BackupListing(emptyList(), false)
            override fun copyTo(location: String, dest: File) = false
            override fun delete(location: String) = Unit
        }
        val env = object : BackupEnvironment { override val versionName = "1.0-test"; override val versionCode = 7L }
        val follow = object : RestoreFollowUp { override suspend fun afterRestore(newOrRelogin: List<Long>, affected: List<Long>) = Unit }
        val service = BackupServiceImpl(db, FakeCredentialStore(), RestoreMergeTest.MapBlobs(), settings, ImportMutex(), storage, env, follow, clock, Dispatchers.IO, File(dir, "state-${dbs.size}"), 1_000)
        return Graph(db, service)
    }

    @Before fun setUp() { dir = Files.createTempDirectory("backup-cg").toFile() }
    @After fun tearDown() { dbs.forEach { it.close() }; dir.deleteRecursively() }

    /** A playlist `uid-a` with the given LIVE groups. */
    private suspend fun library(g: Graph, vararg groups: String): Long {
        val p = g.db.newPlaylist("A", type = PlaylistType.M3U_URL, ref = "cred-a") { it.copy(uid = "uid-a") }
        for (k in groups) g.db.newGroup(p, k, k)
        return p
    }

    private fun export(g: Graph): File = runBlocking {
        val f = File(dir, "b-${System.nanoTime()}.thebox")
        f.outputStream().use { assertTrue(g.service.export(it, null) is Outcome.Ok) }
        f
    }

    private suspend fun prefs(g: Graph) = g.db.strings(
        "SELECT stable_key || '|' || COALESCE(custom_group_key, '-') || '|' || COALESCE(custom_name, '-') FROM channel_prefs ORDER BY stable_key",
    )

    @Test
    fun theColumnIsInTheBackupColumnSet() {
        assertTrue("custom_group_key" in MergePlanner.CHANNEL_PREF_COLUMNS)
        assertTrue(BackupExporter.USER_PREF_PREDICATE.contains("custom_group_key"))
    }

    @Test
    fun roundTrip() = runBlocking {
        val src = graph()
        val a = library(src, "g:news", "g:sport")
        // A move-only row travels (USER_PREF_PREDICATE counts the move).
        src.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_group_key, custom_sort_index) VALUES (?, 'k1', 0, 0, 1, 'g:sport', 4)", a)
        val file = export(src)
        val dst = graph()
        library(dst, "g:news", "g:sport")
        assertTrue(dst.service.restore(file, null, RestoreMode.MERGE) is RestoreResult.Done)
        assertEquals(listOf("k1|g:sport|-"), prefs(dst))
        assertEquals(listOf("4"), dst.db.strings("SELECT custom_sort_index FROM channel_prefs"))
    }

    @Test
    fun aBackupWithoutTheFieldMergeKeepsReplaceNulls() = runBlocking {
        val src = graph()
        val a = library(src, "g:news", "g:sport")
        src.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_name) VALUES (?, 'k1', 0, 0, 1, 'Mine')", a)
        val file = export(src) // k1 carries no custom_group_key field, as a v4 backup
        for ((mode, expected) in listOf(RestoreMode.MERGE to "k1|g:sport|Mine", RestoreMode.REPLACE to "k1|-|Mine")) {
            val dst = graph()
            val d = library(dst, "g:news", "g:sport")
            dst.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_group_key) VALUES (?, 'k1', 0, 0, 1, 'g:sport')", d)
            assertTrue(dst.service.restore(file, null, mode) is RestoreResult.Done)
            assertEquals(mode.name, listOf(expected), prefs(dst))
        }
    }

    @Test
    fun aDanglingKeyIsDroppedSilently() = runBlocking {
        val src = graph()
        val a = library(src, "g:news", "g:sport")
        src.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_group_key, custom_sort_index, custom_name) VALUES (?, 'k1', 0, 0, 1, 'g:sport', 6, 'Mine')", a)
        val file = export(src)
        val dst = graph()
        library(dst, "g:news") // this device's playlist has LIVE groups, but no g:sport
        val r = dst.service.restore(file, null, RestoreMode.MERGE)
        assertTrue(r is RestoreResult.Done)
        assertEquals("the channel stays in its provider group; the rest of the row lands", listOf("k1|-|Mine"), prefs(dst))
        assertEquals("the target's order goes with the dropped key", listOf("null"), dst.db.strings("SELECT custom_sort_index FROM channel_prefs"))
    }

    @Test
    fun aRestoreOntoAPlaylistWithNoGroupsYetKeepsTheMove() = runBlocking {
        val src = graph()
        val a = library(src, "g:news", "g:sport")
        src.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_group_key, custom_sort_index) VALUES (?, 'k1', 0, 0, 1, 'g:sport', 2)", a)
        val file = export(src)
        val dst = graph() // a fresh device: the restore adds the playlist, which has no channel_group rows yet
        assertTrue(dst.service.restore(file, null, RestoreMode.MERGE) is RestoreResult.Done)
        assertEquals(listOf("k1|g:sport|-"), prefs(dst))
        assertEquals(listOf("2"), dst.db.strings("SELECT custom_sort_index FROM channel_prefs"))
        // Until its first refresh the move is inert (no target row); then the channel is in the target.
        val pid = dst.db.strings("SELECT id FROM playlist WHERE uid = 'uid-a'").single().toLong()
        val news = dst.db.newGroup(pid, "g:news", "News")
        dst.db.newChannel(pid, "k1", "K1", news, sortIndex = 0)
        assertEquals("g:news", dst.db.channelDao().row(pid, "k1")!!.groupKey)
        dst.db.newGroup(pid, "g:sport", "Sport")
        assertEquals("g:sport", dst.db.channelDao().row(pid, "k1")!!.groupKey)
    }
}
