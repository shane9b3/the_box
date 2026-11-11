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
 *
 * The mock's CSS (`Stacked.dc.html` `<style>`), per track and direction (ms; Std = `cubic-bezier(.4,0,.2,1)`, Spring =
 * `cubic-bezier(.2,.8,.2,1)`, ease / ease-in the CSS keywords):
 * - SOG open: width 450 Std; contents α 300 / x 350 ease, both after 300. Close: contents α / x 200 ease; width 450 Std
 *   after 350. Length 650 / 800.
 * - Clock close: width 450 Std; contents α 150 ease. Open: width 450 Std after 350; contents α 300 ease after 500.
 *   Length 450 / 800.
 * - Bar open: height 400 Std after 400; stripes A / B 450 Spring after 700 / 780; text α 350 ease and x 450 Spring after
 *   850. Close: height 400 Std; stripes 300 ease-in; text α / x 150 ease. Length 1 300 / 400.
 * - Bar **change** (the lead's §7.1 row): stripes out 300 ease-in and text out 150 ease, the background mix 0 → 1 over
 *   300 (ease, the CSS default), the content swaps at 300 ([StackedFrame.barSwapped]); stripes back 450 Spring after
 *   300 / 380, text back α 350 ease and x 450 Spring after 450. Length 900.
 * - Bar **text swap**: text out 150 ease, swap at 150, text back α 350 ease and x 450 Spring after 150; the stripes and
 *   the background are left where they rest. Length 600.
 * - Row stripes: width 400 ease, both ways.
 *
 * Interruptions: [StackedTransition.CHANGE] and [StackedTransition.SWAP] keep the bar open, so a height or stripe
 * caught mid-way finishes toward open (height 400 Std, no delay; a swap's stripes 450 Spring, no delay) and a swap's
 * background mix finishes toward 1 over 300. A change's background mix always starts at 0 (the runner's `barPrev` is the
 * colour it lerps from), and its stripes and text come back from their hidden state after the swap instant.
 * [StackedFrame.barClosed] is `barHeight == 0` under every transition.
 */
object StackedTimeline {
    /** CSS `ease` = `cubic-bezier(.25,.1,.25,1)`. */
    internal val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
    /** CSS `ease-in` = `cubic-bezier(.42,0,1,1)`. */
    internal val EASE_IN = CubicBezier(0.42f, 0f, 1f, 1f)
    /** "Std" = `cubic-bezier(.4,0,.2,1)` (the widths and the bar height). */
    internal val STD = GoalTimeline.STREAK
    /** "Spring" = `cubic-bezier(.2,.8,.2,1)` (the bar stripes and the bar text's move). */
    internal val SPRING = GoalTimeline.SPRING

    /**
     * Writes only [track]'s channels of [out] for transition [kind] at [tMs] ms since it started, starting from [from]
     * (the interrupted transition's sample), and returns [out]. [to]: [StackedTransition.STILL]'s rest state (true =
     * open / wide, false = closed / narrow); ignored by the others ([StackedTransition.CHANGE] and
     * [StackedTransition.SWAP] end open; on a track other than [StackedTrack.BAR] they act as [StackedTransition.OPEN]).
     * [from] and [out] may not be the same instance. NaN or negative [tMs] is 0; +∞ is the end. Allocation-free.
     */
    fun sample(track: StackedTrack, kind: StackedTransition, tMs: Float, from: StackedFrame, to: Boolean, out: StackedFrame): StackedFrame {
        val t = if (tMs.isNaN() || tMs < 0f) 0f else tMs
        if (kind == StackedTransition.STILL) {
            rest(track, to, out)
            return out
        }
        val opening = kind != StackedTransition.CLOSE
        when (track) {
            StackedTrack.SOG -> sog(opening, t, from, out)
            StackedTrack.CLOCK -> clock(opening, t, from, out)
            StackedTrack.BAR -> when (kind) {
                StackedTransition.OPEN -> barOpen(t, from, out)
                StackedTransition.CLOSE -> barClose(t, from, out)
                StackedTransition.CHANGE -> barChange(t, from, out)
                else -> barSwap(t, from, out)
            }
            StackedTrack.STRIPE_AWAY ->
                out.stripeAway = ch(from.stripeAway, if (opening) 1f else 0f, t, 0L, K.STACKED_ROW_STRIPE_MS, EASE)
            StackedTrack.STRIPE_HOME ->
                out.stripeHome = ch(from.stripeHome, if (opening) 1f else 0f, t, 0L, K.STACKED_ROW_STRIPE_MS, EASE)
        }
        return out
    }

    /**
     * (WP-1 addition; the pin, §2.3 B4) [sample], then [track]'s **layout** channel (the SOG width, the clock width, the
     * bar height) at the end of [kind]: what the pin draws for a track that was mid-transition when a takeover started,
     * while the rest of that track (contents, stripes, text, background mix) finishes on its own clock. The row stripes
     * have no layout channel and are sampled as by [sample]. Every transition is over by 1 300 ms, so by the 3 600 release
     * the pinned and the plain samples agree. Allocation-free.
     */
    fun samplePinned(track: StackedTrack, kind: StackedTransition, tMs: Float, from: StackedFrame, to: Boolean, out: StackedFrame): StackedFrame {
        sample(track, kind, tMs, from, to, out)
        val open = when (kind) {
            StackedTransition.CLOSE -> false
            StackedTransition.STILL -> to
            else -> true
        }
        val end = if (open) 1f else 0f
        when (track) {
            StackedTrack.SOG -> out.sogWidth = end
            StackedTrack.CLOCK -> out.clockWidth = end
            StackedTrack.BAR -> { out.barHeight = end; out.barClosed = !open }
            StackedTrack.STRIPE_AWAY, StackedTrack.STRIPE_HOME -> Unit
        }
        return out
    }

    /**
     * (WP-1) The length of [kind] on [track] in ms, delays included: when the runner's driver for it may stop and the
     * track is at rest. 0 for [StackedTransition.STILL].
     */
    fun totalMs(track: StackedTrack, kind: StackedTransition): Float {
        if (kind == StackedTransition.STILL) return 0f
        val close = kind == StackedTransition.CLOSE
        val ms: Long = when (track) {
            StackedTrack.SOG -> if (close) {
                maxOf(K.STACKED_SOG_CLOSE_DELAY_MS + K.STACKED_SOG_MS, K.STACKED_SOG_OUT_MS)
            } else {
                maxOf(K.STACKED_SOG_MS, K.STACKED_SOG_IN_DELAY_MS + maxOf(K.STACKED_SOG_IN_ALPHA_MS, K.STACKED_SOG_IN_MOVE_MS))
            }
            StackedTrack.CLOCK -> if (close) {
                maxOf(K.STACKED_CLOCK_MS, K.STACKED_CLOCK_OUT_MS)
            } else {
                maxOf(K.STACKED_CLOCK_OPEN_DELAY_MS + K.STACKED_CLOCK_MS, K.STACKED_CLOCK_IN_DELAY_MS + K.STACKED_CLOCK_IN_MS)
            }
            StackedTrack.BAR -> when (kind) {
                StackedTransition.OPEN -> maxOf(
                    K.STACKED_BAR_OPEN_DELAY_MS + K.STACKED_BAR_MS,
                    K.STACKED_BAR_STRIPE_B_DELAY_MS + K.STACKED_BAR_STRIPE_IN_MS,
                    K.STACKED_BAR_TEXT_DELAY_MS + maxOf(K.STACKED_BAR_TEXT_IN_ALPHA_MS, K.STACKED_BAR_TEXT_IN_MOVE_MS),
                )
                StackedTransition.CLOSE -> maxOf(K.STACKED_BAR_MS, K.STACKED_BAR_STRIPE_OUT_MS, K.STACKED_BAR_TEXT_OUT_MS)
                StackedTransition.CHANGE -> K.STACKED_BAR_CHANGE_MS
                else -> K.STACKED_BAR_SWAP_MS
            }
            StackedTrack.STRIPE_AWAY, StackedTrack.STRIPE_HOME -> K.STACKED_ROW_STRIPE_MS
        }
        return ms.toFloat()
    }

    // ------------------------------------------------------------------ the tracks

    private fun sog(open: Boolean, t: Float, from: StackedFrame, out: StackedFrame) {
        if (open) {
            out.sogWidth = ch(from.sogWidth, 1f, t, 0L, K.STACKED_SOG_MS, STD)
            out.sogAlpha = ch(from.sogAlpha, 1f, t, K.STACKED_SOG_IN_DELAY_MS, K.STACKED_SOG_IN_ALPHA_MS, EASE)
            out.sogDx = ch(from.sogDx, 0f, t, K.STACKED_SOG_IN_DELAY_MS, K.STACKED_SOG_IN_MOVE_MS, EASE)
        } else {
            out.sogWidth = ch(from.sogWidth, 0f, t, K.STACKED_SOG_CLOSE_DELAY_MS, K.STACKED_SOG_MS, STD)
            out.sogAlpha = ch(from.sogAlpha, 0f, t, 0L, K.STACKED_SOG_OUT_MS, EASE)
            out.sogDx = ch(from.sogDx, K.STACKED_SOG_DX, t, 0L, K.STACKED_SOG_OUT_MS, EASE)
        }
    }

    private fun clock(open: Boolean, t: Float, from: StackedFrame, out: StackedFrame) {
        if (open) {
            out.clockWidth = ch(from.clockWidth, 1f, t, K.STACKED_CLOCK_OPEN_DELAY_MS, K.STACKED_CLOCK_MS, STD)
            out.clockAlpha = ch(from.clockAlpha, 1f, t, K.STACKED_CLOCK_IN_DELAY_MS, K.STACKED_CLOCK_IN_MS, EASE)
        } else {
            out.clockWidth = ch(from.clockWidth, 0f, t, 0L, K.STACKED_CLOCK_MS, STD)
            out.clockAlpha = ch(from.clockAlpha, 0f, t, 0L, K.STACKED_CLOCK_OUT_MS, EASE)
        }
    }

    private fun barOpen(t: Float, from: StackedFrame, out: StackedFrame) {
        out.barHeight = ch(from.barHeight, 1f, t, K.STACKED_BAR_OPEN_DELAY_MS, K.STACKED_BAR_MS, STD)
        out.stripeAX = ch(from.stripeAX, 0f, t, K.STACKED_BAR_STRIPE_A_DELAY_MS, K.STACKED_BAR_STRIPE_IN_MS, SPRING)
        out.stripeBX = ch(from.stripeBX, 0f, t, K.STACKED_BAR_STRIPE_B_DELAY_MS, K.STACKED_BAR_STRIPE_IN_MS, SPRING)
        out.barTextAlpha = ch(from.barTextAlpha, 1f, t, K.STACKED_BAR_TEXT_DELAY_MS, K.STACKED_BAR_TEXT_IN_ALPHA_MS, EASE)
        out.barTextDx = ch(from.barTextDx, 0f, t, K.STACKED_BAR_TEXT_DELAY_MS, K.STACKED_BAR_TEXT_IN_MOVE_MS, SPRING)
        out.barBgMix = 1f
        out.barSwapped = true
        out.barClosed = out.barHeight <= 0f
    }

    private fun barClose(t: Float, from: StackedFrame, out: StackedFrame) {
        out.barHeight = ch(from.barHeight, 0f, t, 0L, K.STACKED_BAR_MS, STD)
        out.stripeAX = ch(from.stripeAX, K.STACKED_BAR_STRIPE_TRAVEL, t, 0L, K.STACKED_BAR_STRIPE_OUT_MS, EASE_IN)
        out.stripeBX = ch(from.stripeBX, K.STACKED_BAR_STRIPE_TRAVEL, t, 0L, K.STACKED_BAR_STRIPE_OUT_MS, EASE_IN)
        out.barTextAlpha = ch(from.barTextAlpha, 0f, t, 0L, K.STACKED_BAR_TEXT_OUT_MS, EASE)
        out.barTextDx = ch(from.barTextDx, K.STACKED_BAR_TEXT_DX, t, 0L, K.STACKED_BAR_TEXT_OUT_MS, EASE)
        out.barBgMix = 1f
        out.barSwapped = true
        out.barClosed = out.barHeight <= 0f
    }

    private fun barChange(t: Float, from: StackedFrame, out: StackedFrame) {
        out.barHeight = ch(from.barHeight, 1f, t, 0L, K.STACKED_BAR_MS, STD)
        if (t < K.STACKED_BAR_CHANGE_OUT_MS) {
            // Out: the previous content's stripes and text leave.
            out.stripeAX = ch(from.stripeAX, K.STACKED_BAR_STRIPE_TRAVEL, t, 0L, K.STACKED_BAR_STRIPE_OUT_MS, EASE_IN)
            out.stripeBX = ch(from.stripeBX, K.STACKED_BAR_STRIPE_TRAVEL, t, 0L, K.STACKED_BAR_STRIPE_OUT_MS, EASE_IN)
            out.barTextAlpha = ch(from.barTextAlpha, 0f, t, 0L, K.STACKED_BAR_TEXT_OUT_MS, EASE)
            out.barTextDx = ch(from.barTextDx, K.STACKED_BAR_TEXT_DX, t, 0L, K.STACKED_BAR_TEXT_OUT_MS, EASE)
            out.barSwapped = false
        } else {
            // In: the new content's stripes and text, from the hidden state.
            val travel = K.STACKED_BAR_STRIPE_TRAVEL
            out.stripeAX = ch(travel, 0f, t, K.STACKED_BAR_CHANGE_STRIPE_A_DELAY_MS, K.STACKED_BAR_STRIPE_IN_MS, SPRING)
            out.stripeBX = ch(travel, 0f, t, K.STACKED_BAR_CHANGE_STRIPE_B_DELAY_MS, K.STACKED_BAR_STRIPE_IN_MS, SPRING)
            out.barTextAlpha = ch(0f, 1f, t, K.STACKED_BAR_CHANGE_TEXT_DELAY_MS, K.STACKED_BAR_TEXT_IN_ALPHA_MS, EASE)
            out.barTextDx = ch(K.STACKED_BAR_TEXT_DX, 0f, t, K.STACKED_BAR_CHANGE_TEXT_DELAY_MS, K.STACKED_BAR_TEXT_IN_MOVE_MS, SPRING)
            out.barSwapped = true
        }
        out.barBgMix = ch(0f, 1f, t, 0L, K.STACKED_BAR_CHANGE_OUT_MS, EASE)
        out.barClosed = out.barHeight <= 0f
    }

    private fun barSwap(t: Float, from: StackedFrame, out: StackedFrame) {
        out.barHeight = ch(from.barHeight, 1f, t, 0L, K.STACKED_BAR_MS, STD)
        out.stripeAX = ch(from.stripeAX, 0f, t, 0L, K.STACKED_BAR_STRIPE_IN_MS, SPRING)
        out.stripeBX = ch(from.stripeBX, 0f, t, 0L, K.STACKED_BAR_STRIPE_IN_MS, SPRING)
        if (t < K.STACKED_BAR_SWAP_OUT_MS) {
            out.barTextAlpha = ch(from.barTextAlpha, 0f, t, 0L, K.STACKED_BAR_SWAP_OUT_MS, EASE)
            out.barTextDx = ch(from.barTextDx, K.STACKED_BAR_TEXT_DX, t, 0L, K.STACKED_BAR_SWAP_OUT_MS, EASE)
            out.barSwapped = false
        } else {
            out.barTextAlpha = ch(0f, 1f, t, K.STACKED_BAR_SWAP_OUT_MS, K.STACKED_BAR_SWAP_IN_ALPHA_MS, EASE)
            out.barTextDx = ch(K.STACKED_BAR_TEXT_DX, 0f, t, K.STACKED_BAR_SWAP_OUT_MS, K.STACKED_BAR_SWAP_IN_MOVE_MS, SPRING)
            out.barSwapped = true
        }
        out.barBgMix = ch(from.barBgMix, 1f, t, 0L, K.STACKED_BAR_CHANGE_OUT_MS, EASE)
        out.barClosed = out.barHeight <= 0f
    }

    /** One CSS-transitioned channel: [a] until [delay], then [a] → [b] over [dur] through [e], then [b]. */
    private fun ch(a: Float, b: Float, t: Float, delay: Long, dur: Long, e: CubicBezier): Float {
        val p = (t - delay) / dur
        if (p <= 0f) return a
        if (p >= 1f) return b
        return a + (b - a) * e.ease(p)
    }

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
