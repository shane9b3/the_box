// Contract (NFL Stacked commit 0): docs/design/m6-nfl-stacked.md §3.4, §8.1. The types and signatures are fixed for
// WP-2 (the body of `StackedNflTargets.of`), WP-3 (the painters read them) and WP-4 (the runner keys its tracks on them);
// changes go through the lead. Every enum here is append-only.
package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K

/**
 * (NFL Stacked §2.6, §3.4) What the NFL Stacked bar says. Team kinds ([PLAY] with a side, [PAT], [KICKOFF], [TIMEOUT],
 * [SCORE]) take that side's bar colours (`StackedColours.barBg` / `barA` / `barB`); [PLAY] with a null side is the
 * neutral bar (S-D10); [FLAG] is the fixed yellow bar (`STACKED_NFL_FLAG_*`); the break kinds ([END_PERIOD],
 * [HALFTIME], [FINAL]) and [NO_UPDATE] / [DELAYED] (only in a break, ST §8) are neutral (`STACKED_BAR_NEUTRAL`).
 * Append-only.
 *
 * Words (`strings_sports.xml`, all upper case on the bar; label | sub):
 * - [PLAY] "1ST & 10 | KC 35", "3RD | KC 35", "4TH & GOAL | RED ZONE" (sub on red), "… | 50"; no field: label alone;
 * - [PAT] "EXTRA POINT"; [KICKOFF] "LV KICKOFF | LV 35" (never RED ZONE, RC9);
 * - [FLAG] [icon] "FLAG ON THE PLAY"; [TIMEOUT] "TIMEOUT BUF | 1 LEFT";
 * - [END_PERIOD] "END OF 1ST" … "END OF OT"; [HALFTIME] "HALFTIME"; [FINAL] "FINAL" / "FINAL/OT";
 * - [NO_UPDATE] "NO UPDATE"; [DELAYED] "DELAYED";
 * - [SCORE] "TOUCHDOWN | KC +6" (the runner's pinned bar from 216 ms, and "EXTRA POINT" between a pair's takeovers);
 *   never a target of [StackedNflTargets.of].
 */
enum class NflBarKind { PLAY, PAT, KICKOFF, FLAG, TIMEOUT, END_PERIOD, HALFTIME, FINAL, NO_UPDATE, DELAYED, SCORE }

/**
 * (NFL Stacked §3.4, §4.1) The bar track's target key. Every content change (any field) is a REKEY; a clock-only update
 * never changes it. Fields not used by [kind] are null:
 * - [side]: the team whose colours the bar takes ([NflBarKind.PLAY], [NflBarKind.PAT], [NflBarKind.KICKOFF],
 *   [NflBarKind.TIMEOUT], [NflBarKind.SCORE]); null = neutral (or the flag bar).
 * - [down] / [tail]: [NflBarKind.PLAY] and [NflBarKind.KICKOFF] (`NflView.down` / `NflView.tab.tail`, the gated values:
 *   a kick-off never carries `TabTail.RedZone`, RC9). A neutral PLAY key ([side] null, C-N4) carries the raw field
 *   relative to the away side ([StackedNflTargets.neutralTail]).
 * - [left]: [NflBarKind.TIMEOUT]'s timeouts left (0..3).
 * - [period]: [NflBarKind.END_PERIOD]'s period; [finalKind]: [NflBarKind.FINAL]'s kind (REG "FINAL", OT "FINAL/OT").
 * - [score] / [points]: [NflBarKind.SCORE]'s kind and points (6, 1, 3, 2).
 */
data class NflBarKey(
    val kind: NflBarKind,
    val side: Side?,
    val down: DownText? = null,
    val tail: TabTail? = null,
    val left: Int? = null,
    val period: PeriodLabel? = null,
    val finalKind: PeriodKind? = null,
    val score: NflScoreKind? = null,
    val points: Int? = null,
)

/**
 * (NFL Stacked §3.4, §5) Everything the NFL Stacked bug's layout and words follow, as one pure function of the view
 * ([of]): the UI never re-derives a state from `ScoreBugView`. Each layout track of the runner kicks only when its own
 * key changes: CLOCK on [clockOpen], BAR on [bar] (OPEN / CLOSE / REKEY), the ball and the wide row stripe on [possSide],
 * the pips on [awayTimeouts] / [homeTimeouts] (BURN on [burnSide]).
 *
 * - [clockOpen]: the clock column is open (false = folded: halftime, end of quarter, final, and stale / waiting in a
 *   break).
 * - [bar]: the bar's key, null = closed (stale or waiting in play, no `situation`, RC5).
 * - [possSide]: the side with the ball (the possession ball and the 6.3 stripe), null = none (breaks, Final, stale).
 * - [period]: the clock column's period slot (`ScoreBugView.period`), null = empty.
 * - [clockSlot]: the clock column's lower slot while open ([ClockSlot.Clock], [ClockSlot.NoUpdate],
 *   [ClockSlot.Delayed], [ClockSlot.Blank]).
 * - [columnDim]: the clock column at `STACKED_DIM_ALPHA` (stale, waiting); [barDim]: the bar's words are
 *   ([NflBarKind.NO_UPDATE] / [NflBarKind.DELAYED]).
 * - [awayTimeouts] / [homeTimeouts]: the pips lit per side (`NflView`), null = that side's pips hidden; [timeoutSlots]:
 *   the pips drawn per side (`NflView.timeoutSlots`, ≤ 3; 0 = none drawn).
 * - [burnSide]: the side whose pip burns (a timeout mark's caller), null = none.
 *
 * Precedence (RC4): pinned score (the runner's) > stale / waiting > break / final > flag > timeout > play.
 */
data class StackedNflTargets(
    val clockOpen: Boolean,
    val bar: NflBarKey?,
    val possSide: Side?,
    val period: PeriodLabel?,
    val clockSlot: ClockSlot,
    val columnDim: Boolean,
    val barDim: Boolean,
    val awayTimeouts: Int?,
    val homeTimeouts: Int?,
    val timeoutSlots: Int,
    val burnSide: Side?,
) {
    companion object {
        /**
         * (NFL Stacked §3.4; WP-2) The whole state table: one row of §3.4 per view state, in the RC4 precedence, pure
         * and JVM-tested (`StackedNflLayoutTest`). The view's state is read from `top` (the NFL machine's `Period` /
         * `Halftime` / `EndOf` / `Final`, overwritten by `NoUpdate` / `Delayed` while stale / waiting), `nfl.down`,
         * `nfl.tab` (gated: possession, PAT, kick-off, red zone), `nfl.timeout`, `nfl.flagId` and the timeout counts.
         *
         * | View | Clock column | Bar | Ball / wide stripe |
         * |---|---|---|---|
         * | `top` `NoUpdate` / `Delayed` with the clock line (stale / waiting **in play**) | open, `NoUpdate` / `Delayed`, dimmed | **closed** (RC5) | none |
         * | the same with no clock line (stale / waiting in a break or at Final, ST C N10) | folded | `NO_UPDATE` / `DELAYED`, neutral, words dimmed | none |
         * | `Final(kind)` | folded | `FINAL` (`finalKind`) | none |
         * | `Halftime` | folded | `HALFTIME` | none |
         * | `EndOf(period)` | folded | `END_PERIOD` (`period`) | none |
         * | in play, `flagId` set | open | `FLAG` (no side) | the tab's side |
         * | in play, `timeout` set | open | `TIMEOUT` (the caller, `left`); [burnSide] the caller | the tab's side |
         * | in play, `down` a `Down` | open | `PLAY` (the tab's side, null = neutral S-D10; `down`, `tab.tail`) | the tab's side |
         * | in play, `down` `Pat` | open | `PAT` (the tab's side) | the tab's side |
         * | in play, `down` `Kickoff` | open | `KICKOFF` (the kicker; `tab.tail`, never `RedZone`: RC9) | the tab's side |
         * | in play, no `down` (no `situation`, or not shown by the machine) | open | **closed** (R21) | the tab's side, if any |
         *
         * The clock slot in play is `Clock` when the view has `clockSecs`, else `Blank`; a folded column's slot is
         * `Blank`. [columnDim] is `v.dim` in every row; [barDim] exactly for `NO_UPDATE` / `DELAYED`. The pips: the
         * view's counts, clamped to `0..timeoutSlots`; hidden (null) when the view has no slots ([timeoutSlots] 0).
         * The pinned score (the runner's) is above every row and is never a target.
         */
        fun of(v: ScoreBugView): StackedNflTargets {
            val n = v.nfl
            val slots = (n?.timeoutSlots ?: 0).coerceIn(0, K.NFL_TIMEOUTS_MAX)
            val away = if (slots > 0) n?.awayTimeouts?.coerceIn(0, slots) else null
            val home = if (slots > 0) n?.homeTimeouts?.coerceIn(0, slots) else null
            // The NFL machine's in-play view has the clock line under the top (`bottom == Clock`); breaks and Final are one line.
            val inPlay = v.bottom == CentreText.Clock
            val dimKind: NflBarKind? = when (v.top) {
                CentreText.NoUpdate -> NflBarKind.NO_UPDATE
                CentreText.Delayed -> NflBarKind.DELAYED
                else -> null
            }

            fun folded(bar: NflBarKey) = StackedNflTargets(
                clockOpen = false, bar = bar, possSide = null, period = v.period, clockSlot = ClockSlot.Blank,
                columnDim = v.dim, barDim = bar.kind == NflBarKind.NO_UPDATE || bar.kind == NflBarKind.DELAYED,
                awayTimeouts = away, homeTimeouts = home, timeoutSlots = slots, burnSide = null,
            )

            fun open(bar: NflBarKey?, poss: Side?, slot: ClockSlot, burn: Side?) = StackedNflTargets(
                clockOpen = true, bar = bar, possSide = poss, period = v.period, clockSlot = slot, columnDim = v.dim,
                barDim = false, awayTimeouts = away, homeTimeouts = home, timeoutSlots = slots, burnSide = burn,
            )

            // 1. Stale / waiting (RC4: above the break, the flag, the timeout and the play).
            if (dimKind != null) {
                if (!inPlay) return folded(NflBarKey(dimKind, null))
                val slot = if (dimKind == NflBarKind.NO_UPDATE) ClockSlot.NoUpdate else ClockSlot.Delayed
                return open(bar = null, poss = null, slot = slot, burn = null)
            }
            // 2. Breaks and Final.
            when (val top = v.top) {
                is CentreText.Final -> return folded(NflBarKey(NflBarKind.FINAL, null, finalKind = top.kind))
                CentreText.Halftime -> return folded(NflBarKey(NflBarKind.HALFTIME, null))
                is CentreText.EndOf -> return folded(NflBarKey(NflBarKind.END_PERIOD, null, period = top.period))
                else -> Unit
            }
            // 3. In play: flag > timeout > play.
            val slot = if (v.clockSecs != null) ClockSlot.Clock else ClockSlot.Blank
            val tab = n?.tab
            val poss = tab?.side
            if (n?.flagId != null) return open(NflBarKey(NflBarKind.FLAG, null), poss, slot, burn = null)
            val mark = n?.timeout
            if (mark != null) {
                val left = mark.left.coerceIn(0, K.NFL_TIMEOUTS_MAX)
                return open(NflBarKey(NflBarKind.TIMEOUT, mark.side, left = left), poss, slot, burn = mark.side)
            }
            // C-S1: a dead ball after a field goal or a safety holds the bar shown with the held tab (§3.4 "the held bar").
            val shownDown = n?.down ?: n?.heldDown?.takeIf { tab != null }
            val bar = when (val d = shownDown) {
                null -> null
                // C-N4: the neutral bar keeps the field; its tail is relative to the away side (see [NflBarKey]).
                is DownText.Down -> NflBarKey(
                    NflBarKind.PLAY, poss, down = d,
                    tail = if (poss != null) tab?.tail else n?.field?.let { neutralTail(it) },
                )
                DownText.Pat -> NflBarKey(NflBarKind.PAT, poss)
                // RC9: a kick-off never reads RED ZONE, whatever the tab's tail says.
                DownText.Kickoff -> NflBarKey(NflBarKind.KICKOFF, poss, down = d, tail = tab?.tail?.takeUnless { it == TabTail.RedZone })
            }
            return open(bar, poss, slot, burn = null)
        }

        /**
         * (C-N4) A neutral [NflBarKind.PLAY] key's tail: the raw field relative to the **away** side (OWN = the away
         * team's half, OPP = the home team's, midfield 50), so the painter can name the yard line with no possession.
         */
        fun neutralTail(f: FieldPos): TabTail = when (f.territory) {
            null -> TabTail.Field(FieldZone.MIDFIELD, 50)
            Side.AWAY -> TabTail.Field(FieldZone.OWN, f.yard)
            Side.HOME -> TabTail.Field(FieldZone.OPP, f.yard)
        }
    }
}

/**
 * (NFL Stacked §2.3–§2.5, §5.2–§5.4; WP-2) The NFL Stacked box's geometry and fit rules, pure and allocation-free (the
 * fits allocate only their result, once per `begin()` / bake, never per frame). Everything is in **Standard design px**
 * unless a function takes a scale `k` (the painter's device px per Standard px, × `SCORE_LARGE_SCALE` for Large); a
 * Large box is the Standard one × 1.25 exactly.
 *
 * Coordinates:
 * - **row-local**: x from the left edge of the 243-wide rows, as drawn in a left corner (§5.2);
 * - **box-local**: x from the left edge of the drawn top part, `boxW` = `StackedNflFrame.boxW` wide (333 in play, 243 in
 *   a break; the takeover's pinned width);
 * - **bar-local**: x from the bar's left edge; the bar is the box's width.
 *
 * **The mirror (§5.3, the 2026-11-13 rules).** A right-hand corner draws every *position* reflected, never the canvas:
 * row positions about the rows ([rowX]: x ↦ 243 − x − w), columns, bar and takeover about the box ([boxX]:
 * x ↦ boxW − x − w); horizontal offsets and slides negated ([dx]); rotations negated ([deg]); skews ("/" leans) kept;
 * glyphs, bitmaps and sprites never flipped (no function here returns a negative x-scale) and words always read left to
 * right ([barGroup] lays the group out in reading order on both sides, then right-aligns it).
 */
object StackedNflGeometry {
    // ------------------------------------------------------------------ the mirror

    /** A row element at row-local [x], [w] wide: its left edge as drawn ([mirrored]: reflected about the rows). */
    fun rowX(x: Float, w: Float, mirrored: Boolean): Float = if (mirrored) K.STACKED_ROWS_W - x - w else x

    /** A box element at box-local [x], [w] wide, in a [boxW]-wide box: its left edge as drawn ([mirrored]: reflected). */
    fun boxX(x: Float, w: Float, boxW: Float, mirrored: Boolean): Float = if (mirrored) boxW - x - w else x

    /** A horizontal offset or slide ([mirrored]: negated, so it starts on the same side relative to the screen edge). */
    fun dx(d: Float, mirrored: Boolean): Float = if (mirrored) -d else d

    /** A rotation ([mirrored]: negated: the possession ball's pop-in, the badge's spin, the ball's run and arc). */
    fun deg(d: Float, mirrored: Boolean): Float = if (mirrored) -d else d

    /** The rows' left edge in the box (left corners 0; mirrored the rows hug the box's right edge, `boxW − 243`). */
    fun rowsLeft(boxW: Float, mirrored: Boolean): Float = if (mirrored) boxW - K.STACKED_ROWS_W else 0f

    /** The clock column's left edge in the box, [clockW] = `clockWidth × STACKED_CLOCK_W` (left: after the rows; mirrored: outermost left). */
    fun clockX(boxW: Float, clockW: Float, mirrored: Boolean): Float = boxX(K.STACKED_ROWS_W, clockW, boxW, mirrored)

    /**
     * The clock column's contents' 90-wide stack, anchored on the rows' side and clipped by the column while it folds
     * (the CSS's `left: 0; width: 200px` inside the `overflow: hidden` column): left corners at 243, mirrored at
     * `boxW − 243 − 90`.
     */
    fun clockContentX(boxW: Float, mirrored: Boolean): Float = boxX(K.STACKED_ROWS_W, K.STACKED_CLOCK_W, boxW, mirrored)

    // ------------------------------------------------------------------ the row (§2.3, §5.2; row-local)

    /** The row stripe's width: [wide] 0 = 3.6, 1 = 6.3 (the possession side; `StackedNflFrame.stripeAway` / `stripeHome`). */
    fun stripeW(wide: Float): Float = K.STACKED_NFL_STRIPE_W + (K.STACKED_NFL_POSS_STRIPE_W - K.STACKED_NFL_STRIPE_W) * wide

    /** The logo slot's left edge (54 wide; the logo fits inside 50.4 × 43.2, centred). */
    fun logoX(stripeW: Float): Float = stripeW + K.STACKED_NFL_LOGO_ML

    /** The team code's start (stripe + 74.7: 78.3, or 81.0 with the possession stripe). */
    fun codeX(stripeW: Float): Float = stripeW + K.STACKED_NFL_CODE_DX

    /** The possession ball's left edge: 5.4 after the code's advance [codeW]. */
    fun ballX(codeX: Float, codeW: Float): Float = codeX + codeW + K.STACKED_NFL_BALL_GAP

    /** The ball's top in the row: centred on the code's line box (top `STACKED_NFL_CODE_TOP`, `STACKED_NFL_CODE_PX` tall). */
    val BALL_TOP: Float = K.STACKED_NFL_CODE_TOP + K.STACKED_NFL_CODE_PX / 2f - K.STACKED_NFL_BALL / 2f

    /** Timeout pip [i]'s left edge (index 0 nearest the logo, so the lit pips are on the screen-edge side both ways). */
    fun pipX(codeX: Float, i: Int): Float = codeX + i * (K.STACKED_NFL_PIP_W + K.STACKED_NFL_PIP_GAP)

    /** The score slot: 183.6 – 228.6, right-aligned at [SCORE_RIGHT] (mirrored: left-aligned 14.4 from the rows' left). */
    const val SCORE_X: Float = K.STACKED_ROWS_W - K.STACKED_NFL_SCORE_MR - K.STACKED_NFL_SCORE_W
    const val SCORE_RIGHT: Float = K.STACKED_ROWS_W - K.STACKED_NFL_SCORE_MR

    // ------------------------------------------------------------------ the bar (§2.4, §5.3; bar-local)

    /** Stripe A's / B's left edge at rest (20.7 / 37.8, 11.7 wide, skewed −30°; the travel `StackedNflFrame.stripeAX` adds). */
    const val BAR_STRIPE_A_X: Float = K.STACKED_NFL_BAR_STRIPE_A_X
    const val BAR_STRIPE_B_X: Float = K.STACKED_NFL_BAR_STRIPE_B_X

    /** A bar stripe's left edge as drawn: rest [x] plus the travel [travelDx] (−63 → 0); mirrored, from the bar's right end. */
    fun barStripeX(x: Float, travelDx: Float, barW: Float, mirrored: Boolean): Float =
        boxX(x, K.STACKED_NFL_BAR_STRIPE_W, barW, mirrored) + dx(travelDx, mirrored)

    /** The width of the bar's words group: [icon] + gap, the label, and (when [subW] > 0) gap + divider + gap + the sub. */
    fun barGroupW(icon: Boolean, labelW: Float, subW: Float): Float {
        var w = labelW
        if (icon) w += K.STACKED_NFL_FLAG_ICON_W + K.STACKED_NFL_BAR_TEXT_GAP
        if (subW > 0f) w += 2 * K.STACKED_NFL_BAR_TEXT_GAP + K.STACKED_NFL_BAR_DIVIDER_W + subW
        return w
    }

    /** [barGroup]'s output slots. */
    const val G_ICON = 0
    const val G_LABEL = 1
    const val G_DIVIDER = 2
    const val G_SUB = 3
    const val G_WIDTH = 4
    const val G_RED_FROM = 5
    const val G_RED_TO = 6
    const val G_SIZE = 7

    /**
     * (§2.4, §5.3) The bar's words group in a [barW]-wide bar, in reading order on both sides: writes the left edges of
     * the icon, the label, the divider and the sub, the group's width, and the red-zone patch (from the divider, under
     * the sub part on both sides: to the bar's end in a left corner, one text gap past the sub when mirrored so the
     * stripes there keep the team's bar; integration ruling 4) into [out] (size [G_SIZE]; unused slots NaN). Left
     * corners: the group starts at `STACKED_NFL_BAR_TEXT_X` (63); mirrored: it is right-aligned 63 from the bar's right
     * end. The REKEY slide (`StackedNflFrame.barTextDx`) and the flag shake are added by the painter through [dx].
     */
    fun barGroup(barW: Float, icon: Boolean, labelW: Float, subW: Float, mirrored: Boolean, out: FloatArray): FloatArray {
        val gap = K.STACKED_NFL_BAR_TEXT_GAP
        val w = barGroupW(icon, labelW, subW)
        val x0 = if (mirrored) barW - K.STACKED_NFL_BAR_TEXT_X - w else K.STACKED_NFL_BAR_TEXT_X
        var x = x0
        if (icon) { out[G_ICON] = x; x += K.STACKED_NFL_FLAG_ICON_W + gap } else out[G_ICON] = Float.NaN
        out[G_LABEL] = x
        x += labelW
        if (subW > 0f) {
            out[G_DIVIDER] = x + gap
            out[G_SUB] = x + 2 * gap + K.STACKED_NFL_BAR_DIVIDER_W
            out[G_RED_FROM] = out[G_DIVIDER]
            // (Integration ruling 4) Left corners: to the bar's end. Mirrored, the stripes sit at that end: the patch
            // stops one text gap after the sub (the divider's gap, reflected), so the stripes keep the team's bar
            // under them as in a left corner (a red stripe A on the red patch vanished: BUF, ATL, ARI, TB ...).
            out[G_RED_TO] = if (mirrored) out[G_SUB] + subW + gap else barW
        } else {
            out[G_DIVIDER] = Float.NaN; out[G_SUB] = Float.NaN; out[G_RED_FROM] = Float.NaN; out[G_RED_TO] = Float.NaN
        }
        out[G_WIDTH] = w
        return out
    }

    /** The room the words have in a [barW]-wide bar: `barW − 63 − STACKED_NFL_BAR_OVERHANG` (the italic's overhang). */
    fun barRoom(barW: Float): Float = barW - K.STACKED_NFL_BAR_TEXT_X - K.STACKED_NFL_BAR_OVERHANG

    // ------------------------------------------------------------------ the fit rules (§2.5, §5.2, §5.4)

    /**
     * (§5.4) The team code's size: `STACKED_NFL_CODE_PX` (25.2), stepped down by `STACKED_FIT_STEP_PX` (2) to
     * `STACKED_FIT_MIN_PX` (18) until the code, the ball gap, the ball and `STACKED_NFL_CODE_SCORE_GAP_MIN` (12) fit before
     * the score slot with the possession stripe (the widest start, 81.0); never ellipsised. [width] measures the code at a
     * px size in the same units ([k] device px per Standard px). Null = it does not fit even at 18 (a lead escalation).
     */
    fun codePx(k: Float, width: (Float) -> Float): Float? {
        val room = (SCORE_X - K.STACKED_NFL_CODE_SCORE_GAP_MIN - K.STACKED_NFL_BALL_GAP - K.STACKED_NFL_BALL - codeX(K.STACKED_NFL_POSS_STRIPE_W)) * k
        var px = K.STACKED_NFL_CODE_PX
        while (true) {
            if (width(px * k) <= room) return px * k
            if (px <= K.STACKED_FIT_MIN_PX) return null
            px = maxOf(K.STACKED_FIT_MIN_PX, px - K.STACKED_FIT_STEP_PX)
        }
    }

    /**
     * (§5.4) A score's size: `STACKED_NFL_SCORE_PX` (35.1) while it fits the 45-wide slot ("Scores 0–99 at 35.1 in 45";
     * every two-digit score does); otherwise (three digits) scaled to fit `STACKED_NFL_SCORE_FIT_W` (43), floor
     * `STACKED_NFL_SCORE_FLOOR_PX` (24; "100" → 24.5). [width] measures the score at a px size ([k] device px per
     * Standard px); the result is device px.
     */
    fun scorePx(k: Float, width: (Float) -> Float): Float {
        val px = K.STACKED_NFL_SCORE_PX * k
        val w = width(px)
        val fit = K.STACKED_NFL_SCORE_FIT_W * k
        if (w <= K.STACKED_NFL_SCORE_W * k) return px
        return maxOf(K.STACKED_NFL_SCORE_FLOOR_PX * k, px * fit / w)
    }

    /** The takeover word's natural size per kind (§2.5: TD 38.7, XP 33.3, FG 36, SAFETY 38.7, TWO-POINT 36). */
    fun wordPx(kind: NflScoreKind): Float = when (kind) {
        NflScoreKind.TOUCHDOWN -> K.STACKED_NFL_WORD_TD_PX
        NflScoreKind.EXTRA_POINT -> K.STACKED_NFL_WORD_XP_PX
        NflScoreKind.FIELD_GOAL -> K.STACKED_NFL_WORD_FG_PX
        NflScoreKind.SAFETY -> K.STACKED_NFL_WORD_SAFETY_PX
        NflScoreKind.TWO_POINT -> K.STACKED_NFL_WORD_2PT_PX
    }

    /**
     * (§2.5) The takeover's fitted layout, in device px: [pad] each side, the word's [wordPx], whether the [tally] (the
     * block is "+N" over the tally, else "+N" alone) and the [badge] are drawn; [wordFits] false only when even the last
     * step leaves the word under the floor (then it is drawn at the floor and clipped by the panel: a lead escalation;
     * no shipped word reaches it).
     */
    data class TakeoverFit(val pad: Float, val wordPx: Float, val tally: Boolean, val badge: Boolean, val wordFits: Boolean)

    /**
     * (§2.5) The takeover fit against the pinned box width [boxW] (device px), at [k] device px per Standard px:
     * 0. the word at its natural size ([natPx], device px) with the 16.2 padding;
     * 1. the padding drops to `STACKED_TAKEOVER_PAD_TIGHT` (8);
     * 2. the word scales down to fit, floor `STACKED_NFL_WORD_FLOOR_PX` (28);
     * 3. still under the floor: the tally is dropped (the block is [pointsW], "+N" alone);
     * 4. still under the floor (only a takeover pinned at the 243 break width): the badge is dropped (and its gap).
     * [wordW] measures the word at a device px size; [blockW] is the block's width with the tally (the "88 – 88" slot or
     * "+N", whichever is wider), [pointsW] without it. The word is never scaled up past [natPx].
     */
    fun fitTakeover(boxW: Float, k: Float, natPx: Float, wordW: (Float) -> Float, blockW: Float, pointsW: Float): TakeoverFit {
        val gap = K.STACKED_NFL_TAKEOVER_GAP * k
        val badge = K.STACKED_NFL_BADGE * k
        val floor = K.STACKED_NFL_WORD_FLOOR_PX * k
        val natW = wordW(natPx)
        fun room(pad: Float, block: Float, withBadge: Boolean): Float =
            boxW - 2 * pad - block - (if (withBadge) badge + 2 * gap else gap)
        // 0. as drawn by the mock.
        val padWide = K.STACKED_NFL_TAKEOVER_PAD * k
        if (natW <= room(padWide, blockW, true)) return TakeoverFit(padWide, natPx, tally = true, badge = true, wordFits = true)
        val pad = K.STACKED_TAKEOVER_PAD_TIGHT * k
        // 1 – 4: tight padding, then the word scaled toward the floor at each step.
        for (step in 0..2) {
            val tally = step == 0
            val withBadge = step < 2
            val r = room(pad, if (tally) blockW else pointsW, withBadge)
            val px = if (natW <= r) natPx else natPx * r / natW
            if (px >= floor) return TakeoverFit(pad, px, tally, withBadge, wordFits = true)
        }
        return TakeoverFit(pad, floor, tally = false, badge = false, wordFits = false)
    }

    /**
     * (§2.5, §5.3) The takeover's three items' left edges in a [boxW]-wide box (device px): the badge at the padding,
     * the block right-aligned at the far padding, the word centred in the space between (the mock's `flex: 1` centre
     * column); mirrored, each reflected about the box ([boxX]: badge on the right, block on the left). Writes
     * [T_BADGE], [T_WORD], [T_BLOCK] into [out] (size [T_SIZE]); a dropped badge is NaN and its space goes to the word.
     */
    fun takeover(boxW: Float, k: Float, fit: TakeoverFit, wordW: Float, blockW: Float, mirrored: Boolean, out: FloatArray): FloatArray {
        val gap = K.STACKED_NFL_TAKEOVER_GAP * k
        val badge = K.STACKED_NFL_BADGE * k
        val left = fit.pad + if (fit.badge) badge + gap else 0f
        val blockX = boxW - fit.pad - blockW
        val right = blockX - gap
        val wordX = left + (right - left - wordW) / 2f
        out[T_BADGE] = if (fit.badge) boxX(fit.pad, badge, boxW, mirrored) else Float.NaN
        out[T_WORD] = boxX(wordX, wordW, boxW, mirrored)
        out[T_BLOCK] = boxX(blockX, blockW, boxW, mirrored)
        return out
    }

    const val T_BADGE = 0
    const val T_WORD = 1
    const val T_BLOCK = 2
    const val T_SIZE = 3

    /** (§4.3 "×k") The arc's and the posts' x scale for a takeover pinned at [boxW] Standard px: `boxW / 333`. */
    fun arcK(boxW: Float): Float = boxW / K.STACKED_NFL_W_MAX

    /**
     * (Review fix U-B1) How far the goalposts move left (≤ 0, device px, unmirrored) so their ink ends one
     * `STACKED_NFL_TAKEOVER_GAP` before the "+N" / tally block: [blockLeft] the block's left edge, [postsX] the posts'
     * left edge as placed by the spec (`postsX × arcK × k`), [postsW] their drawn width, all device px at [k] device px
     * per Standard px. The mock keeps its posts behind the end of the word, never on the score; the tally here is wider
     * than the mock's, so the mock's x would put them on it. 0 when they already clear it.
     */
    fun postsShift(blockLeft: Float, postsX: Float, postsW: Float, k: Float): Float {
        val inkRight = postsX + postsW * NflShapes.POSTS_INK_RIGHT / NflShapes.POSTS_VIEW_W
        return minOf(0f, blockLeft - K.STACKED_NFL_TAKEOVER_GAP * k - inkRight)
    }

    /**
     * (U-B1) The arc's x bent by the posts' [shift]: 0 at the arc's start ([p0x]) growing linearly to the full shift at
     * its end ([p2x]), so the ball still drops between the moved uprights. Device px, unmirrored.
     */
    fun bendArc(x: Float, shift: Float, p0x: Float, p2x: Float): Float =
        if (shift == 0f || p2x == p0x) x else x + shift * ((x - p0x) / (p2x - p0x)).coerceIn(0f, 1f)

    /** (§5.3) A takeover sprite at box-local [x] (the frame's `ballX`, the posts' `postsX × k`), [w] wide, as drawn. */
    fun spriteX(x: Float, w: Float, boxW: Float, mirrored: Boolean): Float = boxX(x, w, boxW, mirrored)

    /** (§5.3) The wipes' clip edges ([from], [to] fractions of the box) as drawn: mirrored they start at the right. */
    fun wipeFrom(from: Float, to: Float, mirrored: Boolean): Float = if (mirrored) 1f - to else from
    fun wipeTo(from: Float, to: Float, mirrored: Boolean): Float = if (mirrored) 1f - from else to

    /** (§2.3.1, S-D12) A pip's height in real px on a [screenW]-px-wide screen at [k] (Standard 1, Large 1.25). */
    fun pipRealPx(screenW: Float, k: Float): Float = K.STACKED_NFL_PIP_H * k * screenW / DESIGN_W

    private const val DESIGN_W = 1920f
}
