package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipFormat
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipOffer
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.ClipStream
import app.thebox.core.sports.hub.EspnClipRecordDto
import app.thebox.core.sports.hub.EspnVideoMapper
import app.thebox.core.sports.hub.VideoEntry
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §4.2, §4.3, §4.7; P2) ESPN clips.
 *
 * - [info] is **catalog-only** (arch M2): a hit, or `NotFound`; never a request.
 * - [stream] with `fresh = false`: the catalog entry, else **one** record lookup. With `fresh = true` (the expired-link
 *   re-resolve and Try again, arch M3): one record lookup, bypassing the catalog; a 404 is `NotFound`, the same HLS URL as
 *   the catalog's is `Failed(HTTP_OTHER)` (never a replay of the dead URL), a new URL replaces the entry and plays. There
 *   is never a second lookup inside one call, and the resolver never reads a summary.
 * - Then `ClipOffer` at `now + skew` (the catalog's last ESPN `Date` skew): embargoed or within the expiry margin is
 *   [ClipResult.Expired]. HLS only (`links.source.HLS.href`), https (http only from the debug override origin); a record
 *   that maps to nothing playable (no https HLS, a device list without `settop`, an unknown coverage type) is
 *   `Failed(BAD_BODY)` ("can't play on this device").
 * - The record: `GET {clipBase}{id}` (`content.core.api.espn.com/v1/video/clips/`), `@ApiClient`, 10 s, through
 *   [EspnHttp] with the `ESPN_CLIP_RECORD_MAX` cap. 404 → `NotFound`; 429 / 5xx / IO → `Failed(kind, retryAfterS)`;
 *   401 / 403 / 202 with `x-amzn-waf-action` → `Failed(HTTP_429)`, never `Refused`, so NFL video is never switched off by
 *   a block (arch M6).
 * - A clip URL is never logged beyond its host and never registered with `Redact` (arch M5). Log lines carry a path label
 *   and the result kind; no id, no query, no body.
 */
@Singleton
class EspnClipResolver internal constructor(
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> EspnEndpoints,
    private val catalog: EspnClipCatalog,
    /** Wall clock (epoch ms), for the offer check at `now + skew`. */
    private val wallNow: () -> Long = System::currentTimeMillis,
) : ClipResolver {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context, catalog: EspnClipCatalog) : this(
        client, ScoreRepositoryImpl.ELAPSED, endpoints = { EspnEndpoints.resolve(context) }, catalog = catalog,
    )

    private val http = EspnHttp(
        WireMeter.install(client.newBuilder(), client)
            .callTimeout(LOOKUP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .cache(null)
            .build(),
        mono,
        TAG,
    )

    /** Resolved on first use (off the main thread). */
    private val endpoints: EspnEndpoints by lazy(endpoints)

    /** http media only from the debug override origin (§4.3). */
    private val httpOk: Boolean by lazy { this.endpoints.httpMediaOk }

    override suspend fun info(ref: ClipRef): ClipResult<ClipInfo> =
        catalog.get(ref.videoId)?.let { ClipResult.Ok(infoOf(ref, it)) } ?: ClipResult.NotFound

    override suspend fun stream(ref: ClipRef, fresh: Boolean): ClipResult<Pair<ClipInfo, ClipStream>> {
        val known = catalog.get(ref.videoId)
        val entry: VideoEntry = if (known != null && !fresh) {
            known
        } else {
            when (val r = lookup(ref.videoId)) {
                is Looked.Found -> {
                    if (fresh && known != null && r.entry.hlsUrl == known.hlsUrl) {
                        // The master failed with this very URL: replaying it would fail again (arch M3).
                        log("stream (fresh)", "same link")
                        return ClipResult.Failed(FailKind.HTTP_OTHER)
                    }
                    catalog.put(listOf(r.entry))
                    r.entry
                }
                is Looked.Result -> {
                    log(if (fresh) "stream (fresh)" else "stream", r.label)
                    return r.result
                }
            }
        }
        val at = wallNow() + catalog.skewMs
        if (!ClipOffer.offered(entry.clip.embargoMs, entry.clip.expiresMs, at)) {
            log("stream", "expired")
            return ClipResult.Expired
        }
        catalog.served(ref.videoId)
        BoxLog.i(TAG) { "stream: ok (${if (entry === known) "catalog" else "record"}, ${Redact.host(entry.hlsUrl)})" }
        return ClipResult.Ok(infoOf(ref, entry) to ClipStream(entry.hlsUrl, ClipFormat.HLS, captionsUrl = null))
    }

    private sealed interface Looked {
        class Found(val entry: VideoEntry) : Looked
        class Result(val result: ClipResult<Nothing>, val label: String) : Looked
    }

    /** The one record lookup of [videoId], mapped by `EspnVideoMapper` without the time check (done by the caller). */
    private suspend fun lookup(videoId: Long): Looked {
        val url = endpoints.clipBase.toHttpUrl().newBuilder().addPathSegment(videoId.toString()).build()
        val answer = http.call(url, EspnClipRecordDto.serializer(), SportsConstants.ESPN_CLIP_RECORD_MAX)
        // 401 is not covered by EspnHttp's WAF rule (403, 202 + header): the record host treats it as the same block.
        val fetch: Fetch<EspnClipRecordDto> = when (val f = answer.fetch) {
            is Fetch.Failed -> if (answer.code == HTTP_UNAUTHORIZED) f.copy(kind = FailKind.HTTP_429) else f
            else -> f
        }
        http.logTransition(LABEL, fetch, answer)
        return when (fetch) {
            is Fetch.Ok -> {
                catalog.noteServerDate(fetch.serverDateMs, wallNow())
                val videos = fetch.value.videos.orEmpty()
                val dto = videos.firstOrNull { (it.id as? JsonPrimitive)?.content?.trim() == videoId.toString() } ?: videos.firstOrNull()
                val entry = dto?.let { EspnVideoMapper.entry(it, null, httpOk) }?.takeIf { it.clip.videoId == videoId }
                if (entry == null) Looked.Result(ClipResult.Failed(FailKind.BAD_BODY), "nothing playable") else Looked.Found(entry)
            }
            Fetch.NotFound -> Looked.Result(ClipResult.NotFound, "not found")
            is Fetch.Failed -> Looked.Result(ClipResult.Failed(fetch.kind, fetch.retryAfterS), fetch.kind.name)
        }
    }

    private fun infoOf(ref: ClipRef, e: VideoEntry) =
        ClipInfo(ref, e.clip.title, description = null, durationMs = e.clip.durationMs, posterUrl = null, thumbnailUrl = e.clip.thumbUrl)

    private fun log(what: String, result: String) = BoxLog.w(TAG, "$what: $result")

    internal companion object {
        private const val TAG = "EspnVideo"
        private const val LABEL = "video/clips/{id}"
        private const val HTTP_UNAUTHORIZED = 401
        const val LOOKUP_CALL_TIMEOUT_MS = 10_000L
    }
}
