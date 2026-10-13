package app.thebox.core.model

/** How archive (catch-up) URLs are built for a channel. See protocols research §catch-up. */
enum class CatchupMode {
    /** Xtream Codes `timeshift` endpoint (`/timeshift/user/pass/duration/Y-m-d:H-M/id.ts`). */
    XC,

    /** `catchup-source` is a full URL template. */
    DEFAULT,

    /** `catchup-source` is appended to the live URL. */
    APPEND,

    /** `?utc=…&lutc=…` appended to the live URL. */
    SHIFT,

    /** Flussonic `…/archive-{start}-{duration}.ts` / `index-{start}-{duration}.m3u8`. */
    FLUSSONIC,

    /** `catchup=vod` with `{catchup-id}` (v1.1). */
    VOD,

    /** No archive. */
    OFF,

    ;

    companion object {
        /** Maps an M3U `catchup=` / `catchup-type=` attribute value. Unknown or empty → [OFF]. */
        fun fromM3uAttribute(value: String?): CatchupMode = when (value?.trim()?.lowercase()) {
            "xc" -> XC
            "default" -> DEFAULT
            "append" -> APPEND
            "shift", "timeshift" -> SHIFT
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> FLUSSONIC
            "vod" -> VOD
            else -> OFF
        }
    }
}
