// Contract (NFL commit 0, docs/design/m6-nfl.md §5.1, §5.6, §5.7): the frame and the sample signatures are fixed for
// WP-3 (the timeline body and the painters) and WP-4 (the runner); changes go through the lead.
package app.thebox.core.sports

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

    /** True once t ≥ the end (one score: takeover + pop; a pair: `NFL_DOUBLE_MARK_MS`): the frame is the plain bug. */
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
        done = o.done
    }

    companion object {
        /** `NFL_LETTERS_MAX`: "EXTRA POINT" is 11 with the space. */
        const val LETTERS = SportsConstants.NFL_LETTERS_MAX
    }
}

/**
 * (NFL §5.1, §5.3, §5.6, §5.7; WP-3) The mock's keyframes as a pure function of elapsed ms, per keyframe segment as the
 * CSS, every flash on the one 450 ms grid. Allocation-free: each function writes only into the caller's frame and
 * returns it.
 */
object NflScoreTimeline {
    /** One takeover of [kind] at [tMs] since its start (3 600 ms + the 900 ms pop). */
    fun sample(kind: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame = TODO("NFL WP-3: §5.1")

    /** (Ruling 25, §5.7) A +7/+8 pair: [first] (TOUCHDOWN) from 0, [second] from `NFL_PAIR_SECOND_START_MS`. */
    fun samplePair(first: NflScoreKind, second: NflScoreKind, tMs: Float, out: NflScoreFrame): NflScoreFrame =
        TODO("NFL WP-3: §5.7")

    /**
     * (§5.6, ruling 20) The still version: frames A and B for one score ([second] null), or A1, B1, A2, B2 for a pair.
     */
    fun sampleStill(first: NflScoreKind, second: NflScoreKind?, tMs: Float, out: NflScoreFrame): NflScoreFrame =
        TODO("NFL WP-3: §5.6")
}
