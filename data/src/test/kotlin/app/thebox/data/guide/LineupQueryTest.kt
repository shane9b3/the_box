package app.thebox.data.guide

import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.SourceSection
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** docs/design/m3-guide.md §5.2 `LineupQueryTest` (§3.2.1, §3.8.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LineupQueryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph
    private var p = 0L
    private var off = 0L
    private var news = 0L
    private var adultGroup = 0L
    private var hiddenGroup = 0L
    private var lockedGroup = 0L
    private val ids = HashMap<String, Long>()

    @Before
    fun setUp() = runBlocking<Unit> {
        db = TestDb.create(); g = TestGraph(db); gg = GuideGraph(g)
        p = db.newPlaylist("main")
        off = db.newPlaylist("off") { it.copy(enabled = false, sortIndex = 1) }
        news = db.newGroup(p, "g:news", "News")
        adultGroup = db.newGroup(p, "g:adult", "Adult", adult = true)
        hiddenGroup = db.newGroup(p, "g:hidden", "Hidden")
        lockedGroup = db.newGroup(p, "g:locked", "Locked")
        db.exec("INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, updated_at) VALUES (?, 0, 'g:hidden', 1, 1)", p)
        db.exec("INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, updated_at) VALUES (?, 0, 'g:locked', 0, 1, 1)", p)
        fun add(key: String, sort: Int, group: Long? = news, kind: Int = 0, adult: Boolean = false, missing: Long? = null, pl: Long = p) =
            runBlocking { ids[key] = db.newChannel(pl, key, key.uppercase(), groupId = group, sortIndex = sort, kind = kind, adult = adult, missingSince = missing) }
        add("n3", 3); add("n1", 1); add("n2", 2); add("radio", 4, kind = 1); add("movie", 5, kind = 2)
        add("gone", 6, missing = 5L); add("hid", 7); add("xxx", 8, adult = true); add("a1", 9, group = adultGroup)
        add("h1", 10, group = hiddenGroup); add("l1", 11, group = lockedGroup); add("loose", 12, group = null)
        add("off1", 0, group = null, pl = off)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at) VALUES (?, 'hid', 1, 0, 1)", p)
        // User order inside News: n2 first.
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_sort_index) VALUES (?, 'n2', 0, 0, 1, 0)", p)
    }

    @After fun tearDown() { db.close() }

    private suspend fun lineup(source: GuideSource, unlocked: Boolean = false): GuideLineup = gg.library.lineup(source, unlocked).first()
    private fun keysOf(l: GuideLineup) = l.rowIds.map { id -> ids.entries.single { it.value == id }.key }

    @Test
    fun eachSourceMatchesTheM1BrowseOrder() = runBlocking {
        val all = lineup(GuideSource.All(p))
        assertEquals(db.channelDao().browseAll(p, false).first().map { it.channel.id }.filter { it != ids["movie"] }, all.rowIds.toList())
        assertEquals(listOf("n1", "n2", "n3", "radio", "loose"), keysOf(all))
        val group = lineup(GuideSource.Group(p, "g:news"))
        assertEquals(db.channelDao().browseGroup(p, news, false).first().map { it.channel.id }.filter { it != ids["movie"] }, group.rowIds.toList())
        assertEquals(listOf("n2", "n1", "n3", "radio"), keysOf(group))
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "Fav", sortIndex = 0, createdAt = 0))
        for ((i, k) in listOf("n3", "xxx", "n1", "off1", "gone").withIndex()) {
            db.favouriteDao().insert(FavouriteEntity(list, if (k == "off1") off else p, k, i, 0))
        }
        val fav = lineup(GuideSource.Favourites(list))
        assertEquals(db.favouriteDao().channels(list, false).first().map { it.channel.id }, fav.rowIds.toList())
        assertEquals(listOf("n3", "n1"), keysOf(fav))
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, last_watched_at) VALUES (?, 'radio', 0, 1, 1, 100)", p)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, last_watched_at) VALUES (?, 'n1', 0, 1, 1, 200)", p)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, last_watched_at) VALUES (?, 'xxx', 0, 1, 1, 300)", p)
        assertEquals(listOf("n1", "radio"), keysOf(lineup(GuideSource.Recent)))
    }

    @Test
    fun hiddenLockedMissingDisabledAreExcluded() = runBlocking {
        val all = keysOf(lineup(GuideSource.All(p)))
        for (k in listOf("movie", "gone", "hid", "xxx", "a1", "h1", "l1")) assertFalse(k, k in all)
        assertTrue(lineup(GuideSource.All(off)).rowIds.isEmpty())
        // The PIN session (or the debug unlock) shows locked groups and adult channels; hidden ones never.
        val unlocked = keysOf(lineup(GuideSource.All(p), unlocked = true))
        for (k in listOf("xxx", "a1", "l1")) assertTrue(k, k in unlocked)
        for (k in listOf("hid", "h1", "gone", "movie")) assertFalse(k, k in unlocked)
        assertTrue(lineup(GuideSource.Group(p, "g:locked")).rowIds.isEmpty())
        assertEquals(listOf("l1"), keysOf(lineup(GuideSource.Group(p, "g:locked"), unlocked = true)))
    }

    @Test
    fun versionIsStableAndReEmitsAfterAFavouriteAdd() = runBlocking {
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "Fav", sortIndex = 0, createdAt = 0))
        db.favouriteDao().insert(FavouriteEntity(list, p, "n1", 0, 0))
        val seen = Collections.synchronizedList(ArrayList<GuideLineup>())
        val job = launch(Dispatchers.IO) { gg.library.lineup(GuideSource.Favourites(list), false).toList(seen) }
        withTimeout(5_000) { while (seen.isEmpty()) delay(20) }
        val v1 = seen.single().version
        // A write that changes nothing visible re-queries but does not re-emit.
        db.exec("UPDATE playlist SET last_status = 'X' WHERE id = ?", p)
        db.exec("UPDATE channel SET logo_url = 'http://x' WHERE stable_key = 'n1'")
        delay(600)
        assertEquals(1, seen.size)
        gg.library.setFavourite(ChannelIdentity(p, StableKey("n3")), true)
        withTimeout(5_000) { while (seen.size < 2) delay(20) }
        assertNotEquals(v1, seen.last().version)
        assertEquals(listOf("n1", "n3"), keysOf(seen.last()))
        assertTrue(gg.library.isFavourite(ChannelIdentity(p, StableKey("n3"))))
        job.cancel()
        assertEquals(lineup(GuideSource.All(p)).version, lineup(GuideSource.All(p)).version)
    }

    @Test
    fun recentOrderIsFrozenWhileShown() {
        val prev = longArrayOf(1, 2, 3)
        assertArrayEquals(longArrayOf(9, 1, 2, 3), GuideLibraryImpl.frozenRecent(prev, longArrayOf(9, 3, 2, 1)))
        assertArrayEquals(longArrayOf(1, 3), GuideLibraryImpl.frozenRecent(prev, longArrayOf(3, 1)))
    }

    @Test
    fun archiveUpperBoundAndSources() = runBlocking {
        db.exec("UPDATE channel SET catchup_days = 4 WHERE stable_key = 'n1'")
        db.exec("UPDATE channel SET tv_archive = 1 WHERE stable_key = 'n2'")
        db.exec("UPDATE channel SET catchup_days = 14 WHERE stable_key = 'xxx'") // locked: not in the lineup
        assertEquals(4, lineup(GuideSource.All(p)).archiveDaysUpperBound)
        db.exec("UPDATE playlist SET catchup_days_override = 6 WHERE id = ?", p)
        assertEquals(6, lineup(GuideSource.All(p)).archiveDaysUpperBound)
        assertEquals(14, lineup(GuideSource.All(p), unlocked = true).archiveDaysUpperBound)

        db.exec("UPDATE playlist SET live_count = 5 WHERE id = ?", p)
        val entries = gg.library.sources(false).first()
        assertEquals(listOf(SourceSection.ALL, SourceSection.GROUP), entries.map { it.section }.distinct())
        assertEquals(GuideSource.All(p), entries.first().source)
        assertEquals(5, entries.first().count)
        assertEquals("hidden, locked and adult groups excluded", listOf("News"), entries.filter { it.section == SourceSection.GROUP }.map { it.label })
        assertTrue("one enabled playlist: no section headers", entries.all { it.playlistName == null })
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, last_watched_at) VALUES (?, 'n1', 0, 1, 1, 200)", p)
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "Mine", sortIndex = 0, createdAt = 0))
        db.favouriteDao().insert(FavouriteEntity(list, p, "n2", 0, 0))
        val again = gg.library.sources(false).first()
        assertEquals(listOf(SourceSection.FAVOURITES, SourceSection.RECENT, SourceSection.ALL, SourceSection.GROUP), again.map { it.section }.distinct())
        assertEquals("Mine", again.first().label)
        assertEquals(n1Id(), gg.library.lastWatched()!!.rowId)
    }

    private fun n1Id() = ids.getValue("n1")
}
