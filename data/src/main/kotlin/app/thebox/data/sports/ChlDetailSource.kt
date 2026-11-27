package app.thebox.data.sports

import android.content.Context
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChlDetailMapper
import app.thebox.core.sports.hub.ChlRanks
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailSource
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (CHL hub, docs/design/m6-chl-hub.md §4.1, §4.4) The OHL, WHL and QMJHL game detail from HockeyTech `lscluster`, for
 * the pre-game and the final only. **Commit 0 stub:** every call answers `Failed(HTTP_OTHER)` without a request (the
 * repository never asks while `SportsConstants.CHL_HUB_ENABLED` is false). P2 fills the bodies; the constructor, the
 * mapper parameters and the two entry points below are frozen.
 *
 * What P2 implements (the design is the spec):
 * - **Pre-game** ([detailAnswer] with an effective phase SCHEDULED / PRE): `feed=gc&tab=preview` first (its failure
 *   fails the answer); then, at most 3 in flight, `topgoalies` × 2 (`pv.*.team_id`, `pv.current_season.id`) and, unless
 *   `pv.current_season.playoff == "1"`, the two `statviewfeed&view=teams` tables when the (league, season)'s cached
 *   [ChlRanks] is older than `HUB_CHL_TABLES_TTL_MS`. A failed goalie or table read → [DetailAnswer.partial] true,
 *   [DetailAnswer.landingFailed] false; a failed table read reuses the last good [ChlRanks] of that (league, season),
 *   however old. Only mapped [ChlRanks] are cached, never a body.
 * - **Final** ([detailAnswer] with FINAL): `feed=gc&tab=gamesummary` only; [DetailAnswer.box] is the [BoxScore] of the
 *   same parse. [boxScore] is never a second request (the repository's loop answers it from that parse).
 * - **Transport:** [HockeyTechHttp.lscluster] (`gc` takes `lang_code=en`, `modulekit` / `statviewfeed` take `lang=en`;
 *   every request `fmt=json`), [HockeyTechHttp.json] under `MAX_BODY_BYTES`; `statviewfeed` must decode to a
 *   `JsonArray`, every other view to a `JsonObject` (else BAD_BODY); the mapper's null → BAD_BODY. The answer's
 *   `serverDateMs` is set from the response `Date` ([HockeyTechHttp.JsonAnswer.Ok.serverDateMs]).
 * - **Headshots:** `{ endpoints.headshot(league, it) }` is the builder passed to the mapper.
 * - **Logging:** `"chl-detail" → state` on a change only, with an exception class name at most; never a URL, header,
 *   body, key or game id. Detail requests are not `PollMeter` samples.
 * - Nothing is persisted.
 *
 * The mappers are constructor parameters (the `EspnDetailSource` precedent), so P2 tests the transport with stand-ins
 * before P1's `ChlDetailMapper` bodies land.
 */
@Singleton
class ChlDetailSource internal constructor(
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> HockeyTechEndpoints,
    /** `ChlDetailMapper.preview`: `(id, previewBody, awayTopGoalies, homeTopGoalies, ranks, playoff, headshot)`. */
    private val mapPreview: (GameId, JsonObject, JsonObject?, JsonObject?, ChlRanks?, Boolean, (String) -> String?) -> GameDetail? =
        ChlDetailMapper::preview,
    /** `ChlDetailMapper.summary`: `(id, gamesummaryBody, headshot)` → the detail and the box score of one parse. */
    private val mapSummary: (GameId, JsonObject, (String) -> String?) -> Pair<GameDetail, BoxScore>? = ChlDetailMapper::summary,
    /** `ChlDetailMapper.ranks`: `(standardTable, specialTable)`, JSONP already stripped. */
    private val mapRanks: (JsonArray?, JsonArray?) -> ChlRanks? = ChlDetailMapper::ranks,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
) : GameDetailSource {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context) : this(
        client, ScoreRepositoryImpl.ELAPSED, endpoints = { HockeyTechEndpoints.resolve(context) },
    )

    /** The transport: the score source's client rules (`WireMeter`, the call timeout, no cache). */
    @Suppress("unused") // P2
    private val http = HockeyTechHttp(
        WireMeter.install(client.newBuilder(), client).callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).cache(null).build(),
        mono,
    )

    /** Resolved on first use (off the main thread: the repository's first request). */
    @Suppress("unused") // P2
    private val endpoints: HockeyTechEndpoints by lazy(endpoints)

    override suspend fun detail(id: GameId): Fetch<GameDetail> = detailAnswer(id, null).fetch

    /**
     * The shown CHL game's detail. [phaseHint]: the repository's **effective** phase (`DetailPollPolicy.effectivePhase`
     * of the board's and the cached detail's): SCHEDULED / PRE → the pre-game reads; FINAL → `gamesummary`; anything else
     * (the repository never asks: BoardOnly) → `Failed(HTTP_OTHER)` without a request. Commit 0: always that failure.
     */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun detailAnswer(id: GameId, phaseHint: GamePhase?): DetailAnswer =
        DetailAnswer(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()), false)

    /** Never a request of its own (§4.1): the repository answers the CHL box score from the final's parse. */
    override suspend fun boxScore(id: GameId): Fetch<BoxScore> = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())

    /** Drops the cached ranks ([GameDetailRepositoryImpl]'s trim). Commit 0: nothing is cached. */
    fun trimMemory() = Unit

    companion object {
        init {
            HockeyTechKeys.touch() // (L6) the keys are registered with Redact before any request
        }

        internal const val TAG = "ChlDetail"
    }
}
