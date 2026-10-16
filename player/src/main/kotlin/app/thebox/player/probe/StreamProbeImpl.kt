package app.thebox.player.probe

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.ConnectionHandle
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.LeaseKind
import app.thebox.core.setup.ProbeResult
import app.thebox.core.setup.ProbeVerdict
import app.thebox.core.setup.SetupConstants
import app.thebox.core.setup.StreamProbe
import app.thebox.player.datasource.PlaybackHttpClients
import app.thebox.player.internal.StreamBrokerRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The onboarding UA probe (docs/design/m5-setup-settings.md §2.4, §3.4.5): one short GET of a live stream through the
 * playlist's broker, to learn whether the provider answers our user agent.
 *
 * Rules, each tested in `StreamProbeImplTest`:
 * - **One `PROBE` lease** owned by [SetupConstants.PROBE_OWNER_ID] from the playlist's broker (`p:<id>`). The core's
 *   `acquire(PROBE)` would pre-empt another owner's PLAYBACK lease when the slots are full, so the probe checks the
 *   snapshot first and answers LIMIT without acquiring: **the probe never pre-empts playback** (S12). Residual race:
 *   a tune that takes the last slot between the snapshot and the acquire would be pre-empted; nothing plays while
 *   onboarding runs, and from Settings the tuned stream already holds its slot when the probe starts.
 * - **No `Range` header**, ever (CLAUDE.md: live TS); a caller's `Range` is dropped.
 * - Reads at most [SetupConstants.PROBE_BYTES] or for [SetupConstants.PROBE_MS] after the body starts; the whole call
 *   is bounded by [SetupConstants.PROBE_CALL_TIMEOUT_MS].
 * - **Always closes**: the call is cancelled and the lease released on every path, including coroutine cancellation
 *   (a watcher cancels the call, which unblocks the IO thread) and a pre-emption by a tune (verdict LIMIT).
 * - Verdict from the status and the sniffed bytes: 0x47 TS sync → MEDIA, `#EXTM3U` → HLS, markup → HTML.
 * - Logs carry the verdict, status and byte count only, never the URL.
 */
@Singleton
class StreamProbeImpl internal constructor(
    private val brokerFor: (String) -> ConnectionBrokerCore,
    private val client: () -> Call.Factory,
    private val io: CoroutineDispatcher,
    private val clockMs: () -> Long,
) : StreamProbe {

    @Inject internal constructor(brokers: StreamBrokerRegistry, clients: PlaybackHttpClients) :
        this({ brokers.broker(it) }, { clients.live(ignoreTlsErrors = false) }, Dispatchers.IO, { System.nanoTime() / 1_000_000 })

    override suspend fun probe(playlistId: Long, url: String, headers: Map<String, String>, userAgent: String?): ProbeResult {
        val started = clockMs()
        val broker = brokerFor(brokerKey(playlistId))
        val snap = broker.snapshot()
        if (snap.active + snap.closing >= snap.max) return done(ProbeVerdict.LIMIT, null, 0, started)
        val lease = try {
            broker.acquire(LeaseKind.PROBE, SetupConstants.PROBE_OWNER_ID, 0)
        } catch (e: ConnectionLimitException) {
            return done(ProbeVerdict.LIMIT, null, 0, started)
        }
        val request = request(url, headers, userAgent)
        if (request == null) {
            lease.release()
            return done(ProbeVerdict.OTHER, null, 0, started)
        }
        val call = client().newCall(request)
        call.timeout().timeout(SetupConstants.PROBE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val handle = ConnectionHandle { call.cancel() }
        if (!lease.attach(handle)) {
            call.cancel()
            lease.release()
            return done(ProbeVerdict.LIMIT, null, 0, started)
        }
        try {
            return coroutineScope {
                // Coroutine cancellation → cancel the call, which closes the socket and unblocks the IO thread.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    withContext(io) { run(call, lease, started) }
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            call.cancel()
            lease.detach(handle)
            lease.release()
        }
    }

    private fun run(call: Call, lease: CoreLease, started: Long): ProbeResult {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            return done(failure(e, lease), null, 0, started)
        }
        response.use { r ->
            val status = r.code
            when (status) {
                401, 403 -> return done(ProbeVerdict.FORBIDDEN, status, 0, started)
                404, 410 -> return done(ProbeVerdict.NOT_FOUND, status, 0, started)
                // Provider connection limits (458 "user limit", 509): someone else is watching (§3.4.5).
                458, 509 -> return done(ProbeVerdict.LIMIT, status, 0, started)
            }
            if (status !in 200..299) return done(ProbeVerdict.OTHER, status, 0, started)
            val type = r.header("Content-Type")?.lowercase()
            val head = ByteArray(HEAD_BYTES)
            var headLen = 0
            var total = 0L
            val bodyStart = clockMs()
            val buf = ByteArray(8 * 1024)
            try {
                val input = r.body.byteStream()
                while (total < SetupConstants.PROBE_BYTES && clockMs() - bodyStart < SetupConstants.PROBE_MS) {
                    val want = minOf(buf.size.toLong(), SetupConstants.PROBE_BYTES - total).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    if (headLen < head.size) {
                        val k = minOf(n, head.size - headLen)
                        System.arraycopy(buf, 0, head, headLen, k)
                        headLen += k
                    }
                    total += n
                    // Text answers are decided by their first bytes: no need to read on.
                    if (headLen >= TEXT_SNIFF_BYTES) {
                        val v = sniff(head, headLen, type)
                        if (v == ProbeVerdict.HLS || v == ProbeVerdict.HTML) return done(v, status, total, started)
                    }
                }
            } catch (e: IOException) {
                // A body that stops after the first bytes still tells us what the server sends.
                if (headLen == 0) return done(failure(e, lease), status, total, started)
                if (lease.isPreempted) return done(ProbeVerdict.LIMIT, status, total, started)
            }
            return done(if (headLen == 0) ProbeVerdict.OTHER else sniff(head, headLen, type), status, total, started)
        }
    }

    private fun failure(e: IOException, lease: CoreLease): ProbeVerdict = when {
        lease.isPreempted -> ProbeVerdict.LIMIT
        e is InterruptedIOException -> ProbeVerdict.TIMEOUT
        else -> ProbeVerdict.NETWORK
    }

    private fun done(v: ProbeVerdict, status: Int?, bytes: Long, started: Long): ProbeResult {
        val ms = (clockMs() - started).coerceAtLeast(0)
        BoxLog.i(TAG) { "probe ${v.name} status=${status ?: "-"} bytes=$bytes ms=$ms" }
        return ProbeResult(v, status, bytes, ms)
    }

    companion object {
        private const val TAG = "StreamProbe"
        /** Bytes kept for sniffing: enough for two TS packets after an unaligned start. */
        internal const val HEAD_BYTES = 2 * 188 + 188
        /** Text answers (`#EXTM3U`, markup) are decided after this many bytes. */
        private const val TEXT_SNIFF_BYTES = 64

        fun brokerKey(playlistId: Long) = "p:$playlistId"

        /** GET without `Range`; the caller's UA wins over a `User-Agent` in [headers]. Null for a URL OkHttp refuses. */
        internal fun request(url: String, headers: Map<String, String>, userAgent: String?): Request? {
            val u = url.toHttpUrlOrNull() ?: return null
            val b = Request.Builder().url(u).get()
            for ((k, v) in headers) {
                if (k.equals("Range", ignoreCase = true) || k.equals("User-Agent", ignoreCase = true)) continue
                runCatching { b.header(k, v) }
            }
            val ua = userAgent?.takeIf { it.isNotBlank() } ?: headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            if (!ua.isNullOrBlank()) runCatching { b.header("User-Agent", ua) }
            b.removeHeader("Range")
            return b.build()
        }

        /** Verdict from the first bytes: TS sync (aligned or not), an HLS playlist, markup, or a media content type. */
        internal fun sniff(head: ByteArray, len: Int, contentType: String?): ProbeVerdict {
            for (i in 0 until minOf(188, len)) {
                if (head[i] == SYNC && (i + 188 >= len || head[i + 188] == SYNC)) {
                    if (i == 0 || i + 188 < len) return ProbeVerdict.MEDIA
                }
            }
            var s = 0
            if (len >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) s = 3
            while (s < len && (head[s] == ' '.code.toByte() || head[s] == '\n'.code.toByte() || head[s] == '\r'.code.toByte() || head[s] == '\t'.code.toByte())) s++
            val text = String(head, s, minOf(len - s, 32), Charsets.ISO_8859_1).lowercase()
            if (text.startsWith("#extm3u")) return ProbeVerdict.HLS
            if (text.startsWith("<") || contentType?.startsWith("text/html") == true) return ProbeVerdict.HTML
            if (contentType != null && (contentType.startsWith("video/") || contentType.startsWith("audio/"))) return ProbeVerdict.MEDIA
            if (contentType != null && ("mpegurl" in contentType)) return ProbeVerdict.HLS
            return ProbeVerdict.OTHER
        }

        private const val SYNC: Byte = 0x47
    }
}
