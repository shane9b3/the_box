package app.thebox.core.playback.buffer

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.BufferProfileFsm

/** Appendix B constants and the §3.6.1 profile table. */
object BufferConstants {
    const val LOW_RAM_TARGET_BYTES = 24 * 1024 * 1024
    const val NORMAL_TARGET_BYTES = 48 * 1024 * 1024
    const val REBUFFER_WINDOW_MS = 600_000L
    const val REBUFFERS_TO_ESCALATE = 2
    const val DECAY_STABLE_MS = 600_000L
    const val MAX_AUTO_LEVEL = 3
    const val MANUAL_LEVEL = -1
    const val LOG_TAG = "BufferFsm"

    /** One row of §3.6.1 (milliseconds). */
    class Thresholds(val startMs: Int, val afterRebufferMs: Int, val minBufferMs: Int, val maxBufferMs: Int)

    /** Auto L0..L3. */
    val AUTO_LEVELS: List<Thresholds> = listOf(
        Thresholds(1_000, 3_000, 10_000, 20_000),
        Thresholds(1_500, 5_000, 15_000, 30_000),
        Thresholds(2_500, 10_000, 30_000, 45_000),
        Thresholds(5_000, 20_000, 40_000, 60_000),
    )
    /**
     * (follow-up 2026-10-14) Manual "None": the smallest profile that still plays (the decoder needs one full frame).
     * Fastest zapping; rebuffers more often on unstable providers.
     */
    val NONE = Thresholds(500, 500, 2_000, 5_000)
    val SMALL = Thresholds(2_000, 2_000, 10_000, 20_000)
    val MEDIUM = Thresholds(5_000, 5_000, 15_000, 30_000)
    val LARGE = Thresholds(10_000, 10_000, 30_000, 45_000)
    val EXTRA_LARGE = Thresholds(20_000, 20_000, 40_000, 60_000)

    fun targetBytes(lowRam: Boolean): Int = if (lowRam) LOW_RAM_TARGET_BYTES else NORMAL_TARGET_BYTES
}

/**
 * Levels, escalation, decay, manual mapping (§3.6). Per playlist key, in memory (reset at process death).
 *
 * | Event | Rule |
 * |---|---|
 * | rebuffer at L0 | → L1 at once |
 * | rebuffer at L1–L2 | the [BufferConstants.REBUFFERS_TO_ESCALATE]th within [BufferConstants.REBUFFER_WINDOW_MS] → +1, window cleared |
 * | rebuffer at L3 | stay |
 * | stable tick | `now − lastRebufferAt ≥ DECAY_STABLE_MS` and level > 0 → −1, `lastRebufferAt = now` |
 * | manual mode | fixed profile, FSM untouched |
 *
 * The caller filters out rebuffers caused by tune/seek/re-prepare/track change (`SEEK_GRACE_MS`). Thread-safe.
 */
class BufferProfileFsmImpl : BufferProfileFsm {
    private class State {
        var level = 0
        var lastRebufferAt = 0L
        val window = ArrayDeque<Long>(BufferConstants.REBUFFERS_TO_ESCALATE)
    }

    private val states = HashMap<String, State>()

    @Synchronized
    override fun profileFor(playlistKey: String, mode: BufferMode, lowRam: Boolean): BufferProfile {
        if (mode != BufferMode.AUTO) return manual(mode, lowRam)
        return auto(states[playlistKey]?.level ?: 0, lowRam)
    }

    @Synchronized
    override fun onRebuffer(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile? {
        if (mode != BufferMode.AUTO) return null
        val st = states.getOrPut(playlistKey) { State() }
        st.lastRebufferAt = nowMs
        val before = st.level
        when (st.level) {
            0 -> {
                st.level = 1
                st.window.clear()
            }
            BufferConstants.MAX_AUTO_LEVEL -> Unit
            else -> {
                while (st.window.isNotEmpty() && nowMs - st.window.first() >= BufferConstants.REBUFFER_WINDOW_MS) st.window.removeFirst()
                st.window.addLast(nowMs)
                if (st.window.size >= BufferConstants.REBUFFERS_TO_ESCALATE) {
                    st.level++
                    st.window.clear()
                }
            }
        }
        if (st.level == before) return null
        BoxLog.i(BufferConstants.LOG_TAG) { "$playlistKey level $before -> ${st.level} (rebuffer)" }
        return auto(st.level, lowRam)
    }

    @Synchronized
    override fun onStableTick(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile? {
        if (mode != BufferMode.AUTO) return null
        val st = states[playlistKey] ?: return null
        if (st.level == 0 || nowMs - st.lastRebufferAt < BufferConstants.DECAY_STABLE_MS) return null
        st.level--
        st.lastRebufferAt = nowMs
        st.window.clear()
        BoxLog.i(BufferConstants.LOG_TAG) { "$playlistKey level ${st.level + 1} -> ${st.level} (stable)" }
        return auto(st.level, lowRam)
    }

    companion object {
        fun auto(level: Int, lowRam: Boolean): BufferProfile =
            profile(BufferConstants.AUTO_LEVELS[level.coerceIn(0, BufferConstants.MAX_AUTO_LEVEL)], level.coerceIn(0, BufferConstants.MAX_AUTO_LEVEL), lowRam)

        /** Fixed profile of a manual [mode] (archive uses MEDIUM, §3.14). AUTO maps to L0. */
        fun manual(mode: BufferMode, lowRam: Boolean): BufferProfile = when (mode) {
            BufferMode.AUTO -> auto(0, lowRam)
            BufferMode.SMALL -> profile(BufferConstants.SMALL, BufferConstants.MANUAL_LEVEL, lowRam)
            BufferMode.MEDIUM -> profile(BufferConstants.MEDIUM, BufferConstants.MANUAL_LEVEL, lowRam)
            BufferMode.LARGE -> profile(BufferConstants.LARGE, BufferConstants.MANUAL_LEVEL, lowRam)
            BufferMode.EXTRA_LARGE -> profile(BufferConstants.EXTRA_LARGE, BufferConstants.MANUAL_LEVEL, lowRam)
            BufferMode.NONE -> profile(BufferConstants.NONE, BufferConstants.MANUAL_LEVEL, lowRam)
        }

        private fun profile(t: BufferConstants.Thresholds, level: Int, lowRam: Boolean) =
            BufferProfile(t.startMs, t.afterRebufferMs, t.minBufferMs, t.maxBufferMs, BufferConstants.targetBytes(lowRam), level)
    }
}
