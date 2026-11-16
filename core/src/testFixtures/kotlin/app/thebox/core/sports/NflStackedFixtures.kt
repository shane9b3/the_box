package app.thebox.core.sports

import kotlinx.serialization.json.Json

/**
 * (NFL Stacked §3.1, commit 0) The eight trimmed live scoreboards of 2026-11-15 (`resources/sports/nfl/`, one event
 * each) and a mapper helper, shared by WP-1's `EspnMapperStackedTest` / `ScoreBugMachineNflStackedTest`, WP-2's
 * `StackedNflLayoutTest`. Names are the file stems without the date. The bodies live in `core/src/test/resources`, so
 * [dto] / [snapshot] work in `:core` tests only (other modules see the constants).
 */
object NflStackedFixtures {
    /** LAR @ ARI, Q1 7:23: `lastPlay` type 8 "Penalty" (id `401873061503`), "… PENALTY on LA …"; counts 3 / 2. */
    const val PENALTY_TYPE8 = "scoreboard-live-penalty-type8"
    /** LAR @ ARI, Q1 6:56: `lastPlay` type 21 "Timeout" (`…520`), `awayTimeouts` 3 → 2 in the same poll. */
    const val TEAM_TIMEOUT_TYPE21 = "scoreboard-live-team-timeout-type21"
    /** The next poll, 30 s later: the same type-21 `lastPlay` and clock 6:56 (the 21 is held). */
    const val TEAM_TIMEOUT_HELD = "scoreboard-live-team-timeout-held"
    /** The poll after: a new `lastPlay` (`…536`, type 24), 1st & 9 "ARI 9", `isRedZone` true: the timeout ends. */
    const val AFTER_TIMEOUT = "scoreboard-live-after-timeout"
    /** SEA @ LV, Q1 7:41: the kick-off after a score, `lastPlay` 74 "Official Timeout", raw `isRedZone: true` (RC9). */
    const val OFFICIAL_TIMEOUT_KICKOFF_REDZONE = "scoreboard-live-official-timeout-kickoff-redzone"
    /** SEA @ LV, Q1 7:41: `lastPlay` 53 Kickoff, 1st & 10 "SEA 27", no `possession` (NFL §3.4 inference rule 1). */
    const val KICKOFF_RETURN_NO_POSSESSION = "scoreboard-live-kickoff-return-no-possession"
    /** LAR @ ARI, Q1 10:41: the clock held across a new play (`lastPlay` 74 `…329`, then 24 `…345` in [CLOCK_LAG_B]). */
    const val CLOCK_LAG_A = "scoreboard-live-clock-lag-a"
    const val CLOCK_LAG_B = "scoreboard-live-clock-lag-b"

    /** Every fixture above, in the order of §3.1's table. */
    val ALL: List<String> = listOf(
        PENALTY_TYPE8, TEAM_TIMEOUT_TYPE21, TEAM_TIMEOUT_HELD, AFTER_TIMEOUT, OFFICIAL_TIMEOUT_KICKOFF_REDZONE,
        KICKOFF_RETURN_NO_POSSESSION, CLOCK_LAG_A, CLOCK_LAG_B,
    )

    /** LAR @ ARI (the penalty, timeout and clock-lag fixtures) and SEA @ LV (the kick-off fixtures). */
    val LAR_AT_ARI = GameId(League.NFL, "401873061")
    val SEA_AT_LV = GameId(League.NFL, "401873062")

    private val json = Json { ignoreUnknownKeys = true }

    /** The resource path of fixture [name] (a constant above). */
    fun path(name: String): String = "sports/nfl/$name-2026-11-15.json"

    /** Fixture [name]'s body as the per-event endpoint's DTO. */
    fun dto(name: String): EspnGameDto = json.decodeFromString(EspnGameDto.serializer(), SportsFixtures.text(path(name)))

    /** The game id of fixture [name]. */
    fun idOf(name: String): GameId =
        if (name == OFFICIAL_TIMEOUT_KICKOFF_REDZONE || name == KICKOFF_RETURN_NO_POSSESSION) SEA_AT_LV else LAR_AT_ARI

    /** Fixture [name] mapped by `EspnMapper.game` (the follow's path), or null if the mapper drops it. */
    fun snapshot(name: String, serverDateMs: Long? = null): GameSnapshot? =
        EspnMapper.game(dto(name), idOf(name), serverDateMs, "https://a.espncdn.com/")
}
