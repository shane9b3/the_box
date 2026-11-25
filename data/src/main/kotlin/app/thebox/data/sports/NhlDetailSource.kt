package app.thebox.data.sports

import android.content.Context
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailSource
import app.thebox.core.sports.hub.NhlDetailMapper
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.2; WP-1) `gamecenter/{id}/right-rail` + `/landing` together (the result is
 * `Ok` when the right-rail answered; a failed landing leaves goals, stars, leaders and goalies empty and is reported as
 * [DetailAnswer.landingFailed], which the repository shows as `failing`), `/boxscore` on demand; over [NhlHttp] on the
 * `@ApiClient` client with `WireMeter`, `SportsEndpoints.apiBase`, bodies capped at `MAX_BODY_BYTES`, [NhlDetailMapper].
 *
 * Nothing about the user, playlist or device is sent: a request carries the IP, the UA and a game id. A cancelled caller
 * cancels both calls (`NhlHttp.call` cancels the OkHttp call).
 */
@Singleton
class NhlDetailSource internal constructor(
    private val http: NhlHttp,
    private val mono: MonoClock,
    endpoints: () -> SportsEndpoints,
) : GameDetailSource {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context) : this(
        NhlHttp(client, ScoreRepositoryImpl.ELAPSED, TAG),
        ScoreRepositoryImpl.ELAPSED,
        endpoints = { SportsEndpoints.resolve(context) },
    )

    internal constructor(client: OkHttpClient, mono: MonoClock, endpoints: () -> SportsEndpoints, callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS) :
        this(NhlHttp(client, mono, TAG, callTimeoutMs), mono, endpoints)

    /** Resolved on first use (off the main thread: the repository's first request). */
    private val endpoints: SportsEndpoints by lazy(endpoints)

    /** One detail answer: the [fetch] and whether the landing half failed (the detail then lacks its landing facts). */
    internal data class DetailAnswer(val fetch: Fetch<GameDetail>, val landingFailed: Boolean)

    override suspend fun detail(id: GameId): Fetch<GameDetail> = detailAnswer(id, null).fetch

    /** [detail], with the board's [phaseHint] for a detail whose landing failed (§4.2). */
    internal suspend fun detailAnswer(id: GameId, phaseHint: GamePhase?): DetailAnswer {
        if (!valid(id)) return DetailAnswer(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()), false)
        val base = endpoints.apiBase
        val (rr, landing) = coroutineScope {
            val rr = async { http.get(base + "gamecenter/${id.id}/right-rail", "gamecenter/{id}/right-rail", "right-rail") { it } }
            val landing = async { http.get(base + "gamecenter/${id.id}/landing", "gamecenter/{id}/landing", "hub-landing") { it } }
            rr.await() to landing.await()
        }
        val rrOk = when (rr) {
            is Fetch.Ok -> rr
            Fetch.NotFound -> return DetailAnswer(Fetch.NotFound, false)
            is Fetch.Failed -> return DetailAnswer(rr, false)
        }
        val landingBody: JsonObject? = (landing as? Fetch.Ok)?.value
        val landingDate = (landing as? Fetch.Ok)?.serverDateMs
        val date = listOfNotNull(rrOk.serverDateMs, landingDate).maxOrNull()
        val detail = try {
            NhlDetailMapper.detail(id, rrOk.value, landingBody, date, phaseHint)
        } catch (e: RuntimeException) {
            null
        } ?: return DetailAnswer(Fetch.Failed(FailKind.BAD_BODY, null, rrOk.receivedMono), false)
        val wire = listOfNotNull(rrOk.wireBytes, (landing as? Fetch.Ok)?.wireBytes).takeIf { it.isNotEmpty() }?.sum()
        return DetailAnswer(
            Fetch.Ok(detail, rrOk.receivedMono, date, rrOk.ageS, rrOk.maxAgeS, wire),
            landingFailed = landingBody == null,
        )
    }

    override suspend fun boxScore(id: GameId): Fetch<BoxScore> {
        if (!valid(id)) return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
        return http.get(endpoints.apiBase + "gamecenter/${id.id}/boxscore", "gamecenter/{id}/boxscore", "boxscore", NhlDetailMapper::boxScore)
    }

    private fun valid(id: GameId): Boolean = id.league == League.NHL && GAME_ID.matches(id.id)

    private companion object {
        const val TAG = "NhlDetail"
        val GAME_ID = Regex("""\d{1,12}""")
    }
}
