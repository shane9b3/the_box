package app.thebox.core.full.state

/** WP-A stub (docs/design/m4-player-ux.md §1.2, §3.3, §3.1.4): cursor, `top`, accelerated holds, Previous row, page sizes. */
class ChannelListModel {
    /** −1 = the pinned "Previous" row, else a lineup index. */
    val focused: Int get() = TODO("WP-A")
    /** First visible lineup index. */
    val top: Int get() = TODO("WP-A")

    /** Opens on [tunedIndex] (or 0) at visible row 3 (§3.3.1). */
    fun open(size: Int, tunedIndex: Int, hasPrevious: Boolean): Unit = TODO("WP-A")

    /** Moves by [delta] rows; [repeat] drives the acceleration of §3.1.4. */
    fun move(delta: Int, repeat: Int): Unit = TODO("WP-A")

    companion object {
        const val LIST_VISIBLE = 10
        const val LIST_PAGE_ROWS = 10
        const val LIST_PREFETCH_BEFORE = 6
        const val LIST_PREFETCH_AFTER = 8
        const val LIST_ACCEL_AFTER = 8
        const val LIST_ACCEL_STEP = 3
        /** Repeat count from which a held key moves a page (§3.1.4). */
        const val LIST_PAGE_FROM_REPEAT = 20
    }
}
