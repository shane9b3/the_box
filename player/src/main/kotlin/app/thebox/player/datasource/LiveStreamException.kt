package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.thebox.core.playback.NetworkFailureKind
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLException

// Types verbatim from §3.3.8; the mapping functions are WP-B's.

internal enum class LiveFailureKind { HTTP_STATUS, NETWORK, RECONNECT_EXHAUSTED, HTML_PAGE, TINY_BODY, FORMAT_IS_HLS, FORMAT_UNRECOGNIZED, PCR_DISCONTINUITY, LOCAL_LIMIT, PREEMPTED }

internal data class FailureInfo(
    val status: Int?,               // last HTTP status seen
    val host: String?,              // final host:port after redirects, never a path
    val bytesRead: Long,            // this session, all connections
    val attempt: Int,               // in-source reconnect attempt that failed
    val phase: Phase,               // OPEN, SNIFF, READ, RECONNECT
    val networkKind: NetworkFailureKind?,
    val deltaMs: Long?,             // PCR jump
    val elapsedMs: Long,
) { enum class Phase { OPEN, SNIFF, READ, RECONNECT } }

/**
 * Message is "live <kind> <status?>" only: never a URL. [dataSpec] is the session placeholder (thebox-session://<tuneId>),
 * so Media3 logging cannot print the real URL.
 *
 * Reading it (WP-D `FailureClassifier`): the exception is the `PlaybackException.cause`. For
 * [LiveFailureKind.RECONNECT_EXHAUSTED], `noBytes` is `info.networkKind == NetworkFailureKind.TIMEOUT` (the last
 * attempt ended on the byte watchdog or a socket timeout; [reason] is then `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`).
 * For [LiveFailureKind.LOCAL_LIMIT] the broker's `ConnectionLimitException` (active/max) is in [getSuppressed].
 * [cause] is the root network exception for NETWORK (never an exception that carries the real `DataSpec`: [safeCause]).
 */
@OptIn(UnstableApi::class)
internal class LiveStreamException(
    val kind: LiveFailureKind,
    val info: FailureInfo,
    dataSpec: DataSpec,
    cause: IOException?,
) : HttpDataSource.HttpDataSourceException(
    "live ${kind.name.lowercase()} ${info.status ?: ""}".trim(), cause, dataSpec, media3Reason(kind, info), info.phase.media3Type(),
)

/** §3.3.8 `media3Reason()`: the `PlaybackException.ERROR_CODE_*` for [this] kind. */
internal fun LiveFailureKind.media3Reason(): Int = when (this) {
    LiveFailureKind.HTTP_STATUS -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    LiveFailureKind.NETWORK, LiveFailureKind.RECONNECT_EXHAUSTED -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    LiveFailureKind.HTML_PAGE, LiveFailureKind.TINY_BODY, LiveFailureKind.FORMAT_UNRECOGNIZED, LiveFailureKind.FORMAT_IS_HLS ->
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    LiveFailureKind.PCR_DISCONTINUITY, LiveFailureKind.LOCAL_LIMIT, LiveFailureKind.PREEMPTED -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
}

/** [media3Reason] refined by [info]: NETWORK/RECONNECT_EXHAUSTED caused by a timeout or no bytes → `_TIMEOUT`. */
internal fun media3Reason(kind: LiveFailureKind, info: FailureInfo): Int =
    if ((kind == LiveFailureKind.NETWORK || kind == LiveFailureKind.RECONNECT_EXHAUSTED) && info.networkKind == NetworkFailureKind.TIMEOUT) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    } else {
        kind.media3Reason()
    }

/** §3.3.8: OPEN/SNIFF → `TYPE_OPEN`, READ/RECONNECT → `TYPE_READ`. */
internal fun FailureInfo.Phase.media3Type(): Int = when (this) {
    FailureInfo.Phase.OPEN, FailureInfo.Phase.SNIFF -> HttpDataSource.HttpDataSourceException.TYPE_OPEN
    FailureInfo.Phase.READ, FailureInfo.Phase.RECONNECT -> HttpDataSource.HttpDataSourceException.TYPE_READ
}

/**
 * (reviewed 2026-10-13, S3) The root of [t]'s cause chain, skipping the wrappers Media3 and OkHttp add:
 * `HttpDataSourceException`/`DataSourceException`, `ExecutionException`, and plain `IOException(cause)`
 * (`OkHttpDataSource.executeCall` wraps every open failure as `IOException(ExecutionException(cause))`).
 */
internal fun rootCause(t: Throwable): Throwable {
    var cur = t
    var guard = 0
    while (guard++ < 16) {
        val next = cur.cause ?: return cur
        val wrapper = cur is DataSourceException || cur is ExecutionException || cur.javaClass == IOException::class.java
        if (!wrapper || next === cur) return cur
        cur = next
    }
    return cur
}

/**
 * (code review 2026-10-14, N-S3) The root of [t] when it may be kept as a failure's cause: never a
 * `DataSourceException`, whose `DataSpec` may hold the real (credentialed) URL. Null when the root is one.
 */
internal fun safeCause(t: Throwable): IOException? {
    val root = rootCause(t)
    return if (root is DataSourceException) null else root as? IOException
}

/**
 * (reviewed 2026-10-13, S3) Network failure kind from the cause chain: the first specific type found walking from
 * [t] to its root wins; anything else is a reset. `SocketTimeoutException` (a subclass of `InterruptedIOException`)
 * is a timeout; a bare `InterruptedIOException` is a cancellation and is not classified here.
 */
internal fun networkKindOf(t: Throwable): NetworkFailureKind {
    var cur: Throwable? = t
    var guard = 0
    while (cur != null && guard++ < 16) {
        when (cur) {
            is UnknownHostException -> return NetworkFailureKind.DNS
            is ConnectException, is NoRouteToHostException, is PortUnreachableException -> return NetworkFailureKind.CONNECT
            is SocketTimeoutException -> return NetworkFailureKind.TIMEOUT
            is SSLException -> return NetworkFailureKind.TLS
            is HttpDataSource.CleartextNotPermittedException -> return NetworkFailureKind.CLEARTEXT
        }
        if (cur is DataSourceException && cur.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) return NetworkFailureKind.TIMEOUT
        if (cur is DataSourceException && cur.reason == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) return NetworkFailureKind.CLEARTEXT
        cur = if (cur.cause === cur) null else cur.cause
    }
    return NetworkFailureKind.RESET
}

/** True when the failure is a thread interruption (Media3 `Loader.cancelLoading`), not a network problem. */
internal fun isInterruption(t: Throwable): Boolean {
    var cur: Throwable? = t
    var guard = 0
    while (cur != null && guard++ < 16) {
        if (cur is InterruptedException) return true
        if (cur.javaClass == InterruptedIOException::class.java) return true
        cur = if (cur.cause === cur) null else cur.cause
    }
    return false
}
