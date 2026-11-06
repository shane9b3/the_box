package app.thebox.core.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * (docs/design/m6-score-bug.md §2.3, A-S5) False while the display is off, `PowerManager.isInteractive()` is false or
 * HDMI/CEC reports the sink gone, even when the process is still STARTED (Fire OS display-off, CEC standby). Demand is
 * `NONE` while false. Bound in `:player` (`DeviceAwakeState`, fed by `LifecycleCoordinator`'s display/CEC facts).
 */
interface DeviceAwake {
    val awake: StateFlow<Boolean>

    companion object {
        /** Tests and hosts without a coordinator. */
        val ALWAYS: DeviceAwake = object : DeviceAwake {
            override val awake: StateFlow<Boolean> = MutableStateFlow(true)
        }
    }
}
