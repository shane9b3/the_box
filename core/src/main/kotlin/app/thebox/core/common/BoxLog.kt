package app.thebox.core.common

/** Logging facade for :core and :data. :app installs an android.util.Log sink (M5 adds the ring buffer). */
object BoxLog {
    fun interface Sink {
        fun log(level: Int, tag: String, message: String, error: Throwable?)
    }

    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @Volatile var sink: Sink = Sink { _, _, _, _ -> }
    @Volatile var minLevel: Int = INFO

    inline fun d(tag: String, msg: () -> String) { if (minLevel <= DEBUG) emit(DEBUG, tag, msg(), null) }
    inline fun i(tag: String, msg: () -> String) { if (minLevel <= INFO) emit(INFO, tag, msg(), null) }
    fun w(tag: String, msg: String, t: Throwable? = null) = emit(WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = emit(ERROR, tag, msg, t)

    /** Every message is redacted. Never log per row (per channel/programme); log per batch or per source. */
    fun emit(level: Int, tag: String, msg: String, t: Throwable?) = sink.log(level, tag, Redact.text(msg), t)
}
