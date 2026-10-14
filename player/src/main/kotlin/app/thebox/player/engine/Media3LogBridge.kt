package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import app.thebox.core.common.BoxLog

/**
 * Routes `androidx.media3.common.util.Log` through redacting [BoxLog] (docs/design/m2-playback-core.md §3.13), under
 * the tag `Media3`. Installed by `PlayerFactory`; idempotent.
 *
 * Throwables are flattened into the message (class and message of up to [MAX_CAUSES] causes) and the throwable itself
 * is not passed on: `BoxLog.emit` redacts the message but a sink would print a throwable's own messages raw, and
 * OkHttp/Media3 exception messages can carry hosts, paths or query strings.
 */
@OptIn(UnstableApi::class)
internal object Media3LogBridge {
    private const val TAG = "Media3"
    private const val MAX_CAUSES = 4

    /** Sets the logger and the level (`LOG_LEVEL_ALL` in debuggable builds, warnings otherwise). Cheap; idempotent. */
    fun install(debuggable: Boolean) {
        Log.setLogLevel(if (debuggable) Log.LOG_LEVEL_ALL else Log.LOG_LEVEL_WARNING)
        Log.setLogger(Bridge)
    }

    /** The logger, exposed for tests. */
    internal object Bridge : Log.Logger {
        override fun d(tag: String, message: String, throwable: Throwable?) = BoxLog.d(TAG) { format(tag, message, throwable) }
        override fun i(tag: String, message: String, throwable: Throwable?) = BoxLog.i(TAG) { format(tag, message, throwable) }
        override fun w(tag: String, message: String, throwable: Throwable?) = BoxLog.w(TAG, format(tag, message, throwable))
        override fun e(tag: String, message: String, throwable: Throwable?) = BoxLog.e(TAG, format(tag, message, throwable))
    }

    internal fun format(tag: String, message: String, throwable: Throwable?): String {
        if (throwable == null) return "$tag: $message"
        val sb = StringBuilder(tag).append(": ").append(message)
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < MAX_CAUSES) {
            sb.append(if (depth == 0) " | " else " <- ").append(t.javaClass.simpleName)
            t.message?.let { sb.append(": ").append(it) }
            t = t.cause.takeIf { it !== t }
            depth++
        }
        return sb.toString()
    }
}
