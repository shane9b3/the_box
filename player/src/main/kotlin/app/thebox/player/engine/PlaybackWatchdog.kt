package app.thebox.player.engine

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StallDetectors
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.WatchdogSample
import app.thebox.player.internal.PlaybackWatchdogApi
import app.thebox.player.internal.StreamTelemetry

/**
 * Samples the player once a second and runs the `:core` stall detectors (docs/design/m2-playback-core.md §3.7).
 *
 * Main thread only. While [start]ed and `playWhenReady` is true, a single reusable runnable fills the reusable
 * [sample] (no allocation per tick) and feeds it to [detectors]; a verdict goes to the `onStall` callback. The audio,
 * first-frame and underrun flags are kept up to date from `AnalyticsListener` callbacks (this object registers
 * itself on [player]; call [release] when done).
 *
 * The controller drives the session boundaries:
 * - [beginSession] at every tune and re-prepare (clears first-frame/audio/milestone state, then [reset]);
 * - [reset] at seeks, viewport and track changes (detector baselines and the seek grace only);
 * - [setExpectations] whenever the viewport, poster mode or surface changes what must be rendered;
 * - [setSessionKind] (live/archive, HLS) and [bind] (transport telemetry) per session;
 * - [stop] while it executes a ladder decision (global guard of §3.7), [start] afterwards.
 *
 * It also reports the B2 "first media" milestone through [firstMediaListener]: which kind is awaited is decided at the
 * first `onTracksChanged` of a session (a video group selected and the video type not disabled → first rendered frame,
 * otherwise the first `onAudioPositionAdvancing`) and is not changed by later track or viewport changes.
 */
@OptIn(UnstableApi::class)
internal class PlaybackWatchdog(
    private val player: ExoPlayer,
    private val detectors: StallDetectors,
    private val clock: MonoClock,
    looper: Looper = player.applicationLooper,
) : PlaybackWatchdogApi, AnalyticsListener {

    /** The one sample, reused every tick. Exposed for diagnostics and tests (read it on the main thread). */
    val sample = WatchdogSample()

    /** Called once per session with `true` (first rendered frame) or `false` (first audio position advance). */
    var firstMediaListener: ((video: Boolean) -> Unit)? = null

    private val handler = Handler(looper)
    private val tick = Runnable { onTick() }
    private var onStall: ((StallVerdict) -> Unit)? = null
    private var running = false
    private var scheduled = false

    private var telemetry: StreamTelemetry? = null
    private var expectVideo = true
    private var expectAudio = true
    private var isLive = true
    private var isHls = false

    private var videoSelected = false
    private var audioSelected = false
    private var firstFrameAtMs = NONE
    private var audioAdvancing = false
    private var audioPassthrough = false
    private var playStartAtMs = NONE
    private var seekOrPrepareAtMs = 0L
    private var lastState = Player.STATE_IDLE
    private val underrunAtMs = LongArray(UNDERRUN_RING)
    private var underrunNext = 0

    /** -1 undecided, 0 audio milestone, 1 video milestone. */
    private var milestone = -1
    private var milestoneReached = false

    init {
        player.addAnalyticsListener(this)
        seekOrPrepareAtMs = clock.nowMs()
    }

    // ---------------------------------------------------------------- PlaybackWatchdogApi

    override fun start(onStall: (StallVerdict) -> Unit) {
        this.onStall = onStall
        running = true
        schedule()
    }

    override fun stop() {
        running = false
        unschedule()
    }

    override fun bind(telemetry: StreamTelemetry?) {
        this.telemetry = telemetry
    }

    override fun reset() {
        val now = clock.nowMs()
        seekOrPrepareAtMs = now
        underrunAtMs.fill(0L)
        detectors.reset(now)
    }

    override fun setExpectations(videoExpected: Boolean, audioExpected: Boolean) {
        expectVideo = videoExpected
        expectAudio = audioExpected
    }

    // ---------------------------------------------------------------- additions for the controller

    /** A new tune or re-prepare: first-frame, audio, passthrough and milestone state start over. */
    fun beginSession() {
        firstFrameAtMs = NONE
        audioAdvancing = false
        audioPassthrough = false
        playStartAtMs = if (player.isPlaying) clock.nowMs() else NONE
        milestone = -1
        milestoneReached = false
        videoSelected = false
        audioSelected = false
        val now = clock.nowMs()
        seekOrPrepareAtMs = now
        underrunAtMs.fill(0L)
        // (code review 2026-10-14, N-S4) reset() plus the stream rate b1 learned from the previous stream.
        detectors.newStream(now)
    }

    fun setSessionKind(isLive: Boolean, isHls: Boolean) {
        this.isLive = isLive
        this.isHls = isHls
    }

    val firstFrameRendered: Boolean get() = firstFrameAtMs != NONE
    val audioPositionAdvancing: Boolean get() = audioAdvancing
    val isAudioPassthrough: Boolean get() = audioPassthrough

    /** Unregisters from the player and stops ticking. */
    fun release() {
        stop()
        player.removeAnalyticsListener(this)
    }

    /** Fills [sample] now and runs the detectors once (the tick body; tests call it directly). */
    @VisibleForTesting
    fun sampleNow(): StallVerdict? {
        fill(sample)
        return detectors.onSample(sample)
    }

    // ---------------------------------------------------------------- ticking

    private fun schedule() {
        if (!running || scheduled || !player.playWhenReady) return
        scheduled = true
        handler.postDelayed(tick, WATCHDOG_INTERVAL_MS)
    }

    private fun unschedule() {
        if (!scheduled) return
        scheduled = false
        handler.removeCallbacks(tick)
    }

    private fun onTick() {
        scheduled = false
        if (!running) return
        val verdict = sampleNow()
        if (verdict != null) {
            BoxLog.i(TAG) { "stall ${verdict.kind}: ${verdict.evidence}" }
            onStall?.invoke(verdict)
        }
        schedule()
    }

    private fun fill(s: WatchdogSample) {
        val now = clock.nowMs()
        s.nowMs = now
        s.playbackState = player.playbackState
        s.playWhenReady = player.playWhenReady
        s.isPlaying = player.isPlaying
        s.suppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        s.isLive = isLive
        s.positionMs = player.currentPosition
        // positionMs + bufferedDurationMs is the buffered position: detector b1 measures its growth (review B6).
        s.bufferedDurationMs = player.totalBufferedDuration
        val t = telemetry
        if (t != null) {
            s.bytesTotal = t.bytesTotal
            val lastByte = t.lastByteAtMs
            val pending = t.readPendingSinceMs
            // No byte yet: the data source's first-byte timeout owns that case, so report no age. (Code review
            // 2026-10-14, E-B1/N-S2) Silence counts only while a read() is pending (§3.3.6): a loader parked on a full
            // buffer reads nothing for 10–20 s and is not starving.
            s.lastByteAgeMs = if (lastByte > 0 && pending > 0) (now - maxOf(lastByte, pending)).coerceAtLeast(0) else 0
            s.inDataSourceReconnect = t.inReconnect
        } else {
            s.bytesTotal = 0
            s.lastByteAgeMs = 0
            s.inDataSourceReconnect = false
        }
        s.isHls = isHls
        s.videoExpected = expectVideo && videoSelected
        s.firstFrameRendered = firstFrameAtMs != NONE
        val counters = player.videoDecoderCounters
        if (counters != null) {
            counters.ensureUpdated()
            s.videoRenderedCount = counters.renderedOutputBufferCount.toLong()
            s.videoDroppedCount = counters.droppedBufferCount.toLong()
        } else {
            s.videoRenderedCount = 0
            s.videoDroppedCount = 0
        }
        s.tunneling = player.isTunnelingEnabled
        s.audioExpected = expectAudio && audioSelected
        s.audioAdvancing = audioAdvancing
        s.msSinceFirstFrame = if (firstFrameAtMs == NONE) 0 else now - firstFrameAtMs
        s.msSincePlayStart = if (playStartAtMs == NONE) 0 else now - playStartAtMs
        var underruns = 0
        for (at in underrunAtMs) if (at != 0L && now - at <= UNDERRUN_WINDOW_MS) underruns++
        s.audioUnderrunsLast3s = underruns
        s.audioPassthrough = audioPassthrough
        s.msSinceSeekOrPrepare = now - seekOrPrepareAtMs
    }

    // ---------------------------------------------------------------- AnalyticsListener

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) schedule() else unschedule()
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (lastState == Player.STATE_IDLE && state == Player.STATE_BUFFERING) seekOrPrepareAtMs = clock.nowMs()
        lastState = state
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        if (isPlaying) playStartAtMs = clock.nowMs()
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            seekOrPrepareAtMs = clock.nowMs()
        }
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        videoSelected = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        audioSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
        if (milestone == -1 && !tracks.isEmpty) {
            val videoMilestone = videoSelected && C.TRACK_TYPE_VIDEO !in player.trackSelectionParameters.disabledTrackTypes
            milestone = if (videoMilestone) 1 else 0
        }
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (firstFrameAtMs == NONE) firstFrameAtMs = clock.nowMs()
        if (milestone == 1) reachMilestone(video = true)
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        audioAdvancing = true
        if (milestone == 0) reachMilestone(video = false)
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        // A new AudioTrack: the position has to start advancing again (d1 "since the audio renderer (re)started").
        audioAdvancing = false
        audioPassthrough = !Util.isEncodingLinearPcm(audioTrackConfig.encoding)
    }

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        underrunAtMs[underrunNext] = clock.nowMs()
        underrunNext = (underrunNext + 1) % UNDERRUN_RING
    }

    private fun reachMilestone(video: Boolean) {
        if (milestoneReached) return
        milestoneReached = true
        firstMediaListener?.invoke(video)
    }

    companion object {
        private const val TAG = "Watchdog"
        private const val NONE = -1L

        /** `WATCHDOG_INTERVAL_MS` (App. B). */
        const val WATCHDOG_INTERVAL_MS = 1_000L

        /** d2's window (`AUDIO_UNDERRUN_STORM_COUNT` / 3,000 ms, App. B); the ring holds more than the storm count. */
        const val UNDERRUN_WINDOW_MS = 3_000L
        private const val UNDERRUN_RING = 16
    }
}
