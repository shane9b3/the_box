package app.thebox.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.thebox.core.common.Outcome
import app.thebox.core.events.EventIndex
import app.thebox.core.events.EventSnapshot
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.net.ContentCopier
import app.thebox.data.repo.RefreshRepository
import app.thebox.data.repo.Trigger
import app.thebox.data.testutil.FakeDiffer
import app.thebox.data.testutil.FakeM3uParser
import app.thebox.data.testutil.FakeXtreamDecoder
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * docs/design/m6-event-channels.md §10, §11.1 `RefreshSchedulerTest` (event default): auto with 50 or more events gives
 * 6 h; Manual stays manual; a user value stands; the reschedule hook after a refresh's index rebuild (and only when the
 * period changes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefreshSchedulerEventTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var wm: WorkManager
    private lateinit var scheduler: RefreshScheduler

    @Before
    fun setUp() {
        db = TestDb.create()
        g = TestGraph(db)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(context)
        scheduler = RefreshScheduler(wm, db.playlistDao(), db.epgSourceDao(), db.refreshLogDao(), g.store, g.settings, g.clock)
    }

    @After fun tearDown() { db.close() }

    private fun live(id: Long): List<WorkInfo> = wm.getWorkInfosForUniqueWork(RefreshScheduler.periodicName(id)).get().filter { !it.state.isFinished }
    private fun periodH(id: Long): Long? = live(id).singleOrNull()?.periodicityInfo?.repeatIntervalMillis?.let { TimeUnit.MILLISECONDS.toHours(it) }

    @Test
    fun theEffectiveIntervalRule() {
        val f = RefreshScheduler.Companion
        assertEquals(6, f.effectiveIntervalH(24, auto = true, eventCount = 50))
        assertEquals(24, f.effectiveIntervalH(24, auto = true, eventCount = 49))
        assertEquals("a user value stands", 24, f.effectiveIntervalH(24, auto = false, eventCount = 1_543))
        assertEquals("a user value stands", 12, f.effectiveIntervalH(12, auto = false, eventCount = 1_543))
        assertEquals("Manual is never auto", 0, f.effectiveIntervalH(0, auto = true, eventCount = 1_543))
    }

    @Test
    fun scheduleUsesTheEffectiveInterval() = runBlocking {
        val auto = db.newPlaylist("auto") { it.copy(refreshIntervalH = 24, refreshIntervalAuto = true, eventCount = 1_543) }
        val user = db.newPlaylist("user") { it.copy(refreshIntervalH = 24, refreshIntervalAuto = false, eventCount = 1_543) }
        val manual = db.newPlaylist("manual") { it.copy(refreshIntervalH = 0, refreshIntervalAuto = false, eventCount = 1_543) }
        val few = db.newPlaylist("few") { it.copy(refreshIntervalH = 24, refreshIntervalAuto = true, eventCount = 3) }
        for (id in listOf(auto, user, manual, few)) scheduler.schedulePlaylist(db.playlistDao().get(id)!!)
        assertEquals(6L, periodH(auto))
        assertEquals(24L, periodH(user))
        assertNull(periodH(manual))
        assertEquals(24L, periodH(few))
    }

    @Test
    fun pickingAnIntervalClearsAutoAndStands() = runBlocking {
        val id = db.newPlaylist { it.copy(refreshIntervalH = 24, refreshIntervalAuto = true, eventCount = 900) }
        scheduler.schedulePlaylist(db.playlistDao().get(id)!!)
        assertEquals(6L, periodH(id))
        // The settings row (PlaylistSettingsRepository.setRefreshInterval): the DAO write clears auto, then schedules.
        db.playlistDao().setRefreshInterval(id, 24)
        val p = db.playlistDao().get(id)!!
        assertFalse(p.refreshIntervalAuto)
        scheduler.schedulePlaylist(p)
        assertEquals("24 h picked again stands (re-review E13)", 24L, periodH(id))
    }

    @Test
    fun rescheduleIfChangedActsOnlyOnAChangedPeriod() = runBlocking {
        val id = db.newPlaylist { it.copy(refreshIntervalH = 24, refreshIntervalAuto = true, eventCount = 0) }
        assertTrue("nothing enqueued yet: schedules", scheduler.rescheduleIfChanged(id))
        assertEquals(24L, periodH(id))
        assertFalse("same period: untouched", scheduler.rescheduleIfChanged(id))
        db.playlistDao().setEventCount(id, 60)
        assertTrue(scheduler.rescheduleIfChanged(id))
        assertEquals(6L, periodH(id))
        assertFalse(scheduler.rescheduleIfChanged(id))
        // Manual or disabled: never cancels from here (the settings path does that).
        db.playlistDao().setRefreshInterval(id, 0)
        assertFalse(scheduler.rescheduleIfChanged(id))
        assertEquals(6L, periodH(id))
        assertFalse(scheduler.rescheduleIfChanged(999))
    }

    @Test
    fun theRefreshRebuildsTheIndexThenReschedules() = runBlocking {
        val url = "http://panel.example:8080/list.m3u"
        val id = (g.playlists.addM3uUrl("My list", url) as Outcome.Ok).value
        assertTrue(db.playlistDao().get(id)!!.refreshIntervalAuto)
        scheduler.schedulePlaylist(db.playlistDao().get(id)!!)
        assertEquals(24L, periodH(id))
        g.fetcher.bodies[url] = "#EXTM3U\n#EXTINF:-1 group-title=\"Sports\",Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET\nhttp://h/1.ts\n"
        // The index's rebuild writes event_count, as EventIndexImpl does after `finish`.
        val index = object : EventIndex {
            var rebuilds = 0
            var countAtRebuild = -1L
            override val version = MutableStateFlow(0L)
            override suspend fun current(): EventSnapshot = EventSnapshot.EMPTY
            override suspend fun rebuild(): EventSnapshot {
                rebuilds++
                countAtRebuild = db.playlistDao().get(id)!!.refreshSeq
                db.playlistDao().setEventCount(id, 1_543)
                return EventSnapshot.EMPTY
            }
        }
        val repo = RefreshRepository(
            db, db.playlistDao(), db.epgSourceDao(), db.refreshLogDao(), g.playlists, g.matches, g.store, FakeM3uParser(), FakeDiffer(),
            FakeXtreamDecoder(), g.xtream, g.fetcher, ContentCopier(TestDb.context, Dispatchers.IO), g.timeBases, g.importMutex, g.requester,
            File(g.cacheRoot, "playlists"), Dispatchers.IO, g.clock, events = index, scheduler = scheduler,
        )
        val r = repo.refresh(id, Trigger.MANUAL)
        assertNull(r.error)
        assertEquals(1, index.rebuilds)
        assertEquals("rebuilt after `finish` bumped refresh_seq", 1L, index.countAtRebuild)
        assertEquals(6L, periodH(id))
    }
}
