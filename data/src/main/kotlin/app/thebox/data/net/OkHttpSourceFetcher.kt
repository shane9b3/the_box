package app.thebox.data.net

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.data.contracts.FetchRequest
import app.thebox.data.contracts.FetchResult
import app.thebox.data.contracts.SourceFetcher
import app.thebox.data.contracts.TimeoutProfile
import app.thebox.data.di.DownloadClient
import app.thebox.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Conditional GET to `dest.part`, then rename to [dest] (docs/design/m1-data-layer.md §1.6, §4.1).
 * - `If-None-Match` / `If-Modified-Since` from the request; 304 → `notModified`, [dest] untouched.
 * - The body streams to disk (never into memory); more than `maxBytes` aborts with Unsupported and
 *   deletes the part file. No `Accept-Encoding` is set, so OkHttp decodes `Content-Encoding: gzip`
 *   transparently; `.gz` files land compressed and are sniffed by `Compression`.
 * - Coroutine cancellation cancels the call and deletes the part file.
 */
class OkHttpSourceFetcher @Inject constructor(
    @param:DownloadClient private val http: OkHttpClient,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : SourceFetcher {
    private val apiClient: OkHttpClient by lazy { http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }

    override suspend fun fetch(request: FetchRequest, dest: File): Outcome<FetchResult> = withContext(io) {
        val part = File(dest.path + ".part")
        val httpRequest = try {
            val builder = Request.Builder().url(request.url)
            for ((k, v) in request.headers) builder.header(k, v)
            request.etag?.let { builder.header("If-None-Match", it) }
            request.lastModified?.let { builder.header("If-Modified-Since", it) }
            builder.build()
        } catch (e: IllegalArgumentException) {
            return@withContext Outcome.Err(SourceError.Unsupported("bad url: ${Redact.text(request.url)}"))
        }
        val client = if (request.profile == TimeoutProfile.API) apiClient else http
        try {
            client.newCall(httpRequest).await().use { response ->
                val etag = response.header("ETag")
                val lastModified = response.header("Last-Modified")
                if (response.code == 304) {
                    BoxLog.i(TAG) { "not modified: ${request.url}" }
                    return@withContext Outcome.Ok(
                        FetchResult(true, 0, etag ?: request.etag, lastModified ?: request.lastModified, null, null),
                    )
                }
                if (!response.isSuccessful) return@withContext Outcome.Err(NetErrors.fromStatus(response.code))
                val body = response.body
                val declared = body.contentLength()
                if (declared > request.maxBytes) return@withContext Outcome.Err(tooLarge(request.maxBytes))
                part.parentFile?.mkdirs()
                var total = 0L
                body.byteStream().use { input ->
                    part.outputStream().buffered(BUFFER).use { out ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > request.maxBytes) {
                                out.close()
                                part.delete()
                                return@withContext Outcome.Err(tooLarge(request.maxBytes))
                            }
                            out.write(buf, 0, n)
                            coroutineContext.ensureActive()
                        }
                    }
                }
                if (dest.exists() && !dest.delete()) throw IOException("cannot replace ${dest.name}")
                if (!part.renameTo(dest)) throw IOException("cannot rename ${part.name}")
                val contentType = body.contentType()
                BoxLog.i(TAG) { "fetched ${request.url}: $total bytes" }
                Outcome.Ok(FetchResult(false, total, etag, lastModified, contentType?.let { "${it.type}/${it.subtype}" }, contentType?.charset()?.name()))
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            BoxLog.w(TAG, "fetch failed for ${request.url}: ${e.javaClass.simpleName}")
            Outcome.Err(NetErrors.fromIo(e))
        }
    }

    private fun tooLarge(max: Long) = SourceError.Unsupported("file larger than $max bytes")

    companion object {
        private const val TAG = "OkHttpSourceFetcher"
        private const val BUFFER = 64 * 1024
    }
}

/** Suspends on an OkHttp call; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
