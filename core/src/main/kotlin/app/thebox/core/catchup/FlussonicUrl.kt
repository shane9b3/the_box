package app.thebox.core.catchup

import app.thebox.core.model.CatchupMode

/**
 * Flussonic live URL parsing and the derived archive URLs (docs/design/m1-data-layer.md §3.8.6; protocols §4.2).
 * Live forms: `…/STREAM/index.m3u8`, `video.m3u8`, `mono.m3u8`, `…/STREAM/mpegts`, `…/STREAM/index.mpd`.
 * The query (`?token=…`) is always preserved. Times are unix UTC seconds.
 */
data class FlussonicUrl(
    val host: String,
    val stream: String,
    /** `index`, `video`, `mono`, another file name, or `""` for `/mpegts`. */
    val name: String,
    val liveContainer: Container,
    /** Including the leading `?`, or `""`. */
    val query: String,
) {
    enum class Container { HLS, TS, DASH }

    /** HLS playlist type: [name] when it is `index`/`video`/`mono`, else `index`. */
    val hlsType: String get() = if (name in HLS_TYPES) name else "index"

    /** The archive container for [mode]: FLUSSONIC follows the live URL, the forced modes override it. */
    fun containerFor(mode: CatchupMode): Container = when (mode) {
        CatchupMode.FLUSSONIC_HLS -> Container.HLS
        CatchupMode.FLUSSONIC_TS -> Container.TS
        else -> liveContainer
    }

    private val prefix: String get() = "$host/$stream/"

    /** Fixed range (seekable): `{type}-{S}-{D}.m3u8`, `archive-{S}-{D}.ts`, `{type}-{S}-{D}.mpd`. */
    fun range(container: Container, start: Long, durationSec: Long): String = when (container) {
        Container.HLS -> "$prefix$hlsType-$start-$durationSec.m3u8$query"
        Container.TS -> "${prefix}archive-$start-$durationSec.ts$query"
        Container.DASH -> "$prefix$hlsType-$start-$durationSec.mpd$query"
    }

    /** Open-ended range: `{type}-{S}-now.m3u8`, TS → `timeshift_abs-{S}.ts`. */
    fun rangeOpen(container: Container, start: Long): String = when (container) {
        Container.HLS -> "$prefix$hlsType-$start-now.m3u8$query"
        Container.TS -> "${prefix}timeshift_abs-$start.ts$query"
        Container.DASH -> "$prefix$hlsType-$start-now.mpd$query"
    }

    /** Absolute timeshift (live-like from S); null for DASH (no such Flussonic form). */
    fun absolute(container: Container, start: Long): String? = when (container) {
        Container.HLS -> "${prefix}timeshift_abs-$start.m3u8$query"
        Container.TS -> "${prefix}timeshift_abs-$start.ts$query"
        Container.DASH -> null
    }

    /**
     * pvr.iptvsimple conversions: `index.m3u8` → `timeshift_rel-{N−S}.m3u8`; `mono`/`video` →
     * `{name}-timeshift_rel-{N−S}.m3u8`; `mpegts` (TS) → `timeshift_abs-{S}.ts`. Null for DASH.
     */
    fun kodi(container: Container, start: Long, now: Long): String? = when (container) {
        Container.HLS -> {
            val ago = now - start
            if (hlsType == "index") "${prefix}timeshift_rel-$ago.m3u8$query" else "$prefix$hlsType-timeshift_rel-$ago.m3u8$query"
        }
        Container.TS -> "${prefix}timeshift_abs-$start.ts$query"
        Container.DASH -> null
    }

    companion object {
        private val HLS_TYPES = setOf("index", "video", "mono")

        /** Kodi's regex, plus the `.mpd` live form. */
        private val KODI = Regex("""^(https?://[^/]+)/(.*)/([^/]*)(mpegts|\.m3u8|\.mpd)(\?.+=.+)?$""")

        fun parse(liveUrl: String): FlussonicUrl? {
            val m = KODI.matchEntire(liveUrl.trim()) ?: return null
            val stream = m.groupValues[2]
            if (stream.isEmpty()) return null
            val container = when (m.groupValues[4]) {
                "mpegts" -> Container.TS
                ".mpd" -> Container.DASH
                else -> Container.HLS
            }
            return FlussonicUrl(m.groupValues[1], stream, m.groupValues[3], container, m.groupValues[5])
        }
    }
}
