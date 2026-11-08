// Contract (power-play commit 0): docs/design/m6-power-play.md §3.1, §3.2. The types and the `sample` signature are fixed
// for WP-2 (the timeline body) and WP-3 (the runner); changes go through the lead. Every enum here is append-only.
package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K

/** (Power play §3.1) The two animated tracks: [TAB] (slide, shadow, text, shine, pulse) and [PILL] (tint, logo halo). */
enum class PpTrack { TAB, PILL }

/**
 * (Power play §3.3, §3.6) One transition of a track. [OPEN] 1 900 ms, [CLOSE] 600 ms, [PULSE] the label pulse once
 * (450 ms, tab only, every other channel at rest), [STILL] a snap to rest (the still version and the §3.1 snaps).
 */
enum class PpTransition { OPEN, CLOSE, PULSE, STILL }

/**
 * (Power play §3.2) One sampled instant of both tracks: a preallocated, mutable holder that [PowerPlayTimeline.sample]
 * fills in place (draw never allocates). [PowerPlayTimeline.sample] writes only its track's channels.
 *
 * Units: alphas and fractions 0..1, scales are multipliers (1 = rest), offsets in **Standard design px** (the painter
 * multiplies by its own scale, so Large gets × 1.25). Offsets are for a tab below the pill; the painter mirrors them for
 * a tab above (`up`, §3.5).
 */
class PpFrame {
    // ---- TAB track
    /** 0 = hidden under the pill, 1 = fully out (× `PP_TRAVEL`). */
    var slide = 0f
    /** The baked tab shadow's alpha (0 ↔ `PP_SHADOW_ALPHA`). */
    var shadowAlpha = 0f
    /** The text block's alpha. */
    var textAlpha = 0f
    /** The text block's offset toward the pill (−`PP_TEXT_DY` ↔ 0), design px. */
    var textDy = 0f
    /** The shine band is drawn while on; [shineX] is its left edge, tab-local design px (`PP_SHINE_FROM` → `PP_SHINE_TO`). */
    var shineOn = false
    var shineX = 0f
    /** The label's pulse scale about its centre (1 → `PP_PULSE_SCALE` → 1). */
    var labelScale = 1f

    // ---- PILL track
    /** The pill tint's strength (the painter multiplies by `TeamColours.tintScale`). */
    var tint = 0f
    /** The logo halo's strength (× `PP_HALO_ALPHA` × `tintScale`). */
    var halo = 0f

    /** The track is at its closed rest state: nothing of it is drawn. */
    var tabClosed = true
    var pillClosed = true

    /** Copies every channel of both tracks from [o] (no allocation). */
    fun copyFrom(o: PpFrame) {
        slide = o.slide
        shadowAlpha = o.shadowAlpha
        textAlpha = o.textAlpha
        textDy = o.textDy
        shineOn = o.shineOn
        shineX = o.shineX
        labelScale = o.labelScale
        tint = o.tint
        halo = o.halo
        tabClosed = o.tabClosed
        pillClosed = o.pillClosed
    }
}

/** (Power play §3.1) What the tab's text block says. Append-only. */
enum class PpLabel { ONE_MAN, TWO_MAN, PP_GOAL }

/**
 * (Power play §3.1, §5, B2) What the tab says, owned by the run: captured when a transition starts (a close slides back
 * the words it opened with; a pin holds its own). Mutable and preallocated, so the runner and draw never allocate.
 * [secs] is the shown `m:ss` value, −1 = none (the label is drawn alone, centred, as for [PpLabel.PP_GOAL]).
 */
class PpContent(
    var side: Side = Side.AWAY,
    var label: PpLabel = PpLabel.ONE_MAN,
    var secs: Int = NO_SECS,
) {
    fun copyFrom(o: PpContent) {
        side = o.side
        label = o.label
        secs = o.secs
    }

    override fun toString(): String = "PpContent($side, $label, $secs)"

    companion object {
        /** [secs] when the feed gave no seconds. */
        const val NO_SECS = -1
    }
}

/**
 * (Power play §3.2, §3.3, §3.6; WP-2) The tab and pill keyframes, pure and allocation-free. Easing is per keyframe
 * segment (`ease`, `ease-in-out`, `snap` (.2,.8,.2,1), `std` (.4,0,.2,1)); every channel starts from `from` (CSS
 * transition semantics).
 */
object PowerPlayTimeline {
    /**
     * Writes only [track]'s channels of [out] for transition [kind] at [tMs] ms since it started, starting from [from]
     * (the interrupted transition's sample), and returns [out]. [to]: [PpTransition.STILL]'s rest state (true = open,
     * false = closed); ignored by the others. [from] and [out] may not be the same instance.
     */
    fun sample(track: PpTrack, kind: PpTransition, tMs: Float, from: PpFrame, to: Boolean, out: PpFrame): PpFrame {
        val t = if (tMs.isNaN() || tMs < 0f) 0f else tMs
        when (track) {
            PpTrack.TAB -> when (kind) {
                PpTransition.OPEN -> tabOpen(t, from, out)
                PpTransition.CLOSE -> tabClose(t, from, out)
                PpTransition.PULSE -> { tabRest(true, out); out.labelScale = pulse(t) }
                PpTransition.STILL -> tabRest(to, out)
            }
            PpTrack.PILL -> when (kind) {
                PpTransition.OPEN -> pill(t, from, 1f, out)
                PpTransition.CLOSE -> pill(t, from, 0f, out)
                // The pill has no pulse: a PULSE holds it at the open rest state.
                PpTransition.PULSE -> pillRest(true, out)
                PpTransition.STILL -> pillRest(to, out)
            }
        }
        return out
    }

    // ---- easings (CSS timing functions, applied per keyframe segment)
    /** CSS `ease` = `cubic-bezier(.25,.1,.25,1)`: the shadow, the text, the tint and the halo. */
    internal val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
    /** CSS `ease-in-out`: each half of the label pulse. */
    internal val EASE_IN_OUT = CubicBezier(0.42f, 0f, 0.58f, 1f)
    /** `snap` = `cubic-bezier(.2,.8,.2,1)`: the slide. */
    internal val SNAP = CubicBezier(0.2f, 0.8f, 0.2f, 1f)
    /** `std` = `cubic-bezier(.4,0,.2,1)`: the shine. */
    internal val STD = CubicBezier(0.4f, 0f, 0.2f, 1f)

    /** The open's pulse: two `ppPulse` iterations. */
    private const val PULSE_TOTAL_MS = 2 * K.PP_PULSE_MS

    /** [a] → [b] over [start] .. [start] + [dur] through [e]; holds [a] before (a transition's delay) and [b] after. */
    private fun seg(t: Float, start: Float, dur: Float, a: Float, b: Float, e: CubicBezier): Float {
        if (t <= start) return a
        if (t >= start + dur) return b
        return a + (b - a) * e.ease((t - start) / dur)
    }

    /** One `ppPulse` iteration [t] ms in: ×1 → [K.PP_PULSE_SCALE] at 50 % → ×1, ease-in-out per half; 1 outside it. */
    private fun pulse(t: Float): Float {
        if (t <= 0f || t >= K.PP_PULSE_MS) return 1f
        val half = K.PP_PULSE_MS / 2f
        return if (t < half) seg(t, 0f, half, 1f, K.PP_PULSE_SCALE, EASE_IN_OUT)
        else seg(t, half, half, K.PP_PULSE_SCALE, 1f, EASE_IN_OUT)
    }

    private fun tabOpen(t: Float, from: PpFrame, out: PpFrame) {
        // The shine and the pulse are CSS animations of the `.on` state: they play only on an open from (near) closed.
        val fresh = from.slide <= K.PP_REVERSE_SLIDE_MAX
        out.slide = seg(t, 0f, K.PP_SLIDE_MS.toFloat(), from.slide, 1f, SNAP)
        out.shadowAlpha = seg(t, 0f, K.PP_SHADOW_MS.toFloat(), from.shadowAlpha, K.PP_SHADOW_ALPHA, EASE)
        val delay = K.PP_TEXT_DELAY_MS.toFloat()
        out.textAlpha = seg(t, delay, K.PP_TEXT_ALPHA_MS.toFloat(), from.textAlpha, 1f, EASE)
        out.textDy = seg(t, delay, K.PP_TEXT_MOVE_MS.toFloat(), from.textDy, 0f, EASE)
        val shineStart = K.PP_SHINE_DELAY_MS.toFloat()
        out.shineOn = fresh && t >= shineStart && t <= shineStart + K.PP_SHINE_MS
        out.shineX = if (out.shineOn) seg(t, shineStart, K.PP_SHINE_MS.toFloat(), K.PP_SHINE_FROM, K.PP_SHINE_TO, STD) else K.PP_SHINE_FROM
        val p = t - K.PP_PULSE_DELAY_MS
        out.labelScale = if (fresh && p > 0f && p < PULSE_TOTAL_MS) pulse(p % K.PP_PULSE_MS) else 1f
        out.tabClosed = false
    }

    private fun tabClose(t: Float, from: PpFrame, out: PpFrame) {
        out.slide = seg(t, 0f, K.PP_SLIDE_MS.toFloat(), from.slide, 0f, SNAP)
        out.shadowAlpha = seg(t, 0f, K.PP_SHADOW_MS.toFloat(), from.shadowAlpha, 0f, EASE)
        // Lead Q4: the CSS close; the text's fade and move start with the slide.
        out.textAlpha = seg(t, 0f, K.PP_TEXT_OUT_MS.toFloat(), from.textAlpha, 0f, EASE)
        out.textDy = seg(t, 0f, K.PP_TEXT_OUT_MS.toFloat(), from.textDy, -K.PP_TEXT_DY, EASE)
        out.shineOn = false
        out.shineX = K.PP_SHINE_FROM
        out.labelScale = 1f
        out.tabClosed = t >= K.PP_CLOSE_MS
    }

    private fun tabRest(open: Boolean, out: PpFrame) {
        out.slide = if (open) 1f else 0f
        out.shadowAlpha = if (open) K.PP_SHADOW_ALPHA else 0f
        out.textAlpha = if (open) 1f else 0f
        out.textDy = if (open) 0f else -K.PP_TEXT_DY
        out.shineOn = false
        out.shineX = K.PP_SHINE_FROM
        out.labelScale = 1f
        out.tabClosed = !open
    }

    private fun pill(t: Float, from: PpFrame, target: Float, out: PpFrame) {
        val dur = K.PP_TINT_MS.toFloat()
        out.tint = seg(t, 0f, dur, from.tint, target, EASE)
        out.halo = seg(t, 0f, dur, from.halo, target, EASE)
        out.pillClosed = target == 0f && t >= dur
    }

    private fun pillRest(open: Boolean, out: PpFrame) {
        out.tint = if (open) 1f else 0f
        out.halo = if (open) 1f else 0f
        out.pillClosed = !open
    }
}
