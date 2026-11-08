// Contract (NFL commit 0, docs/design/m6-nfl.md §4.3, §4.5, §5.3, §11): the signatures are fixed for WP-3 (the body)
// and WP-4 (the caller, `NflTabRun`); changes go through the lead.
package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.Side
import app.thebox.core.sports.TabLead
import app.thebox.core.sports.TabTail
import app.thebox.core.sports.TeamColours

/**
 * (NFL §4.3, §5.4) What the possession tab says, owned by the run (as [app.thebox.core.sports.PpContent]): captured when a
 * transition starts, so a CLOSE slides back the words it opened with and a pin holds its own. Mutable and preallocated,
 * so the runner and draw never allocate.
 *
 * Plain: "[side] BALL | OWN 35" ([lead], [tail]; a null [tail] draws the lead alone, centred). Pinned ([score] non-null):
 * the score label "TOUCHDOWN | KC +6" ([score], [points]) in [side]'s (the scorer's) colours.
 */
internal class NflTabContent(
    var side: Side = Side.AWAY,
    var lead: TabLead = TabLead.BALL,
    var tail: TabTail? = null,
    var score: NflScoreKind? = null,
    var points: Int = 0,
) {
    fun copyFrom(o: NflTabContent) {
        side = o.side
        lead = o.lead
        tail = o.tail
        score = o.score
        points = o.points
    }

    override fun toString(): String = "NflTabContent($side, $lead, $tail, $score, $points)"
}

/**
 * (NFL §4.3, §4.5, §5.3; WP-3) The possession tab in plain `android.graphics`: PP's geometry and track with the NFL inset
 * (37 / 46), the team's base gradient (`mixBlack .70 / .28 / .10`), the RED ZONE tail half, and the pinned pulse as a
 * second `PorterDuff.Mode.ADD` pass of the same gradient. [s] is device px per design px with Large folded in, [d]
 * without it (as [PowerPlayPainter]).
 *
 * - [bakeSize] once per size (off the main thread): the shadow, the ball sprite, the digit table.
 * - [buildLabels] off the main thread, [publishLabels] on the drawing thread; [labelsFor] says whether they match.
 * - [begin] when a side's colours are needed (a side change, the pin's scorer).
 * - [draw] per frame, allocation-free; `clipRect` only (no `clipPath`, no `saveLayer`).
 *
 * Commit 0: every body is `TODO()`; nothing calls it until WP-4's `NflTabRun`.
 */
internal class NflTabPainter(
    val s: Float,
    val d: Float,
    val pillW: Float,
    val pillH: Float,
    private val sansBold: Typeface?,
    private val sansSemi: Typeface?,
) {
    /** The baked label masks for one pair of teams (§4.3: per side "{ABBR} BALL" / "{ABBR} KICKOFF" / "{ABBR} +N", shared words). */
    class Labels(val away: String, val home: String)

    fun bakeSize(withShadow: Boolean = true): Unit = TODO("NFL WP-3: §4.3")

    fun buildLabels(away: String, home: String, res: Resources): Labels = TODO("NFL WP-3: §4.3")

    fun publishLabels(l: Labels): Unit = TODO("NFL WP-3: §4.3")

    fun labelsFor(away: String, home: String): Boolean = TODO("NFL WP-3: §4.3")

    /** The gradient for [side] from [colours] (`TeamColours.tab`, S1). */
    fun begin(side: Side, colours: TeamColours): Unit = TODO("NFL WP-3: §4.3")

    /**
     * One frame of the tab under its outside clip: [p] is the PP TAB track ([PpFrame.shineOn] and [PpFrame.labelScale]
     * are ignored), [content] the words ([NflTabContent.side]'s colours), [up] mirrors the tab above the pill (bottom
     * corners), [redZone] the RED ZONE layer's alpha 0..1 (`rzDriver`), [pulse] the pinned pulse 0..1
     * ([app.thebox.core.sports.NflScoreFrame.tabPulse]).
     */
    fun draw(c: Canvas, p: PpFrame, content: NflTabContent?, up: Boolean, redZone: Float, pulse: Float): Unit =
        TODO("NFL WP-3: §4.3, §4.5, §5.3")
}
