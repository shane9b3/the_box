package app.thebox.core.playback.errors

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.LoadRetryRules

/** Pure status/exception-kind → retry delay rules used by WP-B's policy (§4.2). */
class LoadRetryRulesImpl : LoadRetryRules {
    override fun retryDelayMs(status: Int?, origin: LoadOrigin, transient: Boolean, errorCount: Int, random: Double): Long? = TODO("WP-A")
}
