package app.thebox.core.playback.ts

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-B. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.TsPacketAligner

/** Sync search and whole-packet delivery, CC gap after resync, post-splice hold (§3.3.4). */
class TsPacketAlignerImpl : TsPacketAligner {
    override fun write(src: ByteArray, off: Int, len: Int): Int = TODO("WP-B")
    override fun read(dst: ByteArray, off: Int, len: Int): Int = TODO("WP-B")
    override fun available(): Int = TODO("WP-B")
    override fun resync(): Int = TODO("WP-B")
    override fun heldBytes(): Int = TODO("WP-B")
    override fun releaseHeld(): Unit = TODO("WP-B")
    override fun dropHeld(): Unit = TODO("WP-B")
}
