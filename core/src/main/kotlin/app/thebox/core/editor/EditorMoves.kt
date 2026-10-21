package app.thebox.core.editor

/**
 * Block gather and splice for Reorder (docs/design/m6-channel-editor.md §2.3 E5, §4 item 6). Pure.
 *
 * Coordinates: a block of k rows is lifted out of a list of n rows. Its position ([EditorMove.index],
 * [EditorWrite.ReorderChannels.toIndex]) is the index of the block's first row in the **resulting** list, which is also
 * the insertion index into the list with the block removed; it runs over `0 .. n − k`. For the Groups pane the move
 * state uses pane indices (offset by the fixed head rows), and the write's `toIndex` is the group index.
 */
object EditorMoves {
    /** A lifted block: its keys in list order, the original list index of each, and the block's start position. */
    data class Block<T>(val rows: List<T>, val keys: List<String>, val sourceIndices: List<Int>, val start: Int)

    /**
     * Gathers the explicitly ticked rows ([ticks]: key → list index when ticked, [rows]: key → row) in list order into
     * one block at [focus] (a list index). Rows missing from [rows] are left out. With no ticks the focused row alone is
     * the block ([focusRow]). Null when there is nothing to lift.
     */
    fun <T> gather(ticks: Map<String, Int>, rows: Map<String, T>, focus: Int, focusKey: String?, focusRow: T?): Block<T>? {
        if (ticks.isEmpty()) {
            if (focusKey == null || focusRow == null) return null
            return Block(listOf(focusRow), listOf(focusKey), listOf(focus), focus)
        }
        val ordered = ticks.entries.filter { rows.containsKey(it.key) }.sortedBy { it.value }
        if (ordered.isEmpty()) return null
        val before = ordered.count { it.value < focus }
        return Block(ordered.map { rows.getValue(it.key) }, ordered.map { it.key }, ordered.map { it.value }, focus - before)
    }

    /** [index] moved by [delta] and clamped to the block's range in a list of [count] rows (block of [size]). */
    fun step(index: Int, delta: Int, count: Int, size: Int): Int = (index + delta).coerceIn(0, maxOf(0, count - size))

    /** The final order: [keys] with [block] removed and inserted (in its order) at [toIndex] (clamped). */
    fun splice(keys: List<String>, block: List<String>, toIndex: Int): List<String> {
        val inBlock = block.toHashSet()
        val rest = keys.filter { it !in inBlock }
        val at = toIndex.coerceIn(0, rest.size)
        return rest.subList(0, at) + block + rest.subList(at, rest.size)
    }

    /**
     * The lifted rows' original indices (ascending) from the published state, for [sourceIndex]: the ticked rows'
     * indices (group indices in the Groups pane), or the focused row alone ([EditorMove.fromIndex], less the head rows for
     * groups). Empty when not moving.
     */
    fun liftedSources(ui: ChannelEditorUiState): List<Int> {
        val m = ui.move ?: return emptyList()
        val groups = m.pane == EditorPane.GROUPS
        return when (val s = if (groups) ui.groupSelection else ui.channelSelection) {
            is Selection.Keys -> s.keys.values.sorted()
            else -> listOf(if (groups) m.fromIndex - ui.groups.head.size else m.fromIndex)
        }
    }

    /**
     * What a drawn row shows while a block of [blockSize] rows sits at [start] (both in list coordinates): `-1` for a
     * block row (the view draws `lifted.rows[displayIndex − start]`), else the original list index to draw there.
     * [sourceIndices] are the block rows' original indices, ascending.
     */
    fun sourceIndex(displayIndex: Int, start: Int, blockSize: Int, sourceIndices: List<Int>): Int {
        if (displayIndex in start until start + blockSize) return -1
        // Index into the list without the block.
        val r = if (displayIndex < start) displayIndex else displayIndex - blockSize
        // Map the r-th non-block row back to its original index: skip block rows at or before it.
        var original = r
        for (s in sourceIndices) if (s <= original) original++ else break
        return original
    }
}
