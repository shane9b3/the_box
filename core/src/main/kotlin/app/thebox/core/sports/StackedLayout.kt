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
         * JVM-tested (`StackedLayoutTest`).
         *
         * **Commit-0 stub:** every view maps to the even-strength play state (SOG closed, clock open with the view's
         * period, bar closed), with [columnDim] = `v.dim`.
         */
        fun of(v: ScoreBugView): StackedTargets = StackedTargets(
            sogOpen = false,
            clockOpen = true,
            bar = null,
            barPeriod = null,
            barSecs = null,
            ppSide = null,
            period = v.period,
            clockSlot = ClockSlot.Clock,
            columnDim = v.dim,
            barDim = false,
        )

        /**
         * (Stacked §2.4, §7.1, S10; WP-1) Whether a bar going from [a] to [b] (both open) is a **text swap** (true: same
         * colours and side; the words cut, stripes and background stay) or a **change** (false: another colour or side;
         * stripes and text close, the background lerps, the content swaps). True when the sides are equal and both kinds
         * are team kinds ([BarKind.POWER_PLAY], [BarKind.TWO_MAN], [BarKind.PP_GOAL]), or both are neutral kinds.
         *
         * **Commit-0 stub:** true only for equal keys.
         */
        fun sameLook(a: BarKey, b: BarKey): Boolean = a == b
    }
}
