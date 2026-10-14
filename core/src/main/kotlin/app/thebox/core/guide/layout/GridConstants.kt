package app.thebox.core.guide.layout

/**
 * Bits of `GuideWindowData.cellFlags` above the `ProgrammeFlags` range (docs/design/m3-guide.md §3.2.4, §3.6.4).
 *
 * WP-A local: §3.2.4/§3.6.4 name these bits but commit 0 did not put them in a contract file. The values are the
 * ones the design gives; the lead should move this object into `GuideModel.kt` (hand-off note) so WP-B's pager
 * sets [RTL] and [COMPLEX] from the same constants.
 */
object CellFlags {
    /** A GAP piece shorter than [GridConstants.SLIVER_MIN]: tiles the row, not focusable, draws nothing. */
    const val SLIVER: Int = 1 shl 30
    /** Title needs bidi (`java.text.Bidi.requiresBidi`), set by the pager once per textId (S6). */
    const val RTL: Int = 1 shl 29
    /** Title contains a combining mark, variation selector or ZWJ: grapheme cuts use `BreakIterator` (S6). */
    const val COMPLEX: Int = 1 shl 28
}

/** WP-A named constants (Appendix B). */
object GridConstants {
    const val SLIVER_MIN = 5
    const val LEAD_MIN = 30
    const val TAIL_MIN = 30
    const val SNAP_AFTER_REPEATS = 3
    const val SCROLL_ANIM_MS = 200
    const val PAGE_ROWS = 6
    const val TIME_PAGE_MIN = 180
    const val TIME_STEP_MIN = 30
    const val A11Y_DEBOUNCE_MS = 300L
    const val TEXT_FIT_SLOTS_TITLES = 512
    const val TEXT_FIT_SLOTS_NAMES = 256
    const val LOGO_CACHE = 160
    const val LOGO_CACHE_LOW_END = 48
    const val LOGO_CACHE_BYTES = 4L * 1024 * 1024
    const val LOGO_CACHE_BYTES_LOW_END = 2L * 1024 * 1024
    /** Logo slot (§3.7), design px. */
    const val LOGO_W = 96f
    const val LOGO_H = 40f
}
