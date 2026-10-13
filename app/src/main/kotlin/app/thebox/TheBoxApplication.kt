package app.thebox

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.thebox.core.common.BoxLog
import app.thebox.data.di.ApplicationScope
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.work.RefreshScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is initialised on demand through [Configuration.Provider] (the default
 * `WorkManagerInitializer` is removed in the manifest), so `@HiltWorker`s get their dependencies
 * (docs/design/m1-data-layer.md §3.10, M0 L2). Installs the `BoxLog` sink (redaction happens in BoxLog)
 * and runs the app-start staleness check on a background scope.
 */
@HiltAndroidApp
class TheBoxApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: Lazy<RefreshScheduler>
    @Inject lateinit var playlists: Lazy<PlaylistRepository>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        BoxLog.minLevel = if (BuildConfig.DEBUG) BoxLog.DEBUG else BoxLog.INFO
        BoxLog.sink = BoxLog.Sink { level, tag, message, error -> Log.println(level, tag, if (error == null) message else message + "\n" + app.thebox.core.common.Redact.text(Log.getStackTraceString(error))) }
        appScope.launch {
            try {
                scheduler.get().onAppStart()
            } catch (e: Exception) {
                BoxLog.e("TheBoxApplication", "app-start scheduling failed", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) playlists.get().clearSecretCache()
    }
}
