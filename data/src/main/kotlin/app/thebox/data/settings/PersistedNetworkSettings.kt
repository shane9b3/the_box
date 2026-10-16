package app.thebox.data.settings

import app.thebox.core.net.NetworkSettings
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.NetworkDomain
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * The `network` settings domain (docs/design/m5-setup-settings.md §2.11, §3.13) as a source for `:data` callers (the
 * connection test, the "Use secure DNS" one-tap). [NetworkSettings] has no non-persisted fields, so this is the store's
 * flow as is; `BoxDns` reads the store directly at lookup time.
 */
@Singleton
class PersistedNetworkSettings @Inject constructor(private val store: SettingsStore) {
    val settings: StateFlow<NetworkSettings> get() = store.flow(NetworkDomain)

    fun update(transform: (NetworkSettings) -> NetworkSettings) = store.update(NetworkDomain, transform)
}
