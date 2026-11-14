package app.thebox.core.sports

import kotlinx.serialization.json.JsonObject

/**
 * (CHL §3) HockeyTech bodies → the normalised types. Pure: no I/O, no clock. Every field is optional and lenient (§3.1);
 * an unmappable body yields null, which the source turns into `FailKind.BAD_BODY`. The signatures are the commit-0
 * contract (with the types in `HockeyTechContracts.kt`); WP-1 fills the bodies without changing them.
 *
 * **Commit-0 stub:** every mapping returns null (unmappable), [overlay] returns the board unchanged.
 */
@Suppress("UNUSED_PARAMETER")
object HockeyTechMapper {
    /**
     * (§3.2, §2.3) A `gamesbydate` body for [dayKey] of [league]: the board (card names from [teams] by team id, else the
     * body's nickname; logos from [teams]), each game's facts, with [playoffBySeason] (`seasons`, season id → playoff) when
     * known. Null = unmappable.
     */
    fun board(
        league: League,
        dayKey: String,
        body: JsonObject,
        teams: Map<String, ChlTeam>?,
        playoffBySeason: Map<String, Boolean>?,
    ): ChlBoard? = null // TODO(WP-1)

    /**
     * (§3.5, §6.1) A `teamsbyseason` body → teams by team id. [logoPrefix] is the league's validated logo folder
     * (`{logoBase}{client}/logos/`); a logo URL outside it, or not matching `[0-9]+(_[0-9]+)?\.(png|jpg|jpeg)`, is no logo;
     * a JPEG becomes `{logoPrefix}{teamId}.png`. Null = unmappable.
     */
    fun teams(body: JsonObject, logoPrefix: String): Map<String, ChlTeam>? = null // TODO(WP-1)

    /** (§2.3) A `seasons` body → season id → playoff (`playoff == "1"`). Null = unmappable. */
    fun seasons(body: JsonObject): Map<String, Boolean>? = null // TODO(WP-1)

    /**
     * (§3.3, §3.4, §5) The followed game of [league] from one live poll's [nodes] and the board's [facts]: scores, shots,
     * period, clock (incl. the "20:00" break form, S12, and "End 3rd", S3), shootout, final kind, `lastGoal`, and the
     * derived `powerPlay` / `powerPlayUnknown` (`ChlPowerPlay.derive`). Null = unmappable (the code gate, a required node
     * unmappable): the source answers `BAD_BODY`.
     */
    fun followed(league: League, facts: ChlGameFacts, nodes: ChlNodes): GameSnapshot? = null // TODO(WP-1)

    /**
     * (§2.3 step 4; CHL fix pass B4) Today's overlay: the `runningclock` and `goalssummary` date queries
     * (`DatePlayed == board.board.dayKey`) replace the clock, status and scores of the matching games, through §3.4's
     * clock table with each game's facts (the playoff flag for a tied 0:00 period 4, the OT length for the "20:00" break
     * form). Either query may be null (that part is skipped). The facts and season ids are returned unchanged.
     */
    fun overlay(board: ChlBoard, runningclock: JsonObject?, goalssummary: JsonObject?): ChlBoard = board // TODO(WP-1)

    /**
     * (§2.4, §3.3 (b)/(c); CHL fix pass L6) Whether the next live poll also requests the `shootouts` node: [prev] (the
     * previous snapshot of this follow, null = none yet) was a possible shootout: `periodKind == SO`, or regular season
     * ([facts]`.playoff == false`), period 4, 0:00 stopped, LIVE, scores tied. The source adds the node only for the WHL and
     * the QMJHL (the OHL has none). Pure, so `:data` never re-implements §3.3 (c).
     */
    fun wantsShootouts(prev: GameSnapshot?, facts: ChlGameFacts): Boolean = false // TODO(WP-1)
}
