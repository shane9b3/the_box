package app.thebox.core.model

/** How archive (catch-up) URLs are built for a channel. Spec: protocols reference §4. */
enum class CatchupMode {
    /** Xtream Codes `timeshift` endpoint. */
    XC,
    /** `catchup-source` is a full URL template. */
    DEFAULT,
    /** `catchup-source` is appended to the live URL. */
    APPEND,
    /** SIPTV: `?utc={utc}&lutc={lutc}` appended to the live URL. */
    SHIFT,
    /** Flussonic; container taken from the live URL (`…/mpegts` = TS, `*.m3u8` = HLS). `flussonic`, `fs`. */
    FLUSSONIC,
    /** Flussonic forced to HLS (`flussonic-hls`). */
    FLUSSONIC_HLS,
    /** Flussonic forced to MPEG-TS (`flussonic-ts`). */
    FLUSSONIC_TS,
    /** `catchup-source` with `{catchup-id}` per programme (v1.1 consumer; builder supports it in M1). */
    VOD,
    /** No archive. */
    OFF,
    ;

    val isFlussonic: Boolean get() = this == FLUSSONIC || this == FLUSSONIC_HLS || this == FLUSSONIC_TS

    companion object {
        /** Maps an M3U `catchup=` / `catchup-type=` value. Unknown or empty → [OFF]. */
        fun fromM3uAttribute(value: String?): CatchupMode = when (value?.trim()?.lowercase()) {
            "xc", "xtream", "xtream-codes", "xtreamcodes" -> XC
            "default" -> DEFAULT
            "append" -> APPEND
            "shift", "timeshift" -> SHIFT
            "flussonic", "fs" -> FLUSSONIC
            "flussonic-hls" -> FLUSSONIC_HLS
            "flussonic-ts" -> FLUSSONIC_TS
            "vod" -> VOD
            else -> OFF
        }
    }
}
