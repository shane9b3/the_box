package app.thebox.core.playback.ts

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-B. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.Sniff
import app.thebox.core.playback.StreamSniffer

/** First-bytes classifier (§3.3.3). */
class StreamSnifferImpl : StreamSniffer {
    override fun sniff(buf: ByteArray, length: Int, endOfStream: Boolean): Sniff = TODO("WP-B")
}
