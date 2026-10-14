package app.thebox.data.guide

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.feed.GuideRowsCacheImpl
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.M3uHeader
import app.thebox.data.db.Converters
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** docs/design/m3-guide.md §5.2 `RowBlockTest` (§3.2.1, §3.2.2, B5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RowBlockTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph

    @Before fun setUp() { db = TestDb.create(); g = TestGraph(db); gg = GuideGraph(g) }
    @After fun tearDown() { db.close() }

    @Test
    fun numberingNamesLogosAndCatchup() = runBlocking {
        val provider = db.newPlaylist("prov")
        val order = db.newPlaylist("order") { it.copy(numberingMode = "PLAYLIST_ORDER") }
        val start = db.newPlaylist("start") { it.copy(numberingMode = "START_AT:100") }
        val a = db.newChannel(provider, "a", "UK: One", sortIndex = 0, number = 7)
        val b = db.newChannel(provider, "b", "Two", sortIndex = 1)
        val c = db.newChannel(order, "c", "Three", sortIndex = 4, number = 7)
        val d = db.newChannel(start, "d", "Four", sortIndex = 2)
        db.exec("UPDATE channel SET display_name = 'One', logo_url = 'http://l/a', catchup_type = 'shift', catchup_days = 5 WHERE id = ?", a)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_name, custom_number, custom_logo) VALUES (?, 'b', 0, 0, 1, 'My Two', 42, 'http://l/mine')", provider)
        val rows = gg.queries.channels(longArrayOf(d, a, -99, b, c), 0, 5)
        assertNull("purged id", rows[2])
        val (rd, ra, _, rb, rc) = rows
        assertEquals("One", ra!!.displayName)
        assertEquals(7, ra.number)
        assertEquals("http://l/a", ra.logoUrl)
        assertEquals(5, ra.archiveDays)
        assertEquals("My Two", rb!!.displayName)
        assertEquals(42, rb.number)
        assertEquals("http://l/mine", rb.logoUrl)
        assertEquals(0, rb.archiveDays)
        assertEquals("PLAYLIST_ORDER: position + 1", 5, rc!!.number)
        assertEquals("START_AT:100", 102, rd!!.number)
        assertEquals(ChannelIdentity(provider, StableKey("a")), ra.identity)
        assertEquals(app.thebox.core.guide.GuideChannel.hashOf(ra.identity), ra.identityHash)
        assertNull(ra.epg)
        // Number entry goes through the same rows.
        assertEquals(b, gg.library.byNumber(provider, 42, false)!!.rowId)
        assertEquals(d, gg.library.byNumber(start, 102, false)!!.rowId)
        assertEquals(c, gg.library.byNumber(order, 5, false)!!.rowId)
        assertEquals(a, gg.library.rowIdOf(ChannelIdentity(provider, StableKey("a"))))
    }

    @Test
    fun resolverEqualsResolvedRefsOnRandomFixtures() = runBlocking {
        val rnd = Random(7)
        var compared = 0
        repeat(10) { round ->
            val p1 = db.newPlaylist("r$round-a")
            val p2 = db.newPlaylist("r$round-b")
            if (rnd.nextBoolean()) db.playlistDao().setHeader(p1, Converters.json.encodeToString(M3uHeader.serializer(), M3uHeader(tvgShiftHours = 1.5)))
            val sources = List(3) { i ->
                db.newSource("s$round-$i", priority = rnd.nextInt(5), playlistId = if (i == 2) p2 else null, shiftMin = rnd.nextInt(-60, 61), enabled = i != 1 || rnd.nextBoolean())
            }
            val inputs = ArrayList<EpgRefResolver.Input>()
            for (i in 0 until 100) {
                val pl = if (i % 2 == 0) p1 else p2
                val key = "k$round-$i"
                db.newChannel(pl, key, key, sortIndex = i)
                val chShift = if (rnd.nextInt(3) == 0) rnd.nextInt(-120, 121) else null
                val prefShift = if (rnd.nextInt(4) == 0) rnd.nextInt(-60, 61) else null
                if (chShift != null) db.exec("UPDATE channel SET epg_shift_min = ? WHERE playlist_id = ? AND stable_key = ?", chShift, pl, key)
                if (prefShift != null) db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, epg_shift_min) VALUES (?, ?, 0, 0, 1, ?)", pl, key, prefShift)
                when (rnd.nextInt(4)) {
                    0 -> db.map(pl, key, sources[rnd.nextInt(3)], "x${rnd.nextInt(20)}")
                    1 -> db.map(pl, key, null, null)
                    else -> Unit
                }
                repeat(rnd.nextInt(4)) { db.match(pl, key, sources[rnd.nextInt(3)], "x${rnd.nextInt(20)}", hasData = rnd.nextBoolean(), status = if (rnd.nextInt(5) == 0) 1 else 0) }
                inputs += EpgRefResolver.Input(ChannelIdentity(pl, StableKey(key)), chShift, prefShift)
            }
            g.settings.update { it.copy(globalShiftMin = rnd.nextInt(-30, 31)) }
            val expected = g.epg.resolvedRefs(listOf(p1, p2))
            val actual = gg.refs.resolve(inputs.shuffled(rnd).take(64)) + gg.refs.resolve(inputs)
            for (input in inputs) {
                assertEquals(input.identity.toString(), expected[input.identity], actual[input.identity])
                compared++
            }
        }
        assertEquals(1_000, compared)
    }

    @Test
    fun matcherWriteAndCustomNameReloadTheLoadedBlock() = runBlocking {
        val p = db.newPlaylist()
        val ids = LongArray(40) { db.newChannel(p, "k$it", "Ch $it", sortIndex = it) }
        val src = db.newSource("xmltv")
        val cache = GuideRowsCacheImpl(gg.library, this)
        cache.setLineup(GuideLineup(GuideSource.All(p), 1, ids, 0))
        cache.ensure(0, 10)
        withTimeout(10_000) { cache.changes.first { cache.channel(5) != null } }
        assertNull(cache.channel(5)!!.epg)

        // B5: the matcher writes epg_match after the first import; membership does not change.
        val tick1 = async { gg.library.rowContent.first() }
        kotlinx.coroutines.delay(100)
        db.match(p, "k5", src, "ch5.uk")
        withTimeout(10_000) { tick1.await() }
        cache.reloadLoaded()
        withTimeout(10_000) { cache.changes.first { cache.channel(5)?.epg != null } }
        assertEquals(EpgChannelRef(src, "ch5.uk"), cache.channel(5)!!.epg)
        assertEquals(40, cache.size)

        // A custom-name edit reloads the block too.
        val tick2 = async { gg.library.rowContent.first() }
        kotlinx.coroutines.delay(100)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_name) VALUES (?, 'k3', 0, 0, 1, 'Renamed')", p)
        withTimeout(10_000) { tick2.await() }
        cache.reloadLoaded()
        withTimeout(10_000) { cache.changes.first { cache.channel(3)?.displayName == "Renamed" } }
        assertTrue(cache.channel(4)!!.displayName == "Ch 4")
    }
}
