package app.thebox.core.full.fakes

import app.thebox.core.common.Hash64
import app.thebox.core.full.AudioChoice
import app.thebox.core.full.StatsFacts
import app.thebox.core.full.TextChoice
import app.thebox.core.full.TrackKey
import app.thebox.core.full.TrackMenu
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.ErrorDetail
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackErrorCode.*
import app.thebox.core.playback.RetryInfo
import app.thebox.core.playback.StreamFormat

/** Player test data (docs/design/m4-player-ux.md §1.8). Channels come from `GuideFixtures`. */
object PlayerFixtures {
    const val HOST = "panel.example:8080"

    /** Track types as Media3's C.TRACK_TYPE_AUDIO / C.TRACK_TYPE_TEXT (:core cannot see Media3). */
    const val TRACK_AUDIO = 1
    const val TRACK_TEXT = 3

    /**
     * A contiguous schedule of [n] programmes of [durMin] minutes on [channel] from display minute [fromMin]
     * (`rawStartMin` = display − the channel's shift).
     */
    fun programmes(channel: GuideChannel, fromMin: Int, n: Int, durMin: Int): List<ProgrammeInfo> = List(n) { i ->
        val start = fromMin + i * durMin
        val title = "${channel.displayName} $i"
        ProgrammeInfo(
            rawStartMin = start - channel.epgShiftMin, startMin = start, endMin = start + durMin, title = title,
            subtitle = null, description = "About $title", genre = null, episodeLabel = null, flags = 0, titleHash = Hash64.of(title),
        )
    }

    fun audio(index: Int, language: String?, codec: String = "AAC", channels: Int = 2, selected: Boolean = false, supported: Boolean = true) =
        AudioChoice(TrackKey(TRACK_AUDIO, "a$index", 0), language, null, codec, channels, describesVideo = false, selected = selected, supported = supported)

    fun text(index: Int, language: String?, kind: String = "DVB", selected: Boolean = false, speculative: Boolean = false) =
        TextChoice(TrackKey(TRACK_TEXT, "t$index", 0), language, null, kind, selected, speculative)

    /** English AAC stereo (selected) + German E-AC-3 5.1, DVB English subtitles + a speculative CEA-608 track. */
    fun trackMenu(): TrackMenu = TrackMenu(
        audio = listOf(audio(0, "en", selected = true), audio(1, "de", codec = "E-AC-3", channels = 6)),
        text = listOf(text(0, "en"), text(1, null, kind = "CEA-608", speculative = true)),
        textEnabled = false,
    )

    fun stats(): StatsFacts = StatsFacts(
        videoMime = "video/avc", videoCodecs = "avc1.640028", videoDecoder = "c2.android.avc.decoder",
        width = 1920, height = 1080, fps = 50f, interlaced = false, hdr = HdrKind.SDR,
        audioMime = "audio/mp4a-latm", audioChannels = 2, audioDecoder = "c2.android.aac.decoder",
        streamBitrateBps = 7_800_000, bufferMs = 2_400, droppedFrames = 3, httpStatus = 200, host = HOST, redirects = 1,
        format = StreamFormat.TS, reconnects = 2, broker = BrokerSnapshot("p:1", active = 1, closing = 0, max = 2, waiting = 0, byKind = emptyMap()), serverIndex = 1, serverCount = 3,
    )

    /**
     * The ladder-shaped [PlaybackError] for [code]: the code's default actions, the detail fields the ladder sets for it
     * (status/host for HTTP failures, host for network failures, codec for decoder failures, connection counts for
     * limits), and for STREAM_INTERRUPTED the retry info of the unlimited-retry card (maxAttempts 0 = unlimited).
     */
    fun error(code: PlaybackErrorCode): PlaybackError {
        val detail = when (code) {
            AUTH_REJECTED -> ErrorDetail(httpStatus = 401, host = HOST)
            ACCOUNT_EXPIRED, ACCOUNT_DISABLED, NEEDS_REAUTH -> ErrorDetail(host = HOST)
            CREDENTIALS_UNAVAILABLE -> ErrorDetail()
            CONNECTION_LIMIT -> ErrorDetail(httpStatus = 458, host = HOST, activeConnections = 1, maxConnections = 1)
            CONNECTION_LIMIT_LOCAL -> ErrorDetail(activeConnections = 2, maxConnections = 2)
            FORBIDDEN -> ErrorDetail(httpStatus = 403, host = HOST)
            CHANNEL_OFFLINE -> ErrorDetail(httpStatus = 404, host = HOST)
            FORMAT_REFUSED -> ErrorDetail(httpStatus = 406, host = HOST)
            WEB_PAGE_INSTEAD -> ErrorDetail(httpStatus = 200, host = HOST)
            EMPTY_RESPONSE -> ErrorDetail(httpStatus = 200, host = HOST)
            NO_DATA_TIMEOUT, STREAM_INTERRUPTED, CONNECT_FAILED, TLS_FAILURE -> ErrorDetail(host = HOST)
            SERVER_ERROR -> ErrorDetail(httpStatus = 503, host = HOST)
            DNS_FAILURE -> ErrorDetail(host = HOST)
            CODEC_UNSUPPORTED -> ErrorDetail(codec = "video/hevc Main10", decoderName = "c2.android.hevc.decoder")
            STREAM_UNDECODABLE -> ErrorDetail(codec = "video/avc", decoderName = "c2.android.avc.decoder")
            AUDIO_OUTPUT_FAILED -> ErrorDetail(codec = "audio/eac3")
            UNSUPPORTED_SOURCE -> ErrorDetail(host = HOST)
            CATCHUP_NOT_IN_ARCHIVE -> ErrorDetail(httpStatus = 404, host = HOST)
            CHANNEL_NOT_FOUND -> ErrorDetail()
            INTERNAL -> ErrorDetail(signal = "Unknown")
        }
        // (WP-C finding, 2026-10-15) The only card the controller shows while retrying is the switch-timeout card
        // before first media (NO_DATA_TIMEOUT, unlimited retries); STREAM_INTERRUPTED after first media is
        // Buffering(RECONNECTING), never a card, and a terminal error has retry = null.
        val retry = if (code == NO_DATA_TIMEOUT) RetryInfo(attemptsMade = 3, maxAttempts = 0, nextAttemptInMs = 5_000) else null
        return PlaybackError(code, detail, code.defaultSteps, retry)
    }

    /** One [error] per code, in declaration order. */
    fun allErrors(): List<PlaybackError> = PlaybackErrorCode.entries.map(::error)
}
