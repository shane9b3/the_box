// Contract (NFL commit 0, docs/design/m6-nfl.md §5.1, §5.6, §5.7): the frame and the sample signatures are fixed for
// WP-3 (the timeline body and the painters) and WP-4 (the runner); changes go through the lead.
// (NFL Stacked commit 0, docs/design/m6-nfl-stacked.md §4.3) [NflScoreSpec] and the spec overloads are fixed too:
// NFL Stacked WP-2 fills `NflScoreSpec.STACKED` and the STACKED-only branches; `NflScoreSpec.PILL` is frozen bit-for-bit
// against `LegacyNflScoreTimeline` (test source) by `NflScoreSpecLegacyTest`.
package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K

/**
 * (NFL §5.1) One sampled instant of the NFL scoring takeover: a preallocated, mutable holder that [NflScoreTimeline]
 * fills in place (no allocation per frame, GA §2.5).
 *
 * Units, as [GoalFrame]: alphas and fractions 0..1; scales are multipliers (1 = rest); angles in degrees; offsets and
 * positions in **Standard design px** (the painter multiplies by its own scale, so Large gets × 1.25). Wipe edges are
 * fractions of the pill width.
 */
class NflScoreFrame {
    /** Lead wipe (accent), left/right clip edges as fractions of the pill width; equal = nothing drawn. */
    var leadFrom = 0f
    var leadTo = 0f
    /** Panel wipe (base); everything in the panel is drawn under this clip. */
    var panelFrom = 0f
    var panelTo = 0f

    /** Glow mask alphas, already multiplied out (the GA masks, colour `glow`). */
    var glowLowAlpha = 0f
    var glowPeakAlpha = 0f

    /** Streak 1 (accent .6, w 51) and streak 2 (white .35, w 19): drawn only while on; x is the band's left edge. */
    var streak1On = false
    var streak1X = 0f
    var streak2On = false
    var streak2X = 0f

    /**
     * The ball: [runOn] (TD, two-point, safety; under the inner band `NFL_RUN_BAND`) or [arcOn] (XP, FG; under the
     * straight-section band `NFL_ARC_BAND`). [ballX]/[ballY] are the sprite's top-left, rotation and scale about its
     * centre.
     */
    var runOn = false
    var arcOn = false
    var ballX = 0f
    var ballY = 0f
    var ballRotDeg = 0f
    var ballScale = 1f
    var ballAlpha = 0f

    /** The goalposts (XP, FG): alpha (in, then the hit pulses on the 450 ms grid) and the drop-in offset. */
    var postsOn = false
    var postsAlpha = 0f
    var postsDy = 0f

    /** The white panel flash (3 × 450 ms from 450, on the flash grid, §5.3). */
    var flashAlpha = 0f

    /** The logo badge: alpha, horizontal offset, scale and rotation about its centre. */
    var badgeAlpha = 0f
    var badgeDx = 0f
    var badgeScale = 1f
    var badgeRotDeg = 0f

    /** The word, per letter (index 0 until [letterCount]; a space is skipped by the painter): alpha, dy, scale. */
    var letterCount = 0
    val letterAlpha = FloatArray(LETTERS)
    val letterDy = FloatArray(LETTERS)
    val letterScale = FloatArray(LETTERS) { 1f }
    /** The word's pulse, a scale about the word's centre composed with each letter's own. */
    var wordPulse = 1f

    /** The score block ("+6" over the tally): alpha, horizontal offset, scale. */
    var blockAlpha = 0f
    var blockDx = 0f
    var blockScale = 1f

    /** The pinned tab's pulse, 0..1: drawn as an ADD pass at alpha `NFL_TAB_PULSE_GAIN` × this (§5.3). */
    var tabPulse = 0f

    /**
     * The scorer's digits on the plain bug: drawn in the pop state while [popOn], at [popScale] (clamped by the painter),
     * the colour lerped from the team's `pop` to white by [popMix] (0 = pop, 1 = white).
     */
    var popOn = false
    var popScale = 1f
    var popMix = 1f

    /** (§5.7) The segment being drawn: 0 = the only score or the pair's first, 1 = the pair's second. */
    var segment = 0

    /**
     * (NFL Stacked §4.3, commit 0) The row flash's envelope, 0..1: 1 at 3 600 of a segment, eased out to 0 over
     * [NflScoreSpec.rowFlashMs]; the still version's frame B writes 1 for its whole length. [NflScoreSpec.PILL]
     * (`rowFlashMs` 0) always writes 0. Not an alpha on its own: the Stacked painter multiplies it by the scorer's C7 peak
     * (`StackedColours.rowFlashAlpha`), or by `STACKED_STILL_ROW_TINT` on the still path.
     */
    var rowFlashAlpha = 0f

    /**
     * True once t ≥ the end (one score: [NflScoreSpec.endMs], the Pill's takeover + pop; a pair:
     * [NflScoreSpec.pairEndMs], the Pill's `NFL_DOUBLE_MARK_MS`): the frame is the plain bug.
     */
    var done = false

    /** Copies every channel from [o] (no allocation). */
    fun copyFrom(o: NflScoreFrame) {
        leadFrom = o.leadFrom
        leadTo = o.leadTo
        panelFrom = o.panelFrom
        panelTo = o.panelTo
        glowLowAlpha = o.glowLowAlpha
        glowPeakAlpha = o.glowPeakAlpha
        streak1On = o.streak1On
        streak1X = o.streak1X
        streak2On = o.streak2On
        streak2X = o.streak2X
        runOn = o.runOn
        arcOn = o.arcOn
        ballX = o.ballX
        ballY = o.ballY
        ballRotDeg = o.ballRotDeg
        ballScale = o.ballScale
        ballAlpha = o.ballAlpha
        postsOn = o.postsOn
        postsAlpha = o.postsAlpha
        postsDy = o.postsDy
        flashAlpha = o.flashAlpha
        badgeAlpha = o.badgeAlpha
        badgeDx = o.badgeDx
        badgeScale = o.badgeScale
        badgeRotDeg = o.badgeRotDeg
        letterCount = o.letterCount
        o.letterAlpha.copyInto(letterAlpha)
        o.letterDy.copyInto(letterDy)
        o.letterScale.copyInto(letterScale)
        wordPulse = o.wordPulse
        blockAlpha = o.blockAlpha
        blockDx = o.blockDx
        blockScale = o.blockScale
        tabPulse = o.tabPulse
        popOn = o.popOn
        popScale = o.popScale
        popMix = o.popMix
        segment = o.segment
        rowFlashAlpha = o.rowFlashAlpha
        done = o.done
    }

    companion object {
        /** `NFL_LETTERS_MAX`: "EXTRA POINT" is 11 with the space. */
        const val LETTERS = K.NFL_LETTERS_MAX
    }
}

/**
 * (NFL Stacked §4.3, commit 0) Every number [NflScoreTimeline] reads that differs between the Pill's takeover ([PILL],
 * the 520 × 70 pill) and the Stacked one ([STACKED], the 333-wide Stacked box), as [GoalSpec] split `GoalTimeline`. The
 * keyframe skeleton (the wipes, the glow, every start time, the 450 ms flash grid, the letter stagger, the pop) is shared
 * and stays in [NflScoreTimeline]. Offsets are Standard design px of the bug the spec draws on; times are ms from the
 * takeover's (or a pair segment's) start, or lengths in ms. Painter-only values ([streak1Alpha], [streak2Alpha],
 * [runPx], [postsX] … [postsH]) are carried for the painters; the timeline does not read them.
 *
 * - [streak1Alpha] / [streak2Alpha]: the streaks' alphas; [streakFrom] / [streakTo]: their left edge's travel.
 * - The ball run (TD, two-point, safety): [runFrom] → [runTo] at [runY] (the sprite's top-left), [runPx] wide, peak
 *   alpha [runAlpha]; the rotation and the alpha keyframes are shared.
 * - The ball arc (XP, FG): the three keyframes ([arcP0X], [arcP0Y]) → ([arcP1X], [arcP1Y]) → ([arcP2X], [arcP2Y]),
 *   scales [arcS0] / [arcS1] / [arcS2], rotations [arcR0] / [arcR1] / [arcR2], peak alpha [arcAlpha].
 * - The goalposts: [postsX], [postsY], [postsW], [postsH] (painter) and the drop-in [postsDy].
 * - [flashAlpha]: the white panel flash's peak.
 * - [badgeDx] / [badgeMidDx]: the badge's start and 70 % offsets; [letterDrop] / [letterOvershoot]: each letter's start
 *   and 60 % dy; [pointsDx] / [pointsMidDx]: the "+N" block's start and 70 % offsets.
 * - [tabPulseCycles]: the pinned tab's pulse cycles on the 450 grid from 0 (the Pill 8; STACKED 0: no bar pulse).
 * - [rowFlashMs]: the row flash's length from 3 600 of a segment (0 = none: PILL).
 * - [endMs]: one score's end ([NflScoreFrame.done]); [pairSecondMs] / [pairEndMs]: a +7/+8 pair's second segment's start
 *   and the pair's end; [stillFrameBMs]: the still version's frame B length from 3 600 for one score (done at
 *   3 600 + it, so it equals `endMs − 3 600`).
 */
data class NflScoreSpec(
    val streak1Alpha: Float,
    val streak2Alpha: Float,
    val streakFrom: Float,
    val streakTo: Float,
    val runFrom: Float,
    val runTo: Float,
    val runY: Float,
    val runPx: Float,
    val runAlpha: Float,
    val arcP0X: Float,
    val arcP0Y: Float,
    val arcP1X: Float,
    val arcP1Y: Float,
    val arcP2X: Float,
    val arcP2Y: Float,
    val arcS0: Float,
    val arcS1: Float,
    val arcS2: Float,
    val arcR0: Float,
    val arcR1: Float,
    val arcR2: Float,
    val arcAlpha: Float,
    val postsX: Float,
    val postsY: Float,
    val postsW: Float,
    val postsH: Float,
    val postsDy: Float,
    val flashAlpha: Float,
    val badgeDx: Float,
    val badgeMidDx: Float,
    val letterDrop: Float,
    val letterOvershoot: Float,
    val pointsDx: Float,
    val pointsMidDx: Float,
    val tabPulseCycles: Int,
    val rowFlashMs: Float,
    val endMs: Float,
    val pairSecondMs: Float,
    val pairEndMs: Float,
    val stillFrameBMs: Float,
) {
    companion object {
        /** The Pill's takeover: today's `SportsConstants.NFL_*` values, unchanged (frozen by `NflScoreSpecLegacyTest`). */
        val PILL = NflScoreSpec(
            streak1Alpha = 0.6f,
            streak2Alpha = 0.35f,
            streakFrom = K.NFL_STREAK_FROM,
            streakTo = K.NFL_STREAK_TO,
            runFrom = K.NFL_RUN_FROM,
            runTo = K.NFL_RUN_TO,
            runY = K.NFL_RUN_Y,
            runPx = K.NFL_BALL_PX,
            runAlpha = K.NFL_RUN_ALPHA,
            arcP0X = K.NFL_ARC_P0_X,
            arcP0Y = K.NFL_ARC_P0_Y,
            arcP1X = K.NFL_ARC_P1_X,
            arcP1Y = K.NFL_ARC_P1_Y,
            arcP2X = K.NFL_ARC_P2_X,
            arcP2Y = K.NFL_ARC_P2_Y,
            arcS0 = K.NFL_ARC_SCALES[0],
            arcS1 = K.NFL_ARC_SCALES[1],
            arcS2 = K.NFL_ARC_SCALES[2],
            arcR0 = K.NFL_ARC_ROTATIONS[0],
            arcR1 = K.NFL_ARC_ROTATIONS[1],
            arcR2 = K.NFL_ARC_ROTATIONS[2],
            arcAlpha = K.NFL_ARC_ALPHA,
            postsX = K.NFL_POSTS_X,
            postsY = K.NFL_POSTS_Y,
            postsW = K.NFL_POSTS_W,
            postsH = K.NFL_POSTS_H,
            postsDy = K.NFL_POSTS_DY,
            flashAlpha = K.NFL_FLASH_ALPHA,
            badgeDx = K.NFL_LOGO_DX,
            badgeMidDx = K.NFL_LOGO_MID_DX,
            letterDrop = K.NFL_LETTER_DROP,
            letterOvershoot = K.NFL_LETTER_OVERSHOOT,
            pointsDx = K.NFL_POINTS_DX,
            pointsMidDx = K.NFL_POINTS_MID_DX,
            tabPulseCycles = K.NFL_TAB_PULSE_CYCLES,
            rowFlashMs = 0f,
            endMs = (K.NFL_TAKEOVER_MS + K.NFL_POP_MS).toFloat(),
            pairSecondMs = K.NFL_PAIR_SECOND_START_MS.toFloat(),
            pairEndMs = K.NFL_DOUBLE_MARK_MS.toFloat(),
            stillFrameBMs = K.NFL_POP_MS.toFloat(),
        )

        /**
         * The Stacked box's takeover (§4.3 "STACKED" column: streaks −90 → 450, run (−36, 38.25) → (450, 38.25) at
         * 31.5 / .45, the arc, posts at (234, 22.5) 40.5 × 54 dy 7.2, flash .40, badge −22.5 / +1.8, letters +27 /
         * −2.25, points +16.2 / −1.35, no tab pulse, row flash 1 200, end 4 800, pair 4 800 / 9 600, still B 1 200).
         *
         * Commit 0 placeholder: **equal to [PILL]** until NFL Stacked WP-2 sets the §4.3 values (and `NflScoreSpecTest`
         * checks them), so the commit-0 fallback is today's takeover.
         */
        val STACKED = PILL // WP-2: the §4.3 STACKED values.
    }
}

/**
 * (NFL §5.1, §5.3, §5.6, §5.7; WP-3) The mock's keyframes as a pure function of elapsed ms, per keyframe segment as the
 * CSS, every flash on the one 450 ms grid. Allocation-free: each function writes only into the caller's frame and
 * returns it.
 *
 * CSS semantics, as [GoalTimeline]: each property has its own keyframes, and the animation's timing function eases each
 * of that property's segments on its own (so the ball's transform, keyed only at 0 % and 100 %, is one eased segment,
 * while its opacity, keyed at 0 / 15 / 85 / 100 %, is three). `fill-mode: both` holds the first and last states where
 * the mock uses it; the flash, the posts' hit, the word pulse and the tab pulse have no fill.
 *
 * The mock's 980 × 144 values are scaled to the 520 × 70 pill (x × .531, y × .486; §5.1), Standard design px.
 */
object NflScoreTimeline {
    /** `cubic-bezier(.7,0,.2,1)`: the lead and panel wipes. */
    internal val WIPE = GoalTimeline.WIPE
    /** CSS `ease-in-out`: the glow, the flash, the posts' hit, the word pulse, the tab pulse. */
    internal val EASE_IN_OUT = GoalTimeline.EASE_IN_OUT
    /** `std` = `cubic-bezier(.4,0,.2,1)`: the streaks. */
    internal val STD = GoalTimeline.STREAK
    /** `spring` = `cubic-bezier(.2,.8,.2,1)`: the letters, the badge, the score block, the pop. */
    internal val SPRING = GoalTimeline.SPRING
    /** CSS `ease` = `cubic-bezier(.25,.1,.25,1)`: the ball's arc. */
    internal val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
    /** `roll` = `cubic-bezier(.3,0,.3,1)`: the ball's run. */
    internal val ROLL = CubicBezier(0.3f, 0f, 0.3f, 1f)
    /** CSS `ease-out` = `cubic-bezier(0,0,.58,1)`: the goalposts' drop-in. */
    internal val EASE_OUT = CubicBezier(0f, 0f, 0.58f, 1f)

    // ---- the GA instants the NFL keeps (tLead, tWipe, tGlow are gLead, gWipe, gGlow)
    /** From here until the lead's exit the lead covers the whole pill (the no-spoiler hold, GA §5.2). */
    const val LEAD_COVERED_MS = GoalTimeline.LEAD_COVERED_MS
    internal const val LEAD_IN_END = GoalTimeline.LEAD_IN_END
    internal const val LEAD_OUT_START = GoalTimeline.LEAD_OUT_START
    internal const val PANEL_IN_START = GoalTimeline.PANEL_IN_START
    internal const val PANEL_IN_END = GoalTimeline.PANEL_IN_END
    internal const val PANEL_OUT_START = GoalTimeline.PANEL_OUT_START
    internal const val PANEL_OUT_END = GoalTimeline.PANEL_OUT_END
    internal const val GLOW_LOW_IN = GoalTimeline.GLOW_LOW_IN
    internal const val GLOW_PEAK = GoalTimeline.GLOW_PEAK
    internal const val GLOW_LOW_OUT = GoalTimeline.GLOW_LOW_OUT

    internal const val TAKEOVER = K.NFL_TAKEOVER_MS.toFloat()
    internal const val POP_MS = K.NFL_POP_MS.toFloat()
    /** The pop's end: the takeover plus the pop (4 500; [NflScoreSpec.PILL]'s one-score `endMs`). */
    internal const val END = TAKEOVER + POP_MS

    // ---- the flash grid (§5.3): every flashing channel is a whole number of 450 ms cycles from a multiple of 450
    internal const val GRID = K.NFL_FLASH_GRID_MS.toFloat()
    internal const val FLASH_START = K.NFL_FLASH_START_MS.toFloat()
    internal const val FLASH_END = FLASH_START + K.NFL_FLASH_CYCLES * GRID
    internal const val POSTS_HIT_START = K.NFL_POSTS_HIT_START_MS.toFloat()
    internal const val POSTS_HIT_END = POSTS_HIT_START + K.NFL_POSTS_HIT_CYCLES * GRID

    internal const val STREAK1_START = 350f
    internal const val STREAK1_MS = 1_000f
    internal const val STREAK2_START = K.NFL_STREAK2_START_MS.toFloat()
    internal const val STREAK2_MS = 800f

    internal const val RUN_START = K.NFL_RUN_START_MS.toFloat()
    internal const val RUN_MS = K.NFL_RUN_MS.toFloat()
    internal const val ARC_START = K.NFL_ARC_START_MS.toFloat()
    internal const val ARC_MS = K.NFL_ARC_MS.toFloat()
    internal const val POSTS_IN_START = K.NFL_POSTS_IN_DELAY_MS.toFloat()
    internal const val POSTS_IN_MS = K.NFL_POSTS_IN_MS.toFloat()

    internal const val LETTER_START = K.NFL_LETTER_START_MS.toFloat()
    internal const val LETTER_STAGGER = K.NFL_LETTER_STAGGER_MS.toFloat()
    internal const val LETTER_MS = K.NFL_LETTER_MS.toFloat()
    internal const val PULSE_START = K.NFL_WORD_PULSE_START_MS.toFloat()
    internal const val PULSE_CYCLE = K.NFL_WORD_PULSE_CYCLE_MS.toFloat()
    internal const val PULSE_END = PULSE_START + K.NFL_WORD_PULSE_CYCLES * PULSE_CYCLE
    internal const val LOGO_START = K.NFL_LOGO_START_MS.toFloat()
    internal const val LOGO_MS = K.NFL_LOGO_MS.toFloat()
    internal const val POINTS_START = K.NFL_POINTS_START_MS.toFloat()
    internal const val POINTS_MS = K.NFL_POINTS_MS.toFloat()

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
    private fun ballAlpha(p: Float, inAt: Float, outAt: Float, peak: Float, e: CubicBezier): Float = when {
        p <= 0f || p >= 1f -> 0f
        p < inAt -> peak * e.ease(p / inAt)
        p < outAt -> peak
        else -> peak * (1f - e.ease((p - outAt) / (1f - outAt)))
    }

    /** One takeover of [kind] at [tMs] since its start (3 600 ms + the 900 ms pop): [NflScoreSpec.PILL]. */
    fun sample(kind: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame = sample(NflScoreSpec.PILL, kind, tMs, out)

    /** (Ruling 25, §5.7) A +7/+8 pair under [NflScoreSpec.PILL]: [first] (TOUCHDOWN) from 0, [second] from 4 500. */
    fun samplePair(first: NflScoreKind, second: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame =
        samplePair(NflScoreSpec.PILL, first, second, tMs, out)

    /** (§5.6) The still version under [NflScoreSpec.PILL]: see the spec overload. */
    fun sampleStill(first: NflScoreKind, second: NflScoreKind?, tMs: Float, out: NflScoreFrame): NflScoreFrame =
        sampleStill(NflScoreSpec.PILL, first, second, tMs, out)

    /**
     * (NFL Stacked §4.3) One takeover of [kind] under [spec] at [tMs] since its start; done at [NflScoreSpec.endMs].
     * Writes every [NflScoreFrame] field, [NflScoreFrame.rowFlashAlpha] included. NaN or negative [tMs] is 0.
     */
    fun sample(spec: NflScoreSpec, kind: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame {
        val t = clean(tMs)
        animate(spec, kind, t, out)
        out.segment = 0
        out.done = t >= spec.endMs
        return out
    }

    /**
     * (Ruling 25, §5.7; NFL Stacked §4.3) A +7/+8 pair under [spec]: [first] (TOUCHDOWN) from 0, [second] from
     * [NflScoreSpec.pairSecondMs]; done at [NflScoreSpec.pairEndMs].
     */
    fun samplePair(spec: NflScoreSpec, first: NflScoreKind, second: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame {
        val t = clean(tMs)
        val pairSecond = spec.pairSecondMs
        if (t < pairSecond) {
            animate(spec, first, t, out)
            out.segment = 0
        } else {
            // The second segment restarts every channel, the flash grid included (its first tab peak is at 4 725).
            animate(spec, second, t - pairSecond, out)
            out.segment = 1
        }
        out.done = t >= spec.pairEndMs
        return out
    }

    /**
     * (§5.6, ruling 20) The still version: frames A and B for one score ([second] null), or A1, B1, A2, B2 for a pair.
     *
     * - **A** (0..3 600 of its segment): the panel at rest (base, badge, word, +N and tally; XP / FG also the posts at
     *   `NFL_POSTS_ALPHA`); no lead, streaks, ball, flash, pulse or glow, and no tab pulse.
     * - **B** (3 600..4 500 of its segment): the plain bug with the scorer's digits in `pop` at × 1.
     * - Then the plain bug ([NflScoreFrame.done]).
     *
     * (NFL Stacked §4.4) Under [spec]: one score's frame B lasts [NflScoreSpec.stillFrameBMs] from 3 600; a pair's B1 ends
     * at [NflScoreSpec.pairSecondMs] and B2 at [NflScoreSpec.pairEndMs]; [NflScoreFrame.rowFlashAlpha] is 1 through a
     * frame B when [NflScoreSpec.rowFlashMs] > 0, else 0.
     */
    fun sampleStill(spec: NflScoreSpec, first: NflScoreKind, second: NflScoreKind?, tMs: Float, out: NflScoreFrame): NflScoreFrame {
        val t = clean(tMs)
        val pairSecond = spec.pairSecondMs
        val inSecond = second != null && t >= pairSecond
        val kind = if (second != null && inSecond) second else first
        val u = if (inSecond) t - pairSecond else t
        val end = if (second != null) spec.pairEndMs else TAKEOVER + spec.stillFrameBMs
        val a = u < TAKEOVER
        out.leadFrom = 0f; out.leadTo = 0f
        out.panelFrom = if (a) 0f else 1f; out.panelTo = 1f
        out.glowLowAlpha = 0f; out.glowPeakAlpha = 0f
        out.streak1On = false; out.streak1X = spec.streakFrom
        out.streak2On = false; out.streak2X = spec.streakFrom
        out.runOn = false
        out.arcOn = false
        out.ballX = 0f; out.ballY = 0f; out.ballRotDeg = 0f; out.ballScale = 1f; out.ballAlpha = 0f
        out.postsOn = a && !runsBall(kind)
        out.postsAlpha = if (out.postsOn) K.NFL_POSTS_ALPHA else 0f
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
        out.rowFlashAlpha = if (spec.rowFlashMs > 0f && out.popOn) 1f else 0f
        out.segment = if (inSecond) 1 else 0
        out.done = t >= end
        return out
    }

    /** Every channel of one takeover of [kind] under [spec] at [t] ≥ 0 (not [NflScoreFrame.segment] / [NflScoreFrame.done]). */
    private fun animate(spec: NflScoreSpec, kind: NflScoreKind, t: Float, out: NflScoreFrame) {
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
        val aLow = K.GOAL_GLOW_LOW_ALPHA
        val aPeak = K.GOAL_GLOW_PEAK_ALPHA
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
        out.streak1X = lerp(spec.streakFrom, spec.streakTo, STD.ease((t - STREAK1_START) / STREAK1_MS))
        out.streak2On = t >= STREAK2_START && t <= STREAK2_START + STREAK2_MS
        out.streak2X = lerp(spec.streakFrom, spec.streakTo, STD.ease((t - STREAK2_START) / STREAK2_MS))

        val run = runsBall(kind)
        if (run) {
            // Ball run (tBall, roll): the transform is one segment (keyed at 0 % and 100 % only), the opacity three.
            val p = (t - RUN_START) / RUN_MS
            out.runOn = p >= 0f && p <= 1f
            out.arcOn = false
            val q = ROLL.ease(p)
            out.ballX = lerp(spec.runFrom, spec.runTo, q)
            out.ballY = spec.runY
            out.ballRotDeg = K.NFL_RUN_ROT * q
            out.ballScale = 1f
            out.ballAlpha = ballAlpha(p, K.NFL_RUN_ALPHA_IN, K.NFL_RUN_ALPHA_OUT, spec.runAlpha, ROLL)
        } else {
            // Ball arc (tArc, ease): the transform keyed at 0 / 50 / 100 % (two segments), the opacity at 0 / 10 / 85 / 100 %.
            val p = (t - ARC_START) / ARC_MS
            out.arcOn = p >= 0f && p <= 1f
            out.runOn = false
            val pc = p.coerceIn(0f, 1f)
            if (pc < 0.5f) {
                val q = EASE.ease(pc / 0.5f)
                out.ballX = lerp(spec.arcP0X, spec.arcP1X, q)
                out.ballY = lerp(spec.arcP0Y, spec.arcP1Y, q)
                out.ballScale = lerp(spec.arcS0, spec.arcS1, q)
                out.ballRotDeg = lerp(spec.arcR0, spec.arcR1, q)
            } else {
                val q = EASE.ease((pc - 0.5f) / 0.5f)
                out.ballX = lerp(spec.arcP1X, spec.arcP2X, q)
                out.ballY = lerp(spec.arcP1Y, spec.arcP2Y, q)
                out.ballScale = lerp(spec.arcS1, spec.arcS2, q)
                out.ballRotDeg = lerp(spec.arcR1, spec.arcR2, q)
            }
            out.ballAlpha = ballAlpha(p, K.NFL_ARC_ALPHA_IN, K.NFL_ARC_ALPHA_OUT, spec.arcAlpha, EASE)
        }
        // Goalposts (XP, FG): tPosts (ease-out, fill both) α 0 → .4, dy 8 → 0 from 200; then tPostsHit, on the grid.
        out.postsOn = !run && t < TAKEOVER
        if (out.postsOn) {
            val q = EASE_OUT.ease((t - POSTS_IN_START) / POSTS_IN_MS)
            out.postsDy = spec.postsDy * (1f - q)
            out.postsAlpha = if (t >= POSTS_HIT_START && t < POSTS_HIT_END) {
                K.NFL_POSTS_ALPHA + (K.NFL_POSTS_HIT_ALPHA - K.NFL_POSTS_ALPHA) * cycles(t, POSTS_HIT_START, GRID, POSTS_HIT_END)
            } else {
                K.NFL_POSTS_ALPHA * q
            }
        } else {
            out.postsAlpha = 0f
            out.postsDy = 0f
        }
        // Panel flash (tFlash, no fill): NFL_FLASH_CYCLES × (0 → .42 → 0) on the grid from 450.
        out.flashAlpha = spec.flashAlpha * cycles(t, FLASH_START, GRID, FLASH_END)
        // Logo badge (tLogo, spring, fill both): α 0 dx −32 ×.4 −25° → 70 %: α 1 dx +2 ×1.1 +4° → rest.
        val bp = ((t - LOGO_START) / LOGO_MS).coerceIn(0f, 1f)
        if (bp < K.NFL_LOGO_MID) {
            val q = SPRING.ease(bp / K.NFL_LOGO_MID)
            out.badgeAlpha = q
            out.badgeDx = lerp(spec.badgeDx, spec.badgeMidDx, q)
            out.badgeScale = lerp(K.NFL_LOGO_FROM_SCALE, K.NFL_LOGO_MID_SCALE, q)
            out.badgeRotDeg = lerp(K.NFL_LOGO_FROM_DEG, K.NFL_LOGO_MID_DEG, q)
        } else {
            val q = SPRING.ease((bp - K.NFL_LOGO_MID) / (1f - K.NFL_LOGO_MID))
            out.badgeAlpha = 1f
            out.badgeDx = lerp(spec.badgeMidDx, 0f, q)
            out.badgeScale = lerp(K.NFL_LOGO_MID_SCALE, 1f, q)
            out.badgeRotDeg = lerp(K.NFL_LOGO_MID_DEG, 0f, q)
        }
        // Letters (tLetter, spring, fill both, 55 ms apart): α 0 dy +22 ×1.9 → 60 %: α 1 dy −2 ×.94 → rest.
        out.letterCount = letterCount(kind)
        val drop = spec.letterDrop
        val over = spec.letterOvershoot
        for (i in 0 until NflScoreFrame.LETTERS) {
            val p = ((t - letterDelay(i)) / LETTER_MS).coerceIn(0f, 1f)
            if (p < K.NFL_LETTER_MID) {
                val q = SPRING.ease(p / K.NFL_LETTER_MID)
                out.letterAlpha[i] = q
                out.letterDy[i] = lerp(drop, over, q)
                out.letterScale[i] = lerp(K.NFL_LETTER_FROM_SCALE, K.NFL_LETTER_MID_SCALE, q)
            } else {
                val q = SPRING.ease((p - K.NFL_LETTER_MID) / (1f - K.NFL_LETTER_MID))
                out.letterAlpha[i] = 1f
                out.letterDy[i] = lerp(over, 0f, q)
                out.letterScale[i] = lerp(K.NFL_LETTER_MID_SCALE, 1f, q)
            }
        }
        // Word pulse (tWord, no fill): 3 × (1 → 1.05 → 1).
        out.wordPulse = 1f + (K.NFL_WORD_PULSE - 1f) * cycles(t, PULSE_START, PULSE_CYCLE, PULSE_END)
        // +N and tally (tSlideR, spring, fill both): α 0 dx +19 ×.6 → 70 %: α 1 dx −1.6 ×1.12 → rest.
        val sp = ((t - POINTS_START) / POINTS_MS).coerceIn(0f, 1f)
        if (sp < K.NFL_POINTS_MID) {
            val q = SPRING.ease(sp / K.NFL_POINTS_MID)
            out.blockAlpha = q
            out.blockDx = lerp(spec.pointsDx, spec.pointsMidDx, q)
            out.blockScale = lerp(K.NFL_POINTS_FROM_SCALE, K.NFL_POINTS_MID_SCALE, q)
        } else {
            val q = SPRING.ease((sp - K.NFL_POINTS_MID) / (1f - K.NFL_POINTS_MID))
            out.blockAlpha = 1f
            out.blockDx = lerp(spec.pointsMidDx, 0f, q)
            out.blockScale = lerp(K.NFL_POINTS_MID_SCALE, 1f, q)
        }
        // The pinned tab's pulse (tTab, no fill): 8 × (0 → 1 → 0) from 0, peaks at 225 + 450 k.
        out.tabPulse = cycles(t, 0f, GRID, spec.tabPulseCycles * GRID)
        // Pop: its first state from the panel's exit (hidden under the lead until the lead leaves, GA §6.2), then the
        // pop keyframes over NFL_POP_MS; the colour has only 0 % / 100 % keyframes, so it is eased once over the pop.
        when {
            t < PANEL_OUT_START -> { out.popOn = false; out.popScale = 1f; out.popMix = 1f }
            t < TAKEOVER -> { out.popOn = true; out.popScale = K.NFL_POP_FROM_SCALE; out.popMix = 0f }
            t < END -> {
                val p = (t - TAKEOVER) / POP_MS
                out.popOn = true
                out.popMix = SPRING.ease(p)
                out.popScale = when {
                    p < K.NFL_POP_K1 -> lerp(K.NFL_POP_FROM_SCALE, K.NFL_POP_K1_SCALE, SPRING.ease(p / K.NFL_POP_K1))
                    p < K.NFL_POP_K2 -> lerp(
                        K.NFL_POP_K1_SCALE, K.NFL_POP_K2_SCALE, SPRING.ease((p - K.NFL_POP_K1) / (K.NFL_POP_K2 - K.NFL_POP_K1)),
                    )
                    else -> lerp(K.NFL_POP_K2_SCALE, 1f, SPRING.ease((p - K.NFL_POP_K2) / (1f - K.NFL_POP_K2)))
                }
            }
            else -> { out.popOn = false; out.popScale = 1f; out.popMix = 1f }
        }
        // (NFL Stacked §4.3) The row flash (from 3 600, ease-out): the envelope 1 → 0 over rowFlashMs; 0 before and after.
        // PILL (rowFlashMs 0) writes 0, never leaves the field as it was.
        out.rowFlashAlpha = if (spec.rowFlashMs > 0f && t >= TAKEOVER && t < TAKEOVER + spec.rowFlashMs) {
            1f - EASE_OUT.ease((t - TAKEOVER) / spec.rowFlashMs)
        } else {
            0f
        }
    }
}

/**
 * (NFL §4.3, §5.1, §5.2; WP-3) The mock's two SVG drawings as data, shared by the painters (which bake them into
 * `ALPHA_8` sprites once per size) and by the timeline's tests (the ink radius, B5). Coordinates are in each drawing's
 * own viewBox units. No path is stroked per frame.
 */
object NflShapes {
    /** The football: viewBox 24 × 24, centre (12, 12). */
    const val BALL_VIEWBOX = 24f
    /** The takeover ball's stroke (the mock's 1.6 of 24); the tab's icon uses `NFL_TAB_ICON_STROKE` (design px). */
    const val BALL_STROKE = 1.6f

    /**
     * The outline `M4.5 19.5 c-1.5-5 1-10.5 5-13 s8-2.5 10-2 c1.5 5-1 10.5-5 13 s-8 2.5-10 2 z` in absolute cubic
     * segments: [BALL_START], then (c1x, c1y, c2x, c2y, x, y) per segment, with the `s` reflections resolved.
     */
    val BALL_START = floatArrayOf(4.5f, 19.5f)
    val BALL_CUBICS = floatArrayOf(
        3f, 14.5f, 5.5f, 9f, 9.5f, 6.5f,
        13.5f, 4f, 17.5f, 4f, 19.5f, 4.5f,
        21f, 9.5f, 18.5f, 15f, 14.5f, 17.5f,
        10.5f, 20f, 6.5f, 20f, 4.5f, 19.5f,
    )

    /** The lace and seam strokes, (x0, y0, x1, y1) each: `M9 15l6-6`, `M10.5 11.5l2 2`, `M12 10l2 2`, `M9 13l2 2`. */
    val BALL_LINES = floatArrayOf(
        9f, 15f, 15f, 9f,
        10.5f, 11.5f, 12.5f, 13.5f,
        12f, 10f, 14f, 12f,
        9f, 13f, 11f, 15f,
    )

    /** The anti-aliasing allowance kept inside `NFL_BALL_INK_R`, design px. */
    const val BALL_AA_PX = 0.5f

    /**
     * The ball's outermost ink about its centre, viewBox units: the farthest control point (a cubic lies inside its
     * control points' hull, so this bounds the curve) plus half the [stroke] (round joins and caps).
     */
    fun ballInkRadiusUnits(stroke: Float = BALL_STROKE): Float {
        var r = 0f
        var i = 0
        while (i < BALL_CUBICS.size) { r = maxOf(r, dist(BALL_CUBICS[i], BALL_CUBICS[i + 1])); i += 2 }
        r = maxOf(r, dist(BALL_START[0], BALL_START[1]))
        i = 0
        while (i < BALL_LINES.size) { r = maxOf(r, dist(BALL_LINES[i], BALL_LINES[i + 1])); i += 2 }
        return r + stroke / 2f
    }

    private fun dist(x: Float, y: Float): Float {
        val dx = x - BALL_VIEWBOX / 2f
        val dy = y - BALL_VIEWBOX / 2f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * (B5) Standard design px per viewBox unit for the takeover ball: the mock's 34 / 24, reduced so the stroked outline's
     * ink stays within `NFL_BALL_INK_R` less [BALL_AA_PX] at any rotation. The design's 15 px is the path alone (10.6
     * units × 34 / 24); with the mock's stroke it would be 16.2, so the ball is drawn at about 90 % of the mock's size.
     */
    val BALL_UNIT_PX: Float = minOf(K.NFL_BALL_PX / BALL_VIEWBOX, (K.NFL_BALL_INK_R - BALL_AA_PX) / ballInkRadiusUnits())

    /** The goalposts: viewBox 60 × 80, stroke 3, round caps; `M10 6V44`, `M50 6V44`, `M10 44H50`, `M30 44V78`. */
    const val POSTS_VIEW_W = 60f
    const val POSTS_VIEW_H = 80f
    const val POSTS_STROKE = 3f
    val POSTS_LINES = floatArrayOf(
        10f, 6f, 10f, 44f,
        50f, 6f, 50f, 44f,
        10f, 44f, 50f, 44f,
        30f, 44f, 30f, 78f,
    )
}
