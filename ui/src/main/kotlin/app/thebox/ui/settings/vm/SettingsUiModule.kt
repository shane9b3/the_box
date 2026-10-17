package app.thebox.ui.settings.vm

import android.content.Context
import app.thebox.core.input.EffectiveKeyMap
import app.thebox.core.settings.SettingsMachineFactory
import app.thebox.core.settings.SettingsNavigator
import app.thebox.core.settings.state.HintValidator
import app.thebox.core.settings.state.SettingsMachineImpl
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.state.WpaPages
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/**
 * WP-A's `:ui` bindings (docs/design/m5-setup-settings.md §1.3a, §2.13): the settings machine factory (with the texts
 * of `strings_settings.xml` and the row validators), the navigator, the texts, and the declaration of the page-source
 * set every WP contributes to (`@IntoSet SettingsPageSource` from its own module).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsUiModule {
    @Multibinds abstract fun pageSources(): Set<SettingsPageSource>

    @Binds abstract fun settingsNavigator(impl: SettingsNavigatorImpl): SettingsNavigator

    companion object {
        @Provides @Singleton
        fun settingsTexts(@ApplicationContext context: Context): SettingsTexts = ResourceSettingsTexts(context.resources)

        /** U-S12 (code review 2026-10-16): the player's "Always passthrough" flag, from the stored playback prefs. */
        @Provides
        fun audioOutputForced(store: app.thebox.core.settings.SettingsStore): app.thebox.ui.full.vm.AudioOutputForced =
            app.thebox.ui.full.vm.AudioOutputForced {
                store.flow(app.thebox.core.settings.state.PlaybackDomain).map { it.audioOutput == app.thebox.core.playback.AudioOutputMode.FORCE_PASSTHROUGH }
            }

        @Provides
        fun settingsMachineFactory(texts: SettingsTexts, keyMap: EffectiveKeyMap): SettingsMachineFactory = SettingsMachineFactory { effects, nowMs ->
            SettingsMachineImpl(effects, nowMs, texts, HintValidator(texts, WpaPages.rowRules(texts)), keyMap = { keyMap })
        }
    }
}
