package app.thebox.data.settings

import android.app.ActivityManager
import android.content.Context
import android.text.format.DateFormat
import app.thebox.core.common.BoxLog
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.settings.DataPrefs
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.DataDomain
import app.thebox.core.settings.state.GuideDomain
import app.thebox.core.settings.state.GuidePrefsMapping
import app.thebox.core.settings.state.PlayerDomain
import app.thebox.data.db.dao.AppSettingDao
import app.thebox.data.di.ApplicationScope
import app.thebox.data.repo.DataSettings
import app.thebox.data.repo.DataSettingsSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `GuideSettingsSource` over the `guide` domain (docs/design/m5-setup-settings.md §2.2, S12). [GuidePrefsMapping]
 * resolves clock System and the automatic mini player / ambient values; `debugUnlocked` (debug receiver) stays in
 * memory and never reaches the store. [update] (M3/M4 callers, the debug receiver) maps the changed fields back, so an
 * untouched automatic value stays automatic.
 */
@Singleton
class PersistedGuideSettings internal constructor(
    private val store: SettingsStore,
    scope: CoroutineScope,
    private val system24h: () -> Boolean,
    private val deviceLowEnd: () -> Boolean,
) : GuideSettingsSource {
    @Inject constructor(store: SettingsStore, @ApplicationScope scope: CoroutineScope, @ApplicationContext context: Context) :
        this(store, scope, { DateFormat.is24HourFormat(context) }, { deviceClassOf(context).lowEnd })

    private var debugUnlocked = false
    private val state = MutableStateFlow(map(store.flow(GuideDomain).value))
    override val settings: StateFlow<GuideSettings> = state.asStateFlow()

    init {
        // A late read, a restore or a Settings page write reaches the guide; Unconfined keeps it synchronous.
        scope.launch(Dispatchers.Unconfined) { store.flow(GuideDomain).collect { state.value = map(it) } }
    }

    override fun update(transform: (GuideSettings) -> GuideSettings) {
        val before = state.value
        val after = transform(before)
        debugUnlocked = after.debugUnlocked
        store.update(GuideDomain) { GuidePrefsMapping.fromSettings(it, before, after) }
        state.value = map(store.flow(GuideDomain).value)
    }

    private fun map(p: GuidePrefs): GuideSettings = GuidePrefsMapping.toSettings(p, system24h(), deviceLowEnd(), debugUnlocked)
}

/** `PlayerSettingsSource` over the `player` domain: `PlayerSettings` is stored as is (§2.12 item 4). */
@Singleton
class PersistedPlayerSettings @Inject constructor(private val store: SettingsStore) : PlayerSettingsSource {
    override val settings: StateFlow<PlayerSettings> get() = store.flow(PlayerDomain)
    override fun update(transform: (PlayerSettings) -> PlayerSettings) = store.update(PlayerDomain, transform)
}

/**
 * `DataSettings` over the `data` domain (§2.2, S12). The user-settable part comes from [DataPrefs]; the rest of
 * [DataSettingsSnapshot] (description cap, languages, XMLTV cache rule, staleness, match thresholds) keeps its M1
 * defaults in memory. **Auto past days** (`epgPastDays = 0`, the one place this mapping lives): the most catch-up days
 * of an enabled playlist, clamped to 1–7 ([autoPastDays]).
 */
@Singleton
class PersistedDataSettings internal constructor(
    private val store: SettingsStore,
    scope: CoroutineScope,
    maxCatchupDays: kotlinx.coroutines.flow.Flow<Int?>,
) : DataSettings {
    @Inject constructor(store: SettingsStore, @ApplicationScope scope: CoroutineScope, dao: AppSettingDao) :
        this(store, scope, dao.observeMaxCatchupDays())

    @Volatile private var extras = DataSettingsSnapshot()
    @Volatile private var catchupDays: Int? = null
    private val state = MutableStateFlow(map(store.flow(DataDomain).value))
    override val settings: StateFlow<DataSettingsSnapshot> = state.asStateFlow()

    init {
        scope.launch(Dispatchers.Unconfined) { store.flow(DataDomain).collect { state.value = map(it) } }
        scope.launch {
            maxCatchupDays.distinctUntilChanged()
                .catch { e -> BoxLog.w(TAG, "catch-up days unavailable: ${e.javaClass.simpleName}") }
                .collect { d ->
                    catchupDays = d
                    state.value = map(store.flow(DataDomain).value)
                }
        }
    }

    override fun update(transform: (DataSettingsSnapshot) -> DataSettingsSnapshot) {
        val before = state.value
        val after = transform(before)
        extras = after
        store.update(DataDomain) { prefsFrom(it, before, after) }
        state.value = map(store.flow(DataDomain).value)
    }

    private fun map(p: DataPrefs): DataSettingsSnapshot = extras.copy(
        epgPastDays = if (p.epgPastDays == 0) autoPastDays(catchupDays) else p.epgPastDays,
        epgFutureDays = p.epgFutureDays,
        match = extras.match.copy(level = p.matchLevel),
        storeDescriptions = p.storeDescriptions,
        epgRefreshIntervalH = if (p.epgDailyAtHour != null) DAILY_INTERVAL_H else p.epgRefreshIntervalH,
        globalShiftMin = p.globalShiftMin,
    )

    companion object {
        private const val TAG = "PersistedDataSettings"
        /** "Daily at a set time" runs as a 24 h period (the hour is a scheduling hint, see `SettingsSideEffects`). */
        const val DAILY_INTERVAL_H = 24

        /** S12: Auto = max catch-up days of the enabled playlists, at least 1 and at most 7. */
        fun autoPastDays(maxCatchupDays: Int?): Int = (maxCatchupDays ?: 1).coerceIn(1, 7)

        /**
         * The stored form after an `update` changed [before] into [after]: only changed fields are applied (so a
         * transform queued before the store's read, S1, never overwrites stored fields it did not change).
         */
        fun prefsFrom(old: DataPrefs, before: DataSettingsSnapshot, after: DataSettingsSnapshot): DataPrefs {
            val intervalChanged = after.epgRefreshIntervalH != before.epgRefreshIntervalH
            return old.copy(
                epgPastDays = GuidePrefsMapping.pick(old.epgPastDays, before.epgPastDays, after.epgPastDays),
                epgFutureDays = GuidePrefsMapping.pick(old.epgFutureDays, before.epgFutureDays, after.epgFutureDays),
                matchLevel = GuidePrefsMapping.pick(old.matchLevel, before.match.level, after.match.level),
                storeDescriptions = GuidePrefsMapping.pick(old.storeDescriptions, before.storeDescriptions, after.storeDescriptions),
                epgRefreshIntervalH = if (intervalChanged) after.epgRefreshIntervalH else old.epgRefreshIntervalH,
                epgDailyAtHour = if (intervalChanged) null else old.epgDailyAtHour,
                globalShiftMin = GuidePrefsMapping.pick(old.globalShiftMin, before.globalShiftMin, after.globalShiftMin),
            )
        }
    }
}

/** `DeviceClass` as `:ui`'s `DeviceClassSourceImpl` computes it (the automatic low-end values, S12). */
internal fun deviceClassOf(context: Context): DeviceClass {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return DeviceClass(lowRam = am.isLowRamDevice, totalMemBytes = mem.totalMem, memoryClassMb = am.memoryClass)
}
