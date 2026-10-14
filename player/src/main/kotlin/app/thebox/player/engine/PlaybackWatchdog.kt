package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import app.thebox.core.playback.StallDetectors
import app.thebox.core.playback.StallVerdict
import app.thebox.player.internal.PlaybackWatchdogApi
import app.thebox.player.internal.StreamTelemetry

/** Samples the player once a second and runs the `:core` detectors (§3.7). */
internal class PlaybackWatchdog(private val detectors: StallDetectors) : PlaybackWatchdogApi {
    override fun start(onStall: (StallVerdict) -> Unit): Unit = TODO("WP-C")
    override fun stop(): Unit = TODO("WP-C")
    override fun bind(telemetry: StreamTelemetry?): Unit = TODO("WP-C")
    override fun reset(): Unit = TODO("WP-C")
    override fun setExpectations(videoExpected: Boolean, audioExpected: Boolean): Unit = TODO("WP-C")
}
