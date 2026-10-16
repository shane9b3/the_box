package app.thebox.core.diag

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Named constants of the log ring and the export (docs/design/m5-setup-settings.md Appendix B, §3.11.3, §3.11.4). */
object LogLimits {
    const val LOG_RING_LINES = 4_000
    const val LOG_LINE_MAX_CHARS = 512
    const val LOG_EXPORT_MAX_BYTES = 8_388_608
    /** The file writer appends a batch every 10 s (and at ON_STOP) and rotates `current.log` at 1 MB, two files kept. */
    const val LOG_FILE_FLUSH_MS = 10_000L
    const val LOG_FILE_ROTATE_BYTES = 1_048_576L
    const val LOG_FILE_CURRENT = "current.log"
    const val LOG_FILE_PREVIOUS = "previous.log"
    const val LOG_DIR = "logs"
}

/**
 * The bounded in-memory ring (§3.11.3): the last [capacity] entries, each cut to [maxChars]. Thread-safe; [add] never
 * allocates beyond the entry it stores. Worst case ≈ 4,000 × 512 one-byte chars ≈ 2 MB (§5.6), compact strings.
 */
class LogRingBuffer(private val capacity: Int = LogLimits.LOG_RING_LINES, private val maxChars: Int = LogLimits.LOG_LINE_MAX_CHARS) {
    private val lines = arrayOfNulls<String>(capacity)
    private var next = 0
    private var size = 0

    fun add(line: String) {
        val cut = if (line.length > maxChars) line.substring(0, maxChars - 1) + "…" else line
        synchronized(lines) {
            lines[next] = cut
            next = (next + 1) % capacity
            if (size < capacity) size++
        }
    }

    /** Oldest first. */
    fun snapshot(): List<String> = synchronized(lines) {
        val out = ArrayList<String>(size)
        val start = (next - size + capacity) % capacity
        for (i in 0 until size) out += lines[(start + i) % capacity]!!
        out
    }

    fun clear() = synchronized(lines) {
        lines.fill(null)
        next = 0
        size = 0
    }
}

/**
 * The composite `BoxLog` sink (§3.11.3; reviewed 2026-10-16, S13). `BoxLog.emit` redacts the message but passes the
 * `Throwable` unredacted (OkHttp exceptions carry full URLs), so this sink formats `Redact.text(stack trace)` **once**
 * and hands the same string to the platform log, the ring and the file writer: nothing unredacted reaches any of them.
 */
class CompositeLogSink(
    /** The platform sink (`android.util.Log` in the app); receives the formatted text and a null throwable. */
    private val platform: BoxLog.Sink?,
    private val ring: LogRingBuffer,
    /** Appends one formatted entry to the file batch (null: no file, e.g. JVM tests). */
    private val file: ((String) -> Unit)?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stackTrace: (Throwable) -> String = ::stackTraceOf,
) : BoxLog.Sink {
    override fun log(level: Int, tag: String, message: String, error: Throwable?) {
        val text = if (error == null) message else message + "\n" + Redact.text(stackTrace(error))
        platform?.log(level, tag, text, null)
        val line = format(clock(), level, tag, text)
        ring.add(line)
        file?.invoke(line)
    }

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        fun format(wallMs: Long, level: Int, tag: String, text: String): String =
            "${TIME.format(Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()))} ${levelChar(level)}/$tag: $text"

        fun levelChar(level: Int): Char = when (level) {
            BoxLog.DEBUG -> 'D'
            BoxLog.INFO -> 'I'
            BoxLog.WARN -> 'W'
            BoxLog.ERROR -> 'E'
            2 -> 'V'
            else -> '?'
        }

        fun stackTraceOf(t: Throwable): String {
            val sw = StringWriter()
            PrintWriter(sw).use { t.printStackTrace(it) }
            return sw.toString().trimEnd()
        }
    }
}
