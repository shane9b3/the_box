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

    @Before fun setUp() = runBlocking {
        db = TestDb.create()
        gg = GuideGraph(TestGraph(db), events = index)
        p = db.newPlaylist("p")
        db.newChannel(p, "k0", "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET")
        Unit
    }

    @After fun tearDown() { db.close() }

    @Test
    fun rowContentTicksOnEveryIndexRebuild() = runBlocking {
        index.put(emptyList()) // a version that exists before the guide subscribes
        val ticks = Collections.synchronizedList(ArrayList<Long>())
        val job = launch(Dispatchers.IO) { gg.library.rowContent.collect { ticks += it } }
        delay(1_000)
        assertEquals("the current version is not a change", 0, ticks.size)
        index.put(listOf(FakeEventIndex.event(1L, "Vegas @ Edmonton", 1_000)))
        withTimeout(5_000) { while (ticks.isEmpty()) delay(20) }
        index.put(listOf(FakeEventIndex.event(1L, "Detroit @ Orlando", 1_000)))
        withTimeout(5_000) { while (ticks.size < 2) delay(20) }
        job.cancel()
    }
}
