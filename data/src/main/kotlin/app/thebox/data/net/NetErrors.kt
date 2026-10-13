package app.thebox.data.net

import app.thebox.core.common.AuthReason
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** IOException / HTTP status → [SourceError] for plain source downloads (§6.6: raw exceptions never cross :data). */
object NetErrors {
    fun fromIo(e: IOException): SourceError.Network {
        val kind = when (e) {
            is UnknownHostException -> NetworkKind.DNS
            is ConnectException, is NoRouteToHostException -> NetworkKind.CONNECT
            is SocketTimeoutException, is InterruptedIOException -> NetworkKind.TIMEOUT
            is SSLException -> NetworkKind.TLS
            is SocketException -> NetworkKind.RESET
            else -> if (e.message?.contains("reset", ignoreCase = true) == true) NetworkKind.RESET else NetworkKind.OTHER
        }
        return SourceError.Network(kind, e.message?.let(Redact::text))
    }

    fun fromStatus(code: Int): SourceError = when (code) {
        401 -> SourceError.Auth(AuthReason.BAD_CREDENTIALS)
        else -> SourceError.Http(code)
    }

    /** Errors a worker should retry later (network, 429, 5xx); everything else fails without retry. */
    fun isTransient(error: SourceError): Boolean = when (error) {
        is SourceError.Network -> true
        is SourceError.Http -> error.status == 408 || error.status == 429 || error.status >= 500
        is SourceError.Credentials -> error.reason == app.thebox.core.common.CredentialFailure.UNAVAILABLE
        else -> false
    }
}
