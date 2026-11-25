package app.thebox.data.backup

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
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.strings
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * docs/design/m6-event-channels.md §4.5, §10 (integration): a pre-v6 backup has no `refresh_interval_auto`; the restore
 * applies the migration's rule (auto only for 24 h) instead of keeping the device row's default (auto on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupIntervalAutoTest {
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

    @Before fun setUp() { dir = Files.createTempDirectory("backup-auto").toFile() }
    @After fun tearDown() { dbs.forEach { it.close() }; dir.deleteRecursively() }

    /** A backup of one playlist at [hours], written as a pre-v6 build would: no `refresh_interval_auto` field. */
    private fun preV6Backup(hours: Int): File = runBlocking {
        val src = graph()
        val p = src.db.newPlaylist("A", type = PlaylistType.M3U_URL, ref = "cred-a") { it.copy(uid = "uid-a") }
        src.db.exec("UPDATE playlist SET refresh_interval_h = ?, refresh_interval_auto = 0 WHERE id = ?", hours, p)
        val f = File(dir, "b-${System.nanoTime()}.thebox")
        f.outputStream().use { assertTrue(src.service.export(it, null) is Outcome.Ok) }
        val text = f.readText()
        val stripped = text.replace(",\"refresh_interval_auto\":0", "")
        assertTrue("the field was in the v6 export", stripped != text && "refresh_interval_auto" !in stripped)
        f.writeText(stripped)
        f
    }

    private suspend fun restored(file: File, mode: RestoreMode): List<String> {
        val dst = graph()
        assertTrue(dst.service.restore(file, null, mode) is RestoreResult.Done)
        return dst.db.strings("SELECT refresh_interval_h || '|' || refresh_interval_auto FROM playlist WHERE uid = 'uid-a'")
    }

    @Test
    fun aChosenIntervalFromAPreV6BackupIsNotAuto() = runBlocking {
        val file = preV6Backup(12)
        for (mode in RestoreMode.entries) assertEquals(mode.name, listOf("12|0"), restored(file, mode))
    }

    @Test
    fun theOldDefaultFromAPreV6BackupIsAuto() = runBlocking {
        assertEquals(listOf("24|1"), restored(preV6Backup(24), RestoreMode.MERGE))
        assertEquals("Manual is never auto", listOf("0|0"), restored(preV6Backup(0), RestoreMode.MERGE))
    }

    @Test
    fun theRule() {
        assertEquals(JsonPrimitive(0), withLegacyIntervalAuto(mapOf("refresh_interval_h" to JsonPrimitive(6)))["refresh_interval_auto"])
        assertEquals(JsonPrimitive(1), withLegacyIntervalAuto(mapOf("refresh_interval_h" to JsonPrimitive(24)))["refresh_interval_auto"])
        val v6 = mapOf("refresh_interval_h" to JsonPrimitive(24), "refresh_interval_auto" to JsonPrimitive(0))
        assertEquals("a v6 backup's own field stands", v6, withLegacyIntervalAuto(v6))
        assertEquals("no interval: nothing to derive", emptyMap<String, JsonPrimitive>(), withLegacyIntervalAuto(emptyMap()))
    }
}
