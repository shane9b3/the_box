package app.thebox.data.backup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.thebox.core.backup.BackupService
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.data.di.WallClock
import app.thebox.data.guide.DisplayNames
import app.thebox.data.repo.Trigger
import app.thebox.data.rules.HideRulesImpl
import app.thebox.data.work.RefreshScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Version facts from the installed package. */
@Singleton
class PackageBackupEnvironment @Inject constructor(@ApplicationContext context: Context) : BackupEnvironment {
    private val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    override val versionName: String = info?.versionName ?: "?"
    override val versionCode: Long = info?.let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
    } ?: 0L
}

/** §3.10.4 step 5: refresh new playlists and those with a new login; recompute hide rules and display names. */
@Singleton
class DefaultRestoreFollowUp @Inject constructor(
    private val scheduler: RefreshScheduler,
    private val rules: HideRulesImpl,
    private val displayNames: DisplayNames,
) : RestoreFollowUp {
    override suspend fun afterRestore(newOrRelogin: List<Long>, affected: List<Long>) {
        for (id in affected) {
            rules.apply(id)
            displayNames.recompute(id)
        }
        for (id in newOrRelogin) scheduler.refreshNow(id, Trigger.PLAYLIST_CHANGED)
    }
}

/**
 * The weekly automatic backup (§3.10.5): periodic 7 days, `KEEP`, storage-not-low; the body is
 * [BackupService.autoBackup] (which waits for `ImportMutex`). [sync] follows the `backup.weekly` row.
 */
@Singleton
class AutoBackupScheduler @Inject constructor(private val workManager: WorkManager) {
    fun sync(weekly: Boolean) {
        if (!weekly) {
            workManager.cancelUniqueWork(NAME)
            return
        }
        // (M5 integration) A periodic request runs at once when first enqueued: on a fresh install that wrote a nearly
        // empty backup to Download/TheBox at the first start (31 orphaned files after the device pass's reinstalls).
        // The first weekly backup waits a day, when there is something to keep.
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setInitialDelay(FIRST_DELAY_HOURS, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val NAME = "backup-weekly"
        /** The first automatic backup after the schedule starts (Appendix B style constant). */
        const val FIRST_DELAY_HOURS = 24L
    }
}

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupService,
    private val clock: WallClock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (backups.autoBackup(clock.now())) {
        is Outcome.Ok -> Result.success()
        is Outcome.Err -> { BoxLog.w("AutoBackupWorker", "weekly backup failed; retried next week"); Result.success() }
    }
}

/**
 * (F17 contradicted, §3.10.5) A document picker exists only when `ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT`
 * resolve **to a known DocumentsUI package** (or a file manager the user picked once): on TVs without DocumentsUI the
 * intent can resolve to a stub activity, so `resolveActivity() != null` proves nothing.
 * On API 30+ the query only sees packages the manifest's `<queries>` declares (lead amendment requested).
 */
class DocumentsUiDetector(
    private val resolve: (Intent) -> List<String>,
    private val extraPackages: () -> Set<String> = { emptySet() },
) {
    @Inject constructor(@ApplicationContext context: Context) : this({ intent ->
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { it.activityInfo?.packageName }
    })

    fun available(): Boolean {
        val known = KNOWN + extraPackages()
        val open = resolve(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        val create = resolve(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream"))
        return open.any { it in known } && create.any { it in known }
    }

    companion object {
        val KNOWN = setOf("com.android.documentsui", "com.google.android.documentsui")
    }
}
