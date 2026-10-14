package app.thebox.player.controller

import android.os.SystemClock
import app.thebox.core.common.ImportGate
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * [ImportGate] implementation (§3.5.5): holds importers while a zap is in progress. Public so `:app` can install it in
 * `ImportMutex.gate` (§6.5).
 *
 * The gate is **closed** by the controller at `tune()` and opens at the earliest of: first media +
 * [ZAP_IMPORT_RELEASE_AFTER_FIRST_FRAME_MS], tune + [ZAP_IMPORT_HOLD_MAX_MS], error, stop. The open time is a deadline
 * read by the waiting importer, so no timer thread is needed. [awaitClear] blocks the importer thread for at most
 * [maxWaitMs] per call, so a stuck tune never starves an import.
 */
@Singleton
class ZapImportGate internal constructor(
    private val clock: () -> Long,
    private val maxWaitMs: Long,
) : ImportGate {
    @Inject constructor() : this({ SystemClock.elapsedRealtime() }, IMPORT_GATE_MAX_WAIT_MS)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    /** [clock] value at which the gate opens by itself; 0 = open. Guarded by [lock]. */
    private var openAtMs = 0L

    /** True while a zap holds the gate (diagnostics and tests). */
    val isClosed: Boolean get() = lock.withLock { openAtMs != 0L && clock() < openAtMs }

    /** `tune()`: close for at most [ZAP_IMPORT_HOLD_MAX_MS]. Main thread. */
    fun closeForZap() = lock.withLock {
        openAtMs = clock() + ZAP_IMPORT_HOLD_MAX_MS
        changed.signalAll()
    }

    /** First media: open [ZAP_IMPORT_RELEASE_AFTER_FIRST_FRAME_MS] from now (never later than the current deadline). */
    fun releaseAfterFirstMedia() = lock.withLock {
        if (openAtMs == 0L) return@withLock
        openAtMs = minOf(openAtMs, clock() + ZAP_IMPORT_RELEASE_AFTER_FIRST_FRAME_MS)
        changed.signalAll()
    }

    /** Error or stop: open now. */
    fun open() = lock.withLock {
        openAtMs = 0L
        changed.signalAll()
    }

    /** Importer threads only (never the main thread): returns when the gate is open or after [maxWaitMs]. */
    override fun awaitClear() {
        val startNs = System.nanoTime()
        lock.withLock {
            while (true) {
                val now = clock()
                if (openAtMs == 0L || now >= openAtMs) return
                val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                val remaining = minOf(openAtMs - now, maxWaitMs - waitedMs)
                if (remaining <= 0) return
                try {
                    changed.await(remaining, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    companion object {
        const val ZAP_IMPORT_HOLD_MAX_MS = 3_000L
        const val ZAP_IMPORT_RELEASE_AFTER_FIRST_FRAME_MS = 500L
        const val IMPORT_GATE_MAX_WAIT_MS = 3_000L
    }
}
