package app.thebox.player.broker

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-A. Replace the TODO bodies; keep the names.
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.MonoClock
import app.thebox.player.internal.StreamBrokerRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** One `ConnectionBrokerCore` per broker key, configured from playlist/account (§3.5). */
@Singleton
internal class StreamBrokerRegistryImpl @Inject constructor(private val clock: MonoClock) : StreamBrokerRegistry {
    override fun broker(key: String): ConnectionBrokerCore = TODO("WP-A")
    override fun configure(key: String, maxConnections: Int): Unit = TODO("WP-A")
}
