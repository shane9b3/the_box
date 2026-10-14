// Contract (M3 commit 0): docs/design/m3-guide.md §2.4a. Verbatim; changes go through the lead.
package app.thebox.core.guide

import kotlinx.coroutines.flow.StateFlow

/** EPG retention the guide limits use (DataSettings in :data; bound in GuideDataModule). */
data class GuideDataWindow(val pastDays: Int = 2, val futureDays: Int = 7)
interface GuideDataWindowSource { val window: StateFlow<GuideDataWindow> }

/** Sync chip input (SyncStatusRepository in :data maps to this; bound in GuideDataModule). */
data class SyncFeedState(val busy: Boolean, val progress: String?, val unacknowledgedFailures: Int)
interface SyncFeed { val state: StateFlow<SyncFeedState> }

/** Bound in :ui (InputModule, WP-D) from ActivityManager; computed once. */
fun interface DeviceClassSource { fun get(): DeviceClass }

/** Debug-only probe for the instrumented suite: the live VM registers its machine; release builds bind a no-op. */
interface GuideDebugProbe {
    fun register(machine: GuideStateMachine?)
    val current: GuideStateMachine?
}
