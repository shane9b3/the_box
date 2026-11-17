// Contract (NFL YDS foundation, docs/design/m6-nfl-stacked-yds.md §1.4): the signatures are fixed for WP-1 (the bodies)
// and the drawing (WP-2 reads [NflYards.text]); changes go through the lead.
package app.thebox.core.sports

/**
 * (NFL YDS §1) Team total yards for the Stacked NFL bug's YDS column: the stoppage a snapshot is in, the competitor ids
 * the statistics URL needs, the value read from ESPN's per-team statistics, and the digits as drawn. Pure.
 */
object NflYards {
    /**
     * (§1.5) The stoppage [s] is in, or null: `"F"` at FINAL; `"H" + period` at halftime; `"E" + period` at the end of a
     * quarter (`STATUS_END_PERIOD`, and the change-of-ends shape `EspnMapper` maps to it, S1); in a LIVE snapshot
     * `"T" + lastPlay.id` for a team timeout (type 21) and `"O" + lastPlay.id` for an official timeout (74) or the
     * two-minute warning (75) (S2); null otherwise (in play, PRE, not the NFL). WP-1 writes the body; the foundation's
     * stub is null (nothing is ever fetched).
     */
    fun stoppageKey(s: GameSnapshot): String? = null

    /**
     * (§1.7) The (away, home) competitor ids of [e] for the statistics URL: `competitions[0].competitors[].id`, else
     * `team.id`, 1–6 digits; null when either is missing. WP-1 writes the body; stub null.
     */
    fun teamIds(e: EspnEventDto): Pair<String, String>? = null

    /**
     * (§1.7, review S3) The team's total yards from one statistics body: the first stat named `totalYards` in any
     * category (ESPN copies the team value into passing, rushing and receiving; net of sacks, the broadcast "total
     * yards", review N1), `value` when finite and integral, else `displayValue` matching `-?\d{1,4}`; null when absent,
     * unreadable or outside `NFL_YARDS_MIN..NFL_YARDS_MAX`. WP-1 writes the body; stub null.
     */
    fun totalYards(dto: EspnStatsDto): Int? = null

    /** (§3.4) The digits as drawn and spoken: "142", "0", "−2" (U+2212 MINUS SIGN), ASCII digits in every locale. */
    fun text(yards: Int): String = if (yards < 0) MINUS + (-yards.toLong()).toString() else yards.toString()

    /** U+2212, in the bundled Instrument Sans Bold. */
    const val MINUS = "−"
}

/** (§1.6) One yards attempt's outcome: both teams' values, or a failure of [kind] (a 403 is reported as `HTTP_429`). */
sealed interface YardsResult {
    data class Ok(val away: Int, val home: Int) : YardsResult
    data class Failed(val kind: FailKind) : YardsResult
}

/**
 * (§1.6) One follow's yards state, owned by `EspnScoreSource` and used only on the score actor (single-threaded): which
 * stoppage to fetch on this poll ([due]), what came back ([fetched]), and the snapshot with the usable yards attached
 * ([attach]). Holds a single current stoppage key (review N7), the confirm rule for the refresh (B1) and the follow-wide
 * block (429 / 403, or two timeout / IO failures in a row, S4). WP-1 writes the bodies; the foundation's stub never
 * fetches and attaches nothing, so the app behaves exactly as before.
 */
class NflYardsPlanner {
    /** The stoppage key to fetch for [s], received at [nowMono], or null (counts the attempt when non-null). */
    fun due(s: GameSnapshot, nowMono: Long): String? = null

    /** The outcome of the attempt [due] returned [key] for, completed at [atMono]. */
    fun fetched(key: String, atMono: Long, r: YardsResult) = Unit

    /** [s] (received at [receivedMono]) with the yards usable for its stoppage, or [s] unchanged. */
    fun attach(s: GameSnapshot, receivedMono: Long): GameSnapshot = s
}
