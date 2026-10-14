package app.thebox.core.playback.stall

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.StallDetectors
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.WatchdogSample

/** The four detectors over a [WatchdogSample] (§3.7). */
class StallDetectorsImpl : StallDetectors {
    override fun onSample(s: WatchdogSample): StallVerdict? = TODO("WP-A")
    override fun reset(nowMs: Long): Unit = TODO("WP-A")
}
