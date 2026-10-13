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
        scheduler = RefreshScheduler(wm, db.playlistDao(), db.epgSourceDao(), store, settings, clock)
    }

    @After fun tearDown() { db.close() }

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
    fun aFreshGuideIsNotRebuiltAtAppStart() = runBlocking {
        db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        store.publish()
        scheduler.onAppStart()
        // publish() sets builtAt = 0, i.e. ancient: it is rebuilt.
        assertEquals(1, infos(RefreshScheduler.EPG_IMPORT).size)
        val db2 = TestDb.create()
        val fresh = FakeEpgStore().apply { publish() }
        val s2 = RefreshScheduler(wm, db2.playlistDao(), db2.epgSourceDao(), fresh, settings, FakeClock(start = 1000))
        db2.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        wm.cancelAllWork().result.get()
        wm.pruneWork().result.get()
        s2.onAppStart()
        assertEquals(0, infos(RefreshScheduler.EPG_IMPORT).size)
        db2.close()
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
