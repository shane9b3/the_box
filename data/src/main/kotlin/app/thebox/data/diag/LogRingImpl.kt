package app.thebox.data.diag

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import app.thebox.core.common.BoxLog
import app.thebox.core.diag.CompositeLogSink
import app.thebox.core.diag.LogLimits
import app.thebox.core.diag.LogRing
import app.thebox.core.diag.LogRingBuffer
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The log ring (docs/design/m5-setup-settings.md §3.11.3): [install] wraps the `android.util.Log` sink that
 * `TheBoxApplication` set into a [CompositeLogSink] (platform log + ring + file batch). The file writer appends the batch
 * to `filesDir/logs/current.log` every [LogLimits.LOG_FILE_FLUSH_MS] and when the last activity stops, rotating at
 * [LogLimits.LOG_FILE_ROTATE_BYTES] into `previous.log` (two files kept). Every entry is redacted once by the composite
 * sink before it reaches any of the three (S13). Nothing leaves the device unless the user exports it.
 */
@Singleton
class LogRingImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : LogRing {
    private val ring = LogRingBuffer()
    private val installed = AtomicBoolean(false)
    private val pending = ArrayList<String>()
    private val fileLock = Any()

    val dir: File get() = File(context.filesDir, LogLimits.LOG_DIR)

    override fun install() {
        if (!installed.compareAndSet(false, true)) return
        val platform = BoxLog.sink
        BoxLog.sink = CompositeLogSink(platform = platform, ring = ring, file = ::enqueue)
        scope.launch(io) {
            while (true) {
                delay(LogLimits.LOG_FILE_FLUSH_MS)
                flushToFile()
            }
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(StopWatcher { scope.launch(io) { flushToFile() } })
    }

    override fun snapshot(): List<String> = ring.snapshot()

    /** Rotated file lines, oldest first (previous.log, then current.log), for the export. Flushes the batch first. */
    fun fileLines(): List<List<String>> {
        flushToFile()
        return listOf(LogLimits.LOG_FILE_PREVIOUS, LogLimits.LOG_FILE_CURRENT).map { File(dir, it) }.filter { it.isFile }.map { f ->
            runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        }
    }

    private fun enqueue(line: String) {
        synchronized(pending) {
            pending += line
            // Never grow without bound if the IO loop is starved: keep the newest ring-sized batch.
            if (pending.size > LogLimits.LOG_RING_LINES) pending.subList(0, pending.size - LogLimits.LOG_RING_LINES).clear()
        }
    }

    /** Appends the pending batch; rotates first when current.log reached the limit. IO thread only. */
    fun flushToFile() {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        synchronized(fileLock) {
            try {
                dir.mkdirs()
                val current = File(dir, LogLimits.LOG_FILE_CURRENT)
                if (current.length() >= LogLimits.LOG_FILE_ROTATE_BYTES) {
                    val previous = File(dir, LogLimits.LOG_FILE_PREVIOUS)
                    previous.delete()
                    current.renameTo(previous)
                }
                File(dir, LogLimits.LOG_FILE_CURRENT).appendText(batch.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            } catch (e: Exception) {
                // Logging about the log file would recurse into this sink; the ring still has the lines.
            }
        }
    }

    /** Flushes when the last started activity stops (the process's ON_STOP without `lifecycle-process` in :data). */
    private class StopWatcher(private val onStop: () -> Unit) : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) { started++ }
        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) onStop()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
