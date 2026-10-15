package app.thebox

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.thebox.core.common.BoxLog
import app.thebox.data.di.ApplicationScope
import app.thebox.data.epg.EpgRecovery
import app.thebox.data.guide.DisplayNames
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.work.ImportMutex
import app.thebox.data.work.RefreshScheduler
import app.thebox.player.controller.ZapImportGate
import app.thebox.reminder.ReminderSchedulerImpl
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is initialised on demand through [Configuration.Provider] (the default
 * `WorkManagerInitializer` is removed in the manifest), so `@HiltWorker`s get their dependencies
 * (docs/design/m1-data-layer.md §3.10, M0 L2). Installs the `BoxLog` sink (redaction happens in BoxLog)
 * and runs the app-start staleness check on a background scope.
 *
 * M3 (docs/design/m3-guide.md §2.13 items 5 and 18): the reminder runtime is injected eagerly so reminders fire (and
 * missed ones are counted) from process start, not only once the guide view model exists; the channel display-name
 * backfill (§3.12.3) runs after `EpgRecovery`, off the startup path.
 */
@HiltAndroidApp
class TheBoxApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: Lazy<RefreshScheduler>
    @Inject lateinit var playlists: Lazy<PlaylistRepository>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var importMutex: ImportMutex
    @Inject lateinit var zapImportGate: ZapImportGate
    /** Eager (§2.13 item 5): constructing it starts its foreground loop. */
    @Inject lateinit var reminderScheduler: ReminderSchedulerImpl
    @Inject lateinit var epgRecovery: Lazy<EpgRecovery>
    @Inject lateinit var displayNames: Lazy<DisplayNames>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        BoxLog.minLevel = if (BuildConfig.DEBUG) BoxLog.DEBUG else BoxLog.INFO
        BoxLog.sink = BoxLog.Sink { level, tag, message, error -> Log.println(level, tag, if (error == null) message else message + "\n" + app.thebox.core.common.Redact.text(Log.getStackTraceString(error))) }
        importMutex.gate = zapImportGate // M2 §3.5.5: importers pause while a zap is in progress
        appScope.launch {
            try {
                scheduler.get().onAppStart()
            } catch (e: Exception) {
                BoxLog.e("TheBoxApplication", "app-start scheduling failed", e)
            }
        }
        appScope.launch {
            try {
                epgRecovery.get().await()
                delay(DISPLAY_NAMES_DELAY_MS) // after the first frame (§3.12.3); it runs on IO under ImportMutex
                displayNames.get().backfill().forEach { p ->
                    BoxLog.i("TheBoxApplication") { "display-name backfill playlist=${p.playlistId} scanned=${p.scanned} changed=${p.changed} ms=${p.ms}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.e("TheBoxApplication", "display-name backfill failed", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) playlists.get().clearSecretCache()
    }

    companion object {
        /** Lets the first guide frame and the startup tune go first (§3.12.3 "after the first frame"). */
        const val DISPLAY_NAMES_DELAY_MS = 3_000L
    }
}
