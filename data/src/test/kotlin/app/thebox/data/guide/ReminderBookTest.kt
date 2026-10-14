package app.thebox.data.guide

import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.SyncFeedState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.ReminderEntity
import app.thebox.data.repo.SyncStatus
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m3-guide.md §5.2 `ReminderBookTest` (§3.11.1; S1, N5), plus the session store and env mappings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderBookTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph

    @Before fun setUp() { db = TestDb.create(); g = TestGraph(db); gg = GuideGraph(g) }
    @After fun tearDown() { db.close() }

    @Test
    fun setCancelIndexDuplicatesAndReSet() = runBlocking {
        val p = db.newPlaylist()
        val ch = GuideFixtures.channel(3, playlistId = p, name = "BBC One", epgShiftMin = 60)
        val info = GuideFixtures.info(startMin = 1_000_060, endMin = 1_000_120, title = "Film", shiftMin = 60)
        val book = gg.reminders
        assertTrue(book.set(ch, info, 60))
        assertFalse("a pending reminder for the key already exists", book.set(ch, info, 60))
        val pending = withTimeout(5_000) { book.pending.first { it.isNotEmpty() } }.single()
        assertEquals(1_000_000, pending.rawStartMin)
        assertEquals(1_000_060, pending.rawEndMin)
        assertEquals("S1: shift_min stored", 60, pending.shiftMin)
        assertEquals("Film", pending.title)
        assertEquals("BBC One", pending.channelName)
        assertEquals(ch.identity, pending.identity)
        assertEquals(60L, db.scalar("SELECT shift_min FROM reminder"))
        val index = withTimeout(5_000) { book.index.first { it.size == 1 } }
        assertTrue(index.contains(ch.identityHash, 1_000_000))
        assertFalse(index.contains(ch.identityHash, 1_000_060))

        // Fired → re-setting the same airing succeeds (N5) and the row is pending again.
        book.markFired(pending.id)
        assertEquals(ReminderEntity.STATE_FIRED.toLong(), db.scalar("SELECT state FROM reminder"))
        withTimeout(5_000) { book.pending.first { it.isEmpty() } }
        book.dismiss(pending.id) // not pending any more: no change
        assertEquals(ReminderEntity.STATE_FIRED.toLong(), db.scalar("SELECT state FROM reminder"))
        assertTrue(book.set(ch, info, 120))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM reminder"))
        val again = withTimeout(5_000) { book.pending.first { it.isNotEmpty() } }.single()
        assertEquals(120, again.leadSec)
        book.markOrphaned(again.id)
        assertEquals(ReminderEntity.STATE_ORPHANED.toLong(), db.scalar("SELECT state FROM reminder"))
        assertTrue(book.set(ch, info, 60))
        book.cancel(ch.identity, info.rawStartMin)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM reminder"))
        assertEquals(0, withTimeout(5_000) { book.index.first { it.size == 0 } }.size)
    }

    @Test
    fun sessionStoreRoundTrips() {
        val store = GuideSessionStoreImpl(TestDb.context)
        assertNull(store.lastSource())
        for (s in listOf(GuideSource.Recent, GuideSource.All(4), GuideSource.Favourites(9), GuideSource.Group(2, "g:uk|news"))) {
            store.setLastSource(s)
            assertEquals(s, GuideSessionStoreImpl(TestDb.context).lastSource())
        }
        assertFalse(store.flag("reminder_first"))
        store.setFlag("reminder_first")
        assertTrue(GuideSessionStoreImpl(TestDb.context).flag("reminder_first"))
        assertNull(GuideSessionStoreImpl.decode("G\u001Fx"))
    }

    @Test
    fun envMappings() {
        assertEquals(
            SyncFeedState(busy = true, progress = "Downloading", unacknowledgedFailures = 0),
            SyncFeedImpl.toFeed(SyncStatus(epgImporting = true, progress = "Downloading")),
        )
        val window = GuideDataWindowSourceImpl(g.settings, g.scope)
        assertEquals(2, window.window.value.pastDays)
        g.settings.update { it.copy(epgPastDays = 5) }
        runBlocking { withTimeout(5_000) { window.window.first { it.pastDays == 5 } } }
        val settings = InMemoryGuideSettings()
        settings.update { it.copy(debugUnlocked = true) }
        assertTrue(settings.settings.value.debugUnlocked)
    }
}
