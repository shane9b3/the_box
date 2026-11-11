package app.thebox.core.playback.diag

/**
 * (Keep-decoders WP-0, docs/measure/keep-decoders-wp0.md) What one zap spent on decoders, measured in today's build with
 * no behaviour change. Times are milliseconds; null means "not seen in this zap".
 *
 * - [videoInitMs] / [audioInitMs]: the sum of Media3's `initializationDurationMs` (codec create + configure + start) over
 *   every init in the zap ([videoInits] / [audioInits] of them; more than one after a ladder repair or a refused reuse).
 *   The names are the last decoder initialised. No URL, host or credential ever goes in here.
 * - [prepareToFirstFrameMs]: the zap's last `prepare()` to its first media (first rendered frame; first audio for radio).
 * - [stopToPrepareMs]: the old channel's `player.stop()` to the zap's first `prepare()` (resolve and IO in between).
 * - [videoReleaseMs]: the old channel's `player.stop()` to Media3's `onVideoDecoderReleased`, both seen on the main
 *   thread. An upper bound of the release: it includes the playback thread's queue and the post back.
 */
data class ZapDecoders(
    val videoDecoder: String? = null,
    val videoInitMs: Long? = null,
    val videoInits: Int = 0,
    val audioDecoder: String? = null,
    val audioInitMs: Long? = null,
    val audioInits: Int = 0,
    val prepareToFirstFrameMs: Long? = null,
    val stopToPrepareMs: Long? = null,
    val videoReleaseMs: Long? = null,
) {
    /** Video + audio init, the number the WP-0 decision rule reads; null when neither was seen. */
    val initTotalMs: Long? get() = if (videoInitMs == null && audioInitMs == null) null else (videoInitMs ?: 0) + (audioInitMs ?: 0)

    /**
     * `video=<name> <ms> ms, audio=<name> <ms> ms, first frame <ms> ms after prepare, stop to prepare <ms> ms, old video
     * release <=<ms> ms`; a missing value is `-`, and `(<n> inits)` follows a decoder that was initialised more than once.
     */
    fun summary(): String = buildString(160) {
        append("video=")
        decoder(videoDecoder, videoInitMs, videoInits)
        append(", audio=")
        decoder(audioDecoder, audioInitMs, audioInits)
        append(", first frame ")
        ms(prepareToFirstFrameMs)
        append(" after prepare, stop to prepare ")
        ms(stopToPrepareMs)
        append(", old video release <=")
        ms(videoReleaseMs)
    }

    /** The one log line per zap (logcat and the log export's Logs section). */
    fun logLine(tuneId: Long): String = "zap decoders: tune=$tuneId ${summary()}"

    private fun StringBuilder.decoder(name: String?, initMs: Long?, inits: Int) {
        append(name ?: "-").append(' ')
        ms(initMs)
        if (inits > 1) append(" (").append(inits).append(" inits)")
    }

    private fun StringBuilder.ms(v: Long?) {
        if (v == null) append('-') else append(v).append(" ms")
    }
}

/**
 * Collects [ZapDecoders] for the zap in progress. Main thread only; no allocation per event (fields only), one
 * [ZapDecoders] per zap at [finish]. The controller calls [begin] when a zap starts (before the old channel's stop),
 * [onStop] at each `player.stop()`, [onPrepare] after each `prepare()`, the decoder callbacks from its
 * `AnalyticsListener`, and [finish] when the zap record is written. Events outside a zap (after [finish], before the
 * next [begin]) are ignored, so a ladder repair during playback does not count.
 */
class ZapDecoderMeter {
    private var open = false
    private var stopAtMs = NONE
    private var firstPrepareAtMs = NONE
    private var lastPrepareAtMs = NONE
    private var releaseMs = NONE
    private var videoName: String? = null
    private var videoMs = NONE
    private var videoCount = 0
    private var audioName: String? = null
    private var audioMs = NONE
    private var audioCount = 0

    fun begin() {
        open = true
        stopAtMs = NONE
        firstPrepareAtMs = NONE
        lastPrepareAtMs = NONE
        releaseMs = NONE
        videoName = null
        videoMs = NONE
        videoCount = 0
        audioName = null
        audioMs = NONE
        audioCount = 0
    }

    /** Only the old channel's stop counts: the first one of the zap, before its first prepare. */
    fun onStop(nowMs: Long) {
        if (open && stopAtMs == NONE && firstPrepareAtMs == NONE) stopAtMs = nowMs
    }

    fun onPrepare(nowMs: Long) {
        if (!open) return
        if (firstPrepareAtMs == NONE) firstPrepareAtMs = nowMs
        lastPrepareAtMs = nowMs
    }

    fun onVideoDecoderInitialized(name: String, durationMs: Long) {
        if (!open) return
        videoName = name
        videoMs = (if (videoMs == NONE) 0 else videoMs) + durationMs.coerceAtLeast(0)
        videoCount++
    }

    fun onAudioDecoderInitialized(name: String, durationMs: Long) {
        if (!open) return
        audioName = name
        audioMs = (if (audioMs == NONE) 0 else audioMs) + durationMs.coerceAtLeast(0)
        audioCount++
    }

    /** The old channel's video decoder: the first release after the zap's stop. */
    fun onVideoDecoderReleased(nowMs: Long) {
        if (open && stopAtMs != NONE && releaseMs == NONE) releaseMs = (nowMs - stopAtMs).coerceAtLeast(0)
    }

    /** Ends the zap. [firstMediaAtMs] is null when the zap ended without media (superseded, error, stop). */
    fun finish(firstMediaAtMs: Long?): ZapDecoders {
        open = false
        return ZapDecoders(
            videoDecoder = videoName,
            videoInitMs = videoMs.orNull(),
            videoInits = videoCount,
            audioDecoder = audioName,
            audioInitMs = audioMs.orNull(),
            audioInits = audioCount,
            prepareToFirstFrameMs = if (firstMediaAtMs != null && lastPrepareAtMs != NONE) (firstMediaAtMs - lastPrepareAtMs).coerceAtLeast(0) else null,
            stopToPrepareMs = if (stopAtMs != NONE && firstPrepareAtMs != NONE) (firstPrepareAtMs - stopAtMs).coerceAtLeast(0) else null,
            videoReleaseMs = releaseMs.orNull(),
        )
    }

    private fun Long.orNull(): Long? = if (this == NONE) null else this

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
