package app.thebox.data.sports

import android.content.Context
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.ClipStream
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §4.2, §4.3, §4.7; P2) ESPN clips. [info] is catalog-only (a hit, or `NotFound`;
 * never a request). [stream]: the catalog entry, else the record lookup (`EspnEndpoints.clipBase` + id, `@ApiClient`,
 * 10 s, `ESPN_CLIP_RECORD_MAX`, through `EspnHttp`); then `ClipOffer` at `now + skew` (else `Expired`); `fresh = true`
 * bypasses the catalog. 401 / 403 / 202 with `x-amzn-waf-action` → `Failed(HTTP_429)`, never `Refused`. A clip URL is
 * never logged beyond its host and never registered with `Redact`.
 *
 * Commit 0: a stub answering `NotFound` without a request.
 */
@Singleton
class EspnClipResolver internal constructor(
    private val client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> EspnEndpoints,
    private val catalog: EspnClipCatalog,
) : ClipResolver {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context, catalog: EspnClipCatalog) : this(
        client, ScoreRepositoryImpl.ELAPSED, endpoints = { EspnEndpoints.resolve(context) }, catalog = catalog,
    )

    /** Resolved on first use (off the main thread). */
    private val endpoints: EspnEndpoints by lazy(endpoints)

    @Suppress("UNUSED_PARAMETER")
    override suspend fun info(ref: ClipRef): ClipResult<ClipInfo> = ClipResult.NotFound // P2

    @Suppress("UNUSED_PARAMETER")
    override suspend fun stream(ref: ClipRef, fresh: Boolean): ClipResult<Pair<ClipInfo, ClipStream>> = ClipResult.NotFound // P2
}
