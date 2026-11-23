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
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.di.WallClock
import app.thebox.data.repo.DataSettings
import app.thebox.data.repo.EpgWorkRequester
import app.thebox.data.repo.Trigger
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
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
    private val logDao: RefreshLogDao,
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

    /**
     * The periodic guide update. With "daily at a set time" the 24 h period starts at the next local occurrence of
     * that hour and the request carries the hour as a tag (review 2026-11-23: the hour was stored and never used, so
     * 03:00 and 18:00 gave the same request). WorkManager runs it at about that time, later when the box was off.
     *
     * UPDATE keeps the timing of work that is already enqueued, so a changed hour needs [reanchor] (cancel and enqueue
     * again): the settings change passes it, and so does [onAppStart] when the enqueued work is for another hour.
     */
    fun scheduleEpgPeriodic(reanchor: Boolean = false) {
        val hours = settings.current.epgRefreshIntervalH.coerceAtLeast(1).toLong()
        val daily = settings.current.epgDailyAtHour
        val builder = PeriodicWorkRequestBuilder<EpgImportWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_S, TimeUnit.SECONDS)
            .setInputData(workDataOf(EpgImportWorker.KEY_TRIGGER to Trigger.PERIODIC.name))
            .addTag(TAG_SYNC).addTag(TAG_EPG_IMPORT).addTag(anchorTag(daily))
        if (daily != null) builder.setInitialDelay(DailyDelay.untilNextMs(daily, clock.now(), ZoneId.systemDefault()), TimeUnit.MILLISECONDS)
        val policy = if (reanchor) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE
        workManager.enqueueUniquePeriodicWork(EPG_PERIODIC, policy, builder.build())
    }

    /** True when the enqueued periodic guide work was made for another daily hour (or for none) than the setting now. */
    private suspend fun epgAnchorChanged(): Boolean {
        val live = workManager.getWorkInfosForUniqueWorkFlow(EPG_PERIODIC).first().filter { !it.state.isFinished }
        return live.isNotEmpty() && live.none { anchorTag(settings.current.epgDailyAtHour) in it.tags }
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
     * is missing or the guide was last found current longer ago than its interval. Also (re)registers the periodic work.
     *
     * (review 2026-11-10, S2) "Found current" is the newer of the generation's build and the last SUCCESS or
     * NOT_MODIFIED `EPG_BUILD` row: a run that downloaded an unchanged guide and skipped the build does not move
     * `built_at_ms`, and measuring from the build alone downloaded the whole guide on every launch until the next
     * rebuild. A FAILED row never counts. A time in the future (the clock moved back) does not count either.
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
        scheduleEpgPeriodic(reanchor = epgAnchorChanged())
        val builtAt = try {
            epgStore.info.value?.builtAtMs
        } catch (e: NotImplementedError) {
            BoxLog.w(TAG, "EPG store not available in this build")
            return
        }
        val interval = settings.current.epgRefreshIntervalH * 3_600_000L
        fun recent(at: Long?) = at != null && now - at in 0 until interval
        if (builtAt == null || !(recent(builtAt) || recent(logDao.lastCurrentAt(RefreshLogEntity.TARGET_EPG_BUILD, null)))) {
            workManager.enqueueUniqueWork(EPG_IMPORT, ExistingWorkPolicy.KEEP, epgImportRequest(Trigger.APP_START))
        }
    }

    companion object {
        private const val TAG = "RefreshScheduler"
        const val TAG_SYNC = "sync"
        const val TAG_PLAYLIST_PREFIX = "playlist:"
        const val TAG_EPG_IMPORT = "epg-import"
        /** The daily hour a periodic guide request was built for: `epg-anchor-3`, or `epg-anchor-none` for a plain interval. */
        fun anchorTag(dailyHour: Int?) = "epg-anchor-" + (dailyHour?.toString() ?: "none")
        const val TAG_EPG_PATCH = "epg-patch"
        const val EPG_PERIODIC = "epg-periodic"
        const val EPG_IMPORT = "epg-import"
        const val EPG_PATCH = "epg-patch"
        const val BACKOFF_S = 30L

        fun periodicName(playlistId: Long) = "playlist-periodic-$playlistId"
        fun refreshName(playlistId: Long) = "playlist-refresh-$playlistId"
    }
}

/** "Daily at a set time": how long until the next local occurrence of an hour. */
internal object DailyDelay {
    /**
     * Milliseconds from [nowMs] to the next [hour]:00 in [zone], always more than 0 and at most about a day. An hour
     * the clocks skip (spring forward) lands on the instant the gap ends; an hour that happens twice uses the first.
     */
    fun untilNextMs(hour: Int, nowMs: Long, zone: ZoneId): Long {
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        var at = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0).atZone(zone)
        if (!at.isAfter(now)) at = now.toLocalDate().plusDays(1).atTime(hour.coerceIn(0, 23), 0).atZone(zone)
        return at.toInstant().toEpochMilli() - nowMs
    }
}
