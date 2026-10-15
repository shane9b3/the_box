package app.thebox.data.guide

import android.util.Xml
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.guide.ProgrammeHit
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * docs/design/m3-guide.md §5.2 `GuideSearchTest` (§3.10; review B6) on a real `EpgStoreImpl` (Robolectric SQLite, the
 * FTS4 path devices without FTS5 use; the FTS5 path is covered in `:core`'s `EpgSearchSplitTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideSearchTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph
    private lateinit var store: EpgStoreImpl
    private lateinit var dir: File
    private val nowMs = EpgTestDb.minutes("2026-10-13T12:00:00Z") * 60_000L
    private val now = (nowMs / 60_000L).toInt()
    private var p = 0L
    private val ids = HashMap<String, Long>()

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create(); g = TestGraph(db)
        dir = File(TestDb.context.cacheDir, "epg-search-${System.nanoTime()}")
        store = EpgStoreImpl(dir, AndroidSQLiteDriver(), { Long.MAX_VALUE }, Xml::newPullParser, { nowMs }, forceFts4 = true)
        gg = GuideGraph(g, store)
        p = db.newPlaylist("main")
        val src = db.newSource("xmltv")
        val locked = db.newGroup(p, "g:locked", "Locked")
        db.exec("INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, updated_at) VALUES (?, 0, 'g:locked', 0, 1, 1)", p)
        var sort = 0
        suspend fun ch(key: String, name: String, epg: String?, adult: Boolean = false, group: Long? = null, archiveDays: Int = 0): Long {
            val id = db.newChannel(p, key, name, sortIndex = sort++, adult = adult, groupId = group)
            if (epg != null) db.match(p, key, src, epg)
            if (archiveDays > 0) db.exec("UPDATE channel SET catchup_type = 'shift', catchup_days = ? WHERE id = ?", archiveDays, id)
            ids[key] = id
            return id
        }
        for (i in 0 until 20) ch("n$i", "Ch $i", "news$i")
        ch("x0", "X0", "x0", archiveDays = 7)
        ch("x1", "X1", "x1")
        ch("x2", "X2", "x2")
        for (i in 1..4) ch("m$i", "Multi $i", "multi")
        ch("lateOk", "Late OK", "late")
        ch("lateAdult", "Late Adult", "late", adult = true)
        ch("lateLocked", "Late Locked", "late", group = locked)
        ch("dup", "Dup", "late")
        db.map(p, "dup", src, "films") // explicit mapping wins: its resolved ref is "films", not "late"
        ch("filmsC", "Films", "films")
        // Channel-search fixture.
        ch("cNews", "News", null); ch("cNews24", "News 24", null); ch("cBbc", "BBC News", null); ch("cRound", "Newsround", null)
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "Fav", sortIndex = 0, createdAt = 0))
        db.favouriteDao().insert(FavouriteEntity(list, p, "cRound", 0, 0))

        val xml = EpgTestDb.xmltv {
            for (i in 0 until 20) {
                channel("news$i", "News $i")
                var m = now - 7 * 1440
                while (m < now + 7 * 1440) { programme("news$i", m, m + 60, "News"); m += 60 }
            }
            channel("x0", "X0"); channel("x1", "X1"); channel("x2", "X2")
            programme("x0", now - 14 * 60, now - 13 * 60, "Newsnight") // yesterday 22:00, in x0's archive
            programme("x0", now - 26 * 60, now - 25 * 60, "Archive Special")
            programme("x1", now, now + 60, "The News Hour")
            programme("x1", now + 10 * 60, now + 11 * 60, "Newsnight") // tonight 22:00
            programme("x2", now - 26 * 60, now - 25 * 60, "Archive Special") // x2 has no archive
            programme("x2", now + 3 * 1440, now + 3 * 1440 + 60, "Newsnight") // in 3 days
            channel("multi", "Multi"); programme("multi", now + 180, now + 240, "Quadruple Feature")
            channel("late", "Late"); programme("late", now + 600, now + 660, "Late Show")
            channel("films", "Films"); programme("films", now - 240, now + 180, "Marathon Movie") // 7 h: split at 6 h
        }.writeTo(File(dir.parentFile, "search-${System.nanoTime()}.xml"))
        store.build(EpgBuildRequest(listOf(EpgSourceFile(src, xml)), nowMs, pastDays = 7, futureDays = 7), EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })
        Unit
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun search(q: String, unlocked: Boolean = false) = runBlocking { gg.search.search(q, nowMs, unlocked) }
    private fun key(h: ProgrammeHit) = ids.entries.single { it.value == h.channel.rowId }.key

    @Test
    fun onNowAndUpcomingAreNotStarvedByWeekOldAirings() {
        // B6: an hourly "News" on 20 channels over 14 days.
        val hits = search("news").programmes.filter { it.info.title == "News" }
        assertTrue("an ON NOW hit", hits.any { it.info.startMin <= now && it.info.endMin > now })
        assertTrue("an UPCOMING hit", hits.any { it.info.startMin > now && it.info.startMin < now + 1440 })
        assertTrue("no week-old airing", hits.none { it.info.endMin <= now })
    }

    @Test
    fun rankingTable() {
        val hits = search("news").programmes
        fun bucket(h: ProgrammeHit) = when {
            h.info.endMin <= now -> "ARCHIVE"
            h.info.startMin <= now -> "ON_NOW"
            h.info.startMin < now + 1440 -> "UPCOMING"
            else -> "LATER"
        }
        val labels = hits.map { "${it.info.title}|${bucket(it)}" }
        // Prefix matches first (News, Newsnight), bucketed ON NOW < UPCOMING < ARCHIVE < LATER; then the rest.
        val prefix = labels.takeWhile { !it.startsWith("The News Hour") }
        assertEquals(labels.size - 1, prefix.size)
        assertEquals("The News Hour|ON_NOW", labels.last())
        val order = listOf("ON_NOW", "UPCOMING", "ARCHIVE", "LATER")
        val buckets = prefix.map { order.indexOf(it.substringAfter('|')) }
        assertEquals(buckets.sorted(), buckets)
        assertTrue(labels.contains("Newsnight|ARCHIVE"))
        assertTrue(labels.contains("Newsnight|UPCOMING"))
        assertTrue(labels.contains("Newsnight|LATER"))
        assertTrue(hits.single { it.info.title == "Newsnight" && bucket(it) == "ARCHIVE" }.inArchive)
        assertEquals(hits.size, hits.map { it.channel.identityHash to it.info.rawStartMin }.toSet().size)
    }

    @Test
    fun pastHitsOnlyInsideTheChannelsArchive() {
        val hits = search("archive special").programmes
        assertEquals(listOf("x0"), hits.map(::key))
        assertTrue(hits.single().inArchive)
    }

    @Test
    fun resolvedRefConfirmationParentalFilterAndPerAiringCap() {
        assertEquals(listOf("lateOk"), search("late show").programmes.map(::key))
        assertEquals(setOf("lateOk", "lateAdult", "lateLocked"), search("late show", unlocked = true).programmes.map(::key).toSet())
        val multi = search("quadruple").programmes
        assertEquals(3, multi.size)
        assertEquals(listOf("m1", "m2", "m3"), multi.map(::key))
    }

    @Test
    fun continuationSegmentsAreNotHits() {
        // filmsC (AUTO) and dup (explicit mapping) both resolve to "films": one airing each, the first segment only.
        val hits = search("marathon").programmes
        assertEquals(setOf("filmsC", "dup"), hits.map(::key).toSet())
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.info.startMin == now - 240 && !it.inArchive })
    }

    @Test
    fun channelsRankedAndFiltered() {
        val r = search("news")
        val names = r.channels.map { it.displayName }
        assertEquals(listOf("News", "Newsround", "News 24", "BBC News"), names)
        assertTrue(search("n").channels.isEmpty() && search("n").programmes.isEmpty())
        assertEquals("FTS keeps the raw name", listOf("Late Adult"), search("late adult", unlocked = true).channels.map { it.displayName })
        assertTrue("adult channel hidden while locked", search("late adult").channels.isEmpty())
        assertFalse("a generation exists", r.guideDataMissing)
    }
}
