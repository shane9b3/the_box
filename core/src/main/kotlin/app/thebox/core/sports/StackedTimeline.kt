// Contract (Stacked commit 0): docs/design/m6-score-bug-stacked-theme.md §2.3, §7.1. The types and the `sample` /
// `totalMs` signatures are fixed for WP-1 (the bodies), WP-2 (the painter reads a StackedFrame) and WP-3 (the runner);
// changes go through the lead. Every enum here is append-only.
package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K

/**
 * (Stacked §2.3) The layout tracks of the Stacked bug. Each has one `Animatable<Float>` of elapsed ms in the runner, read
 * only in draw. The goal takeover is not here: it is `GoalTimeline` under `GoalSpec.STACKED`. Append-only.
 */
enum class StackedTrack { SOG, CLOCK, BAR, STRIPE_AWAY, STRIPE_HOME }

/**
 * (Stacked §7.1) One transition of a track. [OPEN] / [CLOSE] for every track (the stripes: widen / narrow); [CHANGE]
 * and [SWAP] for [StackedTrack.BAR] only (another colour or side / the same look, `StackedTargets.sameLook`); [STILL]
 * a snap to rest: the still version, the §2.3 snaps (first view, reappearance, place mode) and the pin's snap. Append-only.
 */
enum class StackedTransition { OPEN, CLOSE, CHANGE, SWAP, STILL }

/**
 * (Stacked §7.1) One sampled instant of every layout track: a preallocated, mutable holder that [StackedTimeline.sample]
 * fills in place (draw never allocates). [StackedTimeline.sample] writes only its track's channels.
 *
 * Units: alphas and fractions 0..1; offsets in **Standard design px** (the painter multiplies by its own scale, so Large
 * gets × 1.25). The rest values below are the closed bar, closed SOG, open clock and narrow stripes (the play state).
 */
class StackedFrame {
    // ---- SOG track
    /** The SOG column's width as a fraction of [SportsConstants.STACKED_SOG_W] (0 = folded). */
    var sogWidth = 0f
    /** The SOG contents' (digits and label) alpha, and x offset (`STACKED_SOG_DX` ↔ 0). */
    var sogAlpha = 0f
    var sogDx = K.STACKED_SOG_DX

    // ---- CLOCK track
    /** The clock column's width as a fraction of [SportsConstants.STACKED_CLOCK_W] (0 = folded). */
    var clockWidth = 1f
    /** The clock column's contents' alpha (the period slot, the rule, the slot text). */
    var clockAlpha = 1f

    // ---- BAR track
    /** The bar's height as a fraction of [SportsConstants.STACKED_BAR_H] (0 = closed). */
    var barHeight = 0f
    /** The two skewed bar stripes' x offsets (`STACKED_BAR_STRIPE_TRAVEL` ↔ 0). */
    var stripeAX = K.STACKED_BAR_STRIPE_TRAVEL
    var stripeBX = K.STACKED_BAR_STRIPE_TRAVEL
    /** The bar text's alpha and x offset (`STACKED_BAR_TEXT_DX` ↔ 0). */
    var barTextAlpha = 0f
    var barTextDx = K.STACKED_BAR_TEXT_DX
    /**
     * During a [StackedTransition.CHANGE]: the background's mix from the previous content's colour (0) to the current
     * one's (1). 1 at rest and in every other transition.
     */
    var barBgMix = 1f
    /**
     * During a [StackedTransition.CHANGE] or [StackedTransition.SWAP]: false until the content swap instant (300 / 150 ms),
     * while the painter draws the previous content's words and stripe colours; true from then on, and in every other
     * transition (the current content).
     */
    var barSwapped = true
    /** The bar is at its closed rest state: nothing of it is drawn. */
    var barClosed = true

    // ---- STRIPE_AWAY / STRIPE_HOME tracks
    /** Each row stripe's width: 0 = `StackedMock.STRIPE_W` (3.6), 1 = `StackedMock.PP_STRIPE_W` (6.3), lerped. */
    var stripeAway = 0f
    var stripeHome = 0f

    /** The drawn top part's width in Standard design px: the rows plus the open fraction of each column. */
    val boxW: Float get() = K.STACKED_ROWS_W + sogWidth * K.STACKED_SOG_W + clockWidth * K.STACKED_CLOCK_W

    /** The open bar's height in Standard design px (0 when closed). */
    val barH: Float get() = barHeight * K.STACKED_BAR_H

    /** Copies every channel of every track from [o] (no allocation). */
    fun copyFrom(o: StackedFrame) {
        sogWidth = o.sogWidth
        sogAlpha = o.sogAlpha
        sogDx = o.sogDx
        clockWidth = o.clockWidth
        clockAlpha = o.clockAlpha
        barHeight = o.barHeight
        stripeAX = o.stripeAX
        stripeBX = o.stripeBX
        barTextAlpha = o.barTextAlpha
        barTextDx = o.barTextDx
        barBgMix = o.barBgMix
        barSwapped = o.barSwapped
        barClosed = o.barClosed
        stripeAway = o.stripeAway
        stripeHome = o.stripeHome
    }
}

/**
 * (Stacked §7.1; WP-1) The layout transitions as CSS transitions, pure and allocation-free: per-property durations,
 * delays and easings of the §7.1 table, every channel starting from `from` (an interrupted transition starts the new one
 * from the current value with its full duration and delay; no reversing-shortening factor, PP §3.3).
 */
object StackedTimeline {
    /**
     * Writes only [track]'s channels of [out] for transition [kind] at [tMs] ms since it started, starting from [from]
     * (the interrupted transition's sample), and returns [out]. [to]: [StackedTransition.STILL]'s rest state (true =
     * open / wide, false = closed / narrow); ignored by the others ([StackedTransition.CHANGE] and
     * [StackedTransition.SWAP] end open). [from] and [out] may not be the same instance. NaN or negative [tMs] is 0.
     *
     * **Commit-0 stub:** every transition writes its end state at once ([OPEN] / [CHANGE] / [SWAP] → open, [CLOSE] →
     * closed, [STILL] → [to]).
     */
    fun sample(track: StackedTrack, kind: StackedTransition, tMs: Float, from: StackedFrame, to: Boolean, out: StackedFrame): StackedFrame {
        val open = when (kind) {
            StackedTransition.OPEN, StackedTransition.CHANGE, StackedTransition.SWAP -> true
            StackedTransition.CLOSE -> false
            StackedTransition.STILL -> to
        }
        rest(track, open, out)
        return out
    }

    /**
     * (WP-1) The length of [kind] on [track] in ms, delays included: when the runner's driver for it may stop and the
     * track is at rest. 0 for [StackedTransition.STILL].
     *
     * **Commit-0 stub:** 0 for every transition (the stub [sample] is at rest at once).
     */
    @Suppress("UNUSED_PARAMETER")
    fun totalMs(track: StackedTrack, kind: StackedTransition): Float = 0f

    /** Writes [track]'s rest state ([open] = open / wide) into [out]. */
    private fun rest(track: StackedTrack, open: Boolean, out: StackedFrame) {
        when (track) {
            StackedTrack.SOG -> {
                out.sogWidth = if (open) 1f else 0f
                out.sogAlpha = if (open) 1f else 0f
                out.sogDx = if (open) 0f else K.STACKED_SOG_DX
            }
            StackedTrack.CLOCK -> {
                out.clockWidth = if (open) 1f else 0f
                out.clockAlpha = if (open) 1f else 0f
            }
            StackedTrack.BAR -> {
                out.barHeight = if (open) 1f else 0f
                out.stripeAX = if (open) 0f else K.STACKED_BAR_STRIPE_TRAVEL
                out.stripeBX = if (open) 0f else K.STACKED_BAR_STRIPE_TRAVEL
                out.barTextAlpha = if (open) 1f else 0f
                out.barTextDx = if (open) 0f else K.STACKED_BAR_TEXT_DX
                out.barBgMix = 1f
                out.barSwapped = true
                out.barClosed = !open
            }
            StackedTrack.STRIPE_AWAY -> out.stripeAway = if (open) 1f else 0f
            StackedTrack.STRIPE_HOME -> out.stripeHome = if (open) 1f else 0f
        }
    }
}
