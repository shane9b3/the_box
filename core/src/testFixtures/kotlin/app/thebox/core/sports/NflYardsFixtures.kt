package app.thebox.core.sports

import kotlinx.serialization.json.Json

/**
 * (NFL YDS §4.4, WP-1) The yards fixtures under `core/src/test/resources/sports/nfl/`: the five trimmed live2 scoreboards
 * of 2026-11-16 (LAC @ BAL, event 401873065, one event each) and the three per-team statistics bodies. The bodies live in
 * `core/src/test/resources`, which `:data`'s unit tests also see; [NflStackedFixtures] is unedited.
 */
object NflYardsFixtures {
    /** 20:58:13: `STATUS_END_PERIOD`, P1 0:00, `lastPlay` `…1101` type 2 "END QUARTER 1". */
    const val END_PERIOD = "scoreboard-live-end-period-2026-11-16"
    /** 20:58:23: the change-of-ends shape (S1): `STATUS_IN_PROGRESS`, P2 15:00, the same type-2 `lastPlay`. */
    const val CHANGE_OF_ENDS = "scoreboard-live-change-of-ends-2026-11-16"
    /** 21:01:23: the first Q2 snap (`…1121`, type 5), the clock still at 15:00. */
    const val FIRST_PLAY_AFTER_CHANGE_OF_ENDS = "scoreboard-live-first-play-after-change-of-ends-2026-11-16"
    /** 20:42:32: `lastPlay` `…704` type 74 "Official Timeout", Q1 4:39. */
    const val OFFICIAL_TIMEOUT = "scoreboard-live-official-timeout-2026-11-16"
    /** 20:42:42: the same 74, held. */
    const val OFFICIAL_TIMEOUT_HELD = "scoreboard-live-official-timeout-held-2026-11-16"

    /** The scoreboards above in time order (the S1 three, then the official timeout pair). */
    val BOARDS: List<String> = listOf(END_PERIOD, CHANGE_OF_ENDS, FIRST_PLAY_AFTER_CHANGE_OF_ENDS, OFFICIAL_TIMEOUT, OFFICIAL_TIMEOUT_HELD)

    /** ARI's final statistics in LAR @ ARI 401873061 (`athletes` emptied): `totalYards` 123. */
    const val STATS_FINAL = "stats-team-final-2026-11-15"
    /** Synthetic: `totalYards` −2.0 / "-2". */
    const val STATS_NEGATIVE = "stats-team-negative-synthetic"
    /** Synthetic: no `totalYards`. */
    const val STATS_NO_TOTAL = "stats-team-no-total-synthetic"

    /** LAC (away, competitor 24) @ BAL (home, 33). */
    val LAC_AT_BAL = GameId(League.NFL, "401873065")
    const val LAC_ID = "24"
    const val BAL_ID = "33"

    private val json = Json { ignoreUnknownKeys = true }

    /** The resource path of fixture [name] (a constant above). */
    fun path(name: String): String = "sports/nfl/$name.json"

    /** Fixture [name]'s text. */
    fun text(name: String): String = SportsFixtures.text(path(name))

    /** Scoreboard [name] as the follow's DTO. */
    fun board(name: String): EspnGameDto = json.decodeFromString(EspnGameDto.serializer(), text(name))

    /** Scoreboard [name] mapped by `EspnMapper.game`, or null if the mapper drops it. */
    fun snapshot(name: String): GameSnapshot? = EspnMapper.game(board(name), LAC_AT_BAL, null, "https://a.espncdn.com/")

    /** Statistics body [name] as the DTO. */
    fun stats(name: String): EspnStatsDto = json.decodeFromString(EspnStatsDto.serializer(), text(name))
}
