package app.thebox.player.controller

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.StallKind
import app.thebox.player.datasource.LiveFailureKind
import app.thebox.player.datasource.LiveStreamException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * `PlaybackException` + session facts → [FailureSignal] (§4.1). The cause chain is walked from the top; the first
 * matching row wins. Returns null for a **stale** error (L0): a `LiveStreamException(PREEMPTED)`, an error whose session
 * placeholder names another tune, or a `PlaybackException` created before the current session started (§3.1.1
 * "Stale errors"). Pure apart from reading the exception objects; main thread.
 */
@OptIn(UnstableApi::class)
internal class FailureClassifier {

    /** What the classifier needs to know about the session the error is reported for. */
    data class Facts(
        val currentTuneId: Long,
        /** Monotonic (elapsedRealtime) ms the current session was prepared; errors created before it are stale. */
        val sessionStartMs: Long,
        val isArchive: Boolean = false,
        val isHls: Boolean = false,
        /** `C.DATA_TYPE_*` of the last `AnalyticsListener.onLoadError`, if any. */
        val lastLoadDataType: Int? = null,
        /** Bytes received by this session so far (telemetry). */
        val bytesReceived: Long = 0,
        val passthroughActive: Boolean = false,
    )

    fun classify(error: PlaybackException, facts: Facts): FailureSignal? {
        if (error.timestampMs < facts.sessionStartMs) return null
        val chain = causeChain(error)

        // Row 1–5: typed live TS failures (WP-B).
        chain.firstOrNull { it is LiveStreamException }?.let { return live(it as LiveStreamException, facts, chain) }

        // HTTP status from Media3's HTTP sources (HLS, archive, generic progressive).
        chain.firstOrNull { it is HttpDataSource.InvalidResponseCodeException }?.let {
            return FailureSignal.Http((it as HttpDataSource.InvalidResponseCodeException).responseCode, origin(facts))
        }
        // S6: HLS playlist exceptions.
        if (chain.any { it is HlsPlaylistTracker.PlaylistStuckException }) return FailureSignal.Stall(StallKind.HLS_NO_PROGRESS)
        if (chain.any { it is HlsPlaylistTracker.PlaylistResetException }) return FailureSignal.BehindLiveWindow

        // S3: network rows test the root cause below HttpDataSourceException / IOException / ExecutionException.
        if (chain.any { it is HttpDataSource.CleartextNotPermittedException } ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
        ) {
            return FailureSignal.Network(NetworkFailureKind.CLEARTEXT, facts.bytesReceived == 0L)
        }
        if (chain.any { it is HttpDataSource.HttpDataSourceException }) {
            return FailureSignal.Network(networkKind(chain), facts.bytesReceived == 0L)
        }

        if (chain.any { it is BehindLiveWindowException } || error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            return FailureSignal.BehindLiveWindow
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return FailureSignal.Network(NetworkFailureKind.TIMEOUT, facts.bytesReceived == 0L)
        }

        val rendererFormat: Format? = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
        val rendererMime = rendererFormat?.sampleMimeType

        chain.firstOrNull { it is MediaCodecRenderer.DecoderInitializationException }?.let {
            val d = it as MediaCodecRenderer.DecoderInitializationException
            val mime = d.mimeType ?: rendererMime
            return FailureSignal.DecoderInit(isVideo(mime, rendererFormat), mime, d.codecInfo?.name)
        }
        chain.firstOrNull { it is AudioSink.InitializationException }?.let {
            val a = it as AudioSink.InitializationException
            val mime = a.format.sampleMimeType
            return FailureSignal.AudioTrackInit(encoded = mime != MimeTypes.AUDIO_RAW && facts.passthroughActive, a.format.channelCount, mime)
        }
        chain.firstOrNull { it is StuckPlayerException }?.let { return FailureSignal.Stuck((it as StuckPlayerException).stuckType) }
        chain.firstOrNull { it is ParserException }?.let {
            return FailureSignal.Parsing(manifest = (it as ParserException).dataType == C.DATA_TYPE_MANIFEST || isManifestCode(error.errorCode))
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                FailureSignal.DecoderInit(isVideo(rendererMime, rendererFormat), rendererMime, null)
            PlaybackException.ERROR_CODE_DECODING_FAILED -> FailureSignal.DecodingFailed(isVideo(rendererMime, rendererFormat), rendererMime)
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                FailureSignal.FormatUnsupported(isVideo(rendererMime, rendererFormat), rendererMime)
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> FailureSignal.AudioTrackInit(
                encoded = rendererMime != null && rendererMime != MimeTypes.AUDIO_RAW && facts.passthroughActive,
                channelCount = rendererFormat?.channelCount ?: 0,
                mimeType = rendererMime,
            )
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> FailureSignal.AudioWrite
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> FailureSignal.ResourcesReclaimed
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                FailureSignal.Parsing(manifest = false)
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                FailureSignal.Parsing(manifest = true)
            PlaybackException.ERROR_CODE_TIMEOUT -> FailureSignal.Stuck(-1)
            else -> FailureSignal.Unknown(error.errorCode)
        }
    }

    private fun live(e: LiveStreamException, facts: Facts, chain: List<Throwable>): FailureSignal? {
        val sessionId = tuneIdOf(e)
        if (e.kind == LiveFailureKind.PREEMPTED || (sessionId != null && sessionId != facts.currentTuneId)) return null
        val info = e.info
        return when (e.kind) {
            LiveFailureKind.HTTP_STATUS -> FailureSignal.Http(info.status ?: 0, if (facts.isArchive) LoadOrigin.ARCHIVE else LoadOrigin.PROGRESSIVE)
            LiveFailureKind.NETWORK -> FailureSignal.Network(info.networkKind ?: NetworkFailureKind.OTHER, beforeFirstByte = info.bytesRead == 0L)
            // (integration 2026-10-13) WP-B: the last attempt ended on the byte watchdog / a socket timeout.
            LiveFailureKind.RECONNECT_EXHAUSTED -> FailureSignal.ReconnectExhausted(info.status, noBytes = info.networkKind == NetworkFailureKind.TIMEOUT)
            LiveFailureKind.HTML_PAGE -> FailureSignal.HtmlInsteadOfStream
            LiveFailureKind.TINY_BODY -> FailureSignal.TinyBody(info.bytesRead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            LiveFailureKind.FORMAT_IS_HLS -> FailureSignal.FormatIsHls
            LiveFailureKind.FORMAT_UNRECOGNIZED -> FailureSignal.FormatUnrecognized
            LiveFailureKind.PCR_DISCONTINUITY -> FailureSignal.PcrDiscontinuity(info.deltaMs ?: 0L)
            LiveFailureKind.LOCAL_LIMIT -> {
                // (integration 2026-10-13) WP-B attaches the broker's exception as e.suppressed[0]; read the chain too.
                val limit = (e.suppressed.firstOrNull { it is ConnectionLimitException } ?: chain.firstOrNull { it is ConnectionLimitException })
                    as ConnectionLimitException?
                FailureSignal.LocalConnectionLimit(limit?.active ?: 0, limit?.max ?: 0)
            }
            LiveFailureKind.PREEMPTED -> null
        }
    }

    private fun origin(facts: Facts): LoadOrigin = when {
        facts.isArchive -> LoadOrigin.ARCHIVE
        facts.isHls -> when (facts.lastLoadDataType) {
            C.DATA_TYPE_MANIFEST -> LoadOrigin.HLS_MANIFEST
            C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> LoadOrigin.HLS_SEGMENT
            else -> LoadOrigin.UNKNOWN
        }
        else -> LoadOrigin.PROGRESSIVE
    }

    private fun networkKind(chain: List<Throwable>): NetworkFailureKind {
        for (t in chain.asReversed()) {
            when (t) {
                is UnknownHostException -> return NetworkFailureKind.DNS
                is ConnectException, is NoRouteToHostException, is PortUnreachableException -> return NetworkFailureKind.CONNECT
                is SocketTimeoutException -> return NetworkFailureKind.TIMEOUT
                is SSLException -> return NetworkFailureKind.TLS
            }
        }
        return NetworkFailureKind.RESET
    }

    private fun isVideo(mime: String?, format: Format?): Boolean = when {
        mime != null -> MimeTypes.isVideo(mime)
        format != null -> format.width > 0
        else -> false
    }

    private fun isManifestCode(code: Int) =
        code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

    companion object {
        /** The session placeholder URI scheme of the progressive TS `MediaItem`/`DataSpec` (§3.1.3). */
        const val SESSION_SCHEME = "thebox-session"

        /** The cause chain, top first, through every wrapper (`IOException(ExecutionException(cause))`, S3). Bounded. */
        fun causeChain(error: Throwable): List<Throwable> {
            val out = ArrayList<Throwable>(6)
            var t: Throwable? = error
            while (t != null && out.size < 16 && t !in out) {
                out += t
                t = t.cause
            }
            return out
        }

        /** The tune id carried by a live TS exception's placeholder `DataSpec`, or null (HLS, archive). */
        fun tuneIdOf(e: HttpDataSource.HttpDataSourceException): Long? {
            val uri = e.dataSpec.uri
            return if (uri.scheme == SESSION_SCHEME) uri.host?.toLongOrNull() else null
        }
    }
}
