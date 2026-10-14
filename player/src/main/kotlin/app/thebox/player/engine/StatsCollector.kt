package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.thebox.player.api.StreamStats

/** `AnalyticsListener` + telemetry → `StreamStats` at ≤ 2 Hz (§3.13). */
@OptIn(UnstableApi::class)
internal class StatsCollector : AnalyticsListener {
    fun snapshot(): StreamStats = TODO("WP-C")
}
