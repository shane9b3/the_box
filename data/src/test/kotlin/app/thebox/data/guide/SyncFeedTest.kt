package app.thebox.data.guide

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.thebox.core.guide.SyncChipReason
import app.thebox.core.guide.SyncFeedState
import app.thebox.core.guide.syncChipReason
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.repo.SyncStatus
import app.thebox.data.repo.SyncStatusRepository
import app.thebox.data.testutil.FakeEpgStore
import app.thebox.data.testutil.TestDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner finding 2026-11-01 (docs/decisions/2026-11-01-guide-sync-chip.md): the chip's feed carries the newest failure and the generation time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncFeedTest {
    private lateinit var db: LibraryDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() { db = TestDb.create() }
    @After fun tearDown() { scope.cancel(); db.close() }

    private fun row(target: String, code: String, started: Long, targetId: Long? = null) = RefreshLogEntity(
        targetType = target, targetId = targetId, trigger = "PLAYLIST_CHANGED", startedAt = started, finishedAt = started + 10,
        outcome = RefreshOutcome.FAILED.name, errorCode = code,
    )

    @Test
    fun targetConstantMatchesTheEntity() {
        assertEquals(RefreshLogEntity.TARGET_EPG_BUILD, SyncFeedState.TARGET_EPG_BUILD)
    }

    @Test
    fun toFeedCarriesTheNewestFailureAndTheGenerationTime() {
        val older = row(RefreshLogEntity.TARGET_PLAYLIST, "http.403", 1_000, targetId = 1)
        val newest = row(RefreshLogEntity.TARGET_EPG_BUILD, "http.500", 5_000)
        // SyncStatus.failures is newest first (RefreshLogDao.observeUnacknowledgedFailures).
        val feed = SyncFeedImpl.toFeed(SyncStatus(failures = listOf(newest, older)), guideBuiltAtMs = 4_000)
        assertEquals(2, feed.unacknowledgedFailures)
        assertEquals("http.500", feed.lastFailureCode)
        assertEquals(RefreshLogEntity.TARGET_EPG_BUILD, feed.lastFailureTarget)
        assertEquals(5_000L, feed.lastFailureAtMs)
        assertEquals(4_000L, feed.guideBuiltAtMs)
        assertEquals(SyncChipReason.Stale(500, 4_000), syncChipReason(feed))

        val none = SyncFeedImpl.toFeed(SyncStatus())
        assertEquals(0, none.unacknowledgedFailures)
        assertNull(none.lastFailureCode)
        assertNull(none.lastFailureTarget)
        assertNull(none.guideBuiltAtMs)
    }

    @Test
    fun feedFollowsTheDaoOrderAndTheStoreGeneration() = runBlocking {
        val context = TestDb.context
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val repo = SyncStatusRepository(WorkManager.getInstance(context), db.refreshLogDao(), scope)
        val store = FakeEpgStore()
        val feed = SyncFeedImpl(repo, store, scope)
        db.refreshLogDao().insert(row(RefreshLogEntity.TARGET_PLAYLIST, "http.403", 1_000, targetId = 1))
        db.refreshLogDao().insert(row(RefreshLogEntity.TARGET_EPG_BUILD, "http.500", 5_000))

        val noGeneration = withTimeout(5_000) { feed.state.first { it.unacknowledgedFailures == 2 } }
        assertEquals("http.500", noGeneration.lastFailureCode)
        assertEquals(RefreshLogEntity.TARGET_EPG_BUILD, noGeneration.lastFailureTarget)
        assertNull(noGeneration.guideBuiltAtMs)
        assertEquals(SyncChipReason.Failed, syncChipReason(noGeneration))

        store.publish(builtAtMs = 4_000)
        val withGeneration = withTimeout(5_000) { feed.state.first { it.guideBuiltAtMs == 4_000L } }
        assertEquals(SyncChipReason.Stale(500, 4_000), syncChipReason(withGeneration))
    }
}
