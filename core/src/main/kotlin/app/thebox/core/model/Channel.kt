package app.thebox.core.model

/** A live channel inside a playlist. Identity is `(playlistId, stableKey)`, never a row id. */
data class Channel(
    val playlistId: Long,
    val stableKey: StableKey,
    val name: String,
    val streamUrl: String,
    val number: Int? = null,
    val group: String? = null,
    val logoUrl: String? = null,
    /** `tvg-id` (or Xtream `epg_channel_id`) used to match EPG. */
    val tvgId: String? = null,
    val tvgName: String? = null,
    /** Per-channel HTTP headers (`http-user-agent`, `http-referrer`, `#EXTVLCOPT`, …). */
    val headers: Map<String, String> = emptyMap(),
    val catchup: Catchup = Catchup.NONE,
    /** Per-channel EPG shift in hours (`tvg-shift`), applied on top of the source shift. */
    val epgShiftHours: Double = 0.0,
)

/** Catch-up capabilities of one channel. */
data class Catchup(
    val mode: CatchupMode,
    /** Archive depth in days (`catchup-days` / Xtream `tv_archive_duration`). */
    val days: Int = 0,
    /** `catchup-source` template, when the mode uses one. */
    val source: String? = null,
    /** Per-channel `catchup-correction` in hours; overrides [TimeBase.correctionHours] when set. */
    val correctionHours: Double? = null,
) {
    val isAvailable: Boolean get() = mode != CatchupMode.OFF && days > 0

    companion object {
        val NONE = Catchup(CatchupMode.OFF)
    }
}
