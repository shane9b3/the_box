package app.thebox.player.broker

import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.broker.BrokerConstants
import app.thebox.core.playback.broker.ConnectionBrokerCoreImpl
import app.thebox.player.internal.StreamBrokerRegistry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One `ConnectionBrokerCore` per broker key (`p:<playlistId>`, `d:<host>`), configured from playlist/account (§3.5).
 * Brokers live for the process (one small object per playlist or direct host). Thread-safe; no `android.*`.
 */
@Singleton
internal class StreamBrokerRegistryImpl @Inject constructor(private val clock: MonoClock) : StreamBrokerRegistry {
    private val brokers = ConcurrentHashMap<String, ConnectionBrokerCore>()

    override fun broker(key: String): ConnectionBrokerCore = brokers.computeIfAbsent(key) { ConnectionBrokerCoreImpl(it, clock) }

    /** [maxConnections] ≤ 0 is "unknown" (1); values above `MAX_CONNECTIONS_CAP` are capped. */
    override fun configure(key: String, maxConnections: Int) = broker(key).configure(BrokerConstants.clamp(maxConnections))

    /** `override ?: account ?: 1`, clamped (§3.5.1); the controller calls this on every tune. */
    fun configure(key: String, override: Int?, accountMax: Int?) = configure(key, BrokerConstants.effectiveMax(override, accountMax))
}
