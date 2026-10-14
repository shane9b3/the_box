package app.thebox.core.playback.broker

import app.thebox.core.playback.ZapDebouncer
import app.thebox.core.playback.ZapPlan

object ZapConstants {
    const val ZAP_DEBOUNCE_MS = 350L
}

/**
 * Leading-edge + 350 ms trailing coalescer (§3.5.4). A single press tunes at once; a held key (repeat) or presses
 * closer than [debounceMs] (re)schedule one trailing tune [debounceMs] after the last press. Main thread only.
 */
class ZapDebouncerImpl(private val debounceMs: Long = ZapConstants.ZAP_DEBOUNCE_MS) : ZapDebouncer {
    private var lastZapAt = NEVER
    private var trailingPending = false

    override fun onZap(nowMs: Long, isRepeat: Boolean): ZapPlan {
        val quiet = lastZapAt == NEVER || nowMs - lastZapAt > debounceMs
        lastZapAt = nowMs
        if (!isRepeat && quiet && !trailingPending) return ZapPlan(immediate = true, fireAtMs = nowMs)
        trailingPending = true
        return ZapPlan(immediate = false, fireAtMs = nowMs + debounceMs)
    }

    override fun onFired() {
        trailingPending = false
    }

    override fun reset() {
        trailingPending = false
        lastZapAt = NEVER
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
