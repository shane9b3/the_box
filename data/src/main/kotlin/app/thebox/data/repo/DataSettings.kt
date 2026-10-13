package app.thebox.data.repo

import app.thebox.core.match.MatchSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** When the last good XMLTV download (`cacheDir/epg/<id>.src`) is kept after a build (§2.5.5). */
enum class KeepCachedXmltv { ALWAYS, IF_FREE_OVER_1GB, NEVER }

/** Data-layer settings (product §4.3, plan §8). M5 backs [DataSettings] with DataStore. */
data class DataSettingsSnapshot(
    val epgPastDays: Int = 2,
    val epgFutureDays: Int = 7,
    val match: MatchSettings = MatchSettings(),
    val storeDescriptions: Boolean = true,
    val maxDescriptionChars: Int = 2048,
    val preferredLanguages: List<String> = emptyList(),
    val keepCachedXmltv: KeepCachedXmltv = KeepCachedXmltv.IF_FREE_OVER_1GB,
    /** epg-periodic interval (§3.10). */
    val epgRefreshIntervalH: Int = 12,
    /** Added to every channel's guide shift (§2.2). */
    val globalShiftMin: Int = 0,
    /** App-start refresh threshold for playlists with update_on_start (§3.10). */
    val playlistStaleAfterMs: Long = 12L * 3600 * 1000,
)

interface DataSettings {
    val settings: StateFlow<DataSettingsSnapshot>
    val current: DataSettingsSnapshot get() = settings.value
    fun update(transform: (DataSettingsSnapshot) -> DataSettingsSnapshot)
}

/** In-memory default (M1). Values reset on process death; M5 replaces the binding with a DataStore one. */
@Singleton
class InMemoryDataSettings @Inject constructor() : DataSettings {
    private val state = MutableStateFlow(DataSettingsSnapshot())
    override val settings: StateFlow<DataSettingsSnapshot> = state
    override fun update(transform: (DataSettingsSnapshot) -> DataSettingsSnapshot) = state.update(transform)
}
