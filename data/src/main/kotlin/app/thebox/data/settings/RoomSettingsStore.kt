package app.thebox.data.settings

import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings store over `app_setting` (docs/design/m5-setup-settings.md §2.2, §0.4, §0.5). **Commit-0 stub (WP-A
 * replaces the body):** in memory, [ready] is true at once, nothing is persisted, export is empty and import does
 * nothing, so the app runs exactly as before M5 (the M1–M4 in-memory settings classes stay bound until WP-A's persisted
 * adapters replace them).
 */
@Singleton
class RoomSettingsStore @Inject constructor() : SettingsStore {
    private val _ready = MutableStateFlow(true)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val flows = HashMap<String, MutableStateFlow<Any?>>()

    override suspend fun awaitReady() = Unit

    override fun load() = Unit

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(domain: SettingsDomain<T>): MutableStateFlow<T> =
        synchronized(flows) { flows.getOrPut(domain.name) { MutableStateFlow<Any?>(domain.default) } } as MutableStateFlow<T>

    override fun <T> flow(domain: SettingsDomain<T>): StateFlow<T> = state(domain).asStateFlow()

    override fun <T> update(domain: SettingsDomain<T>, transform: (T) -> T) {
        val s = state(domain)
        s.value = transform(s.value)
    }

    override suspend fun flush() = Unit

    override suspend fun export(): Map<String, JsonElement> = emptyMap()

    override suspend fun importInTransaction(documents: Map<String, JsonElement>, replace: Boolean) = Unit
}
