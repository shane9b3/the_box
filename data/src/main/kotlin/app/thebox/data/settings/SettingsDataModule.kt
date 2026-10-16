package app.thebox.data.settings

import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.settings.FirstRunDecider
import app.thebox.core.settings.SettingsStore
import app.thebox.data.repo.DataSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * WP-A's `:data` bindings (docs/design/m5-setup-settings.md §2.13): the Room-backed store, the first-run decision and
 * the persisted adapters that replace the M1–M4 in-memory settings (`InMemoryGuideSettings`, `InMemoryPlayerSettings`,
 * `InMemoryDataSettings` stay as classes for tests, unbound). `PlaybackSettingsSource` is bound in `:app`
 * (`SettingsBindingsModule`), which keeps `defaultUserAgent` and the test hooks out of the store.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsDataModule {
    @Binds @Singleton abstract fun settingsStore(impl: RoomSettingsStore): SettingsStore
    @Binds @Singleton abstract fun firstRunDecider(impl: FirstRunDeciderImpl): FirstRunDecider
    @Binds @Singleton abstract fun guideSettings(impl: PersistedGuideSettings): GuideSettingsSource
    @Binds @Singleton abstract fun playerSettings(impl: PersistedPlayerSettings): PlayerSettingsSource
    @Binds @Singleton abstract fun dataSettings(impl: PersistedDataSettings): DataSettings
}
