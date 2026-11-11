// Contract (Stacked commit 0): docs/design/m6-score-bug-stacked-theme.md §2.4, §8. The types and signatures are fixed for
// WP-1 (the bodies of `of` and `sameLook`), WP-2 (the painters read them) and WP-3 (the runner keys its tracks on them);
// changes go through the lead. Every enum here is append-only.
package app.thebox.core.sports

/**
 * (Stacked §2.4, §8) What the Stacked bar says. Team kinds ([POWER_PLAY], [TWO_MAN], [PP_GOAL]) carry a side and the
 * team's bar colours; the others are neutral (`STACKED_BAR_NEUTRAL`, side null). [NO_UPDATE] and [DELAYED] are used only
 * when a break bar ([INTERMISSION], [SHOOTOUT]) is already the shown bar (§8: stale / waiting in a break). Append-only.
 *
 * Words (`strings_sports.xml`, all upper case on the bar):
 * - [POWER_PLAY] "EDM POWER PLAY 1:37" (`sports_pp_label` + the time when known); [TWO_MAN] "EDM 5-ON-3 1:37"
 *   (`sports_pp_two_man`); [PP_GOAL] "EDM POWER PLAY GOAL" (`sports_stacked_pp_goal`, ruling 15);
 * - [INTERMISSION] "1ST INTERMISSION" / "OT INTERMISSION" (`sports_stacked_intermission`); [SHOOTOUT] "SHOOTOUT";
 * - [FINAL] "FINAL" / "FINAL/OT" / "FINAL/SO" (`sports_final*` upper-cased); [NO_UPDATE] "NO UPDATE"; [DELAYED] "DELAYED".
 */
enum class BarKind { POWER_PLAY, TWO_MAN, PP_GOAL, INTERMISSION, SHOOTOUT, FINAL, NO_UPDATE, DELAYED }

/** (Stacked §2.4) The bar track's target key: [side] is the team for the team kinds, null for the neutral kinds. */
data class BarKey(val kind: BarKind, val side: Side?)

/**
 * (Stacked §2.4, §8) What the clock column's lower slot shows while the column is open (the period slot above it shows
 * [StackedTargets.period]).
 */
sealed interface ClockSlot {
    /** The clock digits: `ScoreBugView.clockSecs` (`m:ss` / `mm:ss`), the snapshot's own value (ruling 6). */
    data object Clock : ClockSlot
    /** "END": the clock reached 0:00 and the intermission has not started (§8 end of period, owner Q5 default). */
    data object End : ClockSlot
    /** "INT": a power play carried into the intermission keeps the column open, with no clock (B3, ruling 33). */
    data object Intermission : ClockSlot
    /** "No update" at [StackedTargets.columnDim] (stale, in play or in a PP carried into INT). */
    data object NoUpdate : ClockSlot
    /** "Delayed" at [StackedTargets.columnDim] (waiting on the delay, in play). */
    data object Delayed : ClockSlot
    /** Nothing in the slot (a clock-less view the table does not cover). */
    data object Blank : ClockSlot
}

/**
 * (Stacked §2.4, §8) Everything the Stacked bug's layout and words follow, as one pure function of the view
 * ([of]): the UI never re-derives a state from `ScoreBugView`. Each layout track of the runner (Stacked §2.3) kicks only
 * when its own key changes: `sog` on [sogOpen], `clock` on [clockOpen], `bar` on [bar], the row stripes on [ppSide].
 *
 * - [sogOpen]: the SOG column is open. Never while either side's `TeamLine.sog` is null (§8 "Missing SOG").
 * - [clockOpen]: the clock column is open (false = folded: an ordinary intermission, the shootout, the final).
 * - [bar]: the bar's key, null = closed. [barPeriod]: the period the bar names ([BarKind.INTERMISSION]'s period; for
 *   [BarKind.FINAL] a `PeriodLabel(finalKind, 1)`, REG → "FINAL", OT → "FINAL/OT", SO → "FINAL/SO"); null otherwise.
 *   [barSecs]: the power play's `secondsRemaining` for [BarKind.POWER_PLAY] / [BarKind.TWO_MAN] (null = unknown, the
 *   label is drawn alone), null otherwise.
 * - [ppSide]: the side whose row stripe is widened (3.6 → 6.3), null = both narrow.
 * - [period]: the period slot's label (`ScoreBugView.period`, B1), null = empty (the place-mode sample).
 * - [clockSlot]: the lower slot of the clock column, read while it is open.
 * - [columnDim]: the clock column is drawn at `STACKED_DIM_ALPHA` (stale, waiting). [barDim]: the bar's words are
 *   (a [BarKind.NO_UPDATE] / [BarKind.DELAYED] bar).
 *
 * A goal's takeover pins the layout at the pre-goal targets (§2.3, the pin); that is the runner's job, not [of]'s.
 */
data class StackedTargets(
    val sogOpen: Boolean,
    val clockOpen: Boolean,
    val bar: BarKey?,
    val barPeriod: PeriodLabel?,
    val barSecs: Int?,
    val ppSide: Side?,
    val period: PeriodLabel?,
    val clockSlot: ClockSlot,
    val columnDim: Boolean,
    val barDim: Boolean,
) {
    companion object {
        /**
         * (Stacked §8; WP-1) The whole state table: one row of §8 per view state, including the N5 rows. Pure and
         * JVM-tested (`StackedLayoutTest`). Equal to `of(v, null)`: see that overload for the one row it needs history
         * for (stale or waiting in a power play carried into the intermission).
         *
         * The view's state is read from `top` (what the machine decided the game is doing) and `bottom` (`NoUpdate` /
         * `Delayed` override it while stale / waiting, B1), `powerPlay` (only on a fresh LIVE or INTERMISSION view,
         * never in the shootout) and the two `TeamLine.sog`:
         *
         * | `top` | Fresh | Stale / waiting (`dim`) |
         * |---|---|---|
         * | `Clock` (play, OT) | SOG + PP bar while `powerPlay`, else closed; clock open, `Clock` (`Blank` without `clockSecs`) | SOG and bar closed (no PP); clock open, `NoUpdate` / `Delayed`, dimmed |
         * | `EndOf` (0:00, stopped) | as play, slot `End` | as play, slot `NoUpdate` / `Delayed` |
         * | `Intermission` + `powerPlay` | PP carried (B3): SOG open, PP bar with the frozen time, clock open, slot `Intermission` | (no `powerPlay` when dim: next row) |
         * | `Intermission` | SOG open, clock folded, neutral `INTERMISSION` bar naming the period ("OT INTERMISSION") | SOG open, clock folded, `NO_UPDATE` / `DELAYED` bar, dimmed |
         * | `Shootout` | SOG open, clock folded, `SHOOTOUT` bar | SOG open, clock folded, `NO_UPDATE` / `DELAYED` bar |
         * | `Final(kind)` | SOG open, clock folded, `FINAL` bar (`barPeriod` = `PeriodLabel(kind, 1)`) | `DELAYED` bar while waiting (following an already-final game with Delay > 0: then a text swap to `FINAL`); `NO_UPDATE` is not reachable (`staleAt()` is null at FINAL) |
         *
         * - The SOG column never opens while either side's `sog` is null (§8 "Missing SOG").
         * - The row stripe widens for the power-play side whenever a PP bar is the target ([ppSide]).
         * - [BarKind.PP_GOAL] is never a target: the runner cuts the words to it at `LEAD_COVERED_MS` (ruling 15) and
         *   re-evaluates at the 3 600 release, so the bar closes or changes per the data then.
         * - A folded clock column's [clockSlot] is [ClockSlot.Blank] (the slot is read only while the column is open).
         * - [columnDim] is `v.dim` in every state; [barDim] is true exactly for the `NO_UPDATE` / `DELAYED` bars.
         */
        fun of(v: ScoreBugView): StackedTargets = of(v, null)

        /**
         * (Stacked §8, WP-1 addition) [of] with the one bit of history §8 needs: [lastFresh] is the targets of the last
         * **fresh** (non-`dim`) view of the same follow, or null. A stale or waiting view in the intermission cannot
         * show whether a power play was carried over (the machine drops `powerPlay` when not fresh), so:
         * - when [lastFresh] is the PP-carried-into-INT state (clock slot [ClockSlot.Intermission]) for the same period,
         *   a dimmed intermission view keeps that layout's columns: SOG open, clock column **open** with the period over
         *   "No update" / "Delayed" at .55, bar closed, stripes narrow (§8 "Stale in a PP carried into INT");
         * - otherwise it is the ordinary stale / waiting break (`NO_UPDATE` / `DELAYED` bar, clock folded).
         * Every other view ignores [lastFresh].
         */
        fun of(v: ScoreBugView, lastFresh: StackedTargets?): StackedTargets {
            val sogKnown = v.away.sog != null && v.home.sog != null
            val dimSlot: ClockSlot? = when (v.bottom) {
                CentreText.NoUpdate -> ClockSlot.NoUpdate
                CentreText.Delayed -> ClockSlot.Delayed
                else -> null
            }
            val dimBar: BarKind? = when (dimSlot) {
                ClockSlot.NoUpdate -> BarKind.NO_UPDATE
                ClockSlot.Delayed -> BarKind.DELAYED
                else -> null
            }
            val pp = v.powerPlay
            return when (val top = v.top) {
                is CentreText.Final -> brk(v, sogKnown, dimBar ?: BarKind.FINAL, PeriodLabel(top.kind, 1))
                CentreText.Shootout -> brk(v, sogKnown, dimBar ?: BarKind.SHOOTOUT, null)
                is CentreText.Intermission -> when {
                    pp != null -> withPp(v, sogKnown, pp, ClockSlot.Intermission)
                    dimSlot != null && lastFresh != null && lastFresh.clockSlot == ClockSlot.Intermission &&
                        lastFresh.clockOpen && lastFresh.period == v.period ->
                        StackedTargets(
                            sogOpen = sogKnown, clockOpen = true, bar = null, barPeriod = null, barSecs = null,
                            ppSide = null, period = v.period, clockSlot = dimSlot, columnDim = v.dim, barDim = false,
                        )
                    else -> brk(v, sogKnown, dimBar ?: BarKind.INTERMISSION, if (dimBar == null) top.period else null)
                }
                is CentreText.EndOf -> play(v, sogKnown, pp, dimSlot ?: ClockSlot.End)
                else -> play(v, sogKnown, pp, dimSlot ?: if (v.clockSecs != null) ClockSlot.Clock else ClockSlot.Blank)
            }
        }

        /** In play (clock column open with [slot]): the PP bar and the SOG column while [pp], else both closed. */
        private fun play(v: ScoreBugView, sogKnown: Boolean, pp: PowerPlay?, slot: ClockSlot): StackedTargets =
            if (pp != null) {
                withPp(v, sogKnown, pp, slot)
            } else {
                StackedTargets(
                    sogOpen = false, clockOpen = true, bar = null, barPeriod = null, barSecs = null, ppSide = null,
                    period = v.period, clockSlot = slot, columnDim = v.dim, barDim = false,
                )
            }

        /** A power play: SOG open (when known), the team's bar with its time, the PP side's stripe wide, clock open. */
        private fun withPp(v: ScoreBugView, sogKnown: Boolean, pp: PowerPlay, slot: ClockSlot): StackedTargets {
            val kind = if (pp.kind == PowerPlayKind.TWO_MAN) BarKind.TWO_MAN else BarKind.POWER_PLAY
            return StackedTargets(
                sogOpen = sogKnown, clockOpen = true, bar = BarKey(kind, pp.side), barPeriod = null,
                barSecs = pp.secondsRemaining, ppSide = pp.side, period = v.period, clockSlot = slot, columnDim = v.dim,
                barDim = false,
            )
        }

        /** A break (intermission, shootout, final): SOG open (when known), clock folded, the neutral [kind] bar. */
        private fun brk(v: ScoreBugView, sogKnown: Boolean, kind: BarKind, barPeriod: PeriodLabel?): StackedTargets =
            StackedTargets(
                sogOpen = sogKnown, clockOpen = false, bar = BarKey(kind, null),
                barPeriod = if (kind == BarKind.NO_UPDATE || kind == BarKind.DELAYED) null else barPeriod,
                barSecs = null, ppSide = null, period = v.period, clockSlot = ClockSlot.Blank, columnDim = v.dim,
                barDim = kind == BarKind.NO_UPDATE || kind == BarKind.DELAYED,
            )

        /**
         * (Stacked §2.4, §7.1, S10; WP-1) Whether a bar going from [a] to [b] (both open) is a **text swap** (true: same
         * colours and side; the words cut, stripes and background stay) or a **change** (false: another colour or side;
         * stripes and text close, the background lerps, the content swaps). True when the sides are equal and both kinds
         * are team kinds ([BarKind.POWER_PLAY], [BarKind.TWO_MAN], [BarKind.PP_GOAL]), or both are neutral kinds (every
         * neutral bar has the same colours: `STACKED_BAR_NEUTRAL` with the away and home row stripes). Equal keys are
         * a swap too (nothing changes colour).
         */
        fun sameLook(a: BarKey, b: BarKey): Boolean {
            val ta = isTeam(a.kind)
            val tb = isTeam(b.kind)
            return if (ta && tb) a.side == b.side else !ta && !tb
        }

        /** The team kinds: the team's own bar colours and a side. */
        fun isTeam(kind: BarKind): Boolean =
            kind == BarKind.POWER_PLAY || kind == BarKind.TWO_MAN || kind == BarKind.PP_GOAL
    }
}
