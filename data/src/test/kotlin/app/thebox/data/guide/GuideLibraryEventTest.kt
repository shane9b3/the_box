package app.thebox.data.guide

import app.thebox.core.events.FakeEventIndex
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Event channels, docs/design/m6-event-channels.md §6.5 (arch B5): the guide's loaded rows reload when the event index
 * rebuilds, so a renamed event slot is redrawn without scrolling; the version current at subscription is not a change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideLibraryEventTest {
    private lateinit var db: LibraryDatabase
    private lateinit var gg: GuideGraph
    private val index = FakeEventIndex()
    private var p = 0L
    private var ch = 0L

    @Before fun setUp() = runBlocking {
        db = TestDb.create()
        gg = GuideGraph(TestGraph(db), events = index)
        p = db.newPlaylist("p")
        ch = db.newChannel(p, "k0", "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET")
        Unit
    }

    @After fun tearDown() { db.close() }

    @Test
    fun rowContentTicksOnEveryIndexRebuild() = runBlocking {
        index.put(emptyList()) // a version that exists before the guide subscribes
        val ids = gg.queries.lineup(app.thebox.core.guide.GuideSource.All(p), app.thebox.core.parental.legacyParentalAccess(false)).ids
        gg.library.channels(ids, 0, ids.size) // the rows carry that version
        val ticks = Collections.synchronizedList(ArrayList<Long>())
        val job = launch(Dispatchers.IO) { gg.library.rowContent.collect { ticks += it } }
        delay(1_000)
        assertEquals("the version the rows carry is not a change", 0, ticks.size)
        index.put(listOf(FakeEventIndex.event(1L, "Vegas @ Edmonton", 1_000)))
        withTimeout(5_000) { while (ticks.isEmpty()) delay(20) }
        gg.library.channels(ids, 0, ids.size)
        index.put(listOf(FakeEventIndex.event(1L, "Detroit @ Orlando", 1_000)))
        withTimeout(5_000) { while (ticks.size < 2) delay(20) }
        job.cancel()
    }

    /**
     * Code review 2026-11-25, data Q1 and m6: the guide's row loader never waits for a cold index. It loads the rows
     * without indexed events (the empty-slot fallback still applies), starts a background build, and a build that
     * finished before the guide subscribed still reloads the rows (the old `drop(1)` swallowed it).
     */
    @Test
    fun aColdIndexIsNotWaitedForAndItsBuildStillReloadsTheRows() = runBlocking {
        index.peekNull = true
        index.put(listOf(FakeEventIndex.event(ch, "Vegas @ Edmonton", 1_000, playlistId = p)))
        val ids = gg.queries.lineup(app.thebox.core.guide.GuideSource.All(p), app.thebox.core.parental.legacyParentalAccess(false)).ids
        val calls = index.currentCalls
        val rows = gg.library.channels(ids, 0, ids.size)
        assertEquals("not waited for", calls, index.currentCalls)
        assertEquals("a build was asked for", 1, index.prefetches)
        assertEquals(null, rows.single()!!.event)
        assertEquals(0L, gg.queries.lastServedVersion)
        // The build finished (version 1) before the guide subscribed: the rows still reload.
        index.peekNull = false
        val ticks = Collections.synchronizedList(ArrayList<Long>())
        val job = launch(Dispatchers.IO) { gg.library.rowContent.collect { ticks += it } }
        withTimeout(5_000) { while (ticks.isEmpty()) delay(20) }
        val again = gg.library.channels(ids, 0, ids.size)
        assertEquals("Vegas @ Edmonton", again.single()!!.event?.title)
        assertEquals(index.version.value, gg.queries.lastServedVersion)
        // Search, the matcher and reminders still wait (the default).
        gg.queries.channels(ids, 0, ids.size)
        assertEquals(calls + 2, index.currentCalls)
        job.cancel()
    }
}
