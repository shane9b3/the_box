package app.thebox.data.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.thebox.core.common.AuthReason
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.core.refresh.RefreshReport
import app.thebox.data.repo.EpgImporter
import app.thebox.data.repo.EpgRunResult
import app.thebox.data.repo.PlaylistRefresher
import app.thebox.data.repo.RefreshResult
import app.thebox.data.repo.Trigger
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** §5.3 "Workers" with fakes: result mapping, foreground info (B4), setForeground failure on API 31+ (B4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = WorkNotifications(context)

    private class FakeRefresher(var result: RefreshResult) : PlaylistRefresher {
        val calls = ArrayList<Triple<Long, Trigger, Boolean>>()
        override suspend fun refresh(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean, progress: (String) -> Unit): RefreshResult {
            calls += Triple(playlistId, trigger, forceAcceptShrink)
            progress("Downloading playlist")
            return result
        }
    }

    private class FakeImporter(var result: EpgRunResult = EpgRunResult(null, null)) : EpgImporter {
        var builds = 0
        var patches = 0
        override suspend fun build(trigger: Trigger, force: Boolean, progress: (String) -> Unit): EpgRunResult { builds++; return result }
        override suspend fun patch(progress: (String) -> Unit): EpgRunResult { patches++; return result }
    }

    private fun factory(refresher: PlaylistRefresher? = null, importer: EpgImporter? = null) = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = when (workerClassName) {
            PlaylistRefreshWorker::class.java.name -> PlaylistRefreshWorker(appContext, workerParameters, refresher!!, notifications, null, null)
            EpgImportWorker::class.java.name -> EpgImportWorker(appContext, workerParameters, importer!!, notifications, null)
            EpgPatchWorker::class.java.name -> EpgPatchWorker(appContext, workerParameters, importer!!, notifications, null)
            else -> null
        }
    }

    private fun report(outcome: RefreshOutcome) = RefreshReport(1, 1, 0, 0, outcome)

    private fun refreshWorker(refresher: PlaylistRefresher, attempt: Int = 0) =
        TestListenableWorkerBuilder<PlaylistRefreshWorker>(context)
            .setInputData(PlaylistRefreshWorker.input(7, Trigger.MANUAL, forceAcceptShrink = true))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(factory(refresher = refresher))
            .build()

    private fun run(refresher: PlaylistRefresher, attempt: Int = 0): ListenableWorker.Result = runBlocking { refreshWorker(refresher, attempt).doWork() }

    @Test
    fun successAndNotModifiedSucceed() {
        val r = FakeRefresher(RefreshResult(report(RefreshOutcome.SUCCESS), null))
        val result = run(r)
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("SUCCESS", result.outputData.getString(PlaylistRefreshWorker.KEY_OUTCOME))
        assertEquals(Triple(7L, Trigger.MANUAL, true), r.calls.single())
        r.result = RefreshResult(report(RefreshOutcome.NOT_MODIFIED), null)
        assertEquals("NOT_MODIFIED", run(r).outputData.getString(PlaylistRefreshWorker.KEY_OUTCOME))
    }

    @Test
    fun networkRetriesThenFails() {
        val r = FakeRefresher(RefreshResult(report(RefreshOutcome.FAILED), SourceError.Network(NetworkKind.TIMEOUT)))
        assertTrue(run(r, attempt = 0) is ListenableWorker.Result.Retry)
        assertTrue(run(r, attempt = 1) is ListenableWorker.Result.Retry)
        val last = run(r, attempt = 2)
        assertTrue(last is ListenableWorker.Result.Failure)
        assertEquals("net.timeout", last.outputData.getString(WorkResults.KEY_ERROR))
        r.result = RefreshResult(null, SourceError.Http(503))
        assertTrue(run(r) is ListenableWorker.Result.Retry)
        r.result = RefreshResult(null, SourceError.Credentials(CredentialFailure.UNAVAILABLE))
        assertTrue(run(r) is ListenableWorker.Result.Retry)
    }

    @Test
    fun authFormatShrinkAndCredentialsFailWithoutRetry() {
        for (e in listOf(
            SourceError.Auth(AuthReason.EXPIRED), SourceError.NotASource(NotASourceKind.HTML_PAGE), SourceError.SuspiciousShrink(2140, 12),
            SourceError.Credentials(CredentialFailure.KEY_INVALIDATED), SourceError.Http(404),
        )) {
            val result = run(FakeRefresher(RefreshResult(null, e)))
            assertTrue(e.code, result is ListenableWorker.Result.Failure)
            assertEquals(e.code, result.outputData.getString(WorkResults.KEY_ERROR))
        }
    }

    @Test
    fun missingInputFails() {
        val w = TestListenableWorkerBuilder<PlaylistRefreshWorker>(context)
            .setWorkerFactory(factory(refresher = FakeRefresher(RefreshResult(null, null)))).build()
        assertTrue(runBlocking { w.doWork() } is ListenableWorker.Result.Failure)
    }

    @Test
    fun refreshWorkerProvidesDataSyncForegroundInfo() = runBlocking {
        // B4: below API 31 an expedited worker runs as an FGS and WorkManager calls getForegroundInfo().
        val info: ForegroundInfo = refreshWorker(FakeRefresher(RefreshResult(null, null))).getForegroundInfo()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(WorkNotifications.ID_PLAYLIST, info.notificationId)
    }

    @Test
    @Config(sdk = [28])
    fun foregroundInfoBelowApi29HasNoType() = runBlocking {
        val info = refreshWorker(FakeRefresher(RefreshResult(null, null))).getForegroundInfo()
        assertEquals(0, info.foregroundServiceType)
    }

    @Test
    fun epgImportContinuesWhenForegroundIsNotAllowed() = runBlocking {
        val importer = FakeImporter()
        val w = TestListenableWorkerBuilder<EpgImportWorker>(context)
            .setWorkerFactory(factory(importer = importer))
            .setForegroundUpdater(FailingForegroundUpdater())
            .build()
        assertFalse(w.tryForeground())
        assertTrue(w.doWork() is ListenableWorker.Result.Success)
        assertEquals(1, importer.builds)
        importer.result = EpgRunResult(null, SourceError.DiskSpace(100, 10))
        assertTrue(w.doWork() is ListenableWorker.Result.Failure)
    }

    @Test
    fun epgPatchWorkerMapsResults() = runBlocking {
        val importer = FakeImporter(EpgRunResult(null, null, skipped = true))
        val w = TestListenableWorkerBuilder<EpgPatchWorker>(context).setWorkerFactory(factory(importer = importer)).build()
        val r = w.doWork()
        assertTrue(r is ListenableWorker.Result.Success)
        assertTrue(r.outputData.getBoolean(EpgImportWorker.KEY_SKIPPED, false))
        assertEquals(WorkNotifications.ID_EPG_PATCH, w.getForegroundInfo().notificationId)
    }

    /** API 31+ background start: setForeground fails with ForegroundServiceStartNotAllowedException (an IllegalStateException). */
    private class FailingForegroundUpdater : ForegroundUpdater {
        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> = object : ListenableFuture<Void> {
            private val error = ExecutionException(IllegalStateException("ForegroundServiceStartNotAllowedException"))
            override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
            override fun cancel(mayInterruptIfRunning: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = true
            override fun get(): Void = throw error
            override fun get(timeout: Long, unit: TimeUnit): Void = throw error
        }
    }
}
