package app.thebox.core.sports

import kotlin.math.abs

// (NFL Stacked §4.3, commit 0) A FROZEN copy of `NflScoreTimeline` as it was at `412446e` (`9d29b27`), before
// `NflScoreSpec` existed: the reference for `NflScoreSpecLegacyTest`'s bit-for-bit golden of `NflScoreSpec.PILL`. Every
// constant it read from `SportsConstants` and `GoalTimeline` is inlined with its value at that commit, and it has its own
// copy of the bezier, so no later edit to the production code can move it. It writes the pre-spec fields of
// `NflScoreFrame` only (never `rowFlashAlpha`). Never edit this file except to delete it.

/** The CSS `cubic-bezier` solver exactly as `CubicBezier` was at `412446e` (Newton, then bisection; doubles). */
private class LegacyNflBezier(x1: Float, y1: Float, x2: Float, y2: Float) {
    private val cx = 3.0 * x1
    private val bx = 3.0 * (x2 - x1) - cx
    private val ax = 1.0 - cx - bx
    private val cy = 3.0 * y1
    private val by = 3.0 * (y2 - y1) - cy
    private val ay = 1.0 - cy - by

    private fun x(t: Double) = ((ax * t + bx) * t + cx) * t
    private fun y(t: Double) = ((ay * t + by) * t + cy) * t
    private fun dx(t: Double) = (3.0 * ax * t + 2.0 * bx) * t + cx

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

internal object LegacyNflScoreTimeline {
    /** `cubic-bezier(.7,0,.2,1)`: the lead and panel wipes. */
    private val WIPE = LegacyNflBezier(0.7f, 0f, 0.2f, 1f)
    /** CSS `ease-in-out`: the glow, the flash, the posts' hit, the word pulse, the tab pulse. */
    private val EASE_IN_OUT = LegacyNflBezier(0.42f, 0f, 0.58f, 1f)
    /** `std` = `cubic-bezier(.4,0,.2,1)`: the streaks. */
    private val STD = LegacyNflBezier(0.4f, 0f, 0.2f, 1f)
    /** `spring` = `cubic-bezier(.2,.8,.2,1)`: the letters, the badge, the score block, the pop. */
    private val SPRING = LegacyNflBezier(0.2f, 0.8f, 0.2f, 1f)
    /** CSS `ease` = `cubic-bezier(.25,.1,.25,1)`: the ball's arc. */
    private val EASE = LegacyNflBezier(0.25f, 0.1f, 0.25f, 1f)
    /** `roll` = `cubic-bezier(.3,0,.3,1)`: the ball's run. */
    private val ROLL = LegacyNflBezier(0.3f, 0f, 0.3f, 1f)
    /** CSS `ease-out` = `cubic-bezier(0,0,.58,1)`: the goalposts' drop-in. */
    private val EASE_OUT = LegacyNflBezier(0f, 0f, 0.58f, 1f)

    // ---- the GA instants the NFL keeps (tLead, tWipe, tGlow are gLead, gWipe, gGlow)
    /** From here until the lead's exit the lead covers the whole pill (the no-spoiler hold, GA §5.2). */
    const val LEAD_COVERED_MS = 216f
    internal const val LEAD_IN_END = 216f
    internal const val LEAD_OUT_START = 3_384f
    internal const val PANEL_IN_START = 108f
    internal const val PANEL_IN_END = 396f
    internal const val PANEL_OUT_START = 3_168f
    internal const val PANEL_OUT_END = 3_492f
    internal const val GLOW_LOW_IN = 540f
    internal const val GLOW_PEAK = 1_800f
    internal const val GLOW_LOW_OUT = 3_060f

    internal const val TAKEOVER = (3_600L).toFloat()
    internal const val POP_MS = (900L).toFloat()
    /** One score's end: the takeover plus the pop (4 500). */
    internal const val END = TAKEOVER + POP_MS
    /** (§5.7) The pair's second segment starts here (4 500) and the pair ends at `NFL_DOUBLE_MARK_MS` (9 000). */
    internal const val PAIR_SECOND = (4_500L).toFloat()
    internal const val PAIR_END = (9_000L).toFloat()

    // ---- the flash grid (§5.3): every flashing channel is a whole number of 450 ms cycles from a multiple of 450
    internal const val GRID = (450L).toFloat()
    internal const val FLASH_START = (450L).toFloat()
    internal const val FLASH_END = FLASH_START + (3) * GRID
    internal const val POSTS_HIT_START = (1_800L).toFloat()
    internal const val POSTS_HIT_END = POSTS_HIT_START + (3) * GRID
    internal const val TAB_PULSE_END = (8) * GRID

    internal const val STREAK1_START = 350f
    internal const val STREAK1_MS = 1_000f
    internal const val STREAK2_START = (1_800L).toFloat()
    internal const val STREAK2_MS = 800f

    internal const val RUN_START = (300L).toFloat()
    internal const val RUN_MS = (1_300L).toFloat()
    internal const val ARC_START = (300L).toFloat()
    internal const val ARC_MS = (1_400L).toFloat()
    internal const val POSTS_IN_START = (200L).toFloat()
    internal const val POSTS_IN_MS = (400L).toFloat()

    internal const val LETTER_START = (420L).toFloat()
    internal const val LETTER_STAGGER = (55L).toFloat()
    internal const val LETTER_MS = (420L).toFloat()
    internal const val PULSE_START = (1_400L).toFloat()
    internal const val PULSE_CYCLE = (500L).toFloat()
    internal const val PULSE_END = PULSE_START + (3) * PULSE_CYCLE
    internal const val LOGO_START = (450L).toFloat()
    internal const val LOGO_MS = (600L).toFloat()
    internal const val POINTS_START = (1_100L).toFloat()
    internal const val POINTS_MS = (500L).toFloat()

    // The arc's three keyframes (the List constants copied once, so a sample never touches a boxed value).
    private val ARC_S0 = listOf(1f, 0.75f, 0.45f)[0]
    private val ARC_S1 = listOf(1f, 0.75f, 0.45f)[1]
    private val ARC_S2 = listOf(1f, 0.75f, 0.45f)[2]
    private val ARC_R0 = listOf(0f, 360f, 720f)[0]
    private val ARC_R1 = listOf(0f, 360f, 720f)[1]
    private val ARC_R2 = listOf(0f, 360f, 720f)[2]

    /** The word's character count for [kind] (the space of "EXTRA POINT" and "FIELD GOAL" takes a stagger slot). */
    fun letterCount(kind: NflScoreKind): Int = when (kind) {
        NflScoreKind.TOUCHDOWN -> 9 // TOUCHDOWN
        NflScoreKind.EXTRA_POINT -> 11 // EXTRA POINT
        NflScoreKind.FIELD_GOAL -> 10 // FIELD GOAL
        NflScoreKind.SAFETY -> 6 // SAFETY
        NflScoreKind.TWO_POINT -> 9 // TWO-POINT
    }

    /** TD, two-point and safety run the ball; XP and FG run the arc and the goalposts (§5.1 "Kinds"). */
    fun runsBall(kind: NflScoreKind): Boolean =
        kind == NflScoreKind.TOUCHDOWN || kind == NflScoreKind.TWO_POINT || kind == NflScoreKind.SAFETY

    /** Letter [i]'s start, ms. */
    internal fun letterDelay(i: Int): Float = LETTER_START + i * LETTER_STAGGER

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun clean(tMs: Float) = if (tMs.isNaN() || tMs < 0f) 0f else tMs

    /** 0 → 1 → 0 over one cycle, each half eased on its own (`0 %, 100 % {0} 50 % {1}`). */
    private fun upDown(p: Float): Float = if (p < 0.5f) EASE_IN_OUT.ease(p * 2f) else 1f - EASE_IN_OUT.ease(p * 2f - 1f)

    /** Whole [cycle]-ms cycles of [upDown] from [start] until [end] (no fill: 0 outside). */
    private fun cycles(t: Float, start: Float, cycle: Float, end: Float): Float =
        if (t >= start && t < end) upDown(((t - start) % cycle) / cycle) else 0f

    /** Three-segment opacity (0 % 0, [inAt] [peak], [outAt] [peak], 100 % 0), each segment through [e]; 0 outside. */
    private fun ballAlpha(p: Float, inAt: Float, outAt: Float, peak: Float, e: LegacyNflBezier): Float = when {
        p <= 0f || p >= 1f -> 0f
        p < inAt -> peak * e.ease(p / inAt)
        p < outAt -> peak
        else -> peak * (1f - e.ease((p - outAt) / (1f - outAt)))
    }

    /** One takeover of [kind] at [tMs] since its start (3 600 ms + the 900 ms pop). */
    fun sample(kind: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame {
        val t = clean(tMs)
        animate(kind, t, out)
        out.segment = 0
        out.done = t >= END
        return out
    }

    /** (Ruling 25, §5.7) A +7/+8 pair: [first] (TOUCHDOWN) from 0, [second] from `NFL_PAIR_SECOND_START_MS`. */
    fun samplePair(first: NflScoreKind, second: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame {
        val t = clean(tMs)
        if (t < PAIR_SECOND) {
            animate(first, t, out)
            out.segment = 0
        } else {
            // The second segment restarts every channel, the flash grid included (its first tab peak is at 4 725).
            animate(second, t - PAIR_SECOND, out)
            out.segment = 1
        }
        out.done = t >= PAIR_END
        return out
    }

    /**
     * (§5.6, ruling 20) The still version: frames A and B for one score ([second] null), or A1, B1, A2, B2 for a pair.
     *
     * - **A** (0..3 600 of its segment): the panel at rest (base, badge, word, +N and tally; XP / FG also the posts at
     *   `NFL_POSTS_ALPHA`); no lead, streaks, ball, flash, pulse or glow, and no tab pulse.
     * - **B** (3 600..4 500 of its segment): the plain bug with the scorer's digits in `pop` at × 1.
     * - Then the plain bug ([NflScoreFrame.done]).
     */
    fun sampleStill(first: NflScoreKind, second: NflScoreKind?, tMs: Float, out: NflScoreFrame): NflScoreFrame {
        val t = clean(tMs)
        val inSecond = second != null && t >= PAIR_SECOND
        val kind = if (second != null && inSecond) second else first
        val u = if (inSecond) t - PAIR_SECOND else t
        val end = if (second != null) PAIR_END else END
        val a = u < TAKEOVER
        out.leadFrom = 0f; out.leadTo = 0f
        out.panelFrom = if (a) 0f else 1f; out.panelTo = 1f
        out.glowLowAlpha = 0f; out.glowPeakAlpha = 0f
        out.streak1On = false; out.streak1X = (-85f)
        out.streak2On = false; out.streak2X = (-85f)
        out.runOn = false
        out.arcOn = false
        out.ballX = 0f; out.ballY = 0f; out.ballRotDeg = 0f; out.ballScale = 1f; out.ballAlpha = 0f
        out.postsOn = a && !runsBall(kind)
        out.postsAlpha = if (out.postsOn) (0.4f) else 0f
        out.postsDy = 0f
        out.flashAlpha = 0f
        out.badgeAlpha = 1f; out.badgeDx = 0f; out.badgeScale = 1f; out.badgeRotDeg = 0f
        out.letterCount = letterCount(kind)
        for (i in 0 until NflScoreFrame.LETTERS) { out.letterAlpha[i] = 1f; out.letterDy[i] = 0f; out.letterScale[i] = 1f }
        out.wordPulse = 1f
        out.blockAlpha = 1f; out.blockDx = 0f; out.blockScale = 1f
        out.tabPulse = 0f
        out.popOn = !a && t < end
        out.popScale = 1f
        out.popMix = if (out.popOn) 0f else 1f
        out.segment = if (inSecond) 1 else 0
        out.done = t >= end
        return out
    }

    /** Every channel of one takeover of [kind] at [t] ≥ 0 (not [NflScoreFrame.segment] / [NflScoreFrame.done]). */
    private fun animate(kind: NflScoreKind, t: Float, out: NflScoreFrame) {
        // Lead (tLead, fill both): entering [0, w·p], full, leaving [w·p, w]; both wipe left to right.
        when {
            t < LEAD_IN_END -> { out.leadFrom = 0f; out.leadTo = WIPE.ease(t / LEAD_IN_END) }
            t < LEAD_OUT_START -> { out.leadFrom = 0f; out.leadTo = 1f }
            t < TAKEOVER -> { out.leadFrom = WIPE.ease((t - LEAD_OUT_START) / (TAKEOVER - LEAD_OUT_START)); out.leadTo = 1f }
            else -> { out.leadFrom = 1f; out.leadTo = 1f }
        }
        // Panel (tWipe, fill both): hidden to 3 %, in to 11 %, full, out 88..97 %, hidden after.
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
        // Glow (tGlow = gGlow, ease-in-out): 0 → low (15 %) → peak (50 %) → low (85 %) → 0, the GA masks' cross-fade.
        val aLow = (0.55f)
        val aPeak = (0.80f)
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
        // Streaks (tStreak, fill both; the filled states sit off the pill, so they are drawn only while running).
        out.streak1On = t >= STREAK1_START && t <= STREAK1_START + STREAK1_MS
        out.streak1X = lerp((-85f), (616f), STD.ease((t - STREAK1_START) / STREAK1_MS))
        out.streak2On = t >= STREAK2_START && t <= STREAK2_START + STREAK2_MS
        out.streak2X = lerp((-85f), (616f), STD.ease((t - STREAK2_START) / STREAK2_MS))

        val run = runsBall(kind)
        if (run) {
            // Ball run (tBall, roll): the transform is one segment (keyed at 0 % and 100 % only), the opacity three.
            val p = (t - RUN_START) / RUN_MS
            out.runOn = p >= 0f && p <= 1f
            out.arcOn = false
            val q = ROLL.ease(p)
            out.ballX = lerp((-42f), (562f), q)
            out.ballY = (18f)
            out.ballRotDeg = (900f) * q
            out.ballScale = 1f
            out.ballAlpha = ballAlpha(p, (0.15f), (0.85f), (0.4f), ROLL)
        } else {
            // Ball arc (tArc, ease): the transform keyed at 0 / 50 / 100 % (two segments), the opacity at 0 / 10 / 85 / 100 %.
            val p = (t - ARC_START) / ARC_MS
            out.arcOn = p >= 0f && p <= 1f
            out.runOn = false
            val pc = p.coerceIn(0f, 1f)
            if (pc < 0.5f) {
                val q = EASE.ease(pc / 0.5f)
                out.ballX = lerp((33f), (202f), q)
                out.ballY = lerp((44f), (-7f), q)
                out.ballScale = lerp(ARC_S0, ARC_S1, q)
                out.ballRotDeg = lerp(ARC_R0, ARC_R1, q)
            } else {
                val q = EASE.ease((pc - 0.5f) / 0.5f)
                out.ballX = lerp((202f), (366f), q)
                out.ballY = lerp((-7f), (12f), q)
                out.ballScale = lerp(ARC_S1, ARC_S2, q)
                out.ballRotDeg = lerp(ARC_R1, ARC_R2, q)
            }
            out.ballAlpha = ballAlpha(p, (0.10f), (0.85f), (0.55f), EASE)
        }
        // Goalposts (XP, FG): tPosts (ease-out, fill both) α 0 → .4, dy 8 → 0 from 200; then tPostsHit, on the grid.
        out.postsOn = !run && t < TAKEOVER
        if (out.postsOn) {
            val q = EASE_OUT.ease((t - POSTS_IN_START) / POSTS_IN_MS)
            out.postsDy = (8f) * (1f - q)
            out.postsAlpha = if (t >= POSTS_HIT_START && t < POSTS_HIT_END) {
                (0.4f) + ((0.95f) - (0.4f)) * cycles(t, POSTS_HIT_START, GRID, POSTS_HIT_END)
            } else {
                (0.4f) * q
            }
        } else {
            out.postsAlpha = 0f
            out.postsDy = 0f
        }
        // Panel flash (tFlash, no fill): NFL_FLASH_CYCLES × (0 → .42 → 0) on the grid from 450.
        out.flashAlpha = (0.42f) * cycles(t, FLASH_START, GRID, FLASH_END)
        // Logo badge (tLogo, spring, fill both): α 0 dx −32 ×.4 −25° → 70 %: α 1 dx +2 ×1.1 +4° → rest.
        val bp = ((t - LOGO_START) / LOGO_MS).coerceIn(0f, 1f)
        if (bp < (0.7f)) {
            val q = SPRING.ease(bp / (0.7f))
            out.badgeAlpha = q
            out.badgeDx = lerp((-32f), (2f), q)
            out.badgeScale = lerp((0.4f), (1.1f), q)
            out.badgeRotDeg = lerp((-25f), (4f), q)
        } else {
            val q = SPRING.ease((bp - (0.7f)) / (1f - (0.7f)))
            out.badgeAlpha = 1f
            out.badgeDx = lerp((2f), 0f, q)
            out.badgeScale = lerp((1.1f), 1f, q)
            out.badgeRotDeg = lerp((4f), 0f, q)
        }
        // Letters (tLetter, spring, fill both, 55 ms apart): α 0 dy +22 ×1.9 → 60 %: α 1 dy −2 ×.94 → rest.
        out.letterCount = letterCount(kind)
        val drop = (22f)
        val over = (-2f)
        for (i in 0 until NflScoreFrame.LETTERS) {
            val p = ((t - letterDelay(i)) / LETTER_MS).coerceIn(0f, 1f)
            if (p < (0.6f)) {
                val q = SPRING.ease(p / (0.6f))
                out.letterAlpha[i] = q
                out.letterDy[i] = lerp(drop, over, q)
                out.letterScale[i] = lerp((1.9f), (0.94f), q)
            } else {
                val q = SPRING.ease((p - (0.6f)) / (1f - (0.6f)))
                out.letterAlpha[i] = 1f
                out.letterDy[i] = lerp(over, 0f, q)
                out.letterScale[i] = lerp((0.94f), 1f, q)
            }
        }
        // Word pulse (tWord, no fill): 3 × (1 → 1.05 → 1).
        out.wordPulse = 1f + ((1.05f) - 1f) * cycles(t, PULSE_START, PULSE_CYCLE, PULSE_END)
        // +N and tally (tSlideR, spring, fill both): α 0 dx +19 ×.6 → 70 %: α 1 dx −1.6 ×1.12 → rest.
        val sp = ((t - POINTS_START) / POINTS_MS).coerceIn(0f, 1f)
        if (sp < (0.7f)) {
            val q = SPRING.ease(sp / (0.7f))
            out.blockAlpha = q
            out.blockDx = lerp((19f), (-1.6f), q)
            out.blockScale = lerp((0.6f), (1.12f), q)
        } else {
            val q = SPRING.ease((sp - (0.7f)) / (1f - (0.7f)))
            out.blockAlpha = 1f
            out.blockDx = lerp((-1.6f), 0f, q)
            out.blockScale = lerp((1.12f), 1f, q)
        }
        // The pinned tab's pulse (tTab, no fill): 8 × (0 → 1 → 0) from 0, peaks at 225 + 450 k.
        out.tabPulse = cycles(t, 0f, GRID, TAB_PULSE_END)
        // Pop: its first state from the panel's exit (hidden under the lead until the lead leaves, GA §6.2), then the
        // pop keyframes over NFL_POP_MS; the colour has only 0 % / 100 % keyframes, so it is eased once over the pop.
        when {
            t < PANEL_OUT_START -> { out.popOn = false; out.popScale = 1f; out.popMix = 1f }
            t < TAKEOVER -> { out.popOn = true; out.popScale = (1.5f); out.popMix = 0f }
            t < END -> {
                val p = (t - TAKEOVER) / POP_MS
                out.popOn = true
                out.popMix = SPRING.ease(p)
                out.popScale = when {
                    p < (0.4f) -> lerp((1.5f), (0.9f), SPRING.ease(p / (0.4f)))
                    p < (0.7f) -> lerp(
                        (0.9f), (1.06f), SPRING.ease((p - (0.4f)) / ((0.7f) - (0.4f))),
                    )
                    else -> lerp((1.06f), 1f, SPRING.ease((p - (0.7f)) / (1f - (0.7f))))
                }
            }
            else -> { out.popOn = false; out.popScale = 1f; out.popMix = 1f }
        }
    }
}
