package app.thebox.player.controller

import app.thebox.core.common.BoxLog
import app.thebox.core.sports.DeviceAwake
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §2.3, A-S5) The process's [DeviceAwake]: [LifecycleCoordinator]
 * calls [set] after every power, display and HDMI event (main thread). Starts awake: the coordinator starts with the
 * player (`MainActivity` prewarms it) and corrects it on its first event.
 */
@Singleton
internal class DeviceAwakeState @Inject constructor() : DeviceAwake {
    private val state = MutableStateFlow(true)
    override val awake: StateFlow<Boolean> = state.asStateFlow()

    fun set(on: Boolean) {
        if (state.value == on) return
        BoxLog.i(TAG) { "device awake $on" }
        state.value = on
    }

    private companion object {
        const val TAG = "DeviceAwake"
    }
}
