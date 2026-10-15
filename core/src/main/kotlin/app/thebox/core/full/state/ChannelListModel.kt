package app.thebox.core.full.state

/**
 * Channel-list cursor (docs/design/m4-player-ux.md §3.3, §3.1.4): cursor, `top`, accelerated holds, the pinned
 * Previous row (−1) and page sizes. Pure; indices only (nothing iterates the lineup).
 */
class ChannelListModel {
    /** −1 = the pinned "Previous" row, else a lineup index. */
    var focused: Int = 0
        private set
    /** First visible lineup index. */
    var top: Int = 0
        private set
    var size: Int = 0
        private set
    var hasPrevious: Boolean = false
        private set

    /** Opens on [tunedIndex] (or 0) at visible row 3 (§3.3.1). */
    fun open(size: Int, tunedIndex: Int, hasPrevious: Boolean) {
        this.size = maxOf(0, size)
        this.hasPrevious = hasPrevious
        focused = if (this.size == 0) (if (hasPrevious) -1 else 0) else tunedIndex.coerceIn(0, this.size - 1)
        top = placeTop(focused)
    }

    /** The lineup changed under the cursor (same source): keep the index, clamped. */
    fun resize(size: Int, hasPrevious: Boolean) {
        this.size = maxOf(0, size)
        this.hasPrevious = hasPrevious
        if (focused == -1 && !hasPrevious) focused = 0
        if (focused >= this.size) focused = maxOf(0, this.size - 1)
        top = clampTop(top)
        keepVisible()
    }

    /** Moves by one event of a key held for [repeat] repeats in direction [delta] (±1), accelerating per §3.1.4. */
    fun move(delta: Int, repeat: Int) {
        val dir = Integer.signum(delta)
        if (dir == 0) return
        if (focused == -1) {
            if (dir > 0 && size > 0) { focused = 0; keepVisible() }
            return
        }
        if (dir < 0 && focused == 0) {
            if (hasPrevious) focused = -1
            return
        }
        if (size == 0) return
        focused = (focused + dir * rowsPerEvent(repeat)).coerceIn(0, size - 1)
        keepVisible()
    }

    /** CH± in the list: one page ([LIST_PAGE_ROWS]) towards lower ([dir] < 0) or higher numbers. */
    fun page(dir: Int) {
        if (size == 0) return
        val from = if (focused < 0) 0 else focused
        focused = (from + Integer.signum(dir) * LIST_PAGE_ROWS).coerceIn(0, size - 1)
        top = clampTop(top + Integer.signum(dir) * LIST_PAGE_ROWS)
        keepVisible()
    }

    fun focusIndex(index: Int) {
        if (size == 0) return
        focused = index.coerceIn(0, size - 1)
        top = placeTop(focused)
    }

    private fun keepVisible() {
        if (focused < 0) { top = 0; return }
        if (focused < top) top = focused
        if (focused >= top + LIST_VISIBLE) top = focused - LIST_VISIBLE + 1
        top = clampTop(top)
    }

    private fun placeTop(cursor: Int): Int = clampTop(cursor - OPEN_ROW)
    private fun clampTop(t: Int): Int = t.coerceIn(0, maxOf(0, size - LIST_VISIBLE))

    companion object {
        const val LIST_VISIBLE = 10
        const val LIST_PAGE_ROWS = 10
        const val LIST_PREFETCH_BEFORE = 6
        const val LIST_PREFETCH_AFTER = 8
        const val LIST_ACCEL_AFTER = 8
        const val LIST_ACCEL_STEP = 3
        /** Repeat count from which a held key moves a page (§3.1.4). */
        const val LIST_PAGE_FROM_REPEAT = 20
        /** The cursor opens on this visible row (0-based: the fourth row, §3.3.1). */
        const val OPEN_ROW = 3

        /** Rows per UP/DOWN event of a hold: 1 below repeat 8, then 3, then a page from repeat 20 (§3.1.4). */
        fun rowsPerEvent(repeat: Int): Int = when {
            repeat < LIST_ACCEL_AFTER -> 1
            repeat < LIST_PAGE_FROM_REPEAT -> LIST_ACCEL_STEP
            else -> LIST_PAGE_ROWS
        }
    }
}
