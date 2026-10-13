package app.thebox.data.xtream

import app.thebox.core.common.AuthReason
import app.thebox.core.common.BoxLog
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.LenientJson
import app.thebox.core.xtream.XtreamCatalogDecoder
import app.thebox.core.xtream.XtreamEpgListing
import app.thebox.core.xtream.XtreamJson
import app.thebox.core.xtream.XtreamPayloadKind
import app.thebox.core.xtream.XtreamUrls
import app.thebox.data.contracts.FetchResult
import app.thebox.data.contracts.XtreamAction
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.contracts.XtreamConnection
import app.thebox.data.contracts.XtreamLogin
import app.thebox.data.di.ApiClient
import app.thebox.data.di.DownloadClient
import app.thebox.data.di.IoDispatcher
import app.thebox.data.net.HttpModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.buffer
import okio.sink
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Xtream player_api client (docs/design/m1-data-layer.md §3.7.6).
 *
 * - Base rotation from `preferredBaseIndex` on IO failures and 5xx/520–524; `Network`/`Http` once all fail.
 * - At most [MAX_CONCURRENT] requests per playlist.
 * - 429: back off 2, 4, 8 s (`Retry-After` honoured up to 30 s), then `Http(429)`.
 * - Auth: 401/513, HTTP 200 with `auth=0`, `[]` or an empty body → `Auth`; HTML on login → `Auth(UNKNOWN)`.
 * - Content-Type is never trusted: bodies are sniffed.
 * - Downloads stream to `dest.part` and are renamed on success. Coroutine cancellation cancels the `Call`.
 */
class XtreamClient internal constructor(
    private val http: OkHttpClient,
    /** Large lists (get_live_streams without a category) use the download profile (amended 2026-10-13: injected). */
    private val downloadHttp: OkHttpClient,
    private val decoder: XtreamCatalogDecoder,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) : XtreamApi {

    @Inject constructor(
        @ApiClient http: OkHttpClient,
        @DownloadClient downloadHttp: OkHttpClient,
        decoder: XtreamCatalogDecoder,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(http, downloadHttp, decoder, io, { System.currentTimeMillis() }, { delay(it) })

    /** Without Hilt (tests): the download profile is derived from [http] the way [HttpModule.downloadClient] does. */
    constructor(
        http: OkHttpClient,
        decoder: XtreamCatalogDecoder,
        io: CoroutineDispatcher,
    ) : this(http, HttpModule.downloadClient(http), decoder, io)

    private val permits = ConcurrentHashMap<Long, Semaphore>()

    override suspend fun login(conn: XtreamConnection): Outcome<XtreamLogin> = request(conn, http, { it.playerApi(null) }) { resp ->
        val measuredAt = clock()
        val bytes = readCapped(resp, MAX_SMALL_BODY)
        decoder.account(ByteArrayInputStream(bytes), measuredAt)
    }.let { r ->
        when (r) {
            is Outcome.Ok -> Outcome.Ok(XtreamLogin(r.value.first, r.value.second))
            is Outcome.Err -> r
        }
    }

    override suspend fun download(
        conn: XtreamConnection,
        action: XtreamAction,
        params: Map<String, String>,
        dest: File,
    ): Outcome<FetchResult> {
        val first = downloadOnce(conn, action, params, dest)
        // get_series_info: some forks want `series=` instead of `series_id=` and answer `[]`/`{}`/nothing otherwise.
        if (action == XtreamAction.SERIES_INFO && first is Outcome.Ok && "series_id" in params && isEmptyPayload(dest)) {
            val alt = LinkedHashMap<String, String>()
            for ((k, v) in params) alt[if (k == "series_id") "series" else k] = v
            BoxLog.i(TAG) { "get_series_info empty with series_id, retrying with series=" }
            return downloadOnce(conn, action, alt, dest)
        }
        return first
    }

    override suspend fun shortEpg(conn: XtreamConnection, streamId: Long, timeBase: TimeBase, limit: Int): Outcome<List<XtreamEpgListing>> =
        epg(conn, XtreamAction.SHORT_EPG, linkedMapOf("stream_id" to streamId.toString(), "limit" to limit.toString()), timeBase)

    override suspend fun simpleDataTable(conn: XtreamConnection, streamId: Long, timeBase: TimeBase): Outcome<List<XtreamEpgListing>> =
        epg(conn, XtreamAction.SIMPLE_DATA_TABLE, linkedMapOf("stream_id" to streamId.toString()), timeBase)

    // ---------------------------------------------------------------- internals

    private suspend fun epg(conn: XtreamConnection, action: XtreamAction, params: Map<String, String>, timeBase: TimeBase) =
        request(conn, http, { it.playerApi(action.wire, params) }) { resp ->
            val bytes = readCapped(resp, MAX_EPG_BODY)
            when (XtreamJson.sniffKind(bytes, minOf(bytes.size, 256))) {
                XtreamPayloadKind.EMPTY -> emptyList()
                else -> {
                    checkAuthObject(bytes)
                    decoder.epgListings(ByteArrayInputStream(bytes), timeBase)
                }
            }
        }.let { r ->
            when (r) {
                is Outcome.Ok -> Outcome.Ok(r.value.first)
                is Outcome.Err -> r
            }
        }

    private suspend fun downloadOnce(conn: XtreamConnection, action: XtreamAction, params: Map<String, String>, dest: File): Outcome<FetchResult> {
        val part = File(dest.path + ".part")
        val allowEmpty = action == XtreamAction.SERIES_INFO || action == XtreamAction.VOD_INFO
        val r = try {
            requestDownload(conn, action, params, dest, part, allowEmpty)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        }
        return when (r) {
            is Outcome.Ok -> withContext(io) {
                if (dest.exists() && !dest.delete()) {
                    part.delete()
                    return@withContext Outcome.Err(SourceError.Internal("cannot replace ${dest.name}"))
                }
                if (!part.renameTo(dest)) {
                    part.delete()
                    return@withContext Outcome.Err(SourceError.Internal("cannot rename ${part.name}"))
                }
                Outcome.Ok(r.value.first)
            }
            is Outcome.Err -> {
                withContext(io) { part.delete() }
                r
            }
        }
    }

    private suspend fun requestDownload(
        conn: XtreamConnection,
        action: XtreamAction,
        params: Map<String, String>,
        dest: File,
        part: File,
        allowEmpty: Boolean,
    ): Outcome<Pair<FetchResult, Int>> =
        request(conn, downloadHttp, { it.playerApi(action.wire, params) }) { resp ->
            dest.parentFile?.mkdirs()
            val prefixBuf = Buffer()
            var total = 0L
            resp.body.source().use { src ->
                part.sink().buffer().use { sink ->
                    val buf = Buffer()
                    while (true) {
                        val n = src.read(buf, CHUNK)
                        if (n == -1L) break
                        if (prefixBuf.size < PREFIX_BYTES) {
                            buf.copyTo(prefixBuf, 0, minOf(PREFIX_BYTES - prefixBuf.size, buf.size))
                        }
                        total += n
                        sink.write(buf, buf.size)
                    }
                }
            }
            val prefix = prefixBuf.readByteArray()
            when (XtreamJson.sniffKind(prefix)) {
                XtreamPayloadKind.EMPTY -> if (!allowEmpty) throw SourceException(SourceError.NotASource(NotASourceKind.EMPTY))
                XtreamPayloadKind.HTML -> throw SourceException(SourceError.NotASource(NotASourceKind.HTML_PAGE))
                XtreamPayloadKind.OTHER -> throw SourceException(SourceError.NotASource(NotASourceKind.WRONG_FORMAT))
                XtreamPayloadKind.OBJECT -> if (total <= MAX_SMALL_BODY) checkAuthObject(part.readBytes())
                XtreamPayloadKind.ARRAY -> Unit
            }
            val ct = resp.body.contentType()
            FetchResult(
                notModified = false,
                bytes = total,
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified"),
                contentType = ct?.let { "${it.type}/${it.subtype}" },
                charset = ct?.charset()?.name(),
            )
        }

    /** `{"user_info":{"auth":0}}` (and other user_info-only bodies with auth false) on an action → Auth. */
    private fun checkAuthObject(bytes: ByteArray) {
        if (XtreamJson.sniffKind(bytes, minOf(bytes.size, 256)) != XtreamPayloadKind.OBJECT) return
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
        if (!head.contains("\"user_info\"")) return
        val root = try {
            XtreamJson.tree(ByteArrayInputStream(bytes)) as? JsonObject
        } catch (_: SourceException) {
            null
        } ?: return
        val ui = LenientJson.obj(root, "user_info") ?: return
        if (LenientJson.bool(ui, "auth") == false) throw SourceException(SourceError.Auth(AuthReason.BAD_CREDENTIALS))
    }

    private fun isEmptyPayload(f: File): Boolean {
        if (!f.exists() || f.length() == 0L) return true
        if (f.length() > 64) return false
        val s = f.readText().trim().trimStart('﻿').trim()
        return s.isEmpty() || s == "[]" || s == "{}" || s == "null"
    }

    private sealed interface Attempt<out T> {
        data class Done<T>(val value: T) : Attempt<T>
        data class Status(val code: Int, val retryAfter: String?) : Attempt<Nothing>
    }

    /**
     * Runs one logical request with rotation, 429 backoff and the per-playlist semaphore. [handle] runs on the
     * OkHttp thread with a 2xx response and may throw [SourceException]. Returns the value and the base index used.
     */
    private suspend fun <T> request(
        conn: XtreamConnection,
        client: OkHttpClient,
        url: (XtreamUrls) -> String,
        handle: (Response) -> T,
    ): Outcome<Pair<T, Int>> {
        Redact.registerSecret(conn.username)
        Redact.registerSecret(conn.password)
        val bases = conn.baseUrls
        if (bases.isEmpty()) return Outcome.Err(SourceError.Internal("no Xtream base URL"))
        val permit = permits.computeIfAbsent(conn.playlistId) { Semaphore(MAX_CONCURRENT) }
        return permit.withPermit {
            var last: SourceError = SourceError.Network(app.thebox.core.common.NetworkKind.OTHER)
            val start = Math.floorMod(conn.preferredBaseIndex, bases.size)
            for (k in bases.indices) {
                val idx = (start + k) % bases.size
                val urls = XtreamUrls(bases[idx], conn.username, conn.password)
                val req = buildRequest(conn, url(urls))
                var retries429 = 0
                while (true) {
                    val attempt = try {
                        call(client, req, handle)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SourceException) {
                        return@withPermit Outcome.Err(e.error)
                    } catch (e: IOException) {
                        last = XtreamHttpErrors.fromIo(e)
                        BoxLog.w(TAG, "base #$idx failed (${last.code}); ${if (k + 1 < bases.size) "rotating" else "no more bases"}")
                        null
                    }
                    when (attempt) {
                        null -> break
                        is Attempt.Done -> return@withPermit Outcome.Ok(attempt.value to idx)
                        is Attempt.Status -> {
                            if (attempt.code == 429) {
                                if (retries429 >= XtreamHttpErrors.MAX_429_RETRIES) return@withPermit Outcome.Err(SourceError.Http(429))
                                val wait = XtreamHttpErrors.retryDelayMs(attempt.retryAfter, retries429)
                                retries429++
                                BoxLog.i(TAG) { "429 from base #$idx: retry $retries429 in ${wait}ms" }
                                sleep(wait)
                                continue
                            }
                            if (XtreamHttpErrors.isRotatable(attempt.code)) {
                                last = SourceError.Http(attempt.code)
                                BoxLog.w(TAG, "base #$idx answered ${attempt.code}; ${if (k + 1 < bases.size) "rotating" else "no more bases"}")
                                break
                            }
                            return@withPermit Outcome.Err(XtreamHttpErrors.fromStatus(attempt.code))
                        }
                    }
                }
            }
            Outcome.Err(last)
        }
    }

    private fun buildRequest(conn: XtreamConnection, url: String): Request {
        val b = Request.Builder().url(url)
        var headerUa: String? = null
        for ((k, v) in conn.headers) {
            if (k.equals("User-Agent", ignoreCase = true)) headerUa = v else b.header(k, v)
        }
        b.header("User-Agent", conn.userAgent?.takeIf { it.isNotBlank() } ?: headerUa ?: DEFAULT_USER_AGENT)
        return b.build()
    }

    private suspend fun <T> call(client: OkHttpClient, req: Request, handle: (Response) -> T): Attempt<T> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { r ->
                            if (!r.isSuccessful) Attempt.Status(r.code, r.header("Retry-After")) else Attempt.Done(handle(r))
                        }
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }

    private fun readCapped(resp: Response, limit: Long): ByteArray {
        val src = resp.body.source()
        val buf = Buffer()
        while (buf.size <= limit) {
            if (src.read(buf, CHUNK) == -1L) return buf.readByteArray()
        }
        throw SourceException(SourceError.Parse("response larger than $limit bytes"))
    }

    companion object {
        private const val TAG = "XtreamClient"
        const val MAX_CONCURRENT = 3
        const val DEFAULT_USER_AGENT = "TheBox/1.0 (Linux; Android TV)"
        private const val MAX_SMALL_BODY = 2L * 1024 * 1024
        private const val MAX_EPG_BODY = 16L * 1024 * 1024
        private const val PREFIX_BYTES = 256L
        private const val CHUNK = 64L * 1024
    }
}
