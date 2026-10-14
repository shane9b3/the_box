package app.thebox.core.playback.resolve

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.StreamFormat

/** URL → [StreamFormat] hint (§3.2.6). Only a hint: sniffing decides (§3.3.3). */
object FormatHints {
    fun hint(url: String): StreamFormat = TODO("WP-A")
}
