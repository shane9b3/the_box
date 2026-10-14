package app.thebox.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.errors.LoadRetryRulesImpl
import app.thebox.player.datasource.BoxLoadErrorHandlingPolicy
import app.thebox.player.internal.MediaSourceBuilderApi
import app.thebox.player.internal.MediaSourceWithTelemetry
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.TransportFactory
import app.thebox.player.internal.TsCompat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DASH and other formats v1 does not build (§7 D20), and (code review 2026-10-14, N-S3) any URL scheme other than
 * `http`/`https`/`thebox-session` (`rtmp`, `rtsp`, `udp`, `rtp`, `mms`, `mmsh`, …): [scheme] is set then. The controller
 * maps it to `UNSUPPORTED_SOURCE` before any network attempt. The message names the format or scheme, never the URL.
 */
internal class UnsupportedStreamFormatException(val format: StreamFormat, val scheme: String? = null) :
    IllegalArgumentException(if (scheme != null) "unsupported scheme $scheme" else "unsupported stream format $format")

/**
 * One tune session → one `MediaSource` (docs/design/m2-playback-core.md §3.1.3).
 *
 * | Format | Source | Transport | `MediaItem` URI |
 * |---|---|---|---|
 * | TS (also UNKNOWN) | progressive, [TsExtractorsFactory] (compat level, dead-track guard) | `liveTs` (live), `http` (archive) | session placeholder |
 * | HLS | `HlsMediaSource`, chunkless, `DefaultHlsExtractorFactory(TS_FLAGS)` | `http` | real URL (relative segment URLs) |
 * | PROGRESSIVE_OTHER | progressive, `DefaultExtractorsFactory` | `http` | session placeholder |
 * | DASH | none: [UnsupportedStreamFormatException] | | |
 *
 * The session placeholder is `thebox-session://<tuneId>`. `LiveTsDataSource` ignores `dataSpec.uri`; for the `http`
 * transport a thin wrapper maps the placeholder to `ctx.resolved.url` at `open()` and reports the placeholder back
 * from `getUri()` (and no response headers), so archive tokens and credentials never reach `MediaItem`, `DataSpec`,
 * `LoadEventInfo` or Media3 exception messages. HLS needs the real URL in the `MediaItem` (§3.1.3).
 *
 * Every source gets its own load-error policy built for its [LoadOrigin] family (review S3).
 */
@OptIn(UnstableApi::class)
@Singleton
internal class MediaSourceBuilder(
    private val policyFor: (LoadOrigin) -> LoadErrorHandlingPolicy,
) : MediaSourceBuilderApi {

    @Inject constructor() : this(defaultPolicies())

    override fun build(
        ctx: StreamSessionContext,
        format: StreamFormat,
        compat: TsCompat,
        transport: TransportFactory,
        archive: ArchiveRequest?,
    ): MediaSourceWithTelemetry {
        val scheme = schemeOf(ctx.resolved.url)
        if (scheme !in SUPPORTED_SCHEMES) throw UnsupportedStreamFormatException(format, scheme.ifEmpty { "none" })
        val isArchive = archive != null
        return when (format) {
            StreamFormat.TS, StreamFormat.UNKNOWN -> {
                val (factory, telemetry) = if (isArchive) transport.http(ctx) else transport.liveTs(ctx)
                val upstream = if (isArchive) SessionUriDataSource.Factory(factory, sessionUri(ctx.tuneId), ctx.resolved.url) else factory
                val source = ProgressiveMediaSource.Factory(upstream, tsExtractors(ctx, compat))
                    .setLoadErrorHandlingPolicy(policyFor(if (isArchive) LoadOrigin.ARCHIVE else LoadOrigin.PROGRESSIVE))
                    .setContinueLoadingCheckIntervalBytes(ProgressiveMediaSource.DEFAULT_LOADING_CHECK_INTERVAL_BYTES)
                    .createMediaSource(sessionItem(ctx.tuneId))
                MediaSourceWithTelemetry(source, telemetry)
            }
            StreamFormat.HLS -> {
                val (factory, telemetry) = transport.http(ctx)
                val item = MediaItem.Builder()
                    .setUri(ctx.resolved.url.toUri())
                    .setMediaId(ctx.tuneId.toString())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .apply {
                        if (!isArchive) {
                            setLiveConfiguration(
                                MediaItem.LiveConfiguration.Builder()
                                    .setMinPlaybackSpeed(HLS_LIVE_MIN_SPEED)
                                    .setMaxPlaybackSpeed(HLS_LIVE_MAX_SPEED)
                                    .build(),
                            )
                        }
                    }
                    .build()
                val source: MediaSource = HlsMediaSource.Factory(factory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(policyFor(if (isArchive) LoadOrigin.ARCHIVE else LoadOrigin.HLS_MANIFEST))
                    .setExtractorFactory(DefaultHlsExtractorFactory(HLS_TS_FLAGS, /* exposeCea608WhenMissingDeclarations = */ true))
                    .createMediaSource(item)
                MediaSourceWithTelemetry(source, telemetry)
            }
            StreamFormat.PROGRESSIVE_OTHER -> {
                val (factory, telemetry) = transport.http(ctx)
                val source = ProgressiveMediaSource.Factory(
                    SessionUriDataSource.Factory(factory, sessionUri(ctx.tuneId), ctx.resolved.url),
                    DefaultExtractorsFactory(),
                )
                    .setLoadErrorHandlingPolicy(policyFor(if (isArchive) LoadOrigin.ARCHIVE else LoadOrigin.PROGRESSIVE))
                    .createMediaSource(sessionItem(ctx.tuneId))
                MediaSourceWithTelemetry(source, telemetry)
            }
            StreamFormat.DASH -> throw UnsupportedStreamFormatException(format)
        }
    }

    /** The session's TS extractor list: compat level and the dead-track guard reporting to the event log. */
    internal fun tsExtractors(ctx: StreamSessionContext, compat: TsCompat): TsExtractorsFactory =
        TsExtractorsFactory(compat, onDeadTrack = { pid, type ->
            ctx.onEvent(PlaybackEventType.SNIFF, DEAD_TRACK_CODE, "pid=$pid type=${trackTypeName(type)}")
        })

    companion object {
        const val SESSION_SCHEME = "thebox-session"
        const val DEAD_TRACK_CODE = "DEAD_TRACK"

        /** HLS live speed band (§3.1.3). */
        const val HLS_LIVE_MIN_SPEED = 0.97f
        const val HLS_LIVE_MAX_SPEED = 1.03f

        /** HLS segments: the TS flags of §3.8.5 (`DefaultHlsExtractorFactory` adds its own HLS-specific flags). */
        const val HLS_TS_FLAGS: Int = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

        /** URL schemes the transports can open (lower case). */
        val SUPPORTED_SCHEMES = setOf("http", "https", SESSION_SCHEME)

        /** The scheme of [url], lower case, or "" when it has none. Never logs or keeps the URL. */
        fun schemeOf(url: String): String {
            val i = url.indexOf("://")
            if (i <= 0) return ""
            val s = url.substring(0, i)
            return if (s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) s.lowercase(java.util.Locale.ROOT) else ""
        }

        fun sessionUri(tuneId: Long): Uri = "$SESSION_SCHEME://$tuneId".toUri()

        fun sessionItem(tuneId: Long): MediaItem =
            MediaItem.Builder().setUri(sessionUri(tuneId)).setMediaId(tuneId.toString()).build()

        private fun defaultPolicies(): (LoadOrigin) -> LoadErrorHandlingPolicy {
            val rules = LoadRetryRulesImpl()
            return { origin -> BoxLoadErrorHandlingPolicy(origin, rules) }
        }

        private fun trackTypeName(type: Int): String = when (type) {
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_TEXT -> "text"
            C.TRACK_TYPE_METADATA -> "metadata"
            else -> type.toString()
        }
    }
}

/**
 * Wraps an HTTP `DataSource` so Media3 only ever sees the session placeholder URI: `open()` swaps a placeholder URI
 * for [realUrl] (keeping position/length, so archive `Range` seeks work), `getUri()` returns the placeholder and
 * `getResponseHeaders()` is empty (it would otherwise reach `LoadEventInfo`, and an `icy-metaint` header would make
 * `ProgressiveMediaPeriod` wrap the source in `IcyDataSource`).
 */
@OptIn(UnstableApi::class)
internal class SessionUriDataSource(
    private val upstream: DataSource,
    private val placeholder: Uri,
    private val realUrl: String,
) : DataSource {
    private var opened = false

    class Factory(private val upstream: DataSource.Factory, private val placeholder: Uri, private val realUrl: String) : DataSource.Factory {
        override fun createDataSource(): DataSource = SessionUriDataSource(upstream.createDataSource(), placeholder, realUrl)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val spec = if (dataSpec.uri.scheme == MediaSourceBuilder.SESSION_SCHEME) dataSpec.withUri(realUrl.toUri()) else dataSpec
        opened = true
        return upstream.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = if (opened) placeholder else null

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        opened = false
        upstream.close()
    }
}
