package app.thebox.player.datasource

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One shared daemon timer thread (`box-ts-watchdog`) checking every live session's last-byte age (§3.3.6).
 *
 * The watchdog only drives the clock: each registered check (one per open `LiveTsDataSource`) decides with its own
 * timeouts whether its current call has been silent too long and cancels it (`Call.cancel()` unblocks a socket read
 * at once; `Thread.interrupt()` does not). Checks run on the watchdog thread and must not block. The thread starts on
 * the first registration and stays (one parked thread per process); (perf review L5) the timer itself runs only while
 * a check is registered, so an idle process is not woken twice a second.
 */
@Singleton
internal class ByteWatchdog @Inject constructor(private val clock: MonoClock) {
    private class Entry(val tuneId: Long, val registeredAtMs: Long, val check: () -> Unit)

    private val entries = CopyOnWriteArrayList<Entry>()
    private var executor: ScheduledExecutorService? = null
    private var timer: ScheduledFuture<*>? = null

    /** False in tests that drive [tick] by hand. */
    @Volatile internal var autoStart = true

    /** Registers a session's connection check; returns the handle to unregister it in `close()`/`endSession` (N9). */
    fun register(tuneId: Long, check: () -> Unit): AutoCloseable {
        val entry = Entry(tuneId, clock.nowMs(), check)
        entries += entry
        if (autoStart) ensureStarted()
        return AutoCloseable { entries.remove(entry) }
    }

    /** Runs every check once (the timer calls this every [WATCHDOG_TICK_MS]). */
    internal fun tick() {
        for (e in entries) {
            try {
                e.check()
            } catch (t: Throwable) {
                BoxLog.w(TAG, "watchdog check failed for tune ${e.tuneId} (registered ${clock.nowMs() - e.registeredAtMs} ms ago)", t)
            }
        }
    }

    internal fun registeredCount(): Int = entries.size

    /** Called after the entry was added, so [timerTick]'s emptiness check under the same lock cannot miss it. */
    @Synchronized
    private fun ensureStarted() {
        if (timer != null) return
        val ex = executor ?: Executors.newSingleThreadScheduledExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }.also { executor = it }
        timer = ex.scheduleWithFixedDelay(::timerTick, WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun timerTick() {
        tick()
        synchronized(this) {
            if (entries.isEmpty()) {
                timer?.cancel(false)
                timer = null
            }
        }
    }

    companion object {
        /** Tick period of the shared timer (Appendix B). */
        const val WATCHDOG_TICK_MS = 500L
        private const val THREAD_NAME = "box-ts-watchdog"
        private const val TAG = "ByteWatchdog"
    }
}
