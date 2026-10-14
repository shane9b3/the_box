package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.thebox.core.playback.NetworkFailureKind
import java.io.IOException

// Types verbatim from §3.3.8; the two mapping functions are WP-B's.

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
 */
@OptIn(UnstableApi::class)
internal class LiveStreamException(
    val kind: LiveFailureKind,
    val info: FailureInfo,
    dataSpec: DataSpec,
    cause: IOException?,
) : HttpDataSource.HttpDataSourceException(
    "live ${kind.name.lowercase()} ${info.status ?: ""}".trim(), cause, dataSpec, kind.media3Reason(), info.phase.media3Type(),
)

/** §3.3.8 `media3Reason()`: the `PlaybackException.ERROR_CODE_*` for [this] kind. */
internal fun LiveFailureKind.media3Reason(): Int = TODO("WP-B")

/** §3.3.8: OPEN/SNIFF → `TYPE_OPEN`, READ/RECONNECT → `TYPE_READ`. */
internal fun FailureInfo.Phase.media3Type(): Int = TODO("WP-B")
