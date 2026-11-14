package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.isChl
import okhttp3.OkHttpClient

/**
 * (CHL §2, docs/design/m6-chl-leagues.md) One CHL league's [ScoreSource]: HockeyTech's keyed `lscluster` for the day list,
 * teams and seasons, and its Firebase database for the followed game and Today's live clocks. One class, three instances
 * (OHL, WHL, QMJHL), bound with `@LeagueKey` in `SportsModule` (WP-3). Nothing above [ScoreSource] learns about HockeyTech.
 *
 * **Commit-0 stub (not bound):** every request answers `Failed(HTTP_OTHER)` and no logo validates. WP-3 fills it (the
 * endpoints, the keys in `HockeyTechEndpoints.kt`, the ETags, the modes of §2.4) and may add internal constructor
 * parameters; the class name, [league] and the [ScoreSource] surface stay.
 */
class HockeyTechScoreSource internal constructor(
    override val league: League,
    @Suppress("unused") private val client: OkHttpClient,
    private val mono: MonoClock,
) : ScoreSource {
    init {
        require(league.isChl) { "HockeyTechScoreSource serves the CHL leagues only, not $league" }
    }

    private fun notYet(): Fetch.Failed = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()) // TODO(WP-3)

    override suspend fun today(): Fetch<DayBoard> = notYet()

    override suspend fun day(dayKey: String): Fetch<DayBoard> = notYet()

    override suspend fun game(id: GameId): Fetch<GameSnapshot> = notYet()

    override suspend fun game(id: GameId, startUtcMs: Long?): Fetch<GameSnapshot> = notYet()

    /** (§3.5) The validated logo URL is already on the line (the mapper's `ChlTeam.logo`); "" = none. */
    override fun logoUrl(team: TeamLine): String = team.logo.orEmpty()

    /** (§1.6 #10) Every live-mode follow poll is one meter endpoint for the CHL. */
    override fun followEndpoint(id: GameId): String? = app.thebox.core.sports.PollMeter.CHL_LIVE
}
