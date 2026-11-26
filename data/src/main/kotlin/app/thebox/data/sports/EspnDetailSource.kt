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
import app.thebox.core.sports.hub.EspnDetailMapper
import app.thebox.core.sports.hub.EspnSummaryDto
import app.thebox.core.sports.hub.EspnVideoMapper
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailSource
import app.thebox.core.sports.hub.VideoEntry
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §5.3, §5.5, §6.5; P2) ESPN's `summary?event={id}` for the shown NFL game, over
 * [EspnHttp] (the `ESPN_SUMMARY_MAX_BYTES` decoded cap, the WAF rule) on the `@ApiClient` client with `WireMeter`, a
 * `SPORTS_CALL_TIMEOUT_MS` call timeout and no cache:
 *
 * - **Streaming decode** into the frozen [EspnSummaryDto] (kotlinx `decodeFromStream`, `ignoreUnknownKeys`): the sections
 *   the DTO does not declare (`drives`, `news`, `winprobability`, `pickcenter`, odds, …) are skipped by the lexer and
 *   never built; the whole body is read (no early stop: ESPN's key order varies) and drained. No `Accept-Encoding` is
 *   set, so OkHttp un-gzips; the cap counts decoded bytes. Over the cap or a wrong spine: `BAD_BODY`, no partial detail.
 * - **Mapping** by [mapDetail] / [mapBox] on [mapDispatcher] (`Dispatchers.Default`), never on the caller's (the view
 *   model's Main) continuation; the DTO is dropped when this returns. A mapper that answers null or throws is `BAD_BODY`.
 * - **The answer** ([DetailAnswer]): the detail with `clockSkewMs` (`Date` − wall clock at receive, clamped to
 *   ±`HUB_CLOCK_SKEW_MAX_MS`, 0 without a `Date`) and `serverDateMs`, plus the box score from the same parse (§5.5), so
 *   the repository's loop never needs a second request for the Box score sheet.
 * - **The catalog**: every offered video entry (`EspnVideoMapper` at `now + skew`; http only from the debug override
 *   origin) is put into [catalog], with the skew; nothing is persisted.
 * - Cancellation cancels the OkHttp call, so a 600 KB body stops at once and its partial DTO is dropped.
 * - Nothing about the user, playlist or device is sent (the IP, the app's UA, an event id). Logs carry the path label
 *   "summary" and the result kind only (never a query, an id or a body).
 *
 * The mappers are constructor parameters so P2 tests the transport without P1's mapper.
 */
@Singleton
class EspnDetailSource internal constructor(
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> EspnEndpoints,
    private val catalog: EspnClipCatalog,
    /** A stand-in mapper for transport tests; null (production) is `EspnDetailMapper.detail(…, httpOk)` (integration §18). */
    private val mapDetail: ((EspnSummaryDto, GameId, Long?) -> GameDetail?)? = null,
    private val mapBox: (EspnSummaryDto) -> BoxScore? = EspnDetailMapper::box,
    /** Wall clock (epoch ms), for the skew and the offer check. */
    private val wallNow: () -> Long = System::currentTimeMillis,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
    private val mapDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GameDetailSource {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context, catalog: EspnClipCatalog) : this(
        client, ScoreRepositoryImpl.ELAPSED, endpoints = { EspnEndpoints.resolve(context) }, catalog = catalog,
    )

    private val http = EspnHttp(
        WireMeter.install(client.newBuilder(), client).callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).cache(null).build(),
        mono,
        TAG,
    )

    /** Resolved on first use (off the main thread: the repository's first request). */
    private val endpoints: EspnEndpoints by lazy(endpoints)

    /** http media and thumbnails only for the debug override (`EspnEndpoints.httpMediaOk`, the one rule; §4.3, §18). */
    private val httpOk: Boolean by lazy { this.endpoints.httpMediaOk }

    override suspend fun detail(id: GameId): Fetch<GameDetail> = detailAnswer(id, null).fetch

    /**
     * The detail with its box score from the same parse (§5.5). [phaseHint] (the board's phase) is not needed: the
     * summary's header carries the status.
     */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun detailAnswer(id: GameId, phaseHint: GamePhase?): DetailAnswer {
        if (!valid(id)) return DetailAnswer(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()), false)
        val url = endpoints.apiBase.toHttpUrl().newBuilder().addPathSegment("summary").addQueryParameter("event", id.id).build()
        val answer = http.call(url, EspnSummaryDto.serializer(), SportsConstants.ESPN_SUMMARY_MAX_BYTES)
        val result: DetailAnswer = when (val f = answer.fetch) {
            is Fetch.Ok -> mapped(id, f)
            Fetch.NotFound -> DetailAnswer(Fetch.NotFound, false)
            is Fetch.Failed -> DetailAnswer(f, false)
        }
        http.logTransition(LABEL, result.fetch, answer)
        return result
    }

    private suspend fun mapped(id: GameId, f: Fetch.Ok<EspnSummaryDto>): DetailAnswer {
        val wall = wallNow()
        val skew = f.serverDateMs?.let { EspnClipCatalog.skewOf(it, wall) } ?: 0L
        val dto = f.value
        val out = try {
            withContext(mapDispatcher) {
                val at = wall + skew
                // (Integration §18) The source owns the skew and the server date; the mapper leaves both at their defaults.
                val stand = mapDetail
                val mapped = if (stand != null) stand(dto, id, at) else EspnDetailMapper.detail(dto, id, at, httpOk)
                val detail = mapped?.copy(clockSkewMs = skew, serverDateMs = f.serverDateMs)
                val box = if (detail != null) mapBox(dto) else null
                val entries = if (detail != null) offered(dto, at) else emptyList()
                Triple(detail, box, entries)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) { // a mapper bug is a bad body, never a crash on the loop
            null
        }
        val detail = out?.first ?: return DetailAnswer(Fetch.Failed(FailKind.BAD_BODY, null, f.receivedMono), false)
        catalog.noteServerDate(f.serverDateMs, wall)
        catalog.put(out.third)
        val box = out.second?.let { Fetch.Ok(it, f.receivedMono, f.serverDateMs, f.ageS, f.maxAgeS) }
        return DetailAnswer(Fetch.Ok(detail, f.receivedMono, f.serverDateMs, f.ageS, f.maxAgeS, f.wireBytes), false, box)
    }

    /** The summary's offered video entries at [atMs] (≤ `HUB_NFL_MAX_CLIPS`), for the catalog. */
    private fun offered(dto: EspnSummaryDto, atMs: Long): List<VideoEntry> =
        dto.videos.orEmpty().asSequence()
            .mapNotNull { EspnVideoMapper.entry(it, atMs, httpOk) }
            .take(SportsConstants.HUB_NFL_MAX_CLIPS)
            .toList()

    /** One summary read mapped to the box; kept for the interface (the repository uses [detailAnswer]'s box). */
    override suspend fun boxScore(id: GameId): Fetch<BoxScore> {
        val a = detailAnswer(id, null)
        a.box?.let { return it }
        return when (val f = a.fetch) {
            is Fetch.Failed -> f
            Fetch.NotFound -> Fetch.NotFound
            is Fetch.Ok -> Fetch.Failed(FailKind.BAD_BODY, null, f.receivedMono)
        }
    }

    /** (§5.4) `onTrimMemory`: the catalog, except the clip last handed to the player. */
    internal fun trimMemory() = catalog.trimToServed()

    private fun valid(id: GameId): Boolean = id.league == League.NFL && EVENT_ID.matches(id.id)

    private companion object {
        const val TAG = "EspnDetail"
        const val LABEL = "summary"
        val EVENT_ID = Regex("""\d{1,12}""")
    }
}
