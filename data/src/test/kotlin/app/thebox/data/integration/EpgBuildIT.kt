package app.thebox.data.integration

import android.util.Xml
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
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

/**
 * §5.6 `EpgBuildIT`: WP-B's EpgStoreImpl on Robolectric's SQLite (AndroidSQLiteDriver, forced FTS4 path, §6.7)
 * with the real MatcherWhitelistResolver: whitelist, `epg_match.has_data`, and the patch for a mapping to a
 * channel the build did not import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpgBuildIT {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var dir: File

    @Before
    fun setUp() {
        db = TestDb.create()
        g = TestGraph(db)
        dir = File(TestDb.context.noBackupFilesDir, "epg-it-${System.nanoTime()}")
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    @Test
    fun buildWhitelistsHasDataAndPatch() = runBlocking {
        // basic.xml's programmes are on 2026-10-13 (UTC); FakeClock's default (2025-10-09) would drop all of them
        // from the 7/7-day window, so pin "now" to the fixture's day (12:00Z).
        g.clock.time.set(1_791_892_800_000L)
        val xml = File(TestDb.context.cacheDir, "basic.xml").apply {
            writeBytes(requireNotNull(EpgBuildIT::class.java.classLoader!!.getResource("xmltv/basic.xml")).readBytes())
        }
        val store = EpgStoreImpl(dir, AndroidSQLiteDriver(), { Long.MAX_VALUE }, Xml::newPullParser, { g.clock.now() }, forceFts4 = true)
        store.recover()
        val p = db.newPlaylist()
        val s = db.epgSourceDao().insert(EpgSourceEntity(name = "basic", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        val report = store.build(EpgBuildRequest(listOf(EpgSourceFile(s, xml)), g.clock.now(), 7, 7), g.matches)
        assertNull(report.failedSourceId)
        val reader = store.openReader()!!
        val channels = ArrayList<app.thebox.core.epg.EpgChannelInfo>()
        reader.use { it.forEachChannel(s) { c -> channels += c } }
        assertTrue(channels.isNotEmpty())
        // Rebuild: "a" matches channel 0 by id, "c" is mapped to channel 2; nothing else is whitelisted.
        db.newChannel(p, "a", channels[0].displayNames.first(), tvgId = channels[0].xmltvId)
        db.newChannel(p, "c", "Something else")
        db.epgMappingDao().upsert(EpgMappingEntity(p, "c", s, channels[2].xmltvId, 1))
        store.build(EpgBuildRequest(listOf(EpgSourceFile(s, xml)), g.clock.now(), 7, 7), g.matches)
        assertEquals(emptyList<Any>(), g.matches.afterBuild(store)) // overrides are whitelisted
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM epg_match WHERE status = 0 AND has_data = 1"))
        // A mapping made after the build needs an epg-patch for exactly that ref.
        db.newChannel(p, "b", "Unrelated name")
        db.epgMappingDao().upsert(EpgMappingEntity(p, "b", s, channels[1].xmltvId, 1))
        val pending = g.matches.pendingPatchRefs(store)
        assertEquals(listOf(app.thebox.core.epg.EpgChannelRef(s, channels[1].xmltvId)), pending)
        store.patch(app.thebox.core.epg.EpgPatchRequest(listOf(EpgSourceFile(s, xml)), pending, g.clock.now(), 7, 7))
        assertEquals(emptyList<Any>(), g.matches.pendingPatchRefs(store))
        store.clear()
    }
}
