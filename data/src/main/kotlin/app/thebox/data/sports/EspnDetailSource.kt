package app.thebox.data.sports

import android.content.Context
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.EspnDetailMapper
import app.thebox.core.sports.hub.EspnSummaryDto
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailSource
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §5.3, §5.5, §6.5; P2) ESPN's `summary?event={id}` for the shown NFL game, over
 * `EspnHttp` (the `ESPN_SUMMARY_MAX_BYTES` cap, the WAF rule) on the `@ApiClient` client: streaming decode into
 * [EspnSummaryDto], mapped by [mapDetail] / [mapBox] on `Dispatchers.Default`; the offered clips go to [catalog]; the
 * skew comes from the answer's `Date`. Nothing about the user, playlist or device is sent; logs carry a path label and
 * the result kind only.
 *
 * The mappers are constructor parameters so P2 tests the transport without P1's mapper.
 *
 * Commit 0: a stub. [detailAnswer] answers `Failed(HTTP_OTHER)` without a request (the repository does not route the NFL
 * here yet); [boxScore] likewise.
 */
@Singleton
class EspnDetailSource internal constructor(
    private val client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> EspnEndpoints,
    private val catalog: EspnClipCatalog,
    private val mapDetail: (EspnSummaryDto, GameId, Long?) -> GameDetail? = EspnDetailMapper::detail,
    private val mapBox: (EspnSummaryDto) -> BoxScore? = EspnDetailMapper::box,
) : GameDetailSource {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context, catalog: EspnClipCatalog) : this(
        client, ScoreRepositoryImpl.ELAPSED, endpoints = { EspnEndpoints.resolve(context) }, catalog = catalog,
    )

    /** Resolved on first use (off the main thread: the repository's first request). */
    private val endpoints: EspnEndpoints by lazy(endpoints)

    override suspend fun detail(id: GameId): Fetch<GameDetail> = detailAnswer(id, null).fetch

    /** The detail with its box score from the same parse (§5.5). [phaseHint]: the board's phase. */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun detailAnswer(id: GameId, phaseHint: GamePhase?): DetailAnswer =
        DetailAnswer(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()), false) // P2

    /** One summary read mapped to the box; kept for the interface (the repository uses [detailAnswer]'s box). */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun boxScore(id: GameId): Fetch<BoxScore> = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()) // P2
}
