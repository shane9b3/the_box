package app.thebox.data.xtream

import app.thebox.core.common.Outcome
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.XtreamCatalogDecoder
import app.thebox.core.xtream.XtreamEpgListing
import app.thebox.data.contracts.FetchResult
import app.thebox.data.contracts.XtreamAction
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.contracts.XtreamConnection
import app.thebox.data.contracts.XtreamLogin
import app.thebox.data.di.ApiClient
import app.thebox.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

/** Xtream player_api client (docs/design/m1-data-layer.md §3.7.6). Commit-0 stub; WP-C owns this file. */
@Suppress("unused")
class XtreamClient @Inject constructor(
    @param:ApiClient private val http: OkHttpClient,
    private val decoder: XtreamCatalogDecoder,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : XtreamApi {
    override suspend fun login(conn: XtreamConnection): Outcome<XtreamLogin> = TODO("WP-C")

    override suspend fun download(
        conn: XtreamConnection,
        action: XtreamAction,
        params: Map<String, String>,
        dest: File,
    ): Outcome<FetchResult> = TODO("WP-C")

    override suspend fun shortEpg(conn: XtreamConnection, streamId: Long, timeBase: TimeBase, limit: Int): Outcome<List<XtreamEpgListing>> =
        TODO("WP-C")

    override suspend fun simpleDataTable(conn: XtreamConnection, streamId: Long, timeBase: TimeBase): Outcome<List<XtreamEpgListing>> =
        TODO("WP-C")
}
