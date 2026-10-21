package app.thebox.core.editor

/**
 * The editor's FIFO of writes in flight (docs/design/m6-channel-editor.md §3.3 "Write queue"). Pure.
 *
 * Each action appends one [EditorWrite] with what it acted on ([T], e.g. the ticks to clear after it lands). Writes are
 * handed to the view model at once and run there one at a time, in order, in the application scope; this queue only
 * mirrors them so the machine can bound them, show "Saving…", and match each `Written` to its entry. Entries are never
 * merged or dropped: a `Written` completes the oldest entry with an equal write.
 */
class EditorWriteQueue<T>(private val max: Int = ChannelEditorMachine.WRITE_QUEUE_MAX) {
    data class Entry<T>(val write: EditorWrite, val meta: T)

    private val entries = ArrayDeque<Entry<T>>()

    val size: Int get() = entries.size
    val full: Boolean get() = entries.size >= max
    fun isEmpty(): Boolean = entries.isEmpty()

    /** False (nothing queued) while [full]. */
    fun offer(write: EditorWrite, meta: T): Boolean {
        if (full) return false
        entries.addLast(Entry(write, meta))
        return true
    }

    /** Removes and returns the oldest entry for [write] (normally the head); null when none is pending. */
    fun complete(write: EditorWrite): Entry<T>? {
        val i = entries.indexOfFirst { it.write == write }
        if (i < 0) return null
        return entries.removeAt(i)
    }

    fun snapshot(): List<Entry<T>> = entries.toList()

    fun clear() = entries.clear()
}
