package app.thebox.ui.settings.vm

import androidx.lifecycle.ViewModel
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsEffects
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsMachineFactory
import app.thebox.core.settings.SettingsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The settings screen's view model (docs/design/m5-setup-settings.md §2.13): creates the [SettingsMachine] (activity
 * scoped, like the player view model); MainActivity hands [machine] to the guide view model (`attachSettings`, S11).
 * **Commit-0 stub (WP-A replaces the body):** the effects do nothing; the stub machine declines every action.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(factory: SettingsMachineFactory) : ViewModel() {
    private val effects = object : SettingsEffects {
        override fun loadPage(route: SettingsRoute) = Unit
        override fun loadItems(route: SettingsRoute, from: Int, to: Int) = Unit
        override fun onIntent(intent: RowIntent) = Unit
        override fun onClosed(changedTimings: Set<EffectTiming>) = Unit
        override fun armCapture(armed: Boolean) = Unit
        override fun exitApp() = Unit
        override fun announce(text: String) = Unit
    }

    val machine: SettingsMachine = factory.create(effects, System::currentTimeMillis)
}
