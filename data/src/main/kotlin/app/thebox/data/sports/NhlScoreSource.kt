package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlMapper
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.TeamLine
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * The NHL [ScoreSource] over `api-web.nhle.com` (docs/design/m6-score-bug.md §2.1, §2.2; WP-2).
 *
 * - The transport (call timeout, no cache, `Date` / `Age`, the body cap, 404 and HTML handling, state-change logging) is
 *   [NhlHttp] (Sports Hub commit 0 moved it there unchanged; [SportsEndpoints] is in its own file).
 * - Nothing about the user, playlist or device is sent: a request carries the IP, the UA and a date or a game id.
 *
 * The mapper functions are parameters so the transport is testable on its own; production uses [NhlMapper].
 */
class NhlScoreSource internal constructor(
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> SportsEndpoints,
    private val mapDay: (JsonObject, String) -> DayBoard? = NhlMapper::day,
    private val mapLanding: (JsonObject, String) -> GameSnapshot? = NhlMapper::landing,
    private val deriveLogo: (String, String) -> String = NhlMapper::darkLogoUrl,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
) : ScoreSource {
    private val http = NhlHttp(client, mono, TAG, callTimeoutMs)

    /** Resolved on first use (the actor's first request, off the main thread). */
    private val endpoints: SportsEndpoints by lazy(endpoints)

    override val league: League = League.NHL

    override suspend fun today(): Fetch<DayBoard> = get("score/now", "score", mapDay)

    override suspend fun day(dayKey: String): Fetch<DayBoard> {
        if (!DAY_KEY.matches(dayKey)) return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
        return get("score/$dayKey", "score", mapDay)
    }

    override suspend fun game(id: GameId): Fetch<GameSnapshot> {
        if (id.league != League.NHL || !GAME_ID.matches(id.id)) return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
        return get("gamecenter/${id.id}/landing", "gamecenter", mapLanding)
    }

    /** (Update interval §3.2) The follow always reads the landing. */
    override fun followEndpoint(id: GameId): String = app.thebox.core.sports.PollMeter.NHL_LANDING

    /** The landing's `darkLogo` only when it is under the logo base (A-N2); else the URL derived from the abbreviation. */
    override fun logoUrl(team: TeamLine): String {
        val base = endpoints.logoBase
        return team.logo?.takeIf { it.startsWith(base) } ?: deriveLogo(team.abbrev, base)
    }

    private suspend fun <T> get(path: String, kind: String, map: (JsonObject, String) -> T?): Fetch<T> {
        val e = endpoints
        return http.get(e.apiBase + path, path, kind) { json -> map(json, e.logoBase) }
    }

    companion object {
        private const val TAG = "NhlScores"
        private val DAY_KEY = Regex("""\d{4}-\d{2}-\d{2}""")
        private val GAME_ID = Regex("""\d{1,12}""")

        // Kept for the ESPN and HockeyTech sources, which share these rules; they delegate to [NhlHttp].
        internal fun ioKind(e: IOException): FailKind = NhlHttp.ioKind(e)

        internal fun serverDateMs(res: Response): Long? = NhlHttp.serverDateMs(res)

        internal fun ageS(res: Response): Int = NhlHttp.ageS(res)

        internal fun retryAfterS(res: Response): Int? = NhlHttp.retryAfterS(res)
    }
}
