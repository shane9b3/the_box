package app.thebox.data.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.thebox.core.common.BoxLog
import app.thebox.core.epg.EpgStore
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.di.WallClock
import app.thebox.data.repo.DataSettings
import app.thebox.data.repo.EpgWorkRequester
import app.thebox.data.repo.Trigger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unique work names, policies and constraints of §3.10:
 *
 * | name | kind / policy | worker | constraints |
 * |---|---|---|---|
 * | `playlist-periodic-<id>` | periodic `refresh_interval_h`, UPDATE | PlaylistRefreshWorker | CONNECTED, storageNotLow |
 * | `playlist-refresh-<id>` | one-time, KEEP (app start) / REPLACE (manual, onboarding; expedited) | PlaylistRefreshWorker | CONNECTED |
 * | `epg-periodic` | periodic 12 h, UPDATE | EpgImportWorker | CONNECTED, storageNotLow |
 * | `epg-import` | one-time, APPEND_OR_REPLACE | EpgImportWorker | CONNECTED |
 * | `epg-patch` | one-time, APPEND_OR_REPLACE, no input | EpgPatchWorker | none (cached files) |
 */
@Singleton
class RefreshScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val playlistDao: PlaylistDao,
    private val sourceDao: EpgSourceDao,
    private val epgStore: EpgStore,
    private val settings: DataSettings,
    private val clock: WallClock,
) : EpgWorkRequester {

    fun schedulePlaylist(p: PlaylistEntity) {
        val name = periodicName(p.id)
        if (p.refreshIntervalH <= 0 || !p.enabled) {
            workManager.cancelUniqueWork(name)
            return
        }
        val request = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(p.refreshIntervalH.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_S, TimeUnit.SECONDS)
            .setInputData(PlaylistRefreshWorker.input(p.id, Trigger.PERIODIC))
            .addTag(TAG_SYNC).addTag(TAG_PLAYLIST_PREFIX + p.id)
            .build()
        workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPlaylist(playlistId: Long) {
        workManager.cancelUniqueWork(periodicName(playlistId))
        workManager.cancelUniqueWork(refreshName(playlistId))
    }

    /** MANUAL/ONBOARDING replace a running refresh and are expedited; others keep an existing one. */
    fun refreshNow(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean = false) {
        val user = trigger == Trigger.MANUAL || trigger == Trigger.ONBOARDING
        val builder = OneTimeWorkRequestBuilder<PlaylistRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_S, TimeUnit.SECONDS)
            .setInputData(PlaylistRefreshWorker.input(playlistId, trigger, forceAcceptShrink))
            .addTag(TAG_SYNC).addTag(TAG_PLAYLIST_PREFIX + playlistId)
        if (user) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        workManager.enqueueUniqueWork(refreshName(playlistId), if (user) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, builder.build())
    }

    fun scheduleEpgPeriodic() {
        val hours = settings.current.epgRefreshIntervalH.coerceAtLeast(1).toLong()
        val request = PeriodicWorkRequestBuilder<EpgImportWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_S, TimeUnit.SECONDS)
            .setInputData(workDataOf(EpgImportWorker.KEY_TRIGGER to Trigger.PERIODIC.name))
            .addTag(TAG_SYNC).addTag(TAG_EPG_IMPORT)
            .build()
        workManager.enqueueUniquePeriodicWork(EPG_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun importEpgNow(trigger: Trigger) {
        workManager.enqueueUniqueWork(EPG_IMPORT, ExistingWorkPolicy.APPEND_OR_REPLACE, epgImportRequest(trigger))
    }

    override fun patchEpg() {
        val request = OneTimeWorkRequestBuilder<EpgPatchWorker>()
            .addTag(TAG_SYNC).addTag(TAG_EPG_PATCH)
            .build()
        workManager.enqueueUniqueWork(EPG_PATCH, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun epgImportRequest(trigger: Trigger): OneTimeWorkRequest = OneTimeWorkRequestBuilder<EpgImportWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_S, TimeUnit.SECONDS)
        .setInputData(workDataOf(EpgImportWorker.KEY_TRIGGER to trigger.name))
        .addTag(TAG_SYNC).addTag(TAG_EPG_IMPORT)
        .build()

    /**
     * App start (§3.10): boxes are often unplugged, so periodic work drifts. Refreshes playlists with
     * `update_on_start` whose last success is older than 12 h (KEEP), and imports the EPG when the generation
     * is missing or older than its interval. Also (re)registers the periodic work.
     */
    suspend fun onAppStart() {
        val now = clock.now()
        val stale = settings.current.playlistStaleAfterMs
        for (p in playlistDao.all()) {
            if (!p.enabled) continue
            schedulePlaylist(p)
            if (p.updateOnStart && (p.lastSuccessAt == null || now - p.lastSuccessAt >= stale)) refreshNow(p.id, Trigger.APP_START)
        }
        if (sourceDao.active().isEmpty()) return
        scheduleEpgPeriodic()
        val builtAt = try {
            epgStore.info.value?.builtAtMs
        } catch (e: NotImplementedError) {
            BoxLog.w(TAG, "EPG store not available in this build")
            return
        }
        val interval = settings.current.epgRefreshIntervalH * 3_600_000L
        if (builtAt == null || now - builtAt >= interval) {
            workManager.enqueueUniqueWork(EPG_IMPORT, ExistingWorkPolicy.KEEP, epgImportRequest(Trigger.APP_START))
        }
    }

    companion object {
        private const val TAG = "RefreshScheduler"
        const val TAG_SYNC = "sync"
        const val TAG_PLAYLIST_PREFIX = "playlist:"
        const val TAG_EPG_IMPORT = "epg-import"
        const val TAG_EPG_PATCH = "epg-patch"
        const val EPG_PERIODIC = "epg-periodic"
        const val EPG_IMPORT = "epg-import"
        const val EPG_PATCH = "epg-patch"
        const val BACKOFF_S = 30L

        fun periodicName(playlistId: Long) = "playlist-periodic-$playlistId"
        fun refreshName(playlistId: Long) = "playlist-refresh-$playlistId"
    }
}
