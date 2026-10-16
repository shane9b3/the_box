package app.thebox.data.settings

import app.thebox.core.settings.FirstRunDecider
import app.thebox.core.settings.SettingsStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * WP-A's `:data` bindings (docs/design/m5-setup-settings.md §2.13). Commit 0 binds the in-memory [RoomSettingsStore]
 * stub and the always-null [FirstRunDecider]; WP-A adds the persisted adapters and `FirstRunDeciderImpl` here and
 * removes the in-memory settings bindings they replace.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsDataModule {
    @Binds @Singleton abstract fun settingsStore(impl: RoomSettingsStore): SettingsStore

    companion object {
        /** Stub (§3.4.1): always the guide. WP-A replaces it with a `@Binds` of `FirstRunDeciderImpl`. */
        @Provides @Singleton fun firstRunDecider(): FirstRunDecider = FirstRunDecider.NONE
    }
}
