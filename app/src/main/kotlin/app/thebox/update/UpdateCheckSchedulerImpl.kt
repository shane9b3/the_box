package app.thebox.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.SettingsStore
import app.thebox.core.update.UpdateCheckScheduler
import app.thebox.core.update.UpdateDomain
import app.thebox.core.update.UpdatePolicy
import app.thebox.core.update.UpdateTimings
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.StartupGate
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * `TheBoxApplication`'s `updates.scheduleCheck()` (docs/design/m5-setup-settings.md §1.3a, §3.12.2): after the first
 * frame ([StartupGate]) it enqueues one unique periodic WorkManager job, 24 h, network connected, its first run
 * [UpdatePolicy.firstCheckDelayMs] away (≥ 60 s after the first frame, or the rest of the 24 h since the last check).
 * `about.auto_check` off cancels it ([cancel]). The worker runs [UpdaterImpl.runCheck] (automatic), which applies the
 * cadence again, so an early or duplicated run asks nothing. Returns at once.
 *
 * (Owner ruling 2026-11-17) Every app start also checks once, [UpdateTimings.UPDATE_STARTUP_CHECK_DELAY_MS] after the
 * first frame, while "Check for updates automatically" is on and the build has an update endpoint.
 */
class UpdateCheckSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val gate: StartupGate,
    @ApplicationScope private val scope: CoroutineScope,
    /** D-L14: no daily worker while the build has no update endpoint (debug builds until the harness sets a manifest URL). */
    private val endpoints: UpdateEndpoints,
    private val updater: dagger.Lazy<UpdaterImpl>,
) : UpdateCheckScheduler {

    override fun scheduleCheck() {
        scope.launch {
            gate.awaitOpen()
            store.awaitReady()
            val prefs = store.flow(UpdateDomain).value
            val delay = firstRunDelay(UpdatePolicy.firstCheckDelayMs(prefs, System.currentTimeMillis()), endpoints.current().enabled)
            if (startupCheck(prefs.checkAutomatically, endpoints.current().enabled)) {
                scope.launch {
                    delay(UpdateTimings.UPDATE_STARTUP_CHECK_DELAY_MS)
                    // Never fatal: a failed check is silent and the daily worker still runs.
                    runCatching { updater.get().runStartupCheck() }.onFailure { BoxLog.w(TAG, "app-start update check failed: ${it.javaClass.simpleName}") }
                }
            }
            val wm = WorkManager.getInstance(context)
            if (delay == null) {
                wm.cancelUniqueWork(WORK)
                return@launch
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(UpdateTimings.UPDATE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // UPDATE keeps the period but takes the new initial delay, so each start re-anchors the first check.
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            BoxLog.i(TAG) { "automatic update check in ${delay / 1000} s, then every 24 h" }
        }
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    companion object {
        const val WORK = "update-check"
        const val TAG = "Updater"

        /** D-L14: the worker's first delay, or null (cancel) when checks are off or the endpoint is disabled. */
        fun firstRunDelay(policyDelayMs: Long?, endpointEnabled: Boolean): Long? = policyDelayMs?.takeIf { endpointEnabled }

        /** Whether this start checks for an update: automatic checks on and an update endpoint in this build. */
        fun startupCheck(checkAutomatically: Boolean, endpointEnabled: Boolean): Boolean = checkAutomatically && endpointEnabled
    }
}

/**
 * The periodic check. A plain worker (`:app` has no androidx.hilt compiler, so `@HiltWorker` would not be generated):
 * `HiltWorkerFactory` finds no assisted factory and WorkManager falls back to this constructor; the updater comes from
 * an [EntryPoint].
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun updater(): UpdaterImpl
    }

    override suspend fun doWork(): Result {
        val updater = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).updater()
        updater.runCheck(manual = false)
        return Result.success()
    }
}
