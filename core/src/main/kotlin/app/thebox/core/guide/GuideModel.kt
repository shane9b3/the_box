// Contract (M3 commit 0): docs/design/m3-guide.md §2.1. Verbatim; changes go through the lead.
package app.thebox.core.guide

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.refresh.ChannelIdentity

/** Design-px geometry (docs/01-design-reference.md, prototype markup; Appendix A.1). Values are normative. */
object GuideGeometry {
    const val CANVAS_W = 1920f
    const val CANVAS_H = 1080f
    // Mini player hole (prototype: left 64, top 56, 720×405, radius 20)
    const val HOLE_L = 64f; const val HOLE_T = 56f; const val HOLE_W = 720f; const val HOLE_H = 405f; const val HOLE_R = 20f
    // Header region right of the hole (left 832, top 56, right 64, height 405)
    const val HEAD_L = 832f; const val HEAD_T = 56f; const val HEAD_R = 64f; const val HEAD_H = 405f
    // Grid panel (left 64, right 64, top 500, height 460, radius 24, padding 0 16 12, 1 px border)
    const val PANEL_L = 64f; const val PANEL_T = 500f; const val PANEL_W = 1792f; const val PANEL_H = 460f; const val PANEL_R = 24f
    const val PANEL_PAD_X = 16f; const val PANEL_PAD_B = 12f; const val PANEL_BORDER = 1f
    const val HEADER_H = 48f
    const val LABEL_W = 240f
    const val LABEL_PAD_X = 12f; const val LABEL_GAP = 14f
    const val ROW_H = 74f; const val ROW_GAP = 8f; const val ROW_PITCH = 82f
    const val VISIBLE_ROWS = 5
    const val WINDOW_MIN = 180
    const val TICK_MIN = 30
    const val TIMELINE_W = 1518f                        // 1792 − 2×1 border − 2×16 padding − 240 (Appendix A.1; owner ruling 2026-10-17)
    const val PX_PER_MIN = TIMELINE_W / WINDOW_MIN      // 8.4333
    const val TICK_LABEL_PAD = 10f
    // Cells
    const val CELL_INSET_X = 3f; const val CELL_R = 12f; const val CELL_PAD_X = 16f; const val CELL_PAD_X_NARROW = 8f
    const val CELL_LINE_GAP = 4f; const val CELL_BORDER = 1f; const val PROGRESS_H = 3f; const val FOCUS_RING = 3f
    /** Below this outer width a cell draws its title only (no time line) with [CELL_PAD_X_NARROW]. */
    const val NARROW_CELL_W = 150f
    /** Below this inner width a cell draws no text at all. */
    const val MIN_TEXT_W = 30f
    // Now line
    const val NOW_W = 2f; const val NOW_DOT = 10f; const val NOW_OVERHANG = 6f; const val NOW_GLOW = 12f
    // Footer
    const val FOOTER_L = 64f; const val FOOTER_B = 40f
}

/** ARGB values. oklch tokens use their sRGB fallbacks (DesignTokens.kt). Commit 0 tests equality with DesignTokens.Colors. */
object GuideColors {
    const val CANVAS = 0xFF0A0D13.toInt()
    const val FG = 0xFFEEF1F6.toInt()
    const val ACCENT = 0xFF5CA8F2.toInt()
    const val ACCENT_TEXT = 0xFFB6D4F7.toInt()
    const val LIVE = 0xFFFF4D5E.toInt()
    const val WARN = 0xFFFFB547.toInt()                 // amber dot of the compact error card (product §2.4)
    const val PANEL_FILL = 0x0BFFFFFF                   // .045
    const val PANEL_BORDER = 0x17FFFFFF                 // .09
    const val ROW_FOCUS_FILL = 0x14FFFFFF               // .08
    const val CELL_FILL = 0x0DFFFFFF                    // .05
    const val CELL_BORDER = 0x14FFFFFF                  // .08
    const val CELL_NOW_FILL = 0x1CFFFFFF                // .11
    const val CELL_PROGRESS = 0x99EEF1F6.toInt()        // .6
    const val ARCHIVE_BAND = 0x08FFFFFF                 // .03 (B2.6)
    const val TICK_RULE = 0x1AFFFFFF                    // .1
    const val TEXT_TICK = 0x8CEEF1F6.toInt()            // .55
    const val TEXT_LABEL = 0x80EEF1F6.toInt()           // .5
    const val TEXT_PAST = 0x73EEF1F6.toInt()            // .45 past, not watchable
    const val TEXT_ARCHIVE = 0xD9EEF1F6.toInt()         // .85 past, in archive (product §3.5)
    const val TEXT_TIME_ALPHA = 0.6f                    // cell time line opacity
    const val SKELETON = 0x0FFFFFFF                     // .06
    const val SHADOW = 0x66000000                       // focused cell 0 12 30 rgba(0,0,0,.4)
}

/** Single-line styles drawn by the renderer. Sizes in design px; weights as in docs/01-design-reference.md. */
enum class GuideTextStyle(val sizePx: Float, val weight: Int, val mono: Boolean, val letterSpacingEm: Float = 0f) {
    CELL_TITLE(30f, 600, false),
    CELL_TIME(20f, 400, true),
    RULER(20f, 400, true),
    RULER_CONTEXT(18f, 600, false, 0.1f),
    CH_NUMBER(20f, 400, true),
    CH_NAME(26f, 600, false),
    NO_INFO(22f, 500, false),
    BUBBLE(64f, 500, true),
}

/** Cell kinds in a [GuideWindowData]. */
object CellKind {
    const val PROGRAMME: Byte = 0
    /** "No information" inside a row that has EPG (a gap between programmes, or before/after its data). */
    const val GAP: Byte = 1
    /** "No information" block of a row with no EPG channel or no programmes in this generation. */
    const val NO_EPG: Byte = 2
    /** Synthesised by the navigator/renderer outside the loaded range; never stored. */
    const val LOADING: Byte = 3
}

/** Row status inside a [GuideWindowData]. */
object RowStatus {
    const val OK: Byte = 0
    const val NO_EPG: Byte = 1
    /** The row's channel block was not loaded when the snapshot was built. */
    const val PENDING: Byte = 2
}

/**
 * One row of the guide. [rowId] is `channel.id`: process-local, used only to page blocks and to find the tuned
 * row; it is never persisted and never crosses a library refresh in a lineup (lineups are rebuilt on refresh).
 */
data class GuideChannel(
    val rowId: Long,
    val identity: ChannelIdentity,
    /** COALESCE(custom_name, display_name, name). */
    val displayName: String,
    val number: Int?,
    /** custom_logo ?: logo_url. */
    val logoUrl: String?,
    /** Catchup.days when Catchup.isAvailable, else 0 (resolver chain, M1 §3.8). */
    val archiveDays: Int,
    /** Resolved EPG reference (M1 §2.2); null = no guide data. */
    val epg: EpgChannelRef?,
    /** Total guide shift in minutes (M1 §2.2). */
    val epgShiftMin: Int,
    val epgHasData: Boolean,
    /** [GuideChannel.hashOf] of [identity]; the renderer's key for tuned/reminder/suspect lookups. */
    val identityHash: Long,
) {
    /**
     * (code review 2026-10-14, O-2) The renderer's `TextFit` key for [displayName], computed where the row is built
     * (the rows cache's IO thread), not per row per frame on main (§3.6.4). Not part of equality.
     */
    val nameHash: Long = app.thebox.core.common.Hash64.of(displayName)

    companion object {
        fun hashOf(identity: ChannelIdentity): Long =
            app.thebox.core.common.Hash64.str(app.thebox.core.common.Hash64.long(app.thebox.core.common.Hash64.of(null), identity.playlistId), identity.stableKey.value)
    }
}

/**
 * Bits of [GuideWindowData.cellFlags] above the `ProgrammeFlags` range (§3.2.4, §3.6.4). (amended 2026-10-14, §2.13
 * item 1: WP-A and WP-B each defined these locally; one definition here, same values.)
 */
object CellFlags {
    /** A GAP piece shorter than `GridConstants.SLIVER_MIN`: tiles the row, not focusable, draws nothing (WP-A builder). */
    const val SLIVER: Int = 1 shl 30
    /**
     * Right-to-left paragraph: the title's first strong character is RTL (`TextFit.isRtl`), set by the pager once per
     * textId (S6; code review 2026-10-14, G-S4: not `Bidi.requiresBidi`, which flagged LTR titles with an RTL word).
     */
    const val RTL: Int = 1 shl 29
    /** Title contains a combining mark, variation selector or ZWJ: grapheme cuts use `BreakIterator` (S6). */
    const val COMPLEX: Int = 1 shl 28
}

/**
 * Immutable programme snapshot for rows [firstRow, firstRow + rowCount) of one lineup and display minutes
 * [fromMin, toMin). Built on `box-guide-epg`, published to main, never mutated after publication.
 * Cells of a row are contiguous, sorted by start, non-overlapping, and tile [fromMin, toMin) exactly
 * (PROGRAMME, GAP or NO_EPG). A PROGRAMME may extend beyond either edge.
 */
class GuideWindowData(
    val seq: Long,
    val lineupVersion: Long,
    /** epg generation, or -1 when there is no guide data at all. */
    val generation: Long,
    val patchSeq: Int,
    val firstRow: Int,
    val rowCount: Int,
    val fromMin: Int,
    val toMin: Int,
    val rowStatus: ByteArray,
    val rowFirstCell: IntArray,
    val rowCellCount: IntArray,
    val cellStart: IntArray,
    val cellEnd: IntArray,
    /** Raw (unshifted) start: the catch-up and reminder key. Equals [cellStart] for GAP/NO_EPG. */
    val cellRawStart: IntArray,
    val cellKind: ByteArray,
    /** ProgrammeFlags (EpgContracts.kt:161-179) plus [CellFlags]. */
    val cellFlags: IntArray,
    /** epg_text id, generation-local: used only as a text-cache key, never persisted. −1 for non-programmes. */
    val cellTextId: IntArray,
    val cellTitle: Array<String?>,
    /** Width of the full title in device px at [metricsScale], measured off-main; −1 = not measured. */
    val cellTitleWidth: FloatArray,
    /** (reviewed 2026-10-14, B4) Hash64.of(title), off-main: the TextFit key (never the generation-local textId). */
    val cellTitleKey: LongArray,
    /** "19:30 – 20:00" built off-main from TimeLabels (null for GAP/NO_EPG), and its width in device px. */
    val cellTimeText: Array<String?>,
    val cellTimeWidth: FloatArray,
    val metricsScale: Float,
    /** (reviewed 2026-10-14, B5/S10) `rows.version` the snapshot was built from, and per row a hash of (epg ref, epgShiftMin). */
    val rowsVersion: Long,
    val rowEpgBasis: LongArray,
) {
    val cellCount: Int get() = cellStart.size
    fun covers(row: Int, minute: Int): Boolean = row >= firstRow && row < firstRow + rowCount && minute >= fromMin && minute < toMin
}

/** The tuned channel as the guide sees it (VM maps PlaybackState → TunedState, §3.16.2). */
data class TunedState(
    val identity: ChannelIdentity? = null,
    /** channel.id of [identity] in the library, −1 when unknown. */
    val rowId: Long = -1,
    val identityHash: Long = 0,
    val phase: MiniPhase = MiniPhase.IDLE,
    val isArchive: Boolean = false,
    val error: PlaybackErrorCode? = null,
    val reconnectAttempt: Int = 0,
    val reconnectMax: Int = 0,
    /**
     * The Still setting or low memory: the hole shows a still, not video (viewport HIDDEN). (code review 2026-10-14,
     * H-B1) Not the HDR poster: that is the controller's MINI state (`VideoInfo.posterMode`), drawn by the shell.
     */
    val still: Boolean = false,
    /** (M4) The ladder's error behind [error] (code, detail, narrowed actions, retry), for the error cards (§3.7, H-L9). */
    val errorDetail: app.thebox.core.playback.PlaybackError? = null,
    /** (M4, reviewed 2026-10-15, F2) `NowPlaying.tuneId` of the controller cycle this state came from (ArchiveVisibility). */
    val tuneId: Long = 0,
    /** (M4, reviewed 2026-10-15, F2) An owned archive hand-off is hidden behind this state (ArchiveVisibility, §3.2.3). */
    val handoff: Boolean = false,
)

enum class MiniPhase { IDLE, CONNECTING, BUFFERING, RECONNECTING, PLAYING, PAUSED, STOPPED, ERROR }

/** Small programme facts for the panel, copied out of ProgrammeDetail on the epg thread (no generation-local ids). */
data class ProgrammeInfo(
    val rawStartMin: Int,
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val genre: String?,
    val episodeLabel: String?,
    val flags: Int,
    val titleHash: Long,
    /** XMLTV `<icon src>` of the programme (poster art), when the EPG carries one (owner request 2026-10-18). */
    val iconUrl: String? = null,
)

/** (M4, reviewed 2026-10-15, N6) The one definition of a programme's raw (unshifted) end minute (§3.5, §3.8). */
val ProgrammeInfo.rawEndMin: Int get() = rawStartMin + (endMin - startMin)

/**
 * (amended 2026-10-14, §2.13 item 20) A row's programme basis (B5/S10a): 0 = not loaded, otherwise a hash of
 * (epg ref, epgShiftMin). The pager stamps it per row on every snapshot ([GuideWindowData.rowEpgBasis]); the state
 * machine accepts a snapshot built from an older rows version when every row's basis is unchanged.
 */
fun epgBasisOf(ch: GuideChannel?): Long {
    if (ch == null) return 0L
    val ref = ch.epg
    var h = app.thebox.core.common.Hash64.long(app.thebox.core.common.Hash64.of(null), ch.epgShiftMin.toLong())
    h = app.thebox.core.common.Hash64.long(h, ref?.sourceId ?: -1L)
    h = app.thebox.core.common.Hash64.str(h, ref?.xmltvId)
    return if (h == 0L) 1L else h
}
