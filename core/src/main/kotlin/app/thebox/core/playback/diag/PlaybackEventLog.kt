package app.thebox.core.playback.diag

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEvent
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.ZapRecord

/** 200-entry redacted ring + 50-entry zap history (§3.13). [wallClock] is epoch milliseconds. */
class PlaybackEventLogImpl(
    private val clock: MonoClock,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : PlaybackEventLog {
    override fun add(type: PlaybackEventType, tuneId: Long, code: String, detail: String): Unit = TODO("WP-A")
    override fun addZap(record: ZapRecord): Unit = TODO("WP-A")
    override fun events(): List<PlaybackEvent> = TODO("WP-A")
    override fun zaps(): List<ZapRecord> = TODO("WP-A")
}
