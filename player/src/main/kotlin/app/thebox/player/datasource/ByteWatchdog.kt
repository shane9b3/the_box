package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import app.thebox.core.playback.MonoClock
import javax.inject.Inject
import javax.inject.Singleton

/** One shared daemon timer thread (`box-ts-watchdog`) checking every live session's last-byte age (§3.3.6). */
@Singleton
internal class ByteWatchdog @Inject constructor(private val clock: MonoClock) {
    /** Registers a session's connection check; returns the handle to unregister it in `close()`/`endSession` (N9). */
    fun register(tuneId: Long, check: () -> Unit): AutoCloseable = TODO("WP-B")
}
