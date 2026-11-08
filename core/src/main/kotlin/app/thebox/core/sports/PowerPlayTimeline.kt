// Contract (power-play commit 0): docs/design/m6-power-play.md §3.1, §3.2. The types and the `sample` signature are fixed
// for WP-2 (the timeline body) and WP-3 (the runner); changes go through the lead. Every enum here is append-only.
package app.thebox.core.sports

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
    fun sample(track: PpTrack, kind: PpTransition, tMs: Float, from: PpFrame, to: Boolean, out: PpFrame): PpFrame =
        TODO("WP-2: power-play design §3.3")
}
