package app.thebox.data.xtream

import app.thebox.core.common.AuthReason
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.SourceError
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** HTTP status and IO failures → [SourceError] (docs/design/m1-data-layer.md §3.7.6). */
internal object XtreamHttpErrors {
    const val MAX_RETRY_AFTER_MS = 30_000L
    private val BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000)

    /** Number of 429 retries before giving up with `Http(429)`. */
    val MAX_429_RETRIES: Int get() = BACKOFF_MS.size

    fun fromIo(e: IOException): SourceError.Network {
        val kind = when (e) {
            is UnknownHostException -> NetworkKind.DNS
            is SocketTimeoutException -> NetworkKind.TIMEOUT
            is ConnectException, is NoRouteToHostException, is PortUnreachableException -> NetworkKind.CONNECT
            is SSLException -> NetworkKind.TLS
            is InterruptedIOException -> NetworkKind.TIMEOUT
            is EOFException -> NetworkKind.RESET
            is SocketException -> if (e.message?.contains("reset", ignoreCase = true) == true) NetworkKind.RESET else NetworkKind.CONNECT
            else -> if (e.javaClass.simpleName.contains("StreamReset")) NetworkKind.RESET else NetworkKind.OTHER
        }
        return SourceError.Network(kind, e.javaClass.simpleName)
    }

    /**
     * Non-2xx status → error. 401 and 513 (XUI "invalid credentials") → `Auth(BAD_CREDENTIALS)`; 403 stays
     * `Http(403)` (the UI hints at the User-Agent setting); everything else → `Http(status)`.
     */
    fun fromStatus(code: Int): SourceError = when (code) {
        401, 513 -> SourceError.Auth(AuthReason.BAD_CREDENTIALS)
        else -> SourceError.Http(code)
    }

    /** 5xx (incl. Cloudflare 520–524) moves on to the next base URL. */
    fun isRotatable(code: Int): Boolean = code in 500..599 && code != 513

    /** Delay before 429 retry number [attempt] (0-based): `Retry-After` seconds up to 30 s, else 2/4/8 s. */
    fun retryDelayMs(retryAfter: String?, attempt: Int): Long {
        val header = retryAfter?.trim()?.toLongOrNull()
        if (header != null && header >= 0) return (header * 1000).coerceAtMost(MAX_RETRY_AFTER_MS)
        return BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.size - 1)]
    }
}
