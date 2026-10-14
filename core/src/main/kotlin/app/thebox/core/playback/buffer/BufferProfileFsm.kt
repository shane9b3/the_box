package app.thebox.core.playback.buffer

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.BufferProfileFsm

/** Levels, escalation, decay, manual mapping (§3.6). Per playlist key, in memory. */
class BufferProfileFsmImpl : BufferProfileFsm {
    override fun profileFor(playlistKey: String, mode: BufferMode, lowRam: Boolean): BufferProfile = TODO("WP-A")
    override fun onRebuffer(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile? = TODO("WP-A")
    override fun onStableTick(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile? = TODO("WP-A")
}
