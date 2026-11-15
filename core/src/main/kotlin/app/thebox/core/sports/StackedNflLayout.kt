// Contract (NFL Stacked commit 0): docs/design/m6-nfl-stacked.md §3.4, §8.1. The types and signatures are fixed for
// WP-2 (the body of `StackedNflTargets.of`), WP-3 (the painters read them) and WP-4 (the runner keys its tracks on them);
// changes go through the lead. Every enum here is append-only.
package app.thebox.core.sports

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
 *   a kick-off never carries `TabTail.RedZone`, RC9).
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
         * **Commit-0 stub:** the play state for every view (clock open, the snapshot clock, no bar, no possession, the
         * view's counts, nothing burning). Commit 0 never draws it (`StackedNflBug` draws the NFL Pill until WP-4).
         */
        fun of(v: ScoreBugView): StackedNflTargets {
            // WP-2: the §3.4 table.
            val n = v.nfl
            return StackedNflTargets(
                clockOpen = true,
                bar = null,
                possSide = null,
                period = v.period,
                clockSlot = if (v.clockSecs != null) ClockSlot.Clock else ClockSlot.Blank,
                columnDim = v.dim,
                barDim = false,
                awayTimeouts = n?.awayTimeouts,
                homeTimeouts = n?.homeTimeouts,
                timeoutSlots = n?.timeoutSlots ?: 0,
                burnSide = null,
            )
        }
    }
}
