package app.thebox.core.sports

import kotlin.math.abs

// (Stacked §10.1, S9) A FROZEN copy of `GoalTimeline` as it was at `87e22d4`, before `GoalSpec` existed: the reference for
// `GoalSpecTest`'s bit-for-bit golden of `GoalSpec.CLASSIC`. Every constant it read from `SportsConstants` is inlined with
// its `87e22d4` value, and it has its own copy of the bezier, so no later edit to the production code can move it. It
// writes the `87e22d4` fields of `GoalFrame` only (never `rowFlashAlpha`). Never edit this file except to delete it.

/**
 * A CSS `cubic-bezier(x1, y1, x2, y2)` timing function: Newton's method on x(t), falling back to bisection, then y(t)
 * (the WebKit `UnitBezier` approach). Pure arithmetic, so [ease] allocates nothing.
 */
private class LegacyBezier(x1: Float, y1: Float, x2: Float, y2: Float) {
    private val cx = 3.0 * x1
    private val bx = 3.0 * (x2 - x1) - cx
    private val ax = 1.0 - cx - bx
    private val cy = 3.0 * y1
    private val by = 3.0 * (y2 - y1) - cy
    private val ay = 1.0 - cy - by

    private fun x(t: Double) = ((ax * t + bx) * t + cx) * t
    private fun y(t: Double) = ((ay * t + by) * t + cy) * t
    private fun dx(t: Double) = (3.0 * ax * t + 2.0 * bx) * t + cx

    /** The output for the input progress [p] (clamped to 0..1). */
    fun ease(p: Float): Float {
        if (p.isNaN() || p <= 0f) return 0f
        if (p >= 1f) return 1f
        val target = p.toDouble()
        var t = target
        for (i in 0 until NEWTON_STEPS) {
            val err = x(t) - target
            if (abs(err) < EPS) return y(t).toFloat()
            val d = dx(t)
            if (abs(d) < 1e-6) break
            t -= err / d
        }
        var lo = 0.0
        var hi = 1.0
        t = target
        for (i in 0 until BISECT_STEPS) {
            val v = x(t)
            if (abs(v - target) < EPS) break
            if (target > v) lo = t else hi = t
            t = (lo + hi) / 2.0
        }
        return y(t).toFloat()
    }

    private companion object {
        const val EPS = 1e-7
        const val NEWTON_STEPS = 8
        const val BISECT_STEPS = 60
    }
}

/**
 * (Goal animation §6.2; WP-2) The CSS keyframes (`design/score-bug-goal-animation.template.html`, the `g*` keyframes) as a
 * pure function of elapsed ms. The easing applies **per keyframe segment**, as in the CSS: each segment's own progress
 * goes through the animation's timing function. `fill-mode: both` is honoured where the CSS uses it; the flash and the
 * pulse have no fill. Allocation-free: [sample] writes only into the caller's [GoalFrame].
 *
 * The flash is the flash ruling's 3 × 400 ms (2.5 Hz), not the mock's 4 × 300 ms. Offsets are Standard design px
 * ([GoalFrame]'s unit): the mock's 780-wide values scaled by 404 / 780, as the design's §6.1 table rounds them.
 */
internal object LegacyGoalTimeline {
    /** `cubic-bezier(.7,0,.2,1)`: the lead and panel wipes. */
    private val WIPE = LegacyBezier(0.7f, 0f, 0.2f, 1f)
    /** CSS `ease-in-out` = `cubic-bezier(.42,0,.58,1)`: the glow, the flash, the pulse. */
    private val EASE_IN_OUT = LegacyBezier(0.42f, 0f, 0.58f, 1f)
    /** `cubic-bezier(.4,0,.2,1)`: the streaks. */
    private val STREAK = LegacyBezier(0.4f, 0f, 0.2f, 1f)
    /** `cubic-bezier(.2,.8,.2,1)`: the letters, the badge, the tally, the pop. */
    private val SPRING = LegacyBezier(0.2f, 0.8f, 0.2f, 1f)

    // ---- keyframe instants, ms from the takeover start (CSS percentages of the 3.6 s wipes in the comments)
    internal const val LEAD_IN_END = 216f          // gLead 6 %
    /**
     * From here until the lead's exit the lead covers the whole pill (animated path). The UI keeps drawing the bug's
     * previous body until then, so the new score is never seen on the plain bug before the takeover hides it.
     */
    const val LEAD_COVERED_MS = LEAD_IN_END
    internal const val LEAD_OUT_START = 3_384f     // gLead 94 %
    internal const val PANEL_IN_START = 108f       // gWipe 3 %
    internal const val PANEL_IN_END = 396f         // gWipe 11 %
    internal const val PANEL_OUT_START = 3_168f    // gWipe 88 %
    internal const val PANEL_OUT_END = 3_492f      // gWipe 97 %
    internal const val GLOW_LOW_IN = 540f          // gGlow 15 %
    internal const val GLOW_PEAK = 1_800f          // gGlow 50 %
    internal const val GLOW_LOW_OUT = 3_060f       // gGlow 85 %
    internal const val STREAK1_START = 350f
    internal const val STREAK1_MS = 1_000f
    internal const val STREAK2_START = 1_750f
    internal const val STREAK2_MS = 800f
    /** The streaks' left-edge travel: the mock's −160 → 980 at 404 / 780 (§6.1). */
    internal const val STREAK_FROM = -83f
    internal const val STREAK_TO = 508f
    private const val LETTER_DELAY_0 = 420f
    /** G, O, A, L start 80 ms apart. */
    internal const val LETTER_STAGGER = 80f
    internal const val LETTER_MS = 420f
    internal const val LETTER_MID = 0.6f
    internal const val LETTER_FROM_SCALE = 1.9f
    internal const val LETTER_MID_SCALE = 0.94f
    internal const val BADGE_START = 450f
    internal const val BADGE_MS = 550f
    internal const val BADGE_MID = 0.7f
    internal const val BADGE_FROM_SCALE = 0.3f
    internal const val BADGE_MID_SCALE = 1.12f
    internal const val BADGE_FROM_DEG = -120f
    internal const val BADGE_MID_DEG = 8f
    internal const val TALLY_START = 900f
    internal const val TALLY_MS = 450f
    internal const val PULSE_START = 1_150f
    internal const val PULSE_CYCLE_MS = 500f
    internal const val PULSE_CYCLES = 3
    internal const val PULSE_PEAK = 1.07f
    internal const val POP_FROM_SCALE = 1.55f
    /** gPop: .9 at 40 %, 1.06 at 70 %. */
    internal const val POP_K1 = 0.4f
    internal const val POP_K1_SCALE = 0.9f
    internal const val POP_K2 = 0.7f
    internal const val POP_K2_SCALE = 1.06f

    internal const val TAKEOVER = 3_600f
    internal const val POP_MS = 900f
    internal const val END = TAKEOVER + POP_MS
    internal const val FLASH_START = 420f
    internal const val FLASH_CYCLE = 400f
    internal const val FLASH_END = FLASH_START + 3 * FLASH_CYCLE
    internal const val PULSE_END = PULSE_START + PULSE_CYCLES * PULSE_CYCLE_MS

    /** Letter [i]'s start (ms). */
    internal fun letterDelay(i: Int): Float = LETTER_DELAY_0 + i * LETTER_STAGGER

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    /** 0 → 1 → 0 over one cycle, each half eased on its own (`0 %, 100 % {0} 50 % {1}`). */
    private fun upDown(p: Float): Float = if (p < 0.5f) EASE_IN_OUT.ease(p * 2f) else 1f - EASE_IN_OUT.ease(p * 2f - 1f)

    /** Fills [out] for [tMs] since the takeover start and returns it. */
    fun sample(tMs: Float, out: GoalFrame): GoalFrame {
        val t = if (tMs.isNaN() || tMs < 0f) 0f else tMs

        // Lead (gLead, fill both): entering [0, w·p], full, leaving [w·p, w]; both wipe left to right.
        when {
            t < LEAD_IN_END -> { out.leadFrom = 0f; out.leadTo = WIPE.ease(t / LEAD_IN_END) }
            t < LEAD_OUT_START -> { out.leadFrom = 0f; out.leadTo = 1f }
            t < TAKEOVER -> { out.leadFrom = WIPE.ease((t - LEAD_OUT_START) / (TAKEOVER - LEAD_OUT_START)); out.leadTo = 1f }
            else -> { out.leadFrom = 1f; out.leadTo = 1f }
        }
        // Panel (gWipe, fill both): hidden to 3 %, in to 11 %, full, out 88..97 %, hidden after.
        when {
            t < PANEL_IN_START -> { out.panelFrom = 0f; out.panelTo = 0f }
            t < PANEL_IN_END -> {
                out.panelFrom = 0f; out.panelTo = WIPE.ease((t - PANEL_IN_START) / (PANEL_IN_END - PANEL_IN_START))
            }
            t < PANEL_OUT_START -> { out.panelFrom = 0f; out.panelTo = 1f }
            t < PANEL_OUT_END -> {
                out.panelFrom = WIPE.ease((t - PANEL_OUT_START) / (PANEL_OUT_END - PANEL_OUT_START)); out.panelTo = 1f
            }
            else -> { out.panelFrom = 1f; out.panelTo = 1f }
        }
        // Glow (gGlow, ease-in-out): 0 → low → peak → low → 0, as a cross-fade of the two masks (§2.4).
        val aLow = 0.55f
        val aPeak = 0.80f
        when {
            t < GLOW_LOW_IN -> { out.glowLowAlpha = aLow * EASE_IN_OUT.ease(t / GLOW_LOW_IN); out.glowPeakAlpha = 0f }
            t < GLOW_PEAK -> {
                val m = EASE_IN_OUT.ease((t - GLOW_LOW_IN) / (GLOW_PEAK - GLOW_LOW_IN))
                out.glowLowAlpha = aLow * (1f - m); out.glowPeakAlpha = aPeak * m
            }
            t < GLOW_LOW_OUT -> {
                val m = 1f - EASE_IN_OUT.ease((t - GLOW_PEAK) / (GLOW_LOW_OUT - GLOW_PEAK))
                out.glowLowAlpha = aLow * (1f - m); out.glowPeakAlpha = aPeak * m
            }
            t < TAKEOVER -> {
                out.glowLowAlpha = aLow * (1f - EASE_IN_OUT.ease((t - GLOW_LOW_OUT) / (TAKEOVER - GLOW_LOW_OUT)))
                out.glowPeakAlpha = 0f
            }
            else -> { out.glowLowAlpha = 0f; out.glowPeakAlpha = 0f }
        }
        // Streaks (gStreak, fill both; the filled states sit off the pill, so they are drawn only while running).
        out.streak1On = t >= STREAK1_START && t <= STREAK1_START + STREAK1_MS
        out.streak1X = lerp(STREAK_FROM, STREAK_TO, STREAK.ease((t - STREAK1_START) / STREAK1_MS))
        out.streak2On = t >= STREAK2_START && t <= STREAK2_START + STREAK2_MS
        out.streak2X = lerp(STREAK_FROM, STREAK_TO, STREAK.ease((t - STREAK2_START) / STREAK2_MS))
        // Flash (gFlash, no fill): GOAL_FLASH_CYCLES × (0 → .45 → 0).
        out.flashAlpha = if (t >= FLASH_START && t < FLASH_END) {
            0.45f * upDown(((t - FLASH_START) % FLASH_CYCLE) / FLASH_CYCLE)
        } else {
            0f
        }
        // Badge (gBadge, fill both): 0 % α 0 ×.3 −120° → 70 % α 1 ×1.12 +8° → rest.
        val bp = ((t - BADGE_START) / BADGE_MS).coerceIn(0f, 1f)
        if (bp < BADGE_MID) {
            val q = SPRING.ease(bp / BADGE_MID)
            out.badgeAlpha = q
            out.badgeScale = lerp(BADGE_FROM_SCALE, BADGE_MID_SCALE, q)
            out.badgeRotDeg = lerp(BADGE_FROM_DEG, BADGE_MID_DEG, q)
        } else {
            val q = SPRING.ease((bp - BADGE_MID) / (1f - BADGE_MID))
            out.badgeAlpha = 1f
            out.badgeScale = lerp(BADGE_MID_SCALE, 1f, q)
            out.badgeRotDeg = lerp(BADGE_MID_DEG, 0f, q)
        }
        // Letters (gLetter, fill both, staggered): 0 % α 0 dy+drop ×1.9 → 60 % α 1 dy overshoot ×.94 → rest.
        val drop = 25f
        val over = -2f
        for (i in 0 until GoalFrame.LETTERS) {
            val p = ((t - letterDelay(i)) / LETTER_MS).coerceIn(0f, 1f)
            if (p < LETTER_MID) {
                val q = SPRING.ease(p / LETTER_MID)
                out.letterAlpha[i] = q
                out.letterDy[i] = lerp(drop, over, q)
                out.letterScale[i] = lerp(LETTER_FROM_SCALE, LETTER_MID_SCALE, q)
            } else {
                val q = SPRING.ease((p - LETTER_MID) / (1f - LETTER_MID))
                out.letterAlpha[i] = 1f
                out.letterDy[i] = lerp(over, 0f, q)
                out.letterScale[i] = lerp(LETTER_MID_SCALE, 1f, q)
            }
        }
        // Pulse (gWord, no fill): PULSE_CYCLES × (1 → 1.07 → 1).
        out.wordPulse = if (t >= PULSE_START && t < PULSE_END) {
            1f + (PULSE_PEAK - 1f) * upDown(((t - PULSE_START) % PULSE_CYCLE_MS) / PULSE_CYCLE_MS)
        } else {
            1f
        }
        // Tally (gSlideR, fill both): α 0, dx +slide → rest.
        val tq = SPRING.ease((t - TALLY_START) / TALLY_MS)
        out.tallyAlpha = tq
        out.tallyDx = 20f * (1f - tq)
        // Pop: its first state from the panel's exit (hidden under the lead until the lead leaves, §6.2 "pop reveal"),
        // then gPop over GOAL_POP_MS. The colour has only the 0 % / 100 % keyframes, so it is eased once over the pop.
        when {
            t < PANEL_OUT_START -> { out.popOn = false; out.popScale = 1f; out.popMix = 1f }
            t < TAKEOVER -> { out.popOn = true; out.popScale = POP_FROM_SCALE; out.popMix = 0f }
            t < END -> {
                val p = (t - TAKEOVER) / POP_MS
                out.popOn = true
                out.popMix = SPRING.ease(p)
                out.popScale = when {
                    p < POP_K1 -> lerp(POP_FROM_SCALE, POP_K1_SCALE, SPRING.ease(p / POP_K1))
                    p < POP_K2 -> lerp(POP_K1_SCALE, POP_K2_SCALE, SPRING.ease((p - POP_K1) / (POP_K2 - POP_K1)))
                    else -> lerp(POP_K2_SCALE, 1f, SPRING.ease((p - POP_K2) / (1f - POP_K2)))
                }
            }
            else -> { out.popOn = false; out.popScale = 1f; out.popMix = 1f }
        }
        out.done = t >= END
        return out
    }

    /**
     * The still version (§7) for [tMs]: frame A (0..3 600, the panel at rest: base, badge, GOAL, tally; no lead,
     * streaks, flash, pulse or glow), frame B (3 600..4 500, the plain bug with the scorer's digit in `pop` at × 1),
     * then the plain bug ([GoalFrame.done]).
     */
    fun sampleStill(tMs: Float, out: GoalFrame): GoalFrame {
        val t = if (tMs.isNaN() || tMs < 0f) 0f else tMs
        val a = t < TAKEOVER
        out.leadFrom = 0f; out.leadTo = 0f
        out.panelFrom = if (a) 0f else 1f; out.panelTo = 1f
        out.glowLowAlpha = 0f; out.glowPeakAlpha = 0f
        out.streak1On = false; out.streak1X = STREAK_FROM
        out.streak2On = false; out.streak2X = STREAK_FROM
        out.flashAlpha = 0f
        out.badgeAlpha = 1f; out.badgeScale = 1f; out.badgeRotDeg = 0f
        for (i in 0 until GoalFrame.LETTERS) { out.letterAlpha[i] = 1f; out.letterDy[i] = 0f; out.letterScale[i] = 1f }
        out.wordPulse = 1f
        out.tallyAlpha = 1f; out.tallyDx = 0f
        out.popOn = !a && t < END
        out.popScale = 1f
        out.popMix = if (out.popOn) 0f else 1f
        out.done = t >= END
        return out
    }
}
