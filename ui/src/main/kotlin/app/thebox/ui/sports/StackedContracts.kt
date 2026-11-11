// Contract (Stacked commit 0): docs/design/m6-score-bug-stacked-theme.md §2.3, §6, §8. The seam between WP-2 (the
// painters: StackedPainter.kt, StackedGoalPainter.kt, NineSlice.kt) and WP-3 (the runner: StackedBug.kt). Neither WP edits
// this file; changes go through the lead.
package app.thebox.ui.sports

import app.thebox.core.sports.BarKind
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.StackedFrame
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.StackedTargets

/**
 * (Stacked §2.3, §8) What the Stacked box shows once its words are resolved: built by [StackedPainter.frame] when the
 * shown view changes (never per frame), or by hand in WP-2's screenshot tests. Sizes are design px (Standard; the painter
 * multiplies by its own scale), already fitted (§4.2 fit rules). Immutable: the runner holds the pre-goal one in its
 * `StackedHolder.lastFrame` until the takeover's lead covers the box (S1 rule 1).
 *
 * - [awayAbbrev] / [homeAbbrev], [awayScore] / [homeScore]: the rows (away is the top row, home the bottom).
 * - [awaySog] / [homeSog]: the SOG digits, "" when that side's `TeamLine.sog` is null (the column never opens then).
 * - [periodText]: the period slot ("2ND", "OT"; "" = empty), at [periodPx].
 * - [slot] / [slotText] / [slotPx]: the clock column's lower slot. [ClockSlot.Clock] draws [clockSecs] (`m:ss` /
 *   `mm:ss`) from the painter's digit table and ignores [slotText]; the others draw [slotText] ("END", "INT",
 *   "No update", "Delayed"; "" for [ClockSlot.Blank]).
 * - [clockSecs]: `ScoreBugView.clockSecs` (ruling 6), 0 when null; read only for [ClockSlot.Clock].
 * - [columnDim]: the clock column at `STACKED_DIM_ALPHA`.
 * - [targets]: the view's `StackedTargets.of(v)`, the layout this body belongs to.
 */
internal class StackedFrameState(
    val awayAbbrev: String,
    val homeAbbrev: String,
    val awayScore: String,
    val homeScore: String,
    val awaySog: String,
    val homeSog: String,
    val periodText: String,
    val periodPx: Float,
    val slot: ClockSlot,
    val slotText: String,
    val slotPx: Float,
    val clockSecs: Int,
    val columnDim: Boolean,
    val targets: StackedTargets,
)

/**
 * (Stacked §2.3 "the run owns what the bar says", PP §3.1 B2) The bar's content, captured by the runner when an open, a
 * change or a text swap starts: a close slides out the words it opened with, and an open bar's seconds update in place
 * while its key is unchanged (ruling 13). Mutable and preallocated (one per bar slot), so the runner and draw never
 * allocate.
 *
 * The runner sets [kind], [side], [secs], [period] and [dim] (from `StackedTargets.bar`, `barSecs`, `barPeriod`,
 * `barDim`), then calls [StackedPainter.fillBar], which resolves [chars] / [len] (the bar string, upper case) and the
 * colours [bg], [stripeA], [stripeB] (ARGB) from the game's `StackedPair` (§5.1: a team kind takes that team's `barBg`,
 * `barA`, `barB`; a neutral kind takes `STACKED_BAR_NEUTRAL` with stripe A = the away row stripe, B = the home row stripe).
 */
internal class BarContent {
    var kind: BarKind = BarKind.POWER_PLAY
    var side: Side? = null
    /** The power play's seconds left; [NO_SECS] = none (the label is drawn alone). */
    var secs: Int = NO_SECS
    var period: PeriodLabel? = null
    var dim: Boolean = false
    /** The resolved bar string's characters (`0 until len`); filled by [StackedPainter.fillBar], never per frame. */
    val chars: CharArray = CharArray(MAX_CHARS)
    var len: Int = 0
    var bg: Int = 0
    var stripeA: Int = 0
    var stripeB: Int = 0

    /** Copies everything from [o] (no allocation). */
    fun copyFrom(o: BarContent) {
        kind = o.kind
        side = o.side
        secs = o.secs
        period = o.period
        dim = o.dim
        o.chars.copyInto(chars, 0, 0, o.len)
        len = o.len
        bg = o.bg
        stripeA = o.stripeA
        stripeB = o.stripeB
    }

    companion object {
        /** [secs] when the feed gave no seconds. */
        const val NO_SECS = -1
        /** Longer than any bar string ("EDM POWER PLAY GOAL", "2OT INTERMISSION", "EDM POWER PLAY 19:59"). */
        const val MAX_CHARS = 48
    }
}

/**
 * (Stacked §2.3, §6) Every argument the Stacked bug's one draw hands [StackedPainter.drawFrame], filled into one
 * preallocated holder per draw (no allocation), as `NflDrawArgs` does for the NFL. A probe can read it without drawing.
 *
 * **Coordinates.** [StackedPainter.drawFrame] draws in device px with the origin at the top-left of the box's **top
 * part** (the rows' left edge, the top of the away row). The top part is `f.boxW` × `STACKED_H` design px; the bar is
 * drawn below it (y from `STACKED_H`) or, when [up], above it (y from −`f.barH` to 0); the drop shadow and the glow reach
 * outside both. The runner's bounded layer (footprint + 2 M) contains all of it.
 *
 * - [body]: what the rows and columns show (the held pre-goal body until the lead covers, S1 rule 1). Null: nothing drawn.
 * - [colours]: the game's pair (`StackedHooks.colours`); null = the fallback look.
 * - [f]: every layout track sampled for this frame (`StackedTimeline.sample`), never null while [body] is set.
 * - [bar]: the bar's current content; [barPrev]: the previous content, drawn while `f.barSwapped` is false (a change or a
 *   text swap before its swap instant) and for the background lerp (`f.barBgMix`); null outside those.
 * - [up]: the bar opens above the box (an **effective** bottom corner, from the placed box, S11).
 * - [logos] / [logoWaited] / [placing]: as the Pill's (`FollowLogos`, the disc after `LOGO_WAIT_MS`, the place ring).
 * - [shadow]: the baked drop shadow ([StackedPainter.bakeShadow]); null = not baked yet (drawn without it).
 * - [goal] / [g] / [glow] / [glowOn]: the takeover: its painter (begun), the sampled `GoalFrame` (`GoalSpec.STACKED`),
 *   the exact-box glow masks and whether the glow may draw; [g] null or `g.done` = no takeover.
 * - [still]: the still version ("Reduce animations"): the row tint reads `STACKED_STILL_ROW_TINT`, not the C7 peak.
 * - [t]: the takeover driver this draw read (0 with none); for probes only.
 */
internal class StackedDrawArgs {
    var body: StackedFrameState? = null
    var colours: StackedPair? = null
    var f: StackedFrame? = null
    var bar: BarContent? = null
    var barPrev: BarContent? = null
    var up = false
    var logos: FollowLogos? = null
    var logoWaited = false
    var placing = false
    var shadow: NineSlice? = null
    var goal: StackedGoalPainter? = null
    var g: GoalFrame? = null
    var glow: GlowMasks? = null
    var glowOn = false
    var still = false
    var t = 0f
}
