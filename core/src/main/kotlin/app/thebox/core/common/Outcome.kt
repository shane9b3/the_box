package app.thebox.core.common

/** Result type of suspend APIs in :data. Blocking :core APIs throw [SourceException] instead. */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Err(val error: SourceError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value

/** Thrown by blocking :core code (parsers, EPG store). :data converts it to [Outcome.Err]. */
class SourceException(val error: SourceError, cause: Throwable? = null) : Exception(error.code, cause)

/**
 * Every failure the data layer can report. The UI (M5) maps each case to "reason + next step";
 * [code] is stable and is what refresh_log and diagnostics store.
 */
sealed interface SourceError {
    val code: String

    data class Network(val kind: NetworkKind, val detail: String? = null) : SourceError {
        override val code: String get() = "net." + kind.name.lowercase()
    }
    data class Http(val status: Int) : SourceError {
        override val code: String get() = "http.$status"
    }
    data class Auth(val reason: AuthReason) : SourceError {
        override val code: String get() = "auth." + reason.name.lowercase()
    }
    data class NotASource(val kind: NotASourceKind) : SourceError {
        override val code: String get() = "format." + kind.name.lowercase()
    }
    /** Pass 1 saw far fewer live entries than last time; nothing was applied (§3.3.5). */
    data class SuspiciousShrink(val previousLive: Int, val nowLive: Int) : SourceError {
        override val code: String get() = "shrink"
    }
    data class Parse(val detail: String) : SourceError {
        override val code: String get() = "parse"
    }
    data class DiskSpace(val neededBytes: Long, val freeBytes: Long) : SourceError {
        override val code: String get() = "disk"
    }
    data class Credentials(val reason: CredentialFailure) : SourceError {
        override val code: String get() = "cred." + reason.name.lowercase()
    }
    data class Unsupported(val detail: String) : SourceError {
        override val code: String get() = "unsupported"
    }
    data object Cancelled : SourceError {
        override val code: String get() = "cancelled"
    }
    data class Internal(val detail: String) : SourceError {
        override val code: String get() = "internal"
    }
}

enum class NetworkKind { DNS, CONNECT, TIMEOUT, TLS, RESET, OTHER }
enum class AuthReason { BAD_CREDENTIALS, EXPIRED, BANNED, DISABLED, UNKNOWN }
enum class NotASourceKind { HTML_PAGE, HLS_MANIFEST, EMPTY, BINARY, WRONG_FORMAT }
/** (reviewed 2026-10-13: UNAVAILABLE = transient Keystore/Tink failure; nothing is deleted, retry later.) */
enum class CredentialFailure { MISSING, KEY_INVALIDATED, CORRUPT, UNAVAILABLE }
