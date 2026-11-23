package app.thebox.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.repo.EpgImporter
import app.thebox.data.repo.EpgRunResult
import app.thebox.data.repo.InMemoryDataSettings
import app.thebox.data.repo.PlaylistRefresher
import app.thebox.data.repo.RefreshResult
import app.thebox.data.repo.Trigger
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.FakeEpgStore
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** §3.10 unique work names, policies, constraints, and the app-start staleness rule, on work-testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefreshSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LibraryDatabase
    private lateinit var wm: WorkManager
    private val clock = FakeClock()
    private val store = FakeEpgStore()
    private val settings = InMemoryDataSettings()
    private val refreshes = ArrayList<Pair<Long, Trigger>>()
    private var epgBuilds = 0
    private var epgPatches = 0
    private lateinit var scheduler: RefreshScheduler

    private val refresher = object : PlaylistRefresher {
        override suspend fun refresh(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean, progress: (String) -> Unit): RefreshResult {
            refreshes += playlistId to trigger
            return RefreshResult(null, null)
        }
    }
    private val importer = object : EpgImporter {
        override suspend fun build(trigger: Trigger, force: Boolean, progress: (String) -> Unit) = EpgRunResult(null, null).also { epgBuilds++ }
        override suspend fun patch(progress: (String) -> Unit) = EpgRunResult(null, null).also { epgPatches++ }
    }

    @Before
    fun setUp() {
        db = TestDb.create()
        val notifications = WorkNotifications(context)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = when (workerClassName) {
                PlaylistRefreshWorker::class.java.name -> PlaylistRefreshWorker(appContext, workerParameters, refresher, notifications, null, null)
                EpgImportWorker::class.java.name -> EpgImportWorker(appContext, workerParameters, importer, notifications, null)
                EpgPatchWorker::class.java.name -> EpgPatchWorker(appContext, workerParameters, importer, notifications, null)
                else -> null
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        wm = WorkManager.getInstance(context)
        scheduler = RefreshScheduler(wm, db.playlistDao(), db.epgSourceDao(), db.refreshLogDao(), store, settings, clock)
    }

    @After fun tearDown() { db.close() }

    private companion object { const val HOUR = 3_600_000L }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    @Test
    fun periodicPlaylistWorkUsesTheIntervalAndConstraints() = runBlocking {
        val id = db.newPlaylist { it.copy(refreshIntervalH = 6) }
        scheduler.schedulePlaylist(db.playlistDao().get(id)!!)
        val info = infos(RefreshScheduler.periodicName(id)).single()
        assertEquals(TimeUnit.HOURS.toMillis(6), info.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(info.constraints.requiresStorageNotLow())
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue("playlist:$id" in info.tags && RefreshScheduler.TAG_SYNC in info.tags)
        // Interval 0 = manual: the periodic work is cancelled.
        scheduler.schedulePlaylist(db.playlistDao().get(id)!!.copy(refreshIntervalH = 0))
        assertEquals(WorkInfo.State.CANCELLED, infos(RefreshScheduler.periodicName(id)).single().state)
    }

    @Test
    fun manualRefreshRunsWhenConnected() = runBlocking {
        val id = db.newPlaylist()
        scheduler.refreshNow(id, Trigger.MANUAL)
        val info = infos(RefreshScheduler.refreshName(id)).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)
        assertEquals(WorkInfo.State.SUCCEEDED, infos(RefreshScheduler.refreshName(id)).single().state)
        assertEquals(listOf(id to Trigger.MANUAL), refreshes)
    }

    @Test
    fun appStartRefreshesOnlyStalePlaylistsAndImportsAMissingGuide() = runBlocking {
        val day = 24L * 3600 * 1000
        val stale = db.newPlaylist("stale") { it.copy(lastSuccessAt = clock.now() - day) }
        val fresh = db.newPlaylist("fresh") { it.copy(lastSuccessAt = clock.now() - 3600_000) }
        val never = db.newPlaylist("never")
        val off = db.newPlaylist("off") { it.copy(updateOnStart = false) }
        db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))

        scheduler.onAppStart()
        assertEquals(1, infos(RefreshScheduler.refreshName(stale)).size)
        assertEquals(0, infos(RefreshScheduler.refreshName(fresh)).size)
        assertEquals(1, infos(RefreshScheduler.refreshName(never)).size)
        assertEquals(0, infos(RefreshScheduler.refreshName(off)).size)
        assertEquals(1, infos(RefreshScheduler.periodicName(fresh)).size)
        assertEquals(1, infos(RefreshScheduler.EPG_PERIODIC).size)
        assertEquals(1, infos(RefreshScheduler.EPG_IMPORT).size)
        // KEEP: a second app start does not stack refreshes.
        scheduler.onAppStart()
        assertEquals(1, infos(RefreshScheduler.refreshName(stale)).size)
    }

    @Test
    fun theDailyHourSetsWhenThePeriodicGuideWorkStarts() = runBlocking {
        // Review 2026-11-23: 03:00 and 18:00 gave the same 24 h request.
        fun live() = infos(RefreshScheduler.EPG_PERIODIC).filter { !it.state.isFinished }
        val zone = java.time.ZoneId.systemDefault()
        settings.update { it.copy(epgRefreshIntervalH = 24, epgDailyAtHour = 3) }
        scheduler.scheduleEpgPeriodic()
        val at3 = live().single()
        assertEquals(TimeUnit.HOURS.toMillis(24), at3.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(DailyDelay.untilNextMs(3, clock.now(), zone), at3.initialDelayMillis)
        assertTrue(RefreshScheduler.anchorTag(3) in at3.tags)

        // The hour changes in Settings: the work is enqueued again for the new hour.
        settings.update { it.copy(epgDailyAtHour = 18) }
        scheduler.scheduleEpgPeriodic(reanchor = true)
        val at18 = live().single()
        assertEquals(DailyDelay.untilNextMs(18, clock.now(), zone), at18.initialDelayMillis)
        assertTrue(RefreshScheduler.anchorTag(18) in at18.tags)
        assertTrue(at18.id != at3.id)

        // An app start keeps work that is already for the right hour, and replaces work that is for another.
        db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        scheduler.onAppStart()
        assertEquals(at18.id, live().single().id)
        settings.update { it.copy(epgDailyAtHour = 5) }
        scheduler.onAppStart()
        assertTrue(RefreshScheduler.anchorTag(5) in live().single().tags)
        assertTrue(live().single().id != at18.id)

        // A plain interval has no start delay.
        settings.update { it.copy(epgRefreshIntervalH = 12, epgDailyAtHour = null) }
        scheduler.scheduleEpgPeriodic(reanchor = true)
        assertEquals(0L, live().single().initialDelayMillis)
        assertEquals(TimeUnit.HOURS.toMillis(12), live().single().periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun theDelayToADailyHourFollowsTheLocalClock() {
        val toronto = java.time.ZoneId.of("America/Toronto")
        fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(toronto).toInstant().toEpochMilli()
        val minute = 60_000L
        assertEquals(120 * minute, DailyDelay.untilNextMs(3, at("2026-01-10T01:00:00"), toronto))
        assertEquals(22 * 60 * minute, DailyDelay.untilNextMs(3, at("2026-01-10T05:00:00"), toronto))
        // Exactly on the hour: the next one, a day away, never a zero delay.
        assertEquals(24 * 60 * minute, DailyDelay.untilNextMs(3, at("2026-01-10T03:00:00"), toronto))
        // Spring forward (8 March 2026, 02:00 → 03:00): 02:00 does not exist and lands when the gap ends.
        assertEquals(90 * minute, DailyDelay.untilNextMs(2, at("2026-03-08T00:30:00"), toronto))
        // The night the clocks go back has 25 hours.
        assertEquals(25 * 60 * minute, DailyDelay.untilNextMs(3, at("2026-11-01T03:00:00").let { it - 24 * 60 * minute - 60 * minute }, toronto))
    }

    @Test
    fun appStartWithOnlyRestorePlaceholdersSchedulesNoGuideWork() = runBlocking {
        // docs/design/m5-restore-placeholder-guides.md §3: a placeholder has no address until the first refresh.
        val restored = db.newPlaylist("restored")
        db.epgSourceDao().insert(
            EpgSourceEntity(
                name = "x", origin = EpgSourceEntity.ORIGIN_PLAYLIST_HEADER, playlistId = restored, credentialRef = "r",
                urlHash = EpgSourceEntity.PLACEHOLDER_HASH, displaySource = "", priority = 0, createdAt = 1,
            ),
        )
        scheduler.onAppStart()
        assertEquals("the playlist itself still refreshes", 1, infos(RefreshScheduler.refreshName(restored)).size)
        assertEquals(0, infos(RefreshScheduler.EPG_PERIODIC).size)
        assertEquals(0, infos(RefreshScheduler.EPG_IMPORT).size)
    }

    @Test
    fun aFreshGuideIsNotRebuiltAtAppStart() = runBlocking {
        db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        store.publish()
        scheduler.onAppStart()
        // publish() sets builtAt = 0, i.e. ancient: it is rebuilt.
        assertEquals(1, infos(RefreshScheduler.EPG_IMPORT).size)
        val db2 = TestDb.create()
        val fresh = FakeEpgStore().apply { publish() }
        val s2 = RefreshScheduler(wm, db2.playlistDao(), db2.epgSourceDao(), db2.refreshLogDao(), fresh, settings, FakeClock(start = 1000))
        db2.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        wm.cancelAllWork().result.get()
        wm.pruneWork().result.get()
        s2.onAppStart()
        assertEquals(0, infos(RefreshScheduler.EPG_IMPORT).size)
        db2.close()
    }

    // ---- review 2026-11-10 S2: a not-modified guide run counts as fresh at app start, a failed one does not

    private suspend fun guideRow(outcome: String, finishedAt: Long) {
        db.refreshLogDao().insert(
            RefreshLogEntity(
                targetType = RefreshLogEntity.TARGET_EPG_BUILD, targetId = null, trigger = "PERIODIC",
                startedAt = finishedAt - 1000, finishedAt = finishedAt, outcome = outcome,
            ),
        )
    }

    private suspend fun guideWithGenerationAge(ageMs: Long) {
        db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        store.publish(builtAtMs = clock.now() - ageMs)
        settings.update { it.copy(epgRefreshIntervalH = 6) }
    }

    @Test
    fun aRecentNotModifiedRunKeepsAnOldGenerationFromDownloadingAtAppStart() = runBlocking {
        guideWithGenerationAge(8 * HOUR)
        guideRow("NOT_MODIFIED", clock.now() - 1 * HOUR)
        scheduler.onAppStart()
        assertEquals(0, infos(RefreshScheduler.EPG_IMPORT).size)
    }

    @Test
    fun aRecentFailedRunDoesNotCountAsFresh() = runBlocking {
        guideWithGenerationAge(8 * HOUR)
        guideRow("NOT_MODIFIED", clock.now() - 7 * HOUR)
        guideRow("FAILED", clock.now() - 1 * HOUR)
        scheduler.onAppStart()
        assertEquals(1, infos(RefreshScheduler.EPG_IMPORT).size)
    }

    @Test
    fun anOldNotModifiedRunStillImports() = runBlocking {
        guideWithGenerationAge(8 * HOUR)
        guideRow("NOT_MODIFIED", clock.now() - 6 * HOUR)
        scheduler.onAppStart()
        assertEquals(1, infos(RefreshScheduler.EPG_IMPORT).size)
    }

    @Test
    fun aRunLoggedInTheFutureDoesNotCountAsFresh() = runBlocking {
        // The clock moved back after the run: a negative age is not "recent".
        guideWithGenerationAge(8 * HOUR)
        guideRow("SUCCESS", clock.now() + 2 * HOUR)
        scheduler.onAppStart()
        assertEquals(1, infos(RefreshScheduler.EPG_IMPORT).size)
    }

    @Test
    fun patchRunsWithoutConstraintsAndAppends() = runBlocking {
        scheduler.patchEpg()
        assertEquals(WorkInfo.State.SUCCEEDED, infos(RefreshScheduler.EPG_PATCH).single().state)
        assertEquals(1, epgPatches)
        scheduler.importEpgNow(Trigger.MAPPING)
        val info = infos(RefreshScheduler.EPG_IMPORT).single()
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }
}
