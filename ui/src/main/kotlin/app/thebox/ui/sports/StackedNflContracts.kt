// Contract (NFL Stacked commit 0): docs/design/m6-nfl-stacked.md §8.1. The seam between WP-3 (the painters:
// StackedNflPainter.kt, StackedNflScorePainter.kt) and WP-4 (the runner: StackedNflBug.kt); extended at integration
// (design doc "Integration rulings (2026-11-15)"). Changes go through the lead.
package app.thebox.ui.sports

import android.graphics.Bitmap
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.Side
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedPair

/** Debug composition counter of the NFL Stacked bug (one per snapshot, none during a takeover); never another bug's. */
const val COMPOSITIONS_STACKED_NFL_BUG = "StackedNflBug"
/** Debug counter: builds of the NFL Stacked bug's resolved words ([StackedNflFrameState]); one per change of what is shown. */
const val FRAMES_STACKED_NFL_BUG = "StackedNflBugFrame"
/** Debug counter: draws of the NFL Stacked bug's one drawing node (the instrumented `StackedNflFramesTest` arms on its bursts). */
const val DRAWS_STACKED_NFL_BUG = "StackedNflBugDraw"
/** The test tag of the NFL Stacked bug's bounded layer (footprint + 2 M, §4.5). */
const val STACKED_NFL_LAYER_TAG = "score_bug_stacked_nfl_layer"

/**
 * (NFL Stacked §3.4, §8.1) What the NFL Stacked box shows once its words are resolved: built by
 * [StackedNflPainter.frame] when the shown view changes (never per frame), or by hand in WP-3's screenshot tests. Sizes
 * are design px (Standard; the painter multiplies by its own scale), already fitted (§2.4, §5.4). Immutable: the runner
 * holds the pre-score one until the takeover's lead covers the box (ST S1 rule 1).
 *
 * - [awayAbbrev] / [homeAbbrev], [awayScore] / [homeScore]: the rows (away is the top row, home the bottom).
 * - [periodText]: the clock column's period slot ("4TH", "OT", "2OT"; "" = empty), at [periodPx].
 * - [slot] / [slotText] / [slotPx]: the clock column's lower slot. [ClockSlot.Clock] draws [clockSecs] (`m:ss` /
 *   `mm:ss`) from the painter's digit table and ignores [slotText]; [ClockSlot.NoUpdate] / [ClockSlot.Delayed] draw
 *   [slotText] ("No update", "Delayed"); [ClockSlot.Blank] draws nothing.
 * - [clockSecs]: `ScoreBugView.clockSecs` (ruling 6), 0 when null; read only for [ClockSlot.Clock].
 * - [columnDim]: the clock column at `STACKED_DIM_ALPHA`.
 * - [targets]: the view's `StackedNflTargets.of(v)`, the layout this body belongs to (the pips' counts and slots, the
 *   possession side, the bar key).
 * - [slotBreak] / [slotDy1] / [slotDy2]: a two-line [slotText] ("No" / "update"): the space it breaks at (−1 = one line)
 *   and the two baselines relative to the lower slot's centre (design px), as `StackedFrameState`.
 */
internal class StackedNflFrameState(
    val awayAbbrev: String,
    val homeAbbrev: String,
    val awayScore: String,
    val homeScore: String,
    val periodText: String,
    val periodPx: Float,
    val slot: ClockSlot,
    val slotText: String,
    val slotPx: Float,
    val clockSecs: Int,
    val columnDim: Boolean,
    val targets: StackedNflTargets,
    val slotBreak: Int = -1,
    val slotDy1: Float = 0f,
    val slotDy2: Float = 0f,
)

/**
 * (NFL Stacked §2.4, §2.6, §4.1) The bar's content, captured by the runner when an OPEN or a REKEY starts (a CLOSE slides
 * out the words it opened with). Mutable and preallocated (one per bar slot), so the runner and draw never allocate.
 *
 * The runner sets [key] (`StackedNflTargets.bar`, or its pinned `NflBarKind.SCORE` key) and [dim]
 * (`StackedNflTargets.barDim`), then calls [StackedNflPainter.fillBar], which resolves the words, upper case, into
 * [label] / [labelLen] (before the divider: "1ST & 10", "TIMEOUT BUF", "FLAG ON THE PLAY") and [sub] / [subLen] (after
 * it: "KC 35", "1 LEFT", "RED ZONE"; 0 = no divider, label alone), and sets [redZone] (the sub part on the red patch),
 * [flag] (the yellow flag bar: the icon before the words, the shake and wave), the colours [bg], [a], [b] (stripes) and
 * [fg] (the words, the divider), ARGB, from the game's `StackedPair` (§2.6), and [wordsAdvance] (device px, the whole
 * words with the icon and divider; a mirrored bar right-aligns by it).
 */
internal class NflBarContent {
    var key: NflBarKey? = null
    val label: CharArray = CharArray(MAX)
    var labelLen: Int = 0
    val sub: CharArray = CharArray(MAX)
    var subLen: Int = 0
    var redZone: Boolean = false
    var flag: Boolean = false
    var dim: Boolean = false
    var bg: Int = 0
    var a: Int = 0
    var b: Int = 0
    var fg: Int = 0
    /** Set by [StackedNflPainter.fillBar]: the drawn words' advance in device px (icon, label, divider, sub). */
    var wordsAdvance: Float = 0f
    /**
     * (Review fix U-N1) Set by [StackedNflPainter.fillBar]: the label's and the sub's measured widths, device px, so the
     * draw path never measures text; −1 = not measured (a hand-filled content), measured on use.
     */
    var labelW: Float = -1f
    var subW: Float = -1f

    /** Back to empty (no key, no words, colours 0). */
    fun clear() {
        key = null
        labelLen = 0
        subLen = 0
        redZone = false
        flag = false
        dim = false
        bg = 0
        a = 0
        b = 0
        fg = 0
        wordsAdvance = 0f
        labelW = -1f
        subW = -1f
    }

    /** Copies everything from [o] (no allocation). */
    fun copyFrom(o: NflBarContent) {
        key = o.key
        o.label.copyInto(label, 0, 0, o.labelLen)
        labelLen = o.labelLen
        o.sub.copyInto(sub, 0, 0, o.subLen)
        subLen = o.subLen
        redZone = o.redZone
        flag = o.flag
        dim = o.dim
        bg = o.bg
        a = o.a
        b = o.b
        fg = o.fg
        wordsAdvance = o.wordsAdvance
        labelW = o.labelW
        subW = o.subW
    }

    companion object {
        /** Longer than any bar part ("FLAG ON THE PLAY", "TOUCHDOWN", "WSH KICKOFF", "END OF 2OT"). */
        const val MAX = 48
    }
}

/**
 * (NFL Stacked §4.5, §5) Every argument the NFL Stacked bug's one draw hands [StackedNflPainter.drawFrame], filled into
 * one preallocated holder per draw (no allocation), as `StackedDrawArgs` does for hockey. A probe can read it without
 * drawing.
 *
 * **Coordinates.** As `StackedDrawArgs`: device px, origin at the top-left of the box's **top part** at the rows' left
 * edge; when [mirrored] (a right-hand corner, §5.3) the rows hug the screen edge and the clock column opens to their
 * left (negative x). The bar is drawn below the top part (y from `STACKED_H`) or, when [up], above it (y from
 * −`frame.barH` to 0). The runner's bounded layer (footprint + 2 M) contains all of it.
 *
 * - [frame]: every layout track sampled for this frame (`StackedNflTimeline`); never null while [state] is set.
 * - [state]: what the rows and the clock column show; null = nothing drawn.
 * - [bar] / [barPrev]: the bar's current content and the previous one (drawn under a REKEY's background lerp,
 *   `frame.barBgMix`); null outside those.
 * - [colours]: the game's pair (`StackedNflHooks.colours`); null = the fallback look.
 * - [awayLogo] / [homeLogo]: the row logos (null = the `LogoDisc` fallback).
 * - [mirrored], [up]: from the placed footprint (`ScoreBugPlacement.stackedMirrored` / `stackedBarUp`).
 * - [still]: the still version ("Reduce animations").
 * - [score] / [scoreSide] / [takeoverOn]: the takeover: the sampled frame (`NflScoreSpec.STACKED`), the scorer, and
 *   whether a takeover is drawn this frame.
 *
 * (Integration rulings 2026-11-15, as `StackedDrawArgs`) The takeover is drawn **inside** [StackedNflPainter.drawFrame]
 * in the hockey order (glow, shadow, box, bar, rows, clock column, takeover, border on top), from:
 * - [takeover]: the score painter `begin()` ran on (null = no takeover drawn); [glow] / [glowOn]: its exact-box masks
 *   and whether the glow may draw (GA N4 latch);
 * - [shadow]: the nine-slice the **drawing** painter baked (`StackedNflPainter.bakeShadow`), null until it lands;
 * - [awayLogoPending] / [homeLogoPending]: a followed team's logo is still loading and `LOGO_WAIT_MS` has not passed:
 *   the slot stays empty (no disc flash before the logo), as the NHL Stacked bug's `logoWaited`.
 */
internal class StackedNflDrawArgs {
    var frame: StackedNflFrame? = null
    var state: StackedNflFrameState? = null
    var bar: NflBarContent? = null
    var barPrev: NflBarContent? = null
    var colours: StackedPair? = null
    var awayLogo: Bitmap? = null
    var homeLogo: Bitmap? = null
    var awayLogoPending = false
    var homeLogoPending = false
    var mirrored = false
    var up = false
    var still = false
    var shadow: NineSlice? = null
    var score: NflScoreFrame? = null
    var scoreSide: Side? = null
    var takeoverOn = false
    var takeover: StackedNflScorePainter? = null
    var glow: GlowMasks? = null
    var glowOn = false
}
