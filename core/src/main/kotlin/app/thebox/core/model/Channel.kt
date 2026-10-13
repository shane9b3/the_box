package app.thebox.core.model

import app.thebox.core.source.EntryKind

/** A channel inside a playlist, as read from library.db. Identity is `(playlistId, stableKey)`, never a row id. */
data class Channel(
    val playlistId: Long,
    val stableKey: StableKey,
    val name: String,
    /** URL template (see §3.2.4 of docs/design/m1-data-layer.md); M2's StreamUrlResolver expands it. */
    val streamUrl: String,
    val number: Int? = null,
    val group: String? = null,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val catchup: Catchup = Catchup.NONE,
    /** Per-channel EPG shift in hours (`tvg-shift`), applied on top of the source shift. */
    val epgShiftHours: Double = 0.0,
    val kind: EntryKind = EntryKind.LIVE,
    val xtreamStreamId: Long? = null,
    val adult: Boolean = false,
)

/** Resolved catch-up capability of one channel (output of `CatchupResolver`). */
data class Catchup(
    val mode: CatchupMode,
    /** Archive depth in days, always ≥ 1 when [mode] != OFF (resolver guarantees it). */
    val days: Int = 0,
    val source: String? = null,
    /** Per-channel `catchup-correction` in hours; overrides [TimeBase.correctionHours] when set. */
    val correctionHours: Double? = null,
    /** Which link of the archive-days chain produced [days] (diagnostics, channel info). */
    val daysSource: ArchiveDaysSource? = null,
) {
    val isAvailable: Boolean get() = mode != CatchupMode.OFF && days > 0

    companion object {
        val NONE = Catchup(CatchupMode.OFF)
    }
}

/** Links of the archive-days fallback chain, §3.8.1. Append-only (persisted by name). */
enum class ArchiveDaysSource {
    PLAYLIST_OVERRIDE, CATCHUP_DAYS, TIMESHIFT, TVG_REC, CATCHUP_TIME, XTREAM_DURATION, PLAYLIST_HEADER, DEFAULT,
}
