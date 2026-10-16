package app.thebox.ui.settings.vm

import app.thebox.core.settings.SettingsMachineFactory
import app.thebox.core.settings.SettingsNavigator
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.state.SettingsMachineImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * WP-A's `:ui` bindings (docs/design/m5-setup-settings.md §1.3a, §2.13). Commit 0 binds the stub machine factory and a
 * navigator that does nothing (the guide machine already opens SETTINGS itself); WP-A owns this file.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsUiModule {
    @Provides fun settingsMachineFactory(): SettingsMachineFactory = SettingsMachineImpl.FACTORY

    @Provides fun settingsNavigator(): SettingsNavigator = object : SettingsNavigator {
        override fun openSettings(route: SettingsRoute, fromFull: Boolean, detached: Boolean) = Unit
    }
}
