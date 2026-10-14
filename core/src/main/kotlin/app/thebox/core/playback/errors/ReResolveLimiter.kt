package app.thebox.core.playback.errors

import app.thebox.core.model.StableKey
import app.thebox.core.playback.ReResolveLimiter
import app.thebox.core.playback.resolve.ResolveConstants
import app.thebox.core.refresh.ChannelIdentity

/**
 * (code review 2026-10-14, N-S6) The limiter key of §4.5: the channel's identity, or for a direct URL (no identity)
 * a synthetic identity per **broker key** (`d:<host>`), so direct tunes on different hosts never share a window.
 */
object ReResolveKeys {
    fun of(identity: ChannelIdentity?, brokerKey: String?): ChannelIdentity? = identity
        ?: brokerKey?.takeIf { it.isNotBlank() }?.let { ChannelIdentity(ResolveConstants.DIRECT_PLAYLIST_ID, StableKey(it)) }
}

/**
 * 2 per 60 s per channel, sliding window (§4.5). Survives re-tunes of the same channel. Callers key direct URLs per
 * broker key through [ReResolveKeys]; a null key (no broker key known) shares one bucket. Thread-safe.
 *
 * Beyond the contract: [wouldAllow] peeks without consuming (to fill `LadderContext.reResolveAllowed` before the
 * ladder decides), and [reset] clears a channel ("Retry" from the error card, §4.3).
 */
class ReResolveLimiterImpl(
    private val maxPerWindow: Int = ErrorConstants.RE_RESOLVE_MAX_PER_WINDOW,
    private val windowMs: Long = ErrorConstants.RE_RESOLVE_WINDOW_MS,
) : ReResolveLimiter {
    private val stamps = HashMap<ChannelIdentity?, ArrayDeque<Long>>()

    @Synchronized
    override fun tryAcquire(key: ChannelIdentity?, nowMs: Long): Boolean {
        val q = stamps.getOrPut(key) { ArrayDeque(maxPerWindow) }
        prune(q, nowMs)
        if (q.size >= maxPerWindow) return false
        q.addLast(nowMs)
        return true
    }

    @Synchronized
    override fun wouldAllow(key: ChannelIdentity?, nowMs: Long): Boolean {
        val q = stamps[key] ?: return true
        prune(q, nowMs)
        if (q.isEmpty()) stamps.remove(key)
        return q.size < maxPerWindow
    }

    @Synchronized
    override fun reset(key: ChannelIdentity?) {
        stamps.remove(key)
    }

    private fun prune(q: ArrayDeque<Long>, nowMs: Long) {
        while (q.isNotEmpty() && nowMs - q.first() >= windowMs) q.removeFirst()
    }
}
