package app.thebox.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.common.Redact
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
 * | Clip ([ClipMedia], Sports Hub §6.5) | HLS as above (VOD) or progressive MP4, plus side-loaded WebVTT ([clipSource]) | `http` | as HLS / session placeholder |
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
        // (M6 time-shift, §5.1, §16.4) Non-null: a buffered live tune; the player reads the ring, never the network.
        ring: app.thebox.player.internal.RingOpen?,
        // (Smooth playback, 2026-11-23) "Keep up with live": false pins an HLS live source at 1.0×.
        liveSpeedCorrection: Boolean,
        // (Sports Hub §6.5) Non-null: a clip cycle (VOD over the brokered `http` transport, captions side-loaded).
        clip: ClipMedia?,
    ): MediaSourceWithTelemetry {
        val scheme = schemeOf(ctx.resolved.url)
        if (scheme !in SUPPORTED_SCHEMES) throw UnsupportedStreamFormatException(format, scheme.ifEmpty { "none" })
        if (clip != null) return clipSource(ctx, format, transport, clip)
        val isArchive = archive != null
        if (ring != null && !isArchive && (format == StreamFormat.TS || format == StreamFormat.UNKNOWN)) {
            // The same progressive TS source and extractor guards (repairs run after the ring, on every read, §4.3), over
            // a RingDataSource: live data type, unseekable, position 0 = the start RAP's PTS (facts §1.3).
            val (factory, telemetry) = transport.ring(ctx, ring)
            val source = ProgressiveMediaSource.Factory(factory, tsExtractors(ctx, compat))
                .setLoadErrorHandlingPolicy(policyFor(LoadOrigin.PROGRESSIVE))
                .setContinueLoadingCheckIntervalBytes(ProgressiveMediaSource.DEFAULT_LOADING_CHECK_INTERVAL_BYTES)
                .createMediaSource(sessionItem(ctx.tuneId))
            return MediaSourceWithTelemetry(source, telemetry)
        }
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
                                    // Off: both ends at 1.0, so Media3's live speed control never leaves it (a speed
                                    // other than 1.0 breaks the frame cadence, and tunneled or passthrough audio
                                    // cannot follow it).
                                    .setMinPlaybackSpeed(if (liveSpeedCorrection) HLS_LIVE_MIN_SPEED else 1f)
                                    .setMaxPlaybackSpeed(if (liveSpeedCorrection) HLS_LIVE_MAX_SPEED else 1f)
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

    /**
     * (Sports Hub §6.5, A-S3) A clip: HLS (VOD; no live speed band) on the real URL, or a progressive MP4 behind the session
     * placeholder (`Range` passes through [SessionUriDataSource] as for archive). Both over the session's brokered `http`
     * transport, so `TuneSession.end()` cancels every segment, caption and range call. Captions: side-loaded as a
     * [MergingMediaSource] beside the media for MP4 always, and for HLS unless [CLIP_HLS_MASTER_CARRIES_CAPTIONS].
     */
    private fun clipSource(ctx: StreamSessionContext, format: StreamFormat, transport: TransportFactory, clip: ClipMedia): MediaSourceWithTelemetry {
        val (factory, telemetry) = transport.http(ctx)
        val media: MediaSource = when (format) {
            StreamFormat.HLS -> HlsMediaSource.Factory(factory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(policyFor(LoadOrigin.HLS_MANIFEST))
                // A clip's captions are the declared or side-loaded ones; no phantom CEA-608 track.
                .setExtractorFactory(DefaultHlsExtractorFactory(HLS_TS_FLAGS, /* exposeCea608WhenMissingDeclarations = */ false))
                .createMediaSource(
                    MediaItem.Builder().setUri(ctx.resolved.url.toUri()).setMediaId(ctx.tuneId.toString()).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                )
            StreamFormat.PROGRESSIVE_OTHER -> ProgressiveMediaSource.Factory(
                SessionUriDataSource.Factory(factory, sessionUri(ctx.tuneId), ctx.resolved.url),
                DefaultExtractorsFactory(),
            )
                .setLoadErrorHandlingPolicy(policyFor(LoadOrigin.ARCHIVE))
                .createMediaSource(sessionItem(ctx.tuneId))
            else -> throw UnsupportedStreamFormatException(format)
        }
        val captions = clip.captionsUrl
        val sideload = captions != null && schemeOf(captions).let { it == "http" || it == "https" } &&
            (format != StreamFormat.HLS || !CLIP_HLS_MASTER_CARRIES_CAPTIONS)
        if (!sideload) return MediaSourceWithTelemetry(media, telemetry)
        return MediaSourceWithTelemetry(MergingMediaSource(media, captionsSource(factory, ctx.tuneId, captions!!)), telemetry)
    }

    /**
     * One WebVTT file as a text track (`en`, default flag, so the selector takes it whenever text is on), parsed during
     * extraction like Media3's own side-loading (`DefaultMediaSourceFactory`, 1.11): the 1.11 text renderer takes
     * `application/x-media3-cues`, not raw VTT samples. The signed address hides behind a placeholder URI.
     */
    private fun captionsSource(factory: DataSource.Factory, tuneId: Long, url: String): MediaSource {
        val placeholder = "$SESSION_SCHEME://$tuneId/captions".toUri()
        val format = Format.Builder()
            .setId(CLIP_CAPTIONS_ID)
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(CLIP_CAPTIONS_LANGUAGE)
            .setLabel(CLIP_CAPTIONS_LABEL)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val parsers = DefaultSubtitleParserFactory()
        // With the format given, the extractor declares the cue track, its seek map and endTracks at init (Media3's own
        // lazy single-track path is package-private).
        return ProgressiveMediaSource.Factory(
            SessionUriDataSource.Factory(factory, placeholder, url),
            ExtractorsFactory { arrayOf<Extractor>(SubtitleExtractor(parsers.create(format), format)) },
        )
            .setLoadErrorHandlingPolicy(policyFor(LoadOrigin.ARCHIVE))
            .createMediaSource(MediaItem.Builder().setUri(placeholder).setMediaId("$tuneId/captions").build())
    }

    /** The session's TS extractor list: compat level and the dead-track guard reporting to the event log. */
    internal fun tsExtractors(ctx: StreamSessionContext, compat: TsCompat): TsExtractorsFactory =
        TsExtractorsFactory(
            compat,
            onDeadTrack = { pid, type -> ctx.onEvent(PlaybackEventType.SNIFF, DEAD_TRACK_CODE, "pid=$pid type=${trackTypeName(type)}") },
            // (device finding 2026-10-18) The controller turns this into FailureSignal.AudioTimestampsStuck.
            onStuckAudio = { pid -> ctx.onEvent(PlaybackEventType.STALL, STUCK_AUDIO_CODE, "pid=$pid") },
            repairAudioTimestamps = ctx.repairAudioTimestamps,
        )

    companion object {
        const val SESSION_SCHEME = "thebox-session"
        const val DEAD_TRACK_CODE = "DEAD_TRACK"
        const val STUCK_AUDIO_CODE = "STUCK_AUDIO_PTS"

        /** HLS live speed band (§3.1.3). */
        const val HLS_LIVE_MIN_SPEED = 0.97f
        const val HLS_LIVE_MAX_SPEED = 1.03f

        /** HLS segments: the TS flags of §3.8.5 (`DefaultHlsExtractorFactory` adds its own HLS-specific flags). */
        const val HLS_TS_FLAGS: Int = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

        /**
         * (Sports Hub §6.5, A-S3) **The captions switch**, settled by the lead's recording of a real goal clip's master
         * (2026-11-24): it lists `#EXT-X-MEDIA:TYPE=SUBTITLES,…,NAME="en",DEFAULT=NO,LANGUAGE="en"` and
         * `CLOSED-CAPTIONS=NONE`. So HLS uses the master's own rendition and nothing is side-loaded beside it; the
         * controller adds English to a clip's preferred text languages so the subtitles setting selects it although it is
         * DEFAULT=NO. MP4 always side-loads.
         */
        const val CLIP_HLS_MASTER_CARRIES_CAPTIONS = true
        const val CLIP_CAPTIONS_ID = "clip-captions"
        const val CLIP_CAPTIONS_LANGUAGE = "en"
        const val CLIP_CAPTIONS_LABEL = "English"

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
 * (Sports Hub §6.5) What a clip session adds to [MediaSourceBuilder.build]: its side-loaded WebVTT address (signed; the
 * `toString` names the host only).
 */
internal class ClipMedia(val captionsUrl: String?) {
    override fun toString(): String = "ClipMedia(${captionsUrl?.let { Redact.host(it) } ?: "no captions"})"
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
