package app.thebox.core.playback.diag

/**
 * (Keep-decoders WP-0, docs/measure/keep-decoders-wp0.md) What one zap spent on decoders, measured in today's build with
 * no behaviour change. Times are milliseconds; null means "not seen in this zap".
 *
 * - [videoInitMs] / [audioInitMs]: the sum of Media3's `initializationDurationMs` (codec create + configure + start) over
 *   every init in the zap ([videoInits] / [audioInits] of them; more than one after a ladder repair or a refused reuse).
 *   The names are the last decoder initialised.
 * - [videoMime], [videoHeight], [videoFps], [audioMime], [audioChannels]: the last input format of the zap (review S1),
 *   so that zaps can be split by channel kind (SD/HD, H.264/HEVC, AAC/MP2/AC-3) without any channel name.
 * - [prepareToFirstFrameMs]: the zap's last `prepare()` to its first media (first rendered frame; first audio for radio).
 * - [stopToPrepareMs]: the old channel's `player.stop()` to the zap's first `prepare()` (resolve and IO in between).
 * - [videoReleaseMs]: the old channel's `player.stop()` to Media3's `onVideoDecoderReleased`, both seen on the main
 *   thread. An upper bound of the release: it includes the playback thread's queue and the post back.
 *
 * No URL, host, channel name or credential ever goes in here: MIME types, numbers and platform decoder names only.
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
    val videoMime: String? = null,
    val videoHeight: Int = 0,
    val videoFps: Float = 0f,
    val audioMime: String? = null,
    val audioChannels: Int = 0,
) {
    /**
     * Video + audio init, the number the WP-0 decision rule reads. Null unless both were seen: a missing audio init
     * (passthrough, or not seen) is never counted as 0 ms (review L2), and neither is a missing video init (radio).
     */
    val initTotalMs: Long? get() = if (videoInitMs == null || audioInitMs == null) null else videoInitMs + audioInitMs

    /** `avc 720 25fps` (`avc 720` without [withFps]); null when no video format was seen. */
    fun videoKind(withFps: Boolean = true): String? {
        val mime = videoMime ?: return null
        return buildString(24) {
            append(shortMime(mime))
            if (videoHeight > 0) append(' ').append(videoHeight)
            if (withFps && videoFps > 0f) append(' ').append(Math.round(videoFps)).append("fps")
        }
    }

    /** `mp4a-latm 2ch` (`mp4a-latm` without [withChannels]); null when no audio format was seen. */
    fun audioKind(withChannels: Boolean = true): String? {
        val mime = audioMime ?: return null
        return if (withChannels && audioChannels > 0) "${shortMime(mime)} ${audioChannels}ch" else shortMime(mime)
    }

    /**
     * `video=avc 720 25fps <name> <ms> ms, audio=mp4a-latm 2ch <name> <ms> ms, init total <ms> ms, first frame <ms> ms
     * after prepare, stop to prepare <ms> ms, old video release <=<ms> ms`; a missing value is `-`, and `(<n> inits)`
     * follows a decoder that was initialised more than once.
     */
    fun summary(): String = buildString(200) {
        append("video=")
        decoder(videoKind(), videoDecoder, videoInitMs, videoInits)
        append(", audio=")
        decoder(audioKind(), audioDecoder, audioInitMs, audioInits)
        append(", init total ")
        ms(initTotalMs)
        append(", first frame ")
        ms(prepareToFirstFrameMs)
        append(" after prepare, stop to prepare ")
        ms(stopToPrepareMs)
        append(", old video release <=")
        ms(videoReleaseMs)
    }

    /** The one log line per zap (logcat and the log export's Logs section). */
    fun logLine(tuneId: Long): String = "zap decoders: tune=$tuneId ${summary()}"

    private fun StringBuilder.decoder(kind: String?, name: String?, initMs: Long?, inits: Int) {
        if (kind != null) append(kind).append(' ')
        append(name ?: "-").append(' ')
        ms(initMs)
        if (inits > 1) append(" (").append(inits).append(" inits)")
    }

    private fun StringBuilder.ms(v: Long?) {
        if (v == null) append('-') else append(v).append(" ms")
    }

    companion object {
        /** `video/avc` → `avc`, `audio/mpeg-L2` → `mpeg-L2`. */
        fun shortMime(mime: String): String = mime.substringAfter('/')
    }
}

/**
 * Collects [ZapDecoders] for the zap in progress. Main thread only; no allocation per event (fields only), one
 * [ZapDecoders] per zap at [finish].
 *
 * The controller calls [begin] with the cycle's id when a zap starts (before the old channel's stop), [onStop] at each
 * `player.stop()`, [onPrepare] after each `prepare()`, the decoder and format callbacks from its `AnalyticsListener`, and
 * [finish] with the same id when the zap record is written.
 *
 * Attribution (review L1, L4):
 * - Decoder and format events count only between this zap's first `prepare()` and [finish]. No decoder of this zap can
 *   initialise before its first prepare, so a late callback of the superseded zap (posted before the stop, delivered
 *   after [begin]) is dropped, and so is a ladder repair's init during playback (after [finish]).
 * - The old video decoder's release counts only between the zap's stop and its first prepare; a release after that is a
 *   decoder of this zap (for example after a radio channel, which had no video decoder to release).
 * - [finish] for another cycle than the one [begin] was given returns null.
 */
class ZapDecoderMeter {
    private var cycleId = NONE
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
    private var videoMime: String? = null
    private var videoHeight = 0
    private var videoFps = 0f
    private var audioMime: String? = null
    private var audioChannels = 0

    fun begin(cycleId: Long) {
        this.cycleId = cycleId
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
        videoMime = null
        videoHeight = 0
        videoFps = 0f
        audioMime = null
        audioChannels = 0
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

    private val counting: Boolean get() = open && firstPrepareAtMs != NONE

    fun onVideoDecoderInitialized(name: String, durationMs: Long) {
        if (!counting) return
        videoName = name
        videoMs = (if (videoMs == NONE) 0 else videoMs) + durationMs.coerceAtLeast(0)
        videoCount++
    }

    fun onAudioDecoderInitialized(name: String, durationMs: Long) {
        if (!counting) return
        audioName = name
        audioMs = (if (audioMs == NONE) 0 else audioMs) + durationMs.coerceAtLeast(0)
        audioCount++
    }

    /** The video input format ([height] and [fps] ≤ 0 when unknown). */
    fun onVideoFormat(mime: String?, height: Int, fps: Float) {
        if (!counting || mime == null) return
        videoMime = mime
        videoHeight = height.coerceAtLeast(0)
        videoFps = if (fps > 0f) fps else 0f
    }

    /** The audio input format ([channels] ≤ 0 when unknown). */
    fun onAudioFormat(mime: String?, channels: Int) {
        if (!counting || mime == null) return
        audioMime = mime
        audioChannels = channels.coerceAtLeast(0)
    }

    /** The old channel's video decoder: the first release after the zap's stop and before its first prepare. */
    fun onVideoDecoderReleased(nowMs: Long) {
        if (open && stopAtMs != NONE && firstPrepareAtMs == NONE && releaseMs == NONE) releaseMs = (nowMs - stopAtMs).coerceAtLeast(0)
    }

    /**
     * Ends the zap of [cycleId]. [firstMediaAtMs] is null when the zap ended without media (superseded, error, stop).
     * Null when the meter belongs to another cycle.
     */
    fun finish(cycleId: Long, firstMediaAtMs: Long?): ZapDecoders? {
        if (!open || cycleId != this.cycleId) return null
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
            videoMime = videoMime,
            videoHeight = videoHeight,
            videoFps = videoFps,
            audioMime = audioMime,
            audioChannels = audioChannels,
        )
    }

    private fun Long.orNull(): Long? = if (this == NONE) null else this

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
