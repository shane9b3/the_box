package app.thebox

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.thebox.core.common.BoxLog
import app.thebox.crash.CrashConfig
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.StartupGate
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is initialised on demand through [Configuration.Provider] (the default
 * `WorkManagerInitializer` is removed in the manifest), so `@HiltWorker`s get their dependencies
 * (docs/design/m1-data-layer.md §3.10, M0 L2). Installs the `BoxLog` sink (redaction happens in BoxLog)
 * and runs the app-start staleness check on a background scope.
 *
 * M3 (docs/design/m3-guide.md §2.13 items 5 and 18): the reminder runtime is started from the application, so reminders
 * fire (and missed ones are counted) whether or not the guide view model exists; (code review 2026-10-14, O-7) it is
 * created once the first frame has drawn ([StartupGate]), not during `Application.onCreate`. The channel display-name
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
    /** Created after the first frame (O-7): constructing it starts its foreground loop (§2.13 item 5). */
    @Inject lateinit var reminderScheduler: Lazy<ReminderSchedulerImpl>
    @Inject lateinit var startupGate: StartupGate
    @Inject lateinit var epgRecovery: Lazy<EpgRecovery>
    @Inject lateinit var displayNames: Lazy<DisplayNames>
    // M5 commit 0 (docs/design/m5-setup-settings.md §1.3a): the startup hooks, in order (bodies: WP-A, WP-E).
    @Inject lateinit var settingsStore: app.thebox.core.settings.SettingsStore
    @Inject lateinit var logRing: app.thebox.core.diag.LogRing
    @Inject lateinit var updates: app.thebox.core.update.UpdateCheckScheduler
    /** (M5 integration, WP-D §1.7a item 2) The weekly automatic backup is scheduled from the stored choice once per start. */
    @Inject lateinit var autoBackup: Lazy<app.thebox.data.backup.AutoBackupScheduler>
    /** (M6 time-shift, docs/design/m6-timeshift.md §8.3) The ring-file sweep at start and the trim-memory discard. */
    @Inject lateinit var timeshift: Lazy<app.thebox.player.timeshift.TimeshiftStartup>
    /**
     * (Save-location integration, S1-Q1; docs/design/m6-storage-locations.md §5.3) The ring-target source, and with it
     * the SMB keeper, built off main at start when a network folder is chosen for rewind, so the first tune can find it
     * READY. The keeper's own first connect still waits for the first frame (A-N6) and idles in the background.
     */
    @Inject lateinit var ringTargets: Lazy<app.thebox.core.playback.timeshift.RingTargetSource>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // M5 commit 0: ACRA's sender process (`:acra`) only hands stored crash reports to the local file sender.
        if (CrashConfig.isSenderProcess()) {
            CrashConfig.installLocalOnly(this)
            return
        }
        BoxLog.minLevel = if (BuildConfig.DEBUG) BoxLog.DEBUG else BoxLog.INFO
        BoxLog.sink = BoxLog.Sink { level, tag, message, error -> Log.println(level, tag, if (error == null) message else message + "\n" + app.thebox.core.common.Redact.text(Log.getStackTraceString(error))) }
        // M5 commit 0 (docs/design/m5-setup-settings.md §1.3a, §0.5): settings load first (one SELECT on IO, returns at
        // once), the log ring before anything logs (it wraps the sink above), crash reports local only, then the update
        // check's schedule.
        settingsStore.load()
        logRing.install()
        CrashConfig.installLocalOnly(this)
        updates.scheduleCheck()
        importMutex.gate = zapImportGate // M2 §3.5.5: importers pause while a zap is in progress
        // (M6 time-shift, §8.3, A-S7) Other processes' ring files go on a background thread; the first buffered tune's
        // prepare() waits for it, so a launch-time auto-tune never loses its own file.
        timeshift.get().onAppStart()
        appScope.launch(Dispatchers.Main) {
            startupGate.awaitOpen()
            reminderScheduler.get()
        }
        appScope.launch(Dispatchers.IO) {
            try {
                settingsStore.awaitReady()
                val chosen = settingsStore.flow(app.thebox.core.settings.state.StorageDomain).value.timeshiftLocationId
                if (chosen.startsWith(app.thebox.core.storage.StorageConstants.SMB_ID_PREFIX)) ringTargets.get()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.e("TheBoxApplication", "save-location keeper start failed", e)
            }
        }
        // (M5 integration) After the settings read and the first frame, off the startup path: the weekly backup's
        // WorkManager entry follows the stored "weekly" choice (on by default), not only a toggle of the row.
        appScope.launch {
            try {
                settingsStore.awaitReady()
                startupGate.awaitOpen()
                autoBackup.get().sync(settingsStore.flow(app.thebox.core.backup.BackupDomain).value.weekly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.e("TheBoxApplication", "weekly backup scheduling failed", e)
            }
        }
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            playlists.get().clearSecretCache()
            // (M6, §8.3) The ring file goes unless a buffered cycle uses it.
            timeshift.get().onTrimMemoryComplete()
        }
    }

    companion object {
        /** Lets the first guide frame and the startup tune go first (§3.12.3 "after the first frame"). */
        const val DISPLAY_NAMES_DELAY_MS = 3_000L
    }
}
