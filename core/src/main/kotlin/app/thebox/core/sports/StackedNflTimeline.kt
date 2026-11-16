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
 * §4.1 table. Every function writes only its track's channels of `out` and returns `out`; `from` is the interrupted
 * transition's sample (a new transition starts from the current value with its full duration and delay, no
 * reversing-shortening, PP §3.3); `from` and `out` may not be the same instance. NaN or negative `tMs` is 0; +∞ is the
 * end. Ms; Std = `cubic-bezier(.4,0,.2,1)`, Spring = `cubic-bezier(.2,.8,.2,1)`, ease / ease-in / ease-in-out the CSS
 * keywords.
 *
 * - **CLOCK** and **STRIPE_AWAY / STRIPE_HOME**: the NHL Stacked CSS exactly (`StackedTimeline`'s curves and constants,
 *   checked bit-for-bit against `StackedTimeline.sample` by `StackedNflTimelineTest`): fold width 450 Std, contents 150
 *   ease; open width 450 Std after 350, contents 300 ease after 500 (450 / 800); stripes 400 ease (3.6 ⇄ 6.3).
 * - **BALL_AWAY / BALL_HOME** (`.ball` ⇄ `.ball.on`): α 300 ease; scale .4 ⇄ 1 and rotation −40° ⇄ 0 400 Spring; no
 *   delay; both ways (400). A hidden ball (α 0) is at .4 / −40°, its CSS resting transform.
 * - **BAR OPEN** (from closed): height 0 → 1 400 Std; the REKEY intro from 300 (stripes A / B from 300 / 380, the words
 *   from 400); background mix 1 (nothing to lerp from). Length 300 + 550 = 850.
 * - **BAR CLOSE** (ST §7.1): height 400 Std; stripes 300 ease-in to the travel; words α / x 150 ease. Length 400.
 * - **BAR REKEY** (the mock's `barKey` re-mount): the old words and stripes are gone at 0 (the new content's intro starts
 *   from its 0 % keyframe); background mix 0 → 1 350 ease (`.bar` background-color .35s ease); stripe A x −63 → 0
 *   450 Spring, B the same after 80 (`stripeIn`); the words α 0 → 1 and x −11.7 → 0 together on the keyframe's Spring,
 *   450 after 100 (`barIn`). A height caught mid-open finishes toward 1 (400 Std, no delay). Length 550.
 * - **PIPS** ([samplePips]): a count change moves each pip lit ⇄ used 400 ease (`.to` background-color / opacity .4s);
 *   BURN: the pip at the new count's index holds lit and flashes 4 × 400 (`toBurn`, ease-in-out per half: lit → white
 *   α .15 at 200 → lit), then fades to used 400 ease; the others move as a count change. Length 2 000.
 * - **FLAG** ([sampleFlag]): the shake 2 × 500 after 150 (`flagShake`, ease-in-out per segment, ±2.7 / ±1.8); the
 *   icon's wave −8° ⇄ +8° alternate 600 per half (`flagWave`), while the flag bar shows.
 * - **STILL** (R20, §4.4): every track snaps to its rest state `to`; a REKEY cuts; no shake, wave (0°) or burn (the
 *   burnt pip is used at once).
 *
 * [StackedNflFrame.barClosed] is `barHeight == 0` under every bar transition. Channels are the left-corner values; the
 * painter mirrors them through `StackedNflGeometry` (offsets and slides negated, rotations negated).
 */
object StackedNflTimeline {
    internal val EASE = StackedTimeline.EASE
    internal val EASE_IN = StackedTimeline.EASE_IN
    internal val STD = StackedTimeline.STD
    internal val SPRING = StackedTimeline.SPRING
    /** CSS `ease-in-out` = `cubic-bezier(.42,0,.58,1)`. */
    internal val EASE_IN_OUT = GoalTimeline.EASE_IN_OUT

    /** The bar OPEN's REKEY intro starts here (§4.1 "then the REKEY intro from 300"). */
    internal const val OPEN_INTRO_MS = 300L

    /**
     * [track]'s channels for transition [kind] at [tMs]. [to]: [StackedNflTransition.STILL]'s rest state (true = open /
     * wide / on); ignored by the others ([StackedNflTransition.OPEN] and [StackedNflTransition.REKEY] end open,
     * [StackedNflTransition.CLOSE] closed; on a track other than [StackedNflTrack.BAR], REKEY acts as OPEN and BURN as
     * STILL). The pip tracks go through [samplePips] (nothing is written here for them).
     */
    fun sample(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame {
        val t = clean(tMs)
        if (kind == StackedNflTransition.STILL || kind == StackedNflTransition.BURN) {
            rest(track, to, out)
            return out
        }
        val opening = kind != StackedNflTransition.CLOSE
        when (track) {
            StackedNflTrack.CLOCK -> clock(opening, t, from, out)
            StackedNflTrack.BAR -> when (kind) {
                StackedNflTransition.OPEN -> barOpen(t, from, out)
                StackedNflTransition.CLOSE -> barClose(t, from, out)
                else -> barRekey(t, from, out)
            }
            StackedNflTrack.STRIPE_AWAY ->
                out.stripeAway = ch(from.stripeAway, if (opening) 1f else 0f, t, 0L, K.STACKED_ROW_STRIPE_MS, EASE)
            StackedNflTrack.STRIPE_HOME ->
                out.stripeHome = ch(from.stripeHome, if (opening) 1f else 0f, t, 0L, K.STACKED_ROW_STRIPE_MS, EASE)
            StackedNflTrack.BALL_AWAY -> {
                val a = from.ballAwayAlpha
                val s0 = if (a <= 0f) K.STACKED_NFL_BALL_FROM_SCALE else from.ballAwayScale
                val d0 = if (a <= 0f) K.STACKED_NFL_BALL_FROM_DEG else from.ballAwayDeg
                out.ballAwayAlpha = ch(a, if (opening) 1f else 0f, t, 0L, K.STACKED_NFL_BALL_ALPHA_MS, EASE)
                out.ballAwayScale = ch(s0, if (opening) 1f else K.STACKED_NFL_BALL_FROM_SCALE, t, 0L, K.STACKED_NFL_BALL_MOVE_MS, SPRING)
                out.ballAwayDeg = ch(d0, if (opening) 0f else K.STACKED_NFL_BALL_FROM_DEG, t, 0L, K.STACKED_NFL_BALL_MOVE_MS, SPRING)
            }
            StackedNflTrack.BALL_HOME -> {
                val a = from.ballHomeAlpha
                val s0 = if (a <= 0f) K.STACKED_NFL_BALL_FROM_SCALE else from.ballHomeScale
                val d0 = if (a <= 0f) K.STACKED_NFL_BALL_FROM_DEG else from.ballHomeDeg
                out.ballHomeAlpha = ch(a, if (opening) 1f else 0f, t, 0L, K.STACKED_NFL_BALL_ALPHA_MS, EASE)
                out.ballHomeScale = ch(s0, if (opening) 1f else K.STACKED_NFL_BALL_FROM_SCALE, t, 0L, K.STACKED_NFL_BALL_MOVE_MS, SPRING)
                out.ballHomeDeg = ch(d0, if (opening) 0f else K.STACKED_NFL_BALL_FROM_DEG, t, 0L, K.STACKED_NFL_BALL_MOVE_MS, SPRING)
            }
            StackedNflTrack.PIPS_AWAY, StackedNflTrack.PIPS_HOME -> Unit // samplePips
        }
        return out
    }

    /**
     * (§4.1 PIPS) [side]'s pips going from [fromLit] to [toLit] lit (0..3) under [kind]: [StackedNflTransition.OPEN] /
     * [StackedNflTransition.CLOSE] (and REKEY) each pip from its value in [from] to lit (index < [toLit]) or used over
     * 400 ease; [StackedNflTransition.BURN] the pip at index [toLit] (the new count) holds lit and flashes 4 × 400, then
     * fades to used over 400, the others as a count change; [StackedNflTransition.STILL] snaps (a burnt pip goes straight
     * to used). [fromLit] is the count the transition left (the burn's caller had `toLit + 1`); the values themselves
     * start from [from]. Writes only [side]'s two arrays.
     */
    fun samplePips(side: Side, kind: StackedNflTransition, fromLit: Int, toLit: Int, tMs: Float, from: StackedNflFrame, out: StackedNflFrame): StackedNflFrame {
        val t = clean(tMs)
        val lit = if (side == Side.AWAY) out.pipAway else out.pipHome
        val burn = if (side == Side.AWAY) out.pipAwayBurn else out.pipHomeBurn
        val lit0 = if (side == Side.AWAY) from.pipAway else from.pipHome
        val n = toLit.coerceIn(0, StackedNflFrame.PIPS)
        val burnAt = if (kind == StackedNflTransition.BURN && toLit in 0 until StackedNflFrame.PIPS) toLit else -1
        for (i in 0 until StackedNflFrame.PIPS) {
            val target = if (i < n) 1f else 0f
            when {
                kind == StackedNflTransition.STILL -> { lit[i] = target; burn[i] = 0f }
                i == burnAt -> {
                    val flashEnd = (K.STACKED_NFL_BURN_CYCLES * K.STACKED_NFL_BURN_CYCLE_MS).toFloat()
                    if (t < flashEnd) {
                        lit[i] = 1f
                        burn[i] = upDown((t % K.STACKED_NFL_BURN_CYCLE_MS) / K.STACKED_NFL_BURN_CYCLE_MS)
                    } else {
                        lit[i] = ch(1f, 0f, t, flashEnd.toLong(), K.STACKED_NFL_PIP_MS, EASE)
                        burn[i] = 0f
                    }
                }
                else -> { lit[i] = ch(lit0[i], target, t, 0L, K.STACKED_NFL_PIP_MS, EASE); burn[i] = 0f }
            }
        }
        return out
    }

    /**
     * (§4.1 FLAG) The flag bar's shake ([StackedNflFrame.shakeDx]: 2 × 500 from 150, 0 → −2.7 (20 %) → +2.7 (40 %) →
     * −1.8 (60 %) → +1.8 (80 %) → 0, each segment ease-in-out) and the icon's wave ([StackedNflFrame.waveDeg]: −8° → +8°
     * → −8° …, 600 ease-in-out per half) at [tMs] since the flag bar's REKEY; [still]: both 0. The runner samples it only
     * while the flag bar shows (≤ `NFL_FLAG_MARK_MS`), so the wave is bounded (S-D8).
     */
    fun sampleFlag(tMs: Float, still: Boolean, out: StackedNflFrame): StackedNflFrame {
        if (still) {
            out.shakeDx = 0f
            out.waveDeg = 0f
            return out
        }
        val t = clean(tMs)
        val shakeEnd = K.STACKED_NFL_SHAKE_DELAY_MS + K.STACKED_NFL_SHAKE_CYCLES * K.STACKED_NFL_SHAKE_MS
        out.shakeDx = if (t <= K.STACKED_NFL_SHAKE_DELAY_MS || t >= shakeEnd) {
            0f
        } else {
            val p = ((t - K.STACKED_NFL_SHAKE_DELAY_MS) % K.STACKED_NFL_SHAKE_MS) / K.STACKED_NFL_SHAKE_MS
            val seg = minOf((p / SHAKE_SEG).toInt(), SHAKE_KEYS.size - 2)
            val q = (p - seg * SHAKE_SEG) / SHAKE_SEG
            val a = SHAKE_KEYS[seg]
            a + (SHAKE_KEYS[seg + 1] - a) * EASE_IN_OUT.ease(q)
        }
        // Review fix C-N5: +∞ ("the end") rests the icon at 0° (∞ % x is NaN); the shake is already 0 there.
        if (!t.isFinite()) { out.waveDeg = 0f; return out }
        val half = K.STACKED_NFL_WAVE_MS.toFloat()
        val ph = t % (2f * half)
        val deg = K.STACKED_NFL_WAVE_DEG
        out.waveDeg = if (ph < half) -deg + 2f * deg * EASE_IN_OUT.ease(ph / half) else deg - 2f * deg * EASE_IN_OUT.ease((ph - half) / half)
        return out
    }

    /**
     * (§4.3, ST B4) A track that was mid-transition when a takeover started: [sample], then its **layout** channel (the
     * clock width, the bar height) at the end of the transition, while the rest of the track (contents, stripes, words,
     * background mix, balls, row stripes) finishes on its own clock, as `StackedTimeline.samplePinned`. Every transition
     * here is over by 2 000 ms, so by the 3 600 release the pinned and the plain samples agree.
     */
    fun samplePinned(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame {
        sample(track, kind, tMs, from, to, out)
        val open = endsOn(kind, to)
        when (track) {
            StackedNflTrack.CLOCK -> out.clockWidth = if (open) 1f else 0f
            StackedNflTrack.BAR -> { out.barHeight = if (open) 1f else 0f; out.barClosed = !open }
            else -> Unit
        }
        return out
    }

    /**
     * [track]'s transition [kind]'s length in ms, delays included (the runner's driver target: the track is at rest from
     * here): CLOCK 450 / 800 (close / open); BAR OPEN 850, CLOSE 400, REKEY 550; STRIPE 400; BALL 400; PIPS 400, BURN
     * 2 000; 0 for a [StackedNflTransition.STILL] snap (and BURN off the pip tracks, which is a snap).
     */
    fun totalMs(track: StackedNflTrack, kind: StackedNflTransition): Float {
        if (kind == StackedNflTransition.STILL) return 0f
        val close = kind == StackedNflTransition.CLOSE
        val ms: Long = when (track) {
            StackedNflTrack.CLOCK -> when {
                kind == StackedNflTransition.BURN -> 0L
                close -> maxOf(K.STACKED_CLOCK_MS, K.STACKED_CLOCK_OUT_MS)
                else -> maxOf(K.STACKED_CLOCK_OPEN_DELAY_MS + K.STACKED_CLOCK_MS, K.STACKED_CLOCK_IN_DELAY_MS + K.STACKED_CLOCK_IN_MS)
            }
            StackedNflTrack.BAR -> when (kind) {
                StackedNflTransition.OPEN -> maxOf(K.STACKED_BAR_MS, OPEN_INTRO_MS + K.STACKED_NFL_REKEY_MS)
                StackedNflTransition.CLOSE -> maxOf(K.STACKED_BAR_MS, K.STACKED_BAR_STRIPE_OUT_MS, K.STACKED_BAR_TEXT_OUT_MS)
                StackedNflTransition.REKEY -> K.STACKED_NFL_REKEY_MS
                else -> 0L
            }
            StackedNflTrack.STRIPE_AWAY, StackedNflTrack.STRIPE_HOME ->
                if (kind == StackedNflTransition.BURN) 0L else K.STACKED_ROW_STRIPE_MS
            StackedNflTrack.BALL_AWAY, StackedNflTrack.BALL_HOME ->
                if (kind == StackedNflTransition.BURN) 0L else maxOf(K.STACKED_NFL_BALL_ALPHA_MS, K.STACKED_NFL_BALL_MOVE_MS)
            StackedNflTrack.PIPS_AWAY, StackedNflTrack.PIPS_HOME ->
                if (kind == StackedNflTransition.BURN) K.STACKED_NFL_BURN_CYCLES * K.STACKED_NFL_BURN_CYCLE_MS + K.STACKED_NFL_PIP_MS
                else K.STACKED_NFL_PIP_MS
        }
        return ms.toFloat()
    }

    // ------------------------------------------------------------------ the tracks

    private fun clock(open: Boolean, t: Float, from: StackedNflFrame, out: StackedNflFrame) {
        if (open) {
            out.clockWidth = ch(from.clockWidth, 1f, t, K.STACKED_CLOCK_OPEN_DELAY_MS, K.STACKED_CLOCK_MS, STD)
            out.clockAlpha = ch(from.clockAlpha, 1f, t, K.STACKED_CLOCK_IN_DELAY_MS, K.STACKED_CLOCK_IN_MS, EASE)
        } else {
            out.clockWidth = ch(from.clockWidth, 0f, t, 0L, K.STACKED_CLOCK_MS, STD)
            out.clockAlpha = ch(from.clockAlpha, 0f, t, 0L, K.STACKED_CLOCK_OUT_MS, EASE)
        }
    }

    private fun barOpen(t: Float, from: StackedNflFrame, out: StackedNflFrame) {
        out.barHeight = ch(from.barHeight, 1f, t, 0L, K.STACKED_BAR_MS, STD)
        intro(t, OPEN_INTRO_MS, out)
        out.barBgMix = 1f
        out.barClosed = out.barHeight <= 0f
    }

    private fun barClose(t: Float, from: StackedNflFrame, out: StackedNflFrame) {
        out.barHeight = ch(from.barHeight, 0f, t, 0L, K.STACKED_BAR_MS, STD)
        out.stripeAX = ch(from.stripeAX, K.STACKED_BAR_STRIPE_TRAVEL, t, 0L, K.STACKED_BAR_STRIPE_OUT_MS, EASE_IN)
        out.stripeBX = ch(from.stripeBX, K.STACKED_BAR_STRIPE_TRAVEL, t, 0L, K.STACKED_BAR_STRIPE_OUT_MS, EASE_IN)
        out.barTextAlpha = ch(from.barTextAlpha, 0f, t, 0L, K.STACKED_BAR_TEXT_OUT_MS, EASE)
        out.barTextDx = ch(from.barTextDx, K.STACKED_NFL_REKEY_TEXT_DX, t, 0L, K.STACKED_BAR_TEXT_OUT_MS, EASE)
        out.barBgMix = 1f
        out.barClosed = out.barHeight <= 0f
    }

    private fun barRekey(t: Float, from: StackedNflFrame, out: StackedNflFrame) {
        out.barHeight = ch(from.barHeight, 1f, t, 0L, K.STACKED_BAR_MS, STD)
        intro(t, 0L, out)
        out.barBgMix = ch(0f, 1f, t, 0L, K.STACKED_NFL_REKEY_BG_MS, EASE)
        out.barClosed = out.barHeight <= 0f
    }

    /** The re-mounted content's keyframe intro from [at]: stripes from the travel, the words from hidden (fill `both`). */
    private fun intro(t: Float, at: Long, out: StackedNflFrame) {
        val travel = K.STACKED_BAR_STRIPE_TRAVEL
        out.stripeAX = ch(travel, 0f, t, at, K.STACKED_NFL_REKEY_STRIPE_MS, SPRING)
        out.stripeBX = ch(travel, 0f, t, at + K.STACKED_NFL_REKEY_STRIPE_B_DELAY_MS, K.STACKED_NFL_REKEY_STRIPE_MS, SPRING)
        val q = ch(0f, 1f, t, at + K.STACKED_NFL_REKEY_TEXT_DELAY_MS, K.STACKED_NFL_REKEY_TEXT_MS, SPRING)
        out.barTextAlpha = q
        out.barTextDx = K.STACKED_NFL_REKEY_TEXT_DX - K.STACKED_NFL_REKEY_TEXT_DX * q // exactly +0 at rest
    }

    // ------------------------------------------------------------------ helpers

    private fun clean(tMs: Float): Float = if (tMs.isNaN() || tMs < 0f) 0f else tMs

    /** One CSS-transitioned channel: [a] until [delay], then [a] → [b] over [dur] through [e], then [b]. */
    private fun ch(a: Float, b: Float, t: Float, delay: Long, dur: Long, e: CubicBezier): Float {
        val p = (t - delay) / dur
        if (p <= 0f) return a
        if (p >= 1f) return b
        return a + (b - a) * e.ease(p)
    }

    /** 0 → 1 → 0 over one cycle, each half ease-in-out (`0 %, 100 % {lit} 50 % {white .15}`). */
    private fun upDown(p: Float): Float = if (p < 0.5f) EASE_IN_OUT.ease(p * 2f) else 1f - EASE_IN_OUT.ease(p * 2f - 1f)

    /** `flagShake`'s keyframes (0, 20, 40, 60, 80, 100 %), Standard px. */
    private val SHAKE_KEYS = floatArrayOf(
        0f, -K.STACKED_NFL_SHAKE_DX1, K.STACKED_NFL_SHAKE_DX1, -K.STACKED_NFL_SHAKE_DX2, K.STACKED_NFL_SHAKE_DX2, 0f,
    )
    private const val SHAKE_SEG = 0.2f

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
            StackedNflTrack.BALL_AWAY -> {
                out.ballAwayAlpha = if (on) 1f else 0f
                out.ballAwayScale = if (on) 1f else K.STACKED_NFL_BALL_FROM_SCALE
                out.ballAwayDeg = if (on) 0f else K.STACKED_NFL_BALL_FROM_DEG
            }
            StackedNflTrack.BALL_HOME -> {
                out.ballHomeAlpha = if (on) 1f else 0f
                out.ballHomeScale = if (on) 1f else K.STACKED_NFL_BALL_FROM_SCALE
                out.ballHomeDeg = if (on) 0f else K.STACKED_NFL_BALL_FROM_DEG
            }
            StackedNflTrack.PIPS_AWAY, StackedNflTrack.PIPS_HOME -> Unit // samplePips
        }
    }
}
