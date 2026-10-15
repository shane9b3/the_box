package app.thebox.core.full.feed

import app.thebox.core.full.StatLabel
import app.thebox.core.full.StatLine
import app.thebox.core.full.StatsFacts
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.StreamFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `StatsFacts` → stat lines in `StatLabel` order (docs/design/m4-player-ux.md §3.4.4, §3.2.4). Values are language-neutral
 * (numbers, codec names, hosts, units) and formatted with [Locale.ROOT]; the UI localises the labels only. A line whose
 * facts are unknown (null, blank, or 0 where 0 means "not measured yet") is left out. Pure; any thread.
 */
object StatsFormatter {
    fun lines(facts: StatsFacts): List<StatLine> {
        val out = ArrayList<StatLine>(StatLabel.entries.size)
        fun add(label: StatLabel, value: String?) {
            if (!value.isNullOrBlank()) out += StatLine(label, value)
        }
        add(StatLabel.VIDEO, videoName(facts.videoMime, facts.videoCodecs))
        add(StatLabel.RESOLUTION, resolution(facts))
        add(StatLabel.FRAME_RATE, frameRate(facts))
        add(StatLabel.VIDEO_DECODER, facts.videoDecoder)
        add(StatLabel.AUDIO, audio(facts))
        add(StatLabel.AUDIO_DECODER, facts.audioDecoder)
        add(StatLabel.BITRATE, if (facts.streamBitrateBps > 0) bitrate(facts.streamBitrateBps) else null)
        add(StatLabel.BUFFER, seconds(facts.bufferMs.coerceAtLeast(0)))
        add(StatLabel.DROPPED, facts.droppedFrames.coerceAtLeast(0).toString())
        add(StatLabel.FORMAT, formatName(facts.format))
        add(StatLabel.HTTP, http(facts))
        add(StatLabel.SERVER, server(facts.host, facts.serverIndex, facts.serverCount))
        add(StatLabel.CONNECTIONS, facts.broker?.let { b ->
            buildString {
                append(b.active).append('/').append(b.max)
                if (b.waiting > 0) append(SEP).append(b.waiting).append(" waiting")
                if (b.closing > 0) append(SEP).append(b.closing).append(" closing")
            }
        })
        add(StatLabel.BUFFER_PROFILE, facts.bufferProfile?.let { p ->
            val mode = if (p.level >= 0) "Auto L${p.level}" else "Manual"
            mode + SEP + "start " + seconds(p.startMs.toLong())
        })
        add(StatLabel.DISPLAY, facts.displayRefreshHz?.takeIf { it > 0f }?.let { number(it) + " Hz" })
        add(StatLabel.TUNNELING, if (facts.tunneling) "on" else "off")
        return out
    }

    /**
     * "1080p50", "720p", "576i25"; null while [height] is unknown. Heights within [HEIGHT_SNAP] of a broadcast height
     * snap to it (1088 → 1080). Interlaced content names its frame rate: a field rate reported as fps (≥ 48 on an
     * interlaced stream) is halved, so 1080i at 50 fields reads "1080i25" (§3.2.4).
     */
    fun resolutionLabel(height: Int, fps: Float?, interlaced: Boolean): String? {
        if (height <= 0) return null
        val h = STANDARD_HEIGHTS.firstOrNull { abs(it - height) <= HEIGHT_SNAP } ?: height
        val rate = fps?.takeIf { it > 0f }?.let { if (interlaced && it >= FIELD_RATE_MIN) it / 2f else it }
        return h.toString() + (if (interlaced) "i" else "p") + (rate?.let { number(it) } ?: "")
    }

    // ------------------------------------------------------------------------------------------------ parts

    private const val SEP = " · "
    private const val HEIGHT_SNAP = 8
    private const val FIELD_RATE_MIN = 48f
    private val STANDARD_HEIGHTS = intArrayOf(4320, 2160, 1440, 1080, 720, 576, 480, 360, 240)

    private fun resolution(f: StatsFacts): String? {
        if (f.width <= 0 || f.height <= 0) return null
        val hdr = hdrName(f.hdr)
        return "${f.width}×${f.height}" + (if (hdr != null) SEP + hdr else "")
    }

    private fun frameRate(f: StatsFacts): String? {
        val fps = f.fps?.takeIf { it > 0f } ?: return null
        val afr = when (f.afr) {
            AfrMode.OFF -> "AFR off"
            AfrMode.SEAMLESS -> "AFR seamless"
            AfrMode.ALWAYS -> "AFR always"
        }
        return number(fps) + " fps" + SEP + afr
    }

    private fun audio(f: StatsFacts): String? {
        val name = audioName(f.audioMime) ?: return null
        val layout = channelLayout(f.audioChannels)
        return name + (if (layout != null) " $layout" else "") + (if (f.audioPassthrough) SEP + "passthrough" else "")
    }

    private fun http(f: StatsFacts): String? {
        val parts = ArrayList<String>(3)
        f.httpStatus?.takeIf { it > 0 }?.let { parts += it.toString() }
        if (f.redirects > 0) parts += plural(f.redirects, "redirect")
        if (f.reconnects > 0) parts += plural(f.reconnects, "reconnect")
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEP)
    }

    /** "host · server 2 of 3" (E23: read-only; the switch is M5); just the host with one base URL. */
    internal fun server(host: String?, index: Int, count: Int): String? {
        val position = if (count > 1) "server ${index.coerceIn(0, count - 1) + 1} of $count" else null
        return listOfNotNull(host?.takeIf { it.isNotBlank() }, position).takeIf { it.isNotEmpty() }?.joinToString(SEP)
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /** "7.8 Mbit/s", "850 kbit/s". */
    internal fun bitrate(bps: Long): String =
        if (bps >= 1_000_000) String.format(Locale.ROOT, "%.1f Mbit/s", bps / 1_000_000.0) else "${(bps / 1000.0).roundToInt()} kbit/s"

    /** "2.4 s". */
    internal fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    /** 50 → "50", 29.97 → "29.97", 23.976 → "23.98". */
    internal fun number(v: Float): String {
        val r = v.roundToInt()
        if (abs(v - r) < 0.005f) return r.toString()
        return String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    private fun hdrName(h: HdrKind): String? = when (h) {
        HdrKind.SDR -> null
        HdrKind.HDR10 -> "HDR10"
        HdrKind.HLG -> "HLG"
        HdrKind.DOLBY_VISION -> "Dolby Vision"
        HdrKind.UNKNOWN_HDR -> "HDR"
    }

    private fun formatName(f: StreamFormat): String? = when (f) {
        StreamFormat.TS -> "TS"
        StreamFormat.HLS -> "HLS"
        StreamFormat.PROGRESSIVE_OTHER -> "Progressive"
        StreamFormat.DASH -> "DASH"
        StreamFormat.UNKNOWN -> null
    }

    /** 1 → "1.0", 2 → "2.0", 6 → "5.1", 8 → "7.1"; others "N ch"; unknown → null. Also the info bar's audio glyph. */
    fun channelLayout(channels: Int): String? = when {
        channels <= 0 -> null
        channels == 1 -> "1.0"
        channels == 2 -> "2.0"
        channels == 3 -> "2.1"
        channels == 6 -> "5.1"
        channels == 8 -> "7.1"
        else -> "$channels ch"
    }

    /** MIME + RFC 6381 `codecs` → "H.264 High", "H.265 Main 10", "MPEG-2"; null without a MIME. */
    internal fun videoName(mime: String?, codecs: String?): String? {
        if (mime.isNullOrBlank()) return null
        val name = when (mime) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265"
            "video/mpeg2" -> "MPEG-2"
            "video/mpeg" -> "MPEG-1"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/dolby-vision" -> "Dolby Vision"
            "video/mp4v-es" -> "MPEG-4"
            "video/3gpp" -> "H.263"
            "video/vc1", "video/wvc1" -> "VC-1"
            else -> mime.substringAfter('/').uppercase(Locale.ROOT)
        }
        val profile = when (mime) {
            "video/avc" -> avcProfile(codecs)
            "video/hevc" -> hevcProfile(codecs)
            else -> null
        }
        return if (profile != null) "$name $profile" else name
    }

    /** `avc1.PPCCLL`: profile_idc in hex. */
    private fun avcProfile(codecs: String?): String? {
        val c = firstCodec(codecs, "avc1", "avc3") ?: return null
        val hex = c.substringAfter('.', "").takeIf { it.length >= 2 } ?: return null
        return when (hex.substring(0, 2).toIntOrNull(16)) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4"
            else -> null
        }
    }

    /** `hvc1.[A|B|C]P.…`: general_profile_idc. */
    private fun hevcProfile(codecs: String?): String? {
        val c = firstCodec(codecs, "hvc1", "hev1") ?: return null
        val p = c.split('.').getOrNull(1)?.trimStart('A', 'B', 'C', 'a', 'b', 'c')?.toIntOrNull() ?: return null
        return when (p) {
            1 -> "Main"
            2 -> "Main 10"
            3 -> "Main Still"
            4 -> "RExt"
            else -> null
        }
    }

    private fun firstCodec(codecs: String?, vararg prefixes: String): String? =
        codecs?.split(',')?.map { it.trim() }?.firstOrNull { c -> prefixes.any { c.startsWith("$it.", ignoreCase = true) } }

    /** Same names as `:player`'s `TrackController.codecName`, so the stats and the audio picker agree. */
    internal fun audioName(mime: String?): String? = when {
        mime.isNullOrBlank() -> null
        mime == "audio/eac3" || mime == "audio/eac3-joc" -> "E-AC-3"
        mime == "audio/ac3" -> "AC-3"
        mime == "audio/ac4" -> "AC-4"
        mime == "audio/mp4a-latm" -> "AAC"
        mime == "audio/mpeg-L2" -> "MP2"
        mime == "audio/mpeg-L1" -> "MP1"
        mime == "audio/mpeg" -> "MP3"
        mime.startsWith("audio/vnd.dts") -> "DTS"
        mime == "audio/true-hd" -> "TrueHD"
        mime == "audio/opus" -> "Opus"
        mime == "audio/vorbis" -> "Vorbis"
        mime == "audio/flac" -> "FLAC"
        mime == "audio/raw" -> "PCM"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }
}
