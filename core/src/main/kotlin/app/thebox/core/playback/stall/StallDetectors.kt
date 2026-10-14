package app.thebox.core.playback.stall

import app.thebox.core.playback.StallDetectors
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.WatchdogSample

/** Appendix B constants of §3.7. */
object StallConstants {
    const val SEEK_GRACE_MS = 2_000L
    const val STALL_COOLDOWN_MS = 15_000L
    const val HLS_NO_PROGRESS_MS = 8_000L
    /**
     * TS backstop of detector (a): `2 × NO_BYTES_TIMEOUT_MS` (WP-B's `LiveTsDataSource`, 4,000). `:core` cannot see
     * WP-B's constant; WP-C may pass `2 × NO_BYTES_TIMEOUT_MS` through [StallThresholds.tsNoBytesBackstopMs].
     */
    const val TS_NO_BYTES_BACKSTOP_MS = 8_000L
    const val DECODER_STALL_BUFFERING_MS = 6_000L
    /** b1's byte floor (256 KiB). With a learned stream rate the threshold is the larger of this and the window's worth. */
    const val DECODER_STALL_MIN_BYTES = 262_144L
    const val DECODER_STALL_MAX_MEDIA_GROWTH_MS = 500L
    /**
     * (code review 2026-10-14, N-S4) b1's byte threshold while the stream's own rate is not learned yet (2 MiB). A
     * working extractor turns 2 MiB into ≥ 500 ms of media for any stream below about 33 Mbit/s.
     */
    const val DECODER_STALL_MIN_BYTES_UNKNOWN_RATE = 2_097_152L
    /** The stream rate (bytes per media second) is learned over spans in which the buffered position grew this much. */
    const val STREAM_RATE_LEARN_MEDIA_MS = 2_000L
    const val POSITION_STUCK_MS = 4_000L
    /** b2: "positionMs advanced < 500 ms over POSITION_STUCK_MS". */
    const val POSITION_STUCK_MIN_ADVANCE_MS = 500L
    const val FROZEN_VIDEO_MS = 3_000L
    const val FROZEN_MIN_AUDIO_ADVANCE_MS = 2_500L
    const val AUDIO_START_TIMEOUT_MS = 3_000L
    const val AUDIO_START_TIMEOUT_PASSTHROUGH_MS = 5_000L
    const val AUDIO_UNDERRUN_STORM_COUNT = 5
    /** The window `WatchdogSample.audioUnderrunsLast3s` covers (filled by WP-C). */
    const val AUDIO_UNDERRUN_STORM_WINDOW_MS = 3_000L

    // Player.STATE_* (androidx.media3.common.Player); :core has no Media3.
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4
}

/** Thresholds of the detectors; the defaults are Appendix B's. */
data class StallThresholds(
    val seekGraceMs: Long = StallConstants.SEEK_GRACE_MS,
    val cooldownMs: Long = StallConstants.STALL_COOLDOWN_MS,
    val hlsNoProgressMs: Long = StallConstants.HLS_NO_PROGRESS_MS,
    val tsNoBytesBackstopMs: Long = StallConstants.TS_NO_BYTES_BACKSTOP_MS,
    val decoderStallBufferingMs: Long = StallConstants.DECODER_STALL_BUFFERING_MS,
    val decoderStallMinBytes: Long = StallConstants.DECODER_STALL_MIN_BYTES,
    val decoderStallMaxMediaGrowthMs: Long = StallConstants.DECODER_STALL_MAX_MEDIA_GROWTH_MS,
    val decoderStallMinBytesUnknownRate: Long = StallConstants.DECODER_STALL_MIN_BYTES_UNKNOWN_RATE,
    val streamRateLearnMediaMs: Long = StallConstants.STREAM_RATE_LEARN_MEDIA_MS,
    val positionStuckMs: Long = StallConstants.POSITION_STUCK_MS,
    val positionStuckMinAdvanceMs: Long = StallConstants.POSITION_STUCK_MIN_ADVANCE_MS,
    val frozenVideoMs: Long = StallConstants.FROZEN_VIDEO_MS,
    val frozenMinAudioAdvanceMs: Long = StallConstants.FROZEN_MIN_AUDIO_ADVANCE_MS,
    val audioStartTimeoutMs: Long = StallConstants.AUDIO_START_TIMEOUT_MS,
    val audioStartTimeoutPassthroughMs: Long = StallConstants.AUDIO_START_TIMEOUT_PASSTHROUGH_MS,
    val audioUnderrunStormCount: Int = StallConstants.AUDIO_UNDERRUN_STORM_COUNT,
)

/**
 * The four detectors over a [WatchdogSample] (§3.7): (a) no bytes, (b1) decoder stuck while bytes arrive, (b2) position
 * stuck while playing, (c) frozen picture with audio, (d1) audio never starts, (d2) audio underrun storm.
 *
 * Main thread, 1 Hz. [onSample] allocates nothing unless a detector fires (primitive fields only; the verdict and its
 * evidence string are built on the firing tick). Every detector keeps a baseline that is re-taken whenever its
 * preconditions break, so each fires exactly when its threshold is reached, never earlier. A fired detector is silent
 * for [StallThresholds.cooldownMs]. Escalation across verdicts (Reprepare → compat → fail) is the ladder's (L27).
 *
 * Global guards (no detector runs, every baseline re-taken): `!playWhenReady`, `suppressed`, IDLE/ENDED, and
 * `msSinceSeekOrPrepare < SEEK_GRACE_MS`. The controller stops the watchdog while it executes a ladder decision.
 */
class StallDetectorsImpl(private val t: StallThresholds = StallThresholds()) : StallDetectors {
    // Last verdict time per StallKind ordinal.
    private val lastFiredAt = LongArray(StallKind.entries.size) { NEVER }

    // (a) HLS
    private var hlsBytes = 0L
    private var hlsSince = NEVER

    // (b1)
    private var b1Since = NEVER
    private var b1Bytes = 0L
    private var b1Media = 0L

    // (b1, N-S4) the stream's own rate: bytes per second of media, learned from the buffered position's growth.
    private var rateBaseBytes = 0L
    private var rateBaseMedia = NEVER
    private var streamBytesPerMediaSecond = 0L

    // (b2)
    private var b2Since = NEVER
    private var b2Pos = 0L
    private var b2Bytes = 0L

    // (c)
    private var cSince = NEVER
    private var cRendered = 0L
    private var cPos = 0L
    private var cDropped = 0L

    override fun reset(nowMs: Long) {
        hlsSince = NEVER
        b1Since = NEVER
        b2Since = NEVER
        cSince = NEVER
        rateBaseMedia = NEVER // a seek or re-prepare moves the buffered position: start a new learning span
    }

    /** A new tune or re-prepare: the learned stream rate belongs to the previous stream. */
    override fun newStream(nowMs: Long) {
        reset(nowMs)
        streamBytesPerMediaSecond = 0L
    }

    /** The learned stream rate in bytes per media second, 0 while unknown (tests and diagnostics). */
    val learnedStreamRate: Long get() = streamBytesPerMediaSecond

    override fun onSample(s: WatchdogSample): StallVerdict? {
        val now = s.nowMs
        if (!s.playWhenReady || s.suppressed || s.playbackState == StallConstants.STATE_IDLE ||
            s.playbackState == StallConstants.STATE_ENDED || s.msSinceSeekOrPrepare < t.seekGraceMs
        ) {
            reset(now)
            return null
        }
        learnStreamRate(s)
        return checkNoBytes(s, now)
            ?: checkDecoderStuck(s, now)
            ?: checkPositionStuck(s, now)
            ?: checkFrozen(s, now)
            ?: checkAudioStart(s, now)
            ?: checkUnderruns(s, now)
    }

    // (a) HLS: BUFFERING with bytesTotal unchanged for HLS_NO_PROGRESS_MS. TS: backstop when the byte watchdog failed.
    // (code review 2026-10-14, E-B1/N-S2) `lastByteAgeMs` is non-zero only while a read() is pending in the live source
    // (WP-C fills it from `StreamTelemetry.readPendingSinceMs`), so a loader parked on a full buffer never trips it.
    private fun checkNoBytes(s: WatchdogSample, now: Long): StallVerdict? {
        if (s.isHls) {
            if (s.playbackState != StallConstants.STATE_BUFFERING || hlsSince == NEVER || s.bytesTotal != hlsBytes) {
                hlsSince = if (s.playbackState == StallConstants.STATE_BUFFERING) now else NEVER
                hlsBytes = s.bytesTotal
                return null
            }
            if (now - hlsSince < t.hlsNoProgressMs || coolingDown(StallKind.HLS_NO_PROGRESS, now)) return null
            hlsSince = now
            return fire(StallKind.HLS_NO_PROGRESS, now, "hls bytes flat ${t.hlsNoProgressMs}ms while buffering")
        }
        if (!s.isLive || s.inDataSourceReconnect || s.lastByteAgeMs <= t.tsNoBytesBackstopMs) return null
        if (coolingDown(StallKind.NO_BYTES, now)) return null
        return fire(StallKind.NO_BYTES, now, "ts last byte ${s.lastByteAgeMs}ms ago, not reconnecting")
    }

    /**
     * (N-S4) Bytes per media second over spans where the buffered position grew ≥ [StallThresholds.streamRateLearnMediaMs].
     * Bytes that arrive while media does not grow (a stuck extractor) only inflate the estimate, which makes b1 more
     * conservative, never less. Reconnects, seeks and a new session's counters start a new span.
     */
    private fun learnStreamRate(s: WatchdogSample) {
        val media = s.positionMs + s.bufferedDurationMs
        if (s.inDataSourceReconnect || rateBaseMedia == NEVER || media < rateBaseMedia || s.bytesTotal < rateBaseBytes) {
            rateBaseMedia = media
            rateBaseBytes = s.bytesTotal
            return
        }
        val grown = media - rateBaseMedia
        if (grown < t.streamRateLearnMediaMs) return
        streamBytesPerMediaSecond = (s.bytesTotal - rateBaseBytes) * 1_000 / grown
        rateBaseMedia = media
        rateBaseBytes = s.bytesTotal
    }

    /**
     * (N-S4) Bytes that must arrive in a b1 window of [elapsedMs] before "no media growth" means a stuck decoder: the
     * window's worth of media at the learned stream rate (the network kept up with real time), at least
     * [StallThresholds.decoderStallMinBytes]; [StallThresholds.decoderStallMinBytesUnknownRate] before a rate is known.
     * A network slower than the stream turns its bytes into proportionally less media and never reaches the threshold.
     */
    private fun decoderStallBytesNeeded(elapsedMs: Long): Long {
        val rate = streamBytesPerMediaSecond
        if (rate <= 0) return t.decoderStallMinBytesUnknownRate
        return maxOf(t.decoderStallMinBytes, rate * elapsedMs / 1_000)
    }

    // (b1) BUFFERING ≥ 6 s, bytes ≥ the window's worth at the stream rate (N-S4), buffered position grew < 500 ms (B6).
    private fun checkDecoderStuck(s: WatchdogSample, now: Long): StallVerdict? {
        val media = s.positionMs + s.bufferedDurationMs
        if (s.playbackState != StallConstants.STATE_BUFFERING || s.inDataSourceReconnect || b1Since == NEVER) {
            rebaseB1(s, now, media)
            return null
        }
        if (now - b1Since < t.decoderStallBufferingMs) return null
        val bytes = s.bytesTotal - b1Bytes
        val grown = media - b1Media
        val elapsed = now - b1Since
        if (bytes >= decoderStallBytesNeeded(elapsed) && grown < t.decoderStallMaxMediaGrowthMs && !coolingDown(StallKind.DECODER_STUCK, now)) {
            rebaseB1(s, now, media)
            return fire(StallKind.DECODER_STUCK, now, "buffering ${elapsed}ms bytes+$bytes media+${grown}ms")
        }
        rebaseB1(s, now, media) // next window
        return null
    }

    private fun rebaseB1(s: WatchdogSample, now: Long, media: Long) {
        b1Since = if (s.playbackState == StallConstants.STATE_BUFFERING && !s.inDataSourceReconnect) now else NEVER
        b1Bytes = s.bytesTotal
        b1Media = media
    }

    // (b2) READY + isPlaying, position advanced < 500 ms over 4 s while bytes arrive.
    private fun checkPositionStuck(s: WatchdogSample, now: Long): StallVerdict? {
        if (s.playbackState != StallConstants.STATE_READY || !s.isPlaying || s.inDataSourceReconnect || b2Since == NEVER ||
            s.positionMs - b2Pos >= t.positionStuckMinAdvanceMs || s.positionMs < b2Pos
        ) {
            rebaseB2(s, now)
            return null
        }
        if (now - b2Since < t.positionStuckMs) return null
        if (s.bytesTotal > b2Bytes && !coolingDown(StallKind.POSITION_STUCK, now)) {
            val advanced = s.positionMs - b2Pos
            val elapsed = now - b2Since
            rebaseB2(s, now)
            return fire(StallKind.POSITION_STUCK, now, "position+${advanced}ms in ${elapsed}ms while bytes arrive")
        }
        rebaseB2(s, now)
        return null
    }

    private fun rebaseB2(s: WatchdogSample, now: Long) {
        b2Since = if (s.playbackState == StallConstants.STATE_READY && s.isPlaying && !s.inDataSourceReconnect) now else NEVER
        b2Pos = s.positionMs
        b2Bytes = s.bytesTotal
    }

    // (c) rendered count unchanged for 3 s while the position advanced ≥ 2.5 s.
    private fun checkFrozen(s: WatchdogSample, now: Long): StallVerdict? {
        val eligible = s.playbackState == StallConstants.STATE_READY && s.isPlaying && s.videoExpected && s.firstFrameRendered &&
            !s.tunneling && !s.inDataSourceReconnect
        if (!eligible || cSince == NEVER || s.videoRenderedCount != cRendered || s.positionMs < cPos) {
            cSince = if (eligible) now else NEVER
            cRendered = s.videoRenderedCount
            cPos = s.positionMs
            cDropped = s.videoDroppedCount
            return null
        }
        if (now - cSince < t.frozenVideoMs) return null
        val advanced = s.positionMs - cPos
        if (advanced < t.frozenMinAudioAdvanceMs || coolingDown(StallKind.FROZEN_VIDEO, now)) return null
        val elapsed = now - cSince
        val dropped = s.videoDroppedCount - cDropped
        cSince = now
        cPos = s.positionMs
        cDropped = s.videoDroppedCount
        return fire(StallKind.FROZEN_VIDEO, now, "no frame for ${elapsed}ms, position+${advanced}ms, dropped+$dropped")
    }

    // (d1) no onAudioPositionAdvancing within the timeout of max(first frame, last isPlaying start) (reviewed 2026-10-13, S11).
    private fun checkAudioStart(s: WatchdogSample, now: Long): StallVerdict? {
        if (!s.audioExpected || s.audioAdvancing || !s.firstFrameRendered || !s.isPlaying) return null
        val since = minOf(s.msSinceFirstFrame, s.msSincePlayStart)
        val limit = if (s.audioPassthrough) t.audioStartTimeoutPassthroughMs else t.audioStartTimeoutMs
        if (since < limit || coolingDown(StallKind.AUDIO_NOT_STARTED, now)) return null
        return fire(StallKind.AUDIO_NOT_STARTED, now, if (s.audioPassthrough) "no audio for ${since}ms (passthrough)" else "no audio for ${since}ms")
    }

    // (d2) ≥ 5 underruns in 3 s while READY.
    private fun checkUnderruns(s: WatchdogSample, now: Long): StallVerdict? {
        if (!s.audioExpected || s.playbackState != StallConstants.STATE_READY || s.audioUnderrunsLast3s < t.audioUnderrunStormCount) return null
        if (coolingDown(StallKind.AUDIO_UNDERRUN_STORM, now)) return null
        return fire(StallKind.AUDIO_UNDERRUN_STORM, now, "${s.audioUnderrunsLast3s} underruns in 3s")
    }

    private fun coolingDown(kind: StallKind, now: Long): Boolean {
        val last = lastFiredAt[kind.ordinal]
        return last != NEVER && now - last < t.cooldownMs
    }

    private fun fire(kind: StallKind, now: Long, evidence: String): StallVerdict {
        lastFiredAt[kind.ordinal] = now
        return StallVerdict(kind, evidence)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
