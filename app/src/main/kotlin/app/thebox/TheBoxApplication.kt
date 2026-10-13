package app.thebox

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * WorkManager is initialised on demand through [Configuration.Provider] (the default
 * `WorkManagerInitializer` is removed in the manifest), so `@HiltWorker`s get their dependencies
 * (docs/design/m1-data-layer.md §3.10, M0 L2). WP-D owns this file after commit 0 and installs the
 * `BoxLog` sink here.
 */
@HiltAndroidApp
class TheBoxApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()
}
