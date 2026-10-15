package app.thebox.core.full.state

/** WP-A stub (docs/design/m4-player-ux.md §1.2, §3.5.2, §3.5.3): pure step table, preview clamp, chip targets. */
object ScrubModel {
    /** One commit this long after the last scrub key (architecture review B5.3). */
    const val SCRUB_COMMIT_MS = 600L
    /** FF/RW step per press. */
    const val FF_RW_STEP_MS = 30_000L

    /** Step for a ←/→ event held [heldMs] (E21: < 1 s 10 s, < 3 s 30 s, < 10 s 120 s, else 300 s). */
    fun stepMs(heldMs: Long): Long = TODO("WP-A")

    /** `clamp((preview ?: playhead) + deltaMs, archiveFromMs, liveEdgeMs)` (programme frame). */
    fun preview(previewMs: Long?, playheadMs: Long, deltaMs: Long, archiveFromMs: Long, liveEdgeMs: Long): Long = TODO("WP-A")
}
