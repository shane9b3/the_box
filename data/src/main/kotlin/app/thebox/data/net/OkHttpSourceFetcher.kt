package app.thebox.data.net

import app.thebox.core.common.Outcome
import app.thebox.data.contracts.FetchRequest
import app.thebox.data.contracts.FetchResult
import app.thebox.data.contracts.SourceFetcher
import app.thebox.data.di.DownloadClient
import app.thebox.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

/** Conditional GET to `dest.part`, then rename (docs/design/m1-data-layer.md §1.6). Commit-0 stub; WP-D owns this file. */
@Suppress("unused")
class OkHttpSourceFetcher @Inject constructor(
    @param:DownloadClient private val http: OkHttpClient,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : SourceFetcher {
    override suspend fun fetch(request: FetchRequest, dest: File): Outcome<FetchResult> = TODO("WP-D")
}
