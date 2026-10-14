package app.thebox.player.controller

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.ResolvedStream
import app.thebox.player.internal.StreamSessionContext

/** Per-tune context; owns the lease, telemetry and the [StreamSessionContext] (§3.1.1 step 6). */
internal class TuneSession(
    val tuneId: Long,
    val resolved: ResolvedStream,
    val ctx: StreamSessionContext,
) {
    val lease: CoreLease? get() = TODO("WP-D")
    fun end(): Unit = TODO("WP-D")
}
