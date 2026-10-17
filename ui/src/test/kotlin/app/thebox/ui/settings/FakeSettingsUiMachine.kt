package app.thebox.ui.settings

import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.parental.PinGate
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.state.SettingsShellEvent
import app.thebox.core.settings.state.SettingsShellLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A settings machine whose state the test sets, recording events and link calls (UI kit tests). */
class FakeSettingsUiMachine(initial: SettingsUiState = SettingsUiState()) : SettingsMachine, SettingsShellLink {
    val state = MutableStateFlow(initial)
    override val ui: StateFlow<SettingsUiState> = state
    override val nextDeadlineMs: StateFlow<Long> = GuideStateMachine.NO_DEADLINE
    override var pinGate: PinGate? = null
    override val textInputActive: Boolean get() = state.value.editor != null
    val events = ArrayList<SettingsEvent>()
    val pushed = ArrayList<SettingsRoute>()
    var guide: ((GuideEvent) -> Unit)? = null
    private val shell = MutableSharedFlow<SettingsShellEvent>(extraBufferCapacity = 8)
    val backs = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override fun open(route: SettingsRoute, fromFull: Boolean, detached: Boolean) { state.value = state.value.copy(route = route) }
    override fun act(action: GuideEvent.Act): Boolean = true
    override fun onLeave() = Unit
    override fun dispatch(event: SettingsEvent) { events += event }

    override fun attachGuide(dispatch: ((GuideEvent) -> Unit)?) { guide = dispatch }
    override fun openFromFull(route: SettingsRoute, detached: Boolean) = Unit
    override fun push(route: SettingsRoute) { pushed += route }
    /** U-S6: routes pushed through the PIN gate (asked through [pinGate] when one is set; pushed when it answers false). */
    val gatedPushed = ArrayList<SettingsRoute>()
    override fun pushGated(route: SettingsRoute) {
        gatedPushed += route
        if (pinGate?.request(app.thebox.core.parental.PinPurpose.SettingsAction(gatedPushed.size.toLong())) != true) pushed += route
    }
    val replaced = ArrayList<SettingsRoute>()
    override val shellEvents: SharedFlow<SettingsShellEvent> = shell
    override fun post(event: SettingsShellEvent) { shell.tryEmit(event) }
    override val editorBacks: SharedFlow<Unit> = backs
    override var backHandler: ((SettingsRoute) -> Boolean)? = null
    override fun replace(route: SettingsRoute) { replaced += route; state.value = state.value.copy(route = route) }
    override fun pop() = Unit
    override fun popTo(route: SettingsRoute) = Unit
    override fun leaveToGuide() = Unit
    override fun confirm(spec: app.thebox.core.settings.ConfirmSpec, intent: app.thebox.core.settings.RowIntent) = Unit
}
