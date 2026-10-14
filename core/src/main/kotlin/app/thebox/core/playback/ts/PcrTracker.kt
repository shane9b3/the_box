package app.thebox.core.playback.ts

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-B. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.PcrTracker

/** PCR PID pick, 33-bit PCR base, jump test (§3.3.5). */
class PcrTrackerImpl : PcrTracker {
    override fun onPacket(pkt: ByteArray, off: Int, nowMs: Long): Unit = TODO("WP-B")
    override fun markReconnect(nowMs: Long): Unit = TODO("WP-B")
    override fun pendingJumpMs(): Long? = TODO("WP-B")
    override fun awaitingPcr(): Boolean = TODO("WP-B")
}
