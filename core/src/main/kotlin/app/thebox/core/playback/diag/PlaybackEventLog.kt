package app.thebox.core.playback.diag

import app.thebox.core.common.Redact
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEvent
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.ZapRecord

object DiagConstants {
    const val EVENT_LOG_CAPACITY = 200
    const val ZAP_HISTORY = 50
    const val EVENT_DETAIL_MAX_CHARS = 160
}

/**
 * 200-entry redacted ring + 50-entry zap history (§3.13). [wallClock] is epoch milliseconds. Thread-safe: data sources
 * add events from loader threads. `detail` (and `code`) go through [Redact.text] first and are then cut to
 * [DiagConstants.EVENT_DETAIL_MAX_CHARS], so a cut never leaves half a secret behind.
 */
class PlaybackEventLogImpl(
    private val clock: MonoClock,
    private val wallClock: () -> Long = System::currentTimeMillis,
    eventCapacity: Int = DiagConstants.EVENT_LOG_CAPACITY,
    zapCapacity: Int = DiagConstants.ZAP_HISTORY,
) : PlaybackEventLog {
    private val events = Ring<PlaybackEvent>(eventCapacity)
    private val zaps = Ring<ZapRecord>(zapCapacity)

    override fun add(type: PlaybackEventType, tuneId: Long, code: String, detail: String) {
        val e = PlaybackEvent(wallClock(), clock.nowMs(), tuneId, type, clean(code), clean(detail))
        synchronized(this) { events.add(e) }
    }

    override fun addZap(record: ZapRecord) {
        synchronized(this) { zaps.add(record) }
    }

    @Synchronized override fun events(): List<PlaybackEvent> = events.toList()

    @Synchronized override fun zaps(): List<ZapRecord> = zaps.toList()

    private fun clean(s: String): String {
        if (s.isEmpty()) return s
        val r = Redact.text(s)
        return if (r.length <= DiagConstants.EVENT_DETAIL_MAX_CHARS) r else r.substring(0, DiagConstants.EVENT_DETAIL_MAX_CHARS)
    }

    /** Fixed-capacity ring, oldest first in [toList]. */
    private class Ring<T : Any>(private val capacity: Int) {
        private val items = arrayOfNulls<Any>(capacity)
        private var start = 0
        private var size = 0

        fun add(item: T) {
            if (size < capacity) {
                items[(start + size) % capacity] = item
                size++
            } else {
                items[start] = item
                start = (start + 1) % capacity
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun toList(): List<T> = List(size) { items[(start + it) % capacity] as T }
    }
}
