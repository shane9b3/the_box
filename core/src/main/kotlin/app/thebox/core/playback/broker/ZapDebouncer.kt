package app.thebox.core.playback.broker

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.ZapDebouncer
import app.thebox.core.playback.ZapPlan

/** Leading-edge + 350 ms trailing coalescer (§3.5.4). */
class ZapDebouncerImpl : ZapDebouncer {
    override fun onZap(nowMs: Long, isRepeat: Boolean): ZapPlan = TODO("WP-A")
    override fun onFired(): Unit = TODO("WP-A")
    override fun reset(): Unit = TODO("WP-A")
}
