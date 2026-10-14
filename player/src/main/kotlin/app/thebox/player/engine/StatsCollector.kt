package app.thebox.player.engine

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StreamFormat
import app.thebox.player.api.StreamStats
import app.thebox.player.internal.StreamTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `AnalyticsListener` + transport telemetry → [StreamStats] (docs/design/m2-playback-core.md §3.13).
 *
 * Counters are collected all the time (cheap fields); [stats] is published at most every [STATS_INTERVAL_MS] and only
 * while [setEnabled] is true, one `StreamStats` per publish. Strings (MIME, codecs, decoder names) are captured on
 * change events, not rebuilt per publish. Main thread only; this object registers itself on [player] (call [release]).
 *
 * The controller feeds what the player does not know: [bind] (telemetry and container format per session),
 * [brokerSnapshot], [bufferProfile], [afr], [displayRefreshHz], [fps].
 */
@OptIn(UnstableApi::class)
internal class StatsCollector(
    private val player: ExoPlayer,
    private val clock: MonoClock,
    looper: Looper = player.applicationLooper,
) : AnalyticsListener {

    private val state = MutableStateFlow(StreamStats())

    /** Updated at ≤ 2 Hz while enabled; frozen at the last value otherwise. */
    val stats: StateFlow<StreamStats> = state.asStateFlow()

    var brokerSnapshot: (() -> BrokerSnapshot?)? = null
    var bufferProfile: BufferProfile? = null
    var afr: AfrMode = AfrMode.SEAMLESS
    var displayRefreshHz: Float? = null
    var fps: Float? = null

    /** Number of publishes so far (diagnostics/tests). */
    var publishCount: Long = 0L
        private set

    private val handler = Handler(looper)
    private val publish = Runnable { onPublish() }
    private var enabled = false

    private var telemetry: StreamTelemetry? = null
    private var format = StreamFormat.UNKNOWN
    private var videoMime: String? = null
    private var videoCodecs: String? = null
    private var videoDecoder: String? = null
    private var width = 0
    private var height = 0
    private var hdr = HdrKind.SDR
    private var audioMime: String? = null
    private var audioChannels = 0
    private var audioDecoder: String? = null
    private var audioPassthrough = false
    private var droppedFrames = 0L

    init {
        player.addAnalyticsListener(this)
    }

    /** A new session: per-stream fields start over. */
    fun bind(telemetry: StreamTelemetry?, format: StreamFormat) {
        this.telemetry = telemetry
        this.format = format
        videoMime = null
        videoCodecs = null
        videoDecoder = null
        width = 0
        height = 0
        hdr = HdrKind.SDR
        audioMime = null
        audioChannels = 0
        audioDecoder = null
        audioPassthrough = false
        droppedFrames = 0
        fps = null
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        handler.removeCallbacks(publish)
        if (enabled) onPublish()
    }

    fun release() {
        setEnabled(false)
        player.removeAnalyticsListener(this)
    }

    /** Builds a snapshot now (also used by the diagnostics dump). */
    fun snapshot(): StreamStats {
        val t = telemetry
        return StreamStats(
            videoMime = videoMime,
            videoCodecs = videoCodecs,
            videoDecoder = videoDecoder,
            width = width,
            height = height,
            fps = fps,
            hdr = hdr,
            audioMime = audioMime,
            audioChannels = audioChannels,
            audioDecoder = audioDecoder,
            audioPassthrough = audioPassthrough,
            streamBitrateBps = t?.bitrateBps ?: 0,
            bufferMs = player.totalBufferedDuration,
            droppedFrames = droppedFrames,
            httpStatus = t?.httpStatus?.takeIf { it > 0 },
            host = t?.finalHost,
            redirects = t?.redirects ?: 0,
            format = t?.sniffed?.takeIf { it != StreamFormat.UNKNOWN } ?: format,
            reconnects = t?.reconnects ?: 0,
            broker = brokerSnapshot?.invoke(),
            bufferProfile = bufferProfile,
            afr = afr,
            displayRefreshHz = displayRefreshHz,
            tunneling = player.isTunnelingEnabled,
            updatedAtElapsedMs = clock.nowMs(),
        )
    }

    private fun onPublish() {
        if (!enabled) return
        state.value = snapshot()
        publishCount++
        handler.postDelayed(publish, STATS_INTERVAL_MS)
    }

    // ---------------------------------------------------------------- AnalyticsListener

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        videoMime = format.sampleMimeType
        videoCodecs = format.codecs
        hdr = TrackController.hdrKindOf(format)
        if (format.width > 0) width = format.width
        if (format.height > 0) height = format.height
        if (format.frameRate > 0f && fps == null) fps = format.frameRate
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        videoDecoder = decoderName
    }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        if (videoSize.width > 0) {
            width = videoSize.width
            height = videoSize.height
        }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        audioMime = format.sampleMimeType
        audioChannels = format.channelCount.coerceAtLeast(0)
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        audioDecoder = decoderName
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        audioPassthrough = !Util.isEncodingLinearPcm(audioTrackConfig.encoding)
        if (audioPassthrough) audioDecoder = "passthrough"
    }

    companion object {
        /** `STATS_INTERVAL_MS` (App. B): ≤ 2 Hz. */
        const val STATS_INTERVAL_MS = 500L
    }
}
