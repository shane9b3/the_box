package app.thebox.core.playback.afr

import app.thebox.core.playback.DisplayModeInfo
import app.thebox.core.playback.DisplayModePicker
import kotlin.math.abs

/** Appendix B constants of §3.9 (the estimator's own constants are WP-C's). */
object AfrConstants {
    /** Relative tolerance of [DisplayModePicker.snapFps] (2 %). */
    const val FPS_SNAP_TOLERANCE = 0.02f
    /** Relative tolerance of refresh = k × fps (0.1 %). */
    const val REFRESH_MATCH_TOLERANCE = 0.001f
    /** "the lowest refresh ≥ 48 Hz" (§3.9.2), compared with the snap tolerance so 47.952 Hz (48 × 1000/1001) qualifies. */
    const val MIN_MULTIPLE_REFRESH_HZ = 48f
    /** Frame rates below this are film rates (23.976/24): a refresh equal to the fps is acceptable for them. */
    const val FILM_FPS_MAX = 24.5f
    /** Multiples tried after 1× and 2×: k = 3..5. */
    const val MAX_MULTIPLE = 5
    const val DOUBLE = 2
    const val FIRST_HIGH_MULTIPLE = 3

    /** Broadcast and film rates, NTSC variants exact (×1000/1001). */
    val STANDARD_FPS: FloatArray = floatArrayOf(
        24_000f / 1_001f, 24f, 25f, 30_000f / 1_001f, 30f, 48_000f / 1_001f, 48f, 50f, 60_000f / 1_001f, 60f,
    )
}

/**
 * Mode choice and fps snapping (§3.9.2, §3.9.3).
 *
 * [pick]: candidates keep the current width × height (no resolution switching). Rules in order: refresh = fps,
 * refresh = 2 × fps, refresh = k × fps for k = 3..5 (each within a 0.1 % window); the first rule with a match decides, and among its
 * matches the lowest refresh wins. A refresh below 48 Hz is only acceptable as 1 × a film rate (23.976/24), so 25 fps
 * goes to 50 Hz even when a 25 Hz mode is offered. If the current mode is among the deciding rule's matches → null.
 */
class DisplayModePickerImpl : DisplayModePicker {
    override fun snapFps(fps: Float): Float? {
        if (!(fps > 0f) || fps.isInfinite()) return null
        var best: Float? = null
        var bestErr = Float.MAX_VALUE
        for (c in AfrConstants.STANDARD_FPS) {
            val err = abs(fps - c) / c
            if (err <= AfrConstants.FPS_SNAP_TOLERANCE && err < bestErr) {
                best = c
                bestErr = err
            }
        }
        return best
    }

    override fun pick(current: DisplayModeInfo, supported: List<DisplayModeInfo>, fps: Float): DisplayModeInfo? {
        if (!(fps > 0f) || fps.isInfinite()) return null
        val sameSize = supported.filter { it.width == current.width && it.height == current.height }
        for (rule in RULES) {
            val matches = sameSize.filter { m -> rule.any { k -> matchesRefresh(m.refreshRate, fps * k) && eligible(m.refreshRate, k, fps) } }
            if (matches.isNotEmpty()) return choose(current, matches)
        }
        return null
    }

    private fun choose(current: DisplayModeInfo, matches: List<DisplayModeInfo>): DisplayModeInfo? {
        if (matches.any { it.id == current.id || sameRefresh(it.refreshRate, current.refreshRate) }) {
            return null
        }
        return matches.minByOrNull { it.refreshRate }
    }

    private companion object {
        /** 1×, then 2×, then 3×..5× as one rule. */
        val RULES: List<IntRange> = listOf(1..1, AfrConstants.DOUBLE..AfrConstants.DOUBLE, AfrConstants.FIRST_HIGH_MULTIPLE..AfrConstants.MAX_MULTIPLE)
    }

    private fun eligible(refresh: Float, k: Int, fps: Float): Boolean =
        refresh >= AfrConstants.MIN_MULTIPLE_REFRESH_HZ * (1f - AfrConstants.FPS_SNAP_TOLERANCE) ||
            (k == 1 && fps < AfrConstants.FILM_FPS_MAX)

    /**
     * The ±0.1 % window is [AfrConstants.REFRESH_MATCH_TOLERANCE] wide and centred on the target: 60 Hz and 59.94 Hz are
     * exactly 0.1 % apart, so a half-width test is what keeps them from matching each other while absorbing the float
     * noise of reported rates (59.940063 vs 59.94006).
     */
    private fun matchesRefresh(refresh: Float, target: Float): Boolean =
        abs(refresh - target) < target * AfrConstants.REFRESH_MATCH_TOLERANCE / 2f

    private fun sameRefresh(a: Float, b: Float): Boolean = matchesRefresh(a, b)
}
