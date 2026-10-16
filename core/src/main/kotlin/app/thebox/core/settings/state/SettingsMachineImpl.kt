package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.parental.PinGate
import app.thebox.core.settings.SettingsEffects
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsMachineFactory
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The settings machine (docs/design/m5-setup-settings.md §2.3, §3.2). **Commit-0 stub (WP-A replaces the body):** it
 * records the route it was opened on and declines every action, so the guide machine keeps M4's SETTINGS behaviour
 * (BACK leaves, everything else is ignored) and the app runs unchanged.
 */
class SettingsMachineImpl(
    @Suppress("unused") private val effects: SettingsEffects,
    @Suppress("unused") private val nowMs: () -> Long,
) : SettingsMachine {
    private val _ui = MutableStateFlow(SettingsUiState())
    override val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()
    override val nextDeadlineMs: StateFlow<Long> = GuideStateMachine.NO_DEADLINE
    override var pinGate: PinGate? = null
    override val textInputActive: Boolean get() = false

    override fun open(route: SettingsRoute, fromFull: Boolean, detached: Boolean) {
        _ui.value = SettingsUiState(route = route)
    }

    override fun act(action: GuideEvent.Act): Boolean = false

    override fun onLeave() = Unit

    override fun dispatch(event: SettingsEvent) = Unit

    companion object {
        val FACTORY = SettingsMachineFactory { effects, nowMs -> SettingsMachineImpl(effects, nowMs) }
    }
}
