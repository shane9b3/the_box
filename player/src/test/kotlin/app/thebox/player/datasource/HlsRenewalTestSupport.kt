package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The captured hand-over (docs/design/m6-hls-edge-handover.md §1.2) as two (or three) local servers:
 *
 * - **origin** O: `/live/u/p/7.m3u8` answers every request with a 302 to a new tokenised media playlist on **edge**
 *   (`/hls/<token>/index.m3u8`), unless [originMode] says otherwise;
 * - **edge**: serves the playlist of every issued token at the current [msn] (6 segments, [td] s, absolute-path
 *   references `/hls/<token>/seg_<n>.ts`, no keys, no PDT, no discontinuities); a token in [expired] answers its
 *   playlist with a 302 to [deadUrl] (a closed local port), while its segments still answer 200 (as measured);
 * - **edge2**: the same edge logic on another port, for "the edge really changed".
 */
internal class FakeOriginEdge(var td: Int = 11, private val window: Int = 6) : AutoCloseable {
    enum class OriginMode { REDIRECT, REDIRECT_EDGE2, DIRECT, MULTIVARIANT, STATUS, STALL }

    val origin = MockWebServer()
    val edge = MockWebServer()
    val edge2 = MockWebServer()

    @Volatile var originMode = OriginMode.REDIRECT
    @Volatile var originStatus = 403
    /** First MSN of the window served by every playlist. */
    @Volatile var msn = 3269L
    /** Advance [msn] by one after every playlist served (the tracker test's clock). */
    @Volatile var autoAdvance = false
    /** When set, the first playlist of each newly issued token starts here instead of [msn] (and [msn] follows). */
    @Volatile var renewalMsn: ((Long) -> Long)? = null
    @Volatile var renewalTd: Int? = null
    @Volatile var dseq: Long? = null
    @Volatile var renewalDseq: Long? = null
    @Volatile var renewalEndList = false
    @Volatile var renewalNamePrefix: String? = null
    @Volatile var segmentStatus = 200
    @Volatile var onOrigin: (() -> Unit)? = null

    val tokens = CopyOnWriteArrayList<String>()
    val expired: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val firstServed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val tokenPrefix = ConcurrentHashMap<String, String>()
    val originHits = AtomicInteger()
    val originRequests = CopyOnWriteArrayList<RecordedRequest>()
    val edgeRequests = CopyOnWriteArrayList<RecordedRequest>()
    val deadUrl: String = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}/dead/index.m3u8" }

    val originUrl: String get() = origin.url("/live/u/p/7.m3u8").toString()

    init {
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = originResponse(request)
        }
        val edgeDispatcher = { server: MockWebServer ->
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = edgeResponse(request)
            }
        }
        edge.dispatcher = edgeDispatcher(edge)
        edge2.dispatcher = edgeDispatcher(edge2)
        origin.start()
        edge.start()
        edge2.start()
    }

    /** The token the origin issues next is stored here; the n-th issued token is `tok<n>`. */
    fun token(n: Int) = "tok$n"

    fun playlistUrl(token: String, server: MockWebServer = edge) = server.url("/hls/$token/index.m3u8").toString()

    /** Expire every issued token so far (its playlist now redirects to the dead address). */
    fun expireAll() {
        expired += tokens
    }

    fun edgePaths(): List<String> = edgeRequests.map { it.url.encodedPath }

    private fun originResponse(request: RecordedRequest): MockResponse {
        originHits.incrementAndGet()
        originRequests += request
        onOrigin?.invoke()
        return when (originMode) {
            OriginMode.REDIRECT, OriginMode.REDIRECT_EDGE2, OriginMode.MULTIVARIANT -> {
                val tok = token(tokens.size + 1)
                tokens += tok
                renewalNamePrefix?.let { if (tokens.size > 1) tokenPrefix[tok] = it }
                val server = if (originMode == OriginMode.REDIRECT_EDGE2) edge2 else edge
                val file = if (originMode == OriginMode.MULTIVARIANT) "master.m3u8" else "index.m3u8"
                MockResponse.Builder().code(302).addHeader("Location", server.url("/hls/$tok/$file").toString()).build()
            }
            OriginMode.DIRECT -> playlistResponse("direct", origin = true)
            OriginMode.STATUS -> MockResponse.Builder().code(originStatus).addHeader("X-Secret", "origin-header").body("origin-error-body").build()
            OriginMode.STALL -> MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).body("#EXTM3U\n").build()
        }
    }

    private fun edgeResponse(request: RecordedRequest): MockResponse {
        edgeRequests += request
        val parts = request.url.encodedPath.trim('/').split('/')
        if (parts.size != 3 || parts[0] != "hls") return MockResponse.Builder().code(404).build()
        val tok = parts[1]
        val file = parts[2]
        if (tok !in tokens) return MockResponse.Builder().code(404).build()
        return when {
            file == "master.m3u8" -> MockResponse.Builder().code(200)
                .body("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nindex.m3u8\n").build()
            file == "index.m3u8" && tok in expired ->
                MockResponse.Builder().code(302).addHeader("Location", deadUrl).build()
            file == "index.m3u8" -> playlistResponse(tok, origin = false)
            file.startsWith("seg_") -> MockResponse.Builder().code(segmentStatus).body("x".repeat(376)).build()
            else -> MockResponse.Builder().code(404).build()
        }
    }

    @Synchronized private fun playlistResponse(tok: String, origin: Boolean): MockResponse {
        val first = tokens.size > 1 && firstServed.add(tok) && !origin
        if (first) renewalMsn?.let { msn = it(msn) }
        val tdNow = if (first) renewalTd ?: td else td
        val ds = if (first && renewalDseq != null) renewalDseq else dseq
        val prefix = tokenPrefix[tok] ?: "seg_"
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$tdNow\n#EXT-X-MEDIA-SEQUENCE:$msn\n")
        if (ds != null) sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(ds).append('\n')
        for (i in 0 until window) {
            val n = msn + i
            sb.append("#EXTINF:").append(tdNow).append(".000,\n")
            if (origin) sb.append("/seg/").append(n).append(".ts\n") else sb.append("/hls/").append(tok).append('/').append(prefix).append(n).append(".ts\n")
        }
        if (first && renewalEndList) sb.append("#EXT-X-ENDLIST\n")
        if (first) td = tdNow
        if (autoAdvance) msn++
        return MockResponse.Builder().code(200).addHeader("Content-Type", "application/vnd.apple.mpegurl").body(sb.toString()).build()
    }

    override fun close() {
        origin.close()
        edge.close()
        edge2.close()
    }

    @OptIn(UnstableApi::class)
    companion object {
        fun readAll(ds: DataSource): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
                out.write(buf, 0, n)
            }
        }

        fun manifest(uri: String): DataSpec = DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build()
    }
}
