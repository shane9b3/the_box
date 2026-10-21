// M6 time-shift, WP-B (docs/design/m6-timeshift.md §8.3, §18 "TheBoxApp start-up sweep hook").
package app.thebox.player.timeshift

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The application's two time-shift hooks (§8.3): [onAppStart] starts the sweep of other processes' ring files from
 * `Application.onCreate` (background thread, returns at once), and [onTrimMemoryComplete] closes and deletes the ring
 * file when no buffered cycle is using it (`onTrimMemory(TRIM_MEMORY_COMPLETE)`).
 */
@Singleton
class TimeshiftStartup @Inject internal constructor(
    private val storage: TimeshiftStorageImpl,
    /** Lazy: the start-up sweep must not build the playback graph. */
    private val sessions: dagger.Lazy<TimeshiftSessionFactoryImpl>,
) {
    fun onAppStart() = storage.startSweep()

    fun onTrimMemoryComplete() = sessions.get().discardIfIdle()
}
