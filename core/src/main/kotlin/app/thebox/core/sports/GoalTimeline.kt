package app.thebox.core.sports

/**
 * (Goal animation, docs/design/m6-goal-animation.md §2, §6.2) One sampled instant of the goal takeover: a preallocated,
 * mutable holder that [GoalTimeline.sample] fills in place (no allocation per frame, §2.5).
 *
 * Units: alphas and fractions 0..1; scales are multipliers (1 = rest); angles in degrees; offsets in **Standard design
 * px** (the painter multiplies by its own scale, so Large gets × 1.25). Wipe edges are fractions of the pill width.
 */
class GoalFrame {
    /** Lead wipe (accent), left/right clip edges as fractions of the pill width; equal = nothing drawn. */
    var leadFrom = 0f
    var leadTo = 0f
    /** Panel wipe (base); everything in the panel (streaks, flash, badge, GOAL, tally) is drawn under this clip. */
    var panelFrom = 0f
    var panelTo = 0f

    /** Glow mask alphas, already multiplied out: low mask α = aLow·(1−m), peak mask α = aPeak·m (§2.4). */
    var glowLowAlpha = 0f
    var glowPeakAlpha = 0f

    /** Streak 1 (accent .6) and streak 2 (white .35): drawn only while `on`; `x` is the band's left edge (translate). */
    var streak1On = false
    var streak1X = 0f
    var streak2On = false
    var streak2X = 0f

    /** The white flash over the panel (3 × 400 ms, 2.5 Hz). */
    var flashAlpha = 0f

    /** The badge (logo or fallback abbreviation): alpha, scale and rotation about its centre. */
    var badgeAlpha = 0f
    var badgeScale = 1f
    var badgeRotDeg = 0f

    /** GOAL, per letter G, O, A, L (index 0..3): alpha, vertical offset (design px), scale about the letter's centre. */
    val letterAlpha = FloatArray(LETTERS)
    val letterDy = FloatArray(LETTERS)
    val letterScale = FloatArray(LETTERS) { 1f }
    /** The word's pulse, a scale about the word's centre composed with each letter's own. */
    var wordPulse = 1f

    /** The tally ("2 – 1"): alpha and horizontal offset (design px, slides in from the right). */
    var tallyAlpha = 0f
    var tallyDx = 0f

    /**
     * The scorer's digit on the plain bug: drawn in the pop state while [popOn], at [popScale] (the painter clamps it,
     * N5), its colour lerped from the team's `pop` to white by [popMix] (0 = pop, 1 = white).
     */
    var popOn = false
    var popScale = 1f
    var popMix = 1f

    /** True once t ≥ the takeover plus the pop: the frame is the plain bug. */
    var done = false

    companion object {
        const val LETTERS = 4
    }
}

/**
 * (Goal animation §6.2; WP-2) The CSS keyframes as a pure function of elapsed ms. The easing applies per keyframe
 * segment, as in the CSS. Allocation-free.
 */
object GoalTimeline {
    /** Fills [out] for [tMs] since the takeover start and returns it. */
    @Suppress("UNUSED_PARAMETER")
    fun sample(tMs: Float, out: GoalFrame): GoalFrame = TODO("WP-2")

    /**
     * The still version (§7) for [tMs]: frame A (0..3 600, the panel at rest: base, badge, GOAL, tally; no lead,
     * streaks, flash, pulse or glow), frame B (3 600..4 500, the plain bug with the scorer's digit in `pop` at × 1),
     * then the plain bug ([GoalFrame.done]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun sampleStill(tMs: Float, out: GoalFrame): GoalFrame = TODO("WP-2")
}
