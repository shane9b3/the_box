// Contract (NFL Stacked commit 0): docs/design/m6-nfl-stacked.md §4.1, §4.4, §8.1. The frame and the sample signatures are
// fixed for WP-2 (the bodies), WP-3 (the painters read the frame) and WP-4 (the runner); changes go through the lead.
// Every enum here is append-only.
package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K

/** (NFL Stacked §4.1) The runner's layout tracks. Append-only. */
enum class StackedNflTrack { CLOCK, BAR, STRIPE_AWAY, STRIPE_HOME, BALL_AWAY, BALL_HOME, PIPS_AWAY, PIPS_HOME }

/**
 * (NFL Stacked §4.1, §4.4) A track's transition. [OPEN] / [CLOSE]: the clock column's open / fold, the bar's open / close,
 * a row stripe's widen / narrow, a ball's on / off, a pip count's rise; [REKEY]: the bar's content change while open (the
 * mock's 550-ms re-mount); [BURN]: a timeout pip's 4 × 400 burn then the fade to used; [STILL]: "Reduce animations",
 * a snap to the rest state `to`. Append-only.
 */
enum class StackedNflTransition { OPEN, CLOSE, REKEY, BURN, STILL }

/**
 * (NFL Stacked §4.1) One sampled instant of every layout track: a preallocated, mutable holder that [StackedNflTimeline]
 * fills in place (draw never allocates). Each sample writes only its track's channels.
 *
 * Units: alphas and fractions 0..1; offsets in **Standard design px** (the painter multiplies by its own scale, so Large
 * gets × 1.25); angles in degrees. The defaults are the closed bar, open clock, narrow stripes, no ball, all pips used.
 *
 * - CLOCK: [clockWidth] (fraction of `STACKED_CLOCK_W`, 0 = folded) and its contents' [clockAlpha].
 * - BAR: [barHeight] (fraction of `STACKED_BAR_H`), the skewed stripes' [stripeAX] / [stripeBX]
 *   (`STACKED_BAR_STRIPE_TRAVEL` ↔ 0), the words' [barTextAlpha] and [barTextDx] (`STACKED_NFL_REKEY_TEXT_DX` ↔ 0; the
 *   painter negates it when mirrored, Rec 5), the background's [barBgMix] from the previous content's colour (0) to the
 *   current one's (1; 1 outside a REKEY), [barClosed] (nothing of the bar is drawn).
 * - FLAG sub-track ([StackedNflTimeline.sampleFlag]): the words', divider's and stripes' [shakeDx] and the icon's
 *   [waveDeg] about its pivot.
 * - STRIPE_AWAY / STRIPE_HOME: [stripeAway] / [stripeHome], 0 = 3.6 wide, 1 = 6.3 (possession), lerped.
 * - BALL_AWAY / BALL_HOME: alpha, scale and rotation of each possession ball ([ballAwayAlpha] …).
 * - PIPS_AWAY / PIPS_HOME: per pip index 0..2 (index 0 nearest the logo), [pipAway] / [pipHome] 1 = lit … 0 = used, and
 *   [pipAwayBurn] / [pipHomeBurn] the burn's white flash 0..1 over the lit colour.
 */
class StackedNflFrame {
    var clockWidth = 1f
    var clockAlpha = 1f

    var barHeight = 0f
    var stripeAX = K.STACKED_BAR_STRIPE_TRAVEL
    var stripeBX = K.STACKED_BAR_STRIPE_TRAVEL
    var barTextAlpha = 0f
    var barTextDx = K.STACKED_NFL_REKEY_TEXT_DX
    var barBgMix = 1f
    var barClosed = true

    var shakeDx = 0f
    var waveDeg = 0f

    var stripeAway = 0f
    var stripeHome = 0f

    var ballAwayAlpha = 0f
    var ballAwayScale = 1f
    var ballAwayDeg = 0f
    var ballHomeAlpha = 0f
    var ballHomeScale = 1f
    var ballHomeDeg = 0f

    val pipAway = FloatArray(PIPS)
    val pipHome = FloatArray(PIPS)
    val pipAwayBurn = FloatArray(PIPS)
    val pipHomeBurn = FloatArray(PIPS)

    /** The drawn top part's width in Standard design px: the rows plus the open fraction of the clock column. */
    val boxW: Float get() = K.STACKED_ROWS_W + clockWidth * K.STACKED_CLOCK_W

    /** The open bar's height in Standard design px (0 when closed). */
    val barH: Float get() = barHeight * K.STACKED_BAR_H

    /** Copies every channel of every track from [o] (no allocation). */
    fun copyFrom(o: StackedNflFrame) {
        clockWidth = o.clockWidth
        clockAlpha = o.clockAlpha
        barHeight = o.barHeight
        stripeAX = o.stripeAX
        stripeBX = o.stripeBX
        barTextAlpha = o.barTextAlpha
        barTextDx = o.barTextDx
        barBgMix = o.barBgMix
        barClosed = o.barClosed
        shakeDx = o.shakeDx
        waveDeg = o.waveDeg
        stripeAway = o.stripeAway
        stripeHome = o.stripeHome
        ballAwayAlpha = o.ballAwayAlpha
        ballAwayScale = o.ballAwayScale
        ballAwayDeg = o.ballAwayDeg
        ballHomeAlpha = o.ballHomeAlpha
        ballHomeScale = o.ballHomeScale
        ballHomeDeg = o.ballHomeDeg
        o.pipAway.copyInto(pipAway)
        o.pipHome.copyInto(pipHome)
        o.pipAwayBurn.copyInto(pipAwayBurn)
        o.pipHomeBurn.copyInto(pipHomeBurn)
    }

    companion object {
        /** The most pips per side (`NFL_TIMEOUTS_MAX`). */
        const val PIPS = K.NFL_TIMEOUTS_MAX
    }
}

/**
 * (NFL Stacked §4.1, §4.4; WP-2) The layout transitions as CSS transitions, pure and allocation-free: every row of the
 * §4.1 table (CLOCK and the row stripes delegate to `StackedTimeline.sample`, the same CSS; the bar's OPEN / CLOSE /
 * REKEY, the balls, the pips and the flag sub-track are implemented here). Every function writes only its track's
 * channels of `out` and returns `out`; `from` is the interrupted transition's sample (a new transition starts from the
 * current value with its full duration and delay); `from` and `out` may not be the same instance. NaN or negative `tMs`
 * is 0; +∞ is the end.
 *
 * **Commit-0 stubs:** every function writes its track's **rest state** at once (as if `tMs` were +∞), and [totalMs] is 0.
 * Commit 0 never runs them (`StackedNflBug` draws the NFL Pill until WP-4).
 */
object StackedNflTimeline {
    /**
     * [track]'s channels for transition [kind] at [tMs]. [to]: [StackedNflTransition.STILL]'s rest state (true = open /
     * wide / on); ignored by the others ([StackedNflTransition.OPEN] and [StackedNflTransition.REKEY] end open,
     * [StackedNflTransition.CLOSE] closed). The pip tracks go through [samplePips].
     */
    fun sample(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame {
        // WP-2: the §4.1 curves.
        rest(track, endsOn(kind, to), out)
        return out
    }

    /**
     * (§4.1 PIPS) [side]'s pips going from [fromLit] to [toLit] lit (0..3) under [kind]: [StackedNflTransition.OPEN] /
     * [StackedNflTransition.CLOSE] each changed pip lit ⇄ used over 400 ease; [StackedNflTransition.BURN] the pip at
     * index [toLit] (the new count) flashes 4 × 400 then fades to used; [StackedNflTransition.STILL] snaps (a burnt pip
     * goes straight to used).
     */
    fun samplePips(side: Side, kind: StackedNflTransition, fromLit: Int, toLit: Int, tMs: Float, from: StackedNflFrame, out: StackedNflFrame): StackedNflFrame {
        // WP-2: the §4.1 PIPS rows.
        val lit = if (side == Side.AWAY) out.pipAway else out.pipHome
        val burn = if (side == Side.AWAY) out.pipAwayBurn else out.pipHomeBurn
        for (i in 0 until StackedNflFrame.PIPS) {
            lit[i] = if (i < toLit) 1f else 0f
            burn[i] = 0f
        }
        return out
    }

    /**
     * (§4.1 FLAG) The flag bar's shake ([StackedNflFrame.shakeDx]: 2 × 500 from 150) and the icon's wave
     * ([StackedNflFrame.waveDeg]: ±8° alternate, 600 per half) at [tMs] since the flag bar's REKEY; [still]: both 0.
     */
    fun sampleFlag(tMs: Float, still: Boolean, out: StackedNflFrame): StackedNflFrame {
        // WP-2: the shake and wave curves.
        out.shakeDx = 0f
        out.waveDeg = 0f
        return out
    }

    /**
     * (§4.3, ST B4) A track that was mid-transition when a takeover started: its layout channels (clock width, bar height)
     * at the end of the transition, the rest on its own clock, as `StackedTimeline.samplePinned`.
     */
    fun samplePinned(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame {
        // WP-2: the pinned layout channels.
        rest(track, endsOn(kind, to), out)
        return out
    }

    /** [track]'s transition [kind]'s length in ms (the runner's driver target); 0 for a [StackedNflTransition.STILL] snap. */
    fun totalMs(track: StackedNflTrack, kind: StackedNflTransition): Float {
        // WP-2: the §4.1 lengths (CLOCK 450 / 800, BAR OPEN 400 + 550, CLOSE 400, REKEY 550, BURN 2 000, …).
        return 0f
    }

    /** Whether [kind] ends open / wide / on. */
    private fun endsOn(kind: StackedNflTransition, to: Boolean): Boolean = when (kind) {
        StackedNflTransition.OPEN, StackedNflTransition.REKEY -> true
        StackedNflTransition.CLOSE -> false
        StackedNflTransition.BURN, StackedNflTransition.STILL -> to
    }

    /** [track]'s rest channels, open ([on]) or closed. */
    private fun rest(track: StackedNflTrack, on: Boolean, out: StackedNflFrame) {
        when (track) {
            StackedNflTrack.CLOCK -> {
                out.clockWidth = if (on) 1f else 0f
                out.clockAlpha = if (on) 1f else 0f
            }
            StackedNflTrack.BAR -> {
                out.barHeight = if (on) 1f else 0f
                out.stripeAX = if (on) 0f else K.STACKED_BAR_STRIPE_TRAVEL
                out.stripeBX = if (on) 0f else K.STACKED_BAR_STRIPE_TRAVEL
                out.barTextAlpha = if (on) 1f else 0f
                out.barTextDx = if (on) 0f else K.STACKED_NFL_REKEY_TEXT_DX
                out.barBgMix = 1f
                out.barClosed = !on
            }
            StackedNflTrack.STRIPE_AWAY -> out.stripeAway = if (on) 1f else 0f
            StackedNflTrack.STRIPE_HOME -> out.stripeHome = if (on) 1f else 0f
            StackedNflTrack.BALL_AWAY -> { out.ballAwayAlpha = if (on) 1f else 0f; out.ballAwayScale = 1f; out.ballAwayDeg = 0f }
            StackedNflTrack.BALL_HOME -> { out.ballHomeAlpha = if (on) 1f else 0f; out.ballHomeScale = 1f; out.ballHomeDeg = 0f }
            StackedNflTrack.PIPS_AWAY, StackedNflTrack.PIPS_HOME -> Unit // samplePips
        }
    }
}
