package app.thebox.core.playback.errors

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.ReResolveLimiter
import app.thebox.core.refresh.ChannelIdentity

/** 2 per 60 s per channel, sliding window (§4.5). */
class ReResolveLimiterImpl : ReResolveLimiter {
    override fun tryAcquire(key: ChannelIdentity?, nowMs: Long): Boolean = TODO("WP-A")
}
