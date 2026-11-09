package app.thebox.data.work

/**
 * Gate for `setProgressAsync`, which is one WorkManager database transaction per call (performance review L8): a line
 * is passed on when it differs from the last one passed and at least [minGapMs] went by since. The in-memory
 * `SyncStatusRepository` still gets every line.
 */
internal class ProgressWrites(private val minGapMs: Long = MIN_GAP_MS, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var last: String? = null
    private var lastAt = 0L

    fun shouldWrite(line: String): Boolean {
        if (line == last) return false
        val now = nowMs()
        if (last != null && now - lastAt < minGapMs) return false
        last = line
        lastAt = now
        return true
    }

    companion object {
        const val MIN_GAP_MS = 2_000L
    }
}
