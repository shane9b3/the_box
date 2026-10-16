package app.thebox.core.setup.lan

import app.thebox.core.common.BoxLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The phone page's own HTTP/1.1 server (docs/design/m5-setup-settings.md §0.3, §3.5.2), pure JVM over a [LanTransport].
 * One accept thread (`box-lan`), at most [Config.maxSockets] concurrent connections served by a pool of that size
 * (more are answered 503 and closed), `Connection: close` on every response (no keep-alive state), and a watchdog
 * that closes a connection with no progress for [Config.socketTimeoutMs] (a slow sender gets 408). Routing, pairing
 * and the security headers belong to the [LanHandler] ([PhoneApi]). Logs carry the method, a path template, the
 * status and the byte count, never a token, code, cookie or body.
 */
class LanHttpServerImpl(
    private val transport: LanTransport,
    private val config: Config = Config(),
) : LanHttpServer {
    data class Config(
        val socketTimeoutMs: Int = LanLimits.HTTP_SOCKET_TIMEOUT_MS,
        val maxSockets: Int = LanLimits.HTTP_MAX_SOCKETS,
        val maxHeaderBytes: Int = LanLimits.HTTP_MAX_HEADER_BYTES,
        val maxHeaders: Int = LanLimits.HTTP_MAX_HEADERS,
        val maxBodyBytes: Long = LanLimits.PHONE_UPLOAD_MAX_BYTES,
        val random: Random = Random.Default,
        /** Turns a request path into what the log may show (the token path becomes a template). */
        val logPath: (String) -> String = ::defaultLogPath,
    )

    private class Active(val conn: LanConnection, @Volatile var lastProgressNs: Long, @Volatile var thread: Thread?) {
        @Volatile var responding = false
    }

    @Volatile private var listener: LanListener? = null
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var watchdog: ScheduledExecutorService? = null
    private val active = ConcurrentHashMap.newKeySet<Active>()
    private val count = AtomicInteger()

    /** The port actually bound; -1 before [start] or after [stop]. */
    @Volatile var port: Int = -1
        private set

    /** Binds [LanBind.preferredPort], else up to [LanLimits.PORT_RANDOM_TRIES] random high ports; throws [IOException] if none binds. */
    @Throws(IOException::class)
    override fun start(bind: LanBind, handler: LanHandler) {
        check(listener == null) { "already started" }
        val l = bindSomePort(bind)
        listener = l
        port = l.port
        val max = minOf(config.maxSockets, bind.maxConnections).coerceAtLeast(1)
        val workers = Executors.newFixedThreadPool(max) { r -> Thread(r, "box-lan-w").apply { isDaemon = true } }
        pool = workers
        val dog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "box-lan-t").apply { isDaemon = true } }
        watchdog = dog
        val tick = (config.socketTimeoutMs / 4L).coerceIn(10L, 1_000L)
        dog.scheduleWithFixedDelay({ sweep() }, tick, tick, TimeUnit.MILLISECONDS)
        Thread({ acceptLoop(l, workers, handler, max) }, "box-lan").apply { isDaemon = true }.start()
        BoxLog.i(TAG) { "listening on port ${l.port}" }
    }

    override fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { l.close() }
        pool?.shutdownNow()
        watchdog?.shutdownNow()
        pool = null
        watchdog = null
        for (a in active) { runCatching { a.conn.close() }; a.thread?.interrupt() }
        active.clear()
        port = -1
        BoxLog.i(TAG) { "stopped" }
    }

    private fun bindSomePort(bind: LanBind): LanListener {
        var last: IOException? = null
        val candidates = buildList {
            if (bind.preferredPort > 0) add(bind.preferredPort)
            repeat(LanLimits.PORT_RANDOM_TRIES) { add(config.random.nextInt(LanLimits.PORT_RANDOM_FROM, LanLimits.PORT_RANDOM_TO + 1)) }
        }
        for (p in candidates) {
            try {
                return transport.listen(bind.address, p)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no port")
    }

    private fun acceptLoop(l: LanListener, workers: ExecutorService, handler: LanHandler, max: Int) {
        while (listener === l) {
            val conn = try {
                l.accept()
            } catch (e: IOException) {
                if (listener !== l) break
                BoxLog.w(TAG, "accept failed: ${e.javaClass.simpleName}")
                continue
            } ?: break
            if (count.incrementAndGet() > max) {
                count.decrementAndGet()
                Thread({ refuseBusy(conn) }, "box-lan-busy").apply { isDaemon = true }.start()
                continue
            }
            val a = Active(conn, System.nanoTime(), null)
            active += a
            try {
                workers.execute { serve(a, handler) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                finish(a)
                break
            }
        }
    }

    private fun refuseBusy(conn: LanConnection) {
        try {
            writeResponse(conn.output, HttpResponse(503, mapOf("Content-Type" to "text/plain; charset=utf-8", "Retry-After" to "2"), "Busy".encodeToByteArray()))
        } catch (_: IOException) {
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun sweep() {
        val limitNs = config.socketTimeoutMs * 1_000_000L
        val now = System.nanoTime()
        for (a in active) {
            if (!a.responding && now - a.lastProgressNs > limitNs) {
                BoxLog.i(TAG) { "request timed out" }
                a.responding = true
                runCatching { writeResponse(a.conn.output, plain(408, "Request timeout")) }
                runCatching { a.conn.close() }
                a.thread?.interrupt()
                finish(a)
            }
        }
    }

    private fun finish(a: Active) {
        if (active.remove(a)) count.decrementAndGet()
    }

    private fun serve(a: Active, handler: LanHandler) {
        a.thread = Thread.currentThread()
        val conn = a.conn
        var method = "?"
        var pathForLog = "?"
        try {
            runCatching { conn.setReadTimeout(config.socketTimeoutMs) }
            val input = BufferedInputStream(conn.input, 8_192)
            val response = try {
                val head = HttpParser.readHead(input, config.maxHeaderBytes, config.maxHeaders)
                method = head.method
                pathForLog = config.logPath(head.path)
                a.lastProgressNs = System.nanoTime()
                val len = head.contentLength
                if (len != null && len > config.maxBodyBytes) {
                    plain(413, "Too large")
                } else {
                    val body = if (len != null && len > 0) StreamBody(input, len) { a.lastProgressNs = System.nanoTime() } else null
                    val request = HttpRequest(head.method, head.path, head.query, head.headers, conn.remoteAddress, body)
                    try {
                        handler.handle(request)
                    } catch (e: HttpParseException) {
                        plain(e.status, "Bad request")
                    } catch (e: RuntimeException) {
                        BoxLog.e(TAG, "handler failed for $method $pathForLog", e)
                        plain(500, "Internal error")
                    }
                }
            } catch (e: HttpParseException) {
                plain(e.status, "Bad request")
            }
            if (a.responding) return // the watchdog answered
            a.responding = true
            val sent = writeResponse(conn.output, if (method == "HEAD") response.copy(body = null, file = null) else response)
            BoxLog.i(TAG) { "$method $pathForLog ${response.status} $sent" }
        } catch (e: IOException) {
            BoxLog.i(TAG) { "$method $pathForLog connection ended: ${e.javaClass.simpleName}" }
        } finally {
            runCatching { conn.close() }
            finish(a)
            a.thread = null
            Thread.interrupted() // clear a late interrupt before the pool reuses the thread
        }
    }

    companion object {
        private const val TAG = "LanHttpServer"

        private val REASONS = mapOf(
            200 to "OK", 202 to "Accepted", 204 to "No Content", 303 to "See Other", 400 to "Bad Request",
            401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
            408 to "Request Timeout", 409 to "Conflict", 410 to "Gone", 411 to "Length Required", 413 to "Content Too Large",
            421 to "Misdirected Request", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error",
            503 to "Service Unavailable", 505 to "HTTP Version Not Supported", 507 to "Insufficient Storage",
        )

        fun plain(status: Int, text: String) =
            HttpResponse(status, mapOf("Content-Type" to "text/plain; charset=utf-8"), text.encodeToByteArray())

        /** Never the token path or a download name. */
        fun defaultLogPath(path: String): String = when {
            path.startsWith("/s/") -> "/s/*"
            path.startsWith("/api/download/") -> "/api/download/*"
            else -> path.take(40)
        }

        /** Writes status, headers (+ Content-Length, Connection: close) and the body or file; returns the body bytes. */
        fun writeResponse(raw: OutputStream, r: HttpResponse): Long {
            val out = BufferedOutputStream(raw, 16_384)
            val length = r.body?.size?.toLong() ?: r.file?.length() ?: 0L
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(r.status).append(' ').append(REASONS[r.status] ?: "Status").append("\r\n")
            for ((k, v) in r.headers) {
                if (k.equals("Content-Length", true) || k.equals("Connection", true)) continue
                require(k.none { it == '\r' || it == '\n' } && v.none { it == '\r' || it == '\n' }) { "header injection" }
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Content-Length: ").append(length).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().encodeToByteArray())
            r.body?.let { out.write(it) }
            r.file?.let { f -> FileInputStream(f).use { it.copyTo(out, LanLimits.UPLOAD_CHUNK_BYTES) } }
            out.flush()
            return length
        }
    }
}
