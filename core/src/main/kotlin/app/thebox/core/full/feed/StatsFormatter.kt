package app.thebox.core.full.feed

import app.thebox.core.full.StatLine
import app.thebox.core.full.StatsFacts

/** WP-C stub (docs/design/m4-player-ux.md §1.4, §3.4.4, §3.2.4): `StatsFacts` → stat lines in `StatLabel` order. */
object StatsFormatter {
    fun lines(facts: StatsFacts): List<StatLine> = TODO("WP-C")

    /** "1080p50", "720p", "576i25"; null while [height] is unknown. */
    fun resolutionLabel(height: Int, fps: Float?, interlaced: Boolean): String? = TODO("WP-C")
}
