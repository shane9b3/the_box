package app.thebox.data.guide

import app.thebox.core.guide.GuideSource
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * M3 code review 2026-10-14: G-S6 (a tune's `channel_prefs` writes are not guide changes) and the O-1 row-block cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideFlowsTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph
    private var p = 0L

    @Before fun setUp() = runBlocking {
        db = TestDb.create(); g = TestGraph(db); gg = GuideGraph(g)
        p = db.newPlaylist("p")
        for (i in 0 until 6) db.newChannel(p, "k$i", "Channel $i", sortIndex = i)
    }

    @After fun tearDown() { db.close() }

    /** G-S6: watch history and learned playback facts leave the lineup, the Groups panel and the row blocks alone. */
    @Test
    fun tuneTimeWritesDoNotRequeryTheGuide() = runBlocking {
        val ticks = Collections.synchronizedList(ArrayList<Long>())
        val jobs = listOf(
            launch(Dispatchers.IO) { gg.library.rowContent.collect { ticks += it } },
            launch(Dispatchers.IO) { gg.library.lineup(GuideSource.All(p), false).collect() },
            launch(Dispatchers.IO) { gg.library.sources(false).collect() },
        )
        withTimeout(5_000) { while (gg.queries.lineupQueries == 0 || gg.library.sourceBuilds == 0) delay(20) }
        // The first watch of a channel adds it to "Recently watched": the Groups panel may rebuild once for that.
        db.channelPrefsDao().recordWatched(p, "k1", 1_000)
        delay(1_200)
        val lineups0 = gg.queries.lineupQueries
        val sources0 = gg.library.sourceBuilds
        // What a tune writes about 5 s after first media and while it learns the stream (M2 §3.12).
        db.channelPrefsDao().recordWatched(p, "k1", 2_000)
        db.channelPrefsDao().upsertPlaybackFlags(p, "k1", 4, 2_000)
        db.channelPrefsDao().upsertFpsMilli(p, "k1", 50_000, 2_000)
        db.channelPrefsDao().upsertAudioLang(p, "k2", "fra", 2_000)
        delay(1_500) // past the 500 ms rowContent debounce
        assertEquals("row blocks not reloaded", 0, ticks.size)
        assertEquals("lineup not re-queried", lineups0, gg.queries.lineupQueries)
        assertEquals("Groups panel not rebuilt", sources0, gg.library.sourceBuilds)

        // A guide-relevant preference still triggers all three.
        gg.library.hideChannel(ChannelIdentity(p, StableKey("k3")))
        withTimeout(5_000) { while (ticks.isEmpty()) delay(20) }
        assertTrue("lineup re-queried after a hide", gg.queries.lineupQueries > lineups0)
        assertTrue("Groups panel rebuilt after a hide", gg.library.sourceBuilds > sources0)
        jobs.forEach { it.cancel() }
    }

    /** G-S6: Recent still follows the watch history. */
    @Test
    fun recentFollowsTheWatchHistory() = runBlocking {
        db.channelPrefsDao().recordWatched(p, "k1", 1_000)
        val seen = Collections.synchronizedList(ArrayList<List<Long>>())
        val job = launch(Dispatchers.IO) { gg.library.lineup(GuideSource.Recent, false).collect { seen += it.rowIds.toList() } }
        withTimeout(5_000) { while (seen.isEmpty()) delay(20) }
        db.channelPrefsDao().recordWatched(p, "k4", 2_000)
        withTimeout(5_000) { while (seen.last().size < 2) delay(20) }
        job.cancel()
    }

    /** O-1: the cached row-block path equals the uncached one and follows playlist and source changes. */
    @Test
    fun metaCacheMatchesTheUncachedPathAndFollowsInvalidation() = runBlocking {
        val meta = GuideMetaCache(db, g.playlists, db.epgSourceDao())
        val refs = EpgRefResolver(db, db.epgSourceDao(), db.playlistDao(), g.settings, meta)
        val cached = GuideQueries(db, g.playlists, gg.catchup, refs, meta)
        val src = db.newSource("s", shiftMin = 30)
        for (i in 0 until 6) db.match(p, "k$i", src, "x$i")
        val ids = LongArray(6) { i -> gg.queries.rowIdOf(ChannelIdentity(p, StableKey("k$i")))!! }
        assertEquals(gg.queries.channels(ids, 0, 6), cached.channels(ids, 0, 6))
        val loads = meta.loads
        repeat(3) { cached.channels(ids, 0, 6) }
        assertEquals("warm blocks read no playlist or source rows", loads, meta.loads)
        // A source shift change invalidates the cache (Room's tracker, asynchronous): wait for it, then compare again.
        db.exec("UPDATE epg_source SET shift_min = 90 WHERE id = ?", src)
        withTimeout(5_000) {
            while (cached.channels(ids, 0, 6) != gg.queries.channels(ids, 0, 6)) delay(20)
        }
        assertEquals(90 + 0, cached.channels(ids, 0, 1).single()!!.epgShiftMin)
    }
}
