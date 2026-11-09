package app.thebox.core.guide.layout

/** WP-A named constants (Appendix B). */
object GridConstants {
    const val SLIVER_MIN = 5
    const val LEAD_MIN = 30
    const val TAIL_MIN = 30
    const val SNAP_AFTER_REPEATS = 3
    const val SCROLL_ANIM_MS = 200
    const val PAGE_ROWS = 5
    const val TIME_PAGE_MIN = 180
    const val TIME_STEP_MIN = 30
    const val A11Y_DEBOUNCE_MS = 300L
    const val TEXT_FIT_SLOTS_TITLES = 512
    const val TEXT_FIT_SLOTS_NAMES = 256
    const val LOGO_CACHE = 160
    const val LOGO_CACHE_LOW_END = 48
    const val LOGO_CACHE_BYTES = 4L * 1024 * 1024
    const val LOGO_CACHE_BYTES_LOW_END = 2L * 1024 * 1024
    /** Logo slot (§3.7), design px: 130 × 64 in a 74 row until 2026-11-09, now in proportion to the 85 row. */
    const val LOGO_W = 150f
    const val LOGO_H = 74f
}
