// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §5.1): the input / output types and the [ChlPowerPlay.derive]
// signature are fixed for WP-1 (the derivation) and the mapper; changes go through the lead.
package app.thebox.core.sports

/**
 * (CHL §5.1) One `penalties` row, as the mapper reads it (Appendix B field names). Game times are in s.
 * - [home]: `Home == 1`, the penalised team is the home team;
 * - [timeS]: `Time`, elapsed in [period] when called; [startS]: `TimeStart` when parsable and `TimeStartSet != 0`;
 * - [onS]: `TimeOn` when `TimeOnSet == 1` and parsable;
 * - [ppFlag]: `PowerPlay == 1`; [penaltyShot]: `PenaltyShot == 1`;
 * - [playerId]: `PenalizedPlayerId`, 0 → null (B2; bench minors).
 */
data class ChlPenalty(
    val id: Long,
    val home: Boolean,
    val period: Int,
    val timeS: Int?,
    val startS: Int?,
    val onS: Int?,
    val minutes: Int?,
    val ppFlag: Boolean,
    val penaltyShot: Boolean,
    val playerId: Long?,
)

/** (CHL §5.1) One `goals` row: `IsHome`, `Period`, `Time` (elapsed s), `PowerPlay`, `ShortHanded`, `PenaltyShot`. */
data class ChlGoal(
    val id: Long,
    val home: Boolean,
    val period: Int?,
    val timeS: Int?,
    val pp: Boolean,
    val sh: Boolean,
    val penaltyShot: Boolean,
)

/** (CHL §5.1) [period] / [remainingS] after §3.4's adjustment; [feedPeriod] the raw feed period (S12). */
data class ChlNow(
    val period: Int,
    val remainingS: Int,
    val feedPeriod: Int,
    val phase: GamePhase,
    val shootout: Boolean,
    val decided: Boolean,
)

/** (CHL §5.1) The derivation's answer: no power play, unknown (a §5.6 hide rule fired), or one to show. */
sealed interface ChlPp {
    data object None : ChlPp
    data object Unknown : ChlPp
    data class Shown(val pp: PowerPlay) : ChlPp
}

/**
 * (CHL §5) The derived power play: pure, no I/O, no clock. The executable reference is `tools/chl/pp_replay.py`; the
 * mapper sets `GameSnapshot.powerPlay` from [ChlPp.Shown] and `powerPlayUnknown = (result == ChlPp.Unknown)`.
 *
 * **Commit-0 stub:** always [ChlPp.Unknown] ("prefer hiding the bar to showing a wrong one"). WP-1 implements §5.2–§5.6
 * without changing the signature.
 */
object ChlPowerPlay {
    /**
     * [pens] / [goals]: the nodes' rows (null = the node is `null`); [goalTotal]: both teams' goals, the shootout +1
     * excluded; [ppAttempts]: both teams' `*PowerPlayAttempts` (null = unknown); [periodLength]: a period's length in s
     * (null = unknown, H1).
     *
     * (CHL fix pass L7) [now] is non-null by construction, so H1's "no clock" and "period < 1" are the **mapper's**
     * checks: with no clock or no period it does not call [derive] and sets `powerPlayUnknown = true` itself. [derive]
     * checks only the period lengths part of H1.
     */
    @Suppress("UNUSED_PARAMETER")
    fun derive(
        pens: List<ChlPenalty>?,
        goals: List<ChlGoal>?,
        goalTotal: Int,
        ppAttempts: Int?,
        now: ChlNow,
        periodLength: (Int) -> Int?,
    ): ChlPp = ChlPp.Unknown // TODO(WP-1): design §5
}
