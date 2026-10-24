// M6 time-shift, WP-B (docs/design/m6-timeshift.md §8.3, §18 "TheBoxApp start-up sweep hook"). Save locations WP-S2
// (docs/design/m6-storage-locations.md §6): the volume watcher starts here too.
package app.thebox.player.timeshift

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The application's two time-shift hooks (§8.3): [onAppStart] starts the sweep of other processes' ring files from
 * `Application.onCreate` (background thread, returns at once) and the USB/SD [VolumeWatcher] (its receiver; listing and
 * volume sweep on `box-ts-sweep`), and [onTrimMemoryComplete] closes and deletes the ring file when no buffered cycle is
 * using it (`onTrimMemory(TRIM_MEMORY_COMPLETE)`).
 */
@Singleton
class TimeshiftStartup @Inject internal constructor(
    private val storage: TimeshiftStorageImpl,
    /** Lazy: the start-up sweep must not build the playback graph. */
    private val sessions: dagger.Lazy<TimeshiftSessionFactoryImpl>,
    private val volumes: dagger.Lazy<VolumeWatcher>,
) {
    fun onAppStart() {
        storage.startSweep()
        volumes.get().start()
    }

    fun onTrimMemoryComplete() = sessions.get().discardIfIdle()
}
