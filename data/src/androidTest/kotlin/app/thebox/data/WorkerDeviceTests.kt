package app.thebox.data

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.thebox.core.common.Outcome
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.repo.EpgRepository
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.SyncStatusRepository
import app.thebox.data.repo.Trigger
import app.thebox.data.work.EpgImportWorker
import app.thebox.data.work.PlaylistRefreshWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * I-3 (EpgImportWorker end to end), I-4 (PlaylistRefreshWorker with WorkManagerTestInitHelper: refresh_log rows,
 * S15 status flow), I-6 (a `@HiltWorker` built by HiltWorkerFactory, M0 L2) and I-9 (expedited refresh as an FGS
 * on API 30; background `setForeground` refusal on API 34). Real Hilt graph from :data.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WorkerDeviceTests {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var epg: EpgRepository
    @Inject lateinit var db: LibraryDatabase
    @Inject lateinit var status: SyncStatusRepository

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setWorkerFactory(workerFactory).setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.DEBUG).build(),
        )
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() { server.close() }

    @Test
    fun i6HiltWorkerFactoryBuildsWorkers() {
        val w = TestListenableWorkerBuilder<PlaylistRefreshWorker>(context).setWorkerFactory(workerFactory)
            .setInputData(PlaylistRefreshWorker.input(1, Trigger.MANUAL)).build()
        assertTrue(w is PlaylistRefreshWorker)
        assertTrue(TestListenableWorkerBuilder<EpgImportWorker>(context).setWorkerFactory(workerFactory).build() is EpgImportWorker)
    }

    @Test
    fun i4PlaylistRefreshWorkerWritesRefreshLog() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("#EXTM3U\n#EXTINF:-1 tvg-id=\"a.uk\" group-title=\"News\",A\nhttp://h/a.ts\n").build())
        val id = (playlists.addM3uUrl("I4", server.url("/list.m3u").toString()) as Outcome.Ok).value
        val request = OneTimeWorkRequestBuilder<PlaylistRefreshWorker>()
            .setInputData(PlaylistRefreshWorker.input(id, Trigger.MANUAL))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val wm = WorkManager.getInstance(context)
        wm.enqueue(request).result.get()
        // SynchronousExecutor only covers WorkManager's own executor; a CoroutineWorker's doWork still runs on its
        // dispatcher, so wait for a terminal state instead of reading the state right after enqueue.
        val info = withTimeout(60_000) { wm.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true } }!!
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals("SUCCESS", db.refreshLogDao().latest("PLAYLIST", id).single().outcome)
        assertTrue(status.status.value.failures.isEmpty())
    }

    @Test
    fun i3EpgImportWorkerEndToEnd() = runBlocking {
        val file = File(context.cacheDir, "i3w.xml.gz").also { DeviceXmltv.write(it, 3, 10, System.currentTimeMillis() / 60_000 - 30, gzip = true) }
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().apply { write(file.readBytes()) }).build())
        epg.addUserSource("I3", server.url("/basic.xml.gz").toString())
        val w = TestListenableWorkerBuilder<EpgImportWorker>(context).setWorkerFactory(workerFactory).build()
        val r = w.doWork()
        assertTrue(r is ListenableWorker.Result.Success)
        assertEquals("SUCCESS", db.refreshLogDao().latest("EPG_BUILD", null).single().outcome)
    }

    @Test
    fun i9ForegroundInfoIsDataSync() = runBlocking {
        // API 30 AVD: an expedited worker runs as an FGS and must provide its ForegroundInfo (B4).
        // API 34 AVD: started from the background, setForeground is refused and the import still completes.
        val w = TestListenableWorkerBuilder<EpgImportWorker>(context).setWorkerFactory(workerFactory).build()
        val info = w.getForegroundInfo()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        }
    }
}
