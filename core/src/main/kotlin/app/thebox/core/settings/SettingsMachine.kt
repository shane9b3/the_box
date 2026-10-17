// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.3. Verbatim; changes go through the lead.
package app.thebox.core.settings

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CaptureState
import app.thebox.core.input.CapturedKey
import app.thebox.core.parental.PinGate
import app.thebox.core.parental.PinPurpose
import kotlinx.coroutines.flow.StateFlow

enum class Pane { SECTIONS, PAGE }

data class EditorState(val row: RowId, val label: String, val initial: String, val hint: TextHint, val masked: Boolean, val chips: List<String>, val error: String? = null) {
    // U-L10: a masked initial value never prints.
    override fun toString(): String =
        "EditorState(row=$row, label=$label, initial=${if (masked) maskForLog(initial) else initial}, hint=$hint, masked=$masked, chips=$chips, error=$error)"
}
data class NumberPadState(val row: RowId, val label: String, val digits: String, val min: Int, val max: Int, val focusedKey: Int = 4, val error: String? = null)
data class ConfirmState(val spec: ConfirmSpec, val intent: RowIntent, val focused: Int = 1 /* cancel */)
data class ChoiceState(val row: RowId, val focused: Int)
data class MoveState(val row: RowId, val fromIndex: Int, val index: Int)
data class MenuState(val row: RowId, val options: List<ChoiceOption>, val focused: Int)

data class SettingsUiState(
    val route: SettingsRoute = SettingsRoute.Root,
    val stack: List<SettingsRoute> = emptyList(),
    val pane: Pane = Pane.SECTIONS,
    val sections: List<SectionId> = emptyList(),
    val sectionCursor: Int = 0,
    val page: PageModel? = null,
    val cursor: Int = 0,
    /** First visible row index (one-row lookahead scrolling, §3.2.4). */
    val top: Int = 0,
    val advanced: Boolean = false,
    val choice: ChoiceState? = null,
    val menu: MenuState? = null,
    val confirm: ConfirmState? = null,
    val editor: EditorState? = null,
    val numberPad: NumberPadState? = null,
    val move: MoveState? = null,
    /** "Press a key now" (button mapping, §3.8.3); the router's capture hook is armed while non-null. */
    val capture: CaptureState? = null,
    /** Transient line at the bottom of the page ("Saved", "Copied from link"). */
    val notice: String? = null,
    /**
     * (M5 integration 2026-10-16, additive) The capture overlay's button focus (0 Save, 1 Try another, 2 Cancel),
     * "Test a key" mode and its report (what the key does now in the guide and the player): WP-C's `CaptureFlow`.
     */
    val captureButton: Int = 0,
    val captureTest: Boolean = false,
    val captureReport: Pair<BoxAction?, BoxAction?>? = null,
)

/** Effects the machine requests; :ui's SettingsEffectsImpl implements them. Main-safe, non-blocking. */
interface SettingsEffects {
    fun loadPage(route: SettingsRoute)
    fun loadItems(route: SettingsRoute, from: Int, to: Int)
    fun onIntent(intent: RowIntent)
    /** Settings left by any route (S9): apply REPREPARE-class changes once through LiveSettingsApplier (§0.6), flush the store. */
    fun onClosed(changedTimings: Set<EffectTiming>)
    fun armCapture(armed: Boolean)
    fun exitApp()
    fun announce(text: String)
}

sealed interface SettingsEvent {
    data class Page(val page: PageModel) : SettingsEvent
    data class Items(val route: SettingsRoute, val version: Long, val from: Int, val rows: List<SettingsRow>) : SettingsEvent
    /** The editor overlay closed (Compose owned the keys meanwhile). */
    data class EditorClosed(val row: RowId, val text: String?, val committed: Boolean) : SettingsEvent {
        // U-L10: the event does not know whether the editor was masked, so the text never prints.
        override fun toString(): String = "EditorClosed(row=$row, text=${maskForLog(text)}, committed=$committed)"
    }
    /** (reviewed 2026-10-16, S3) BACK while the editor is open: the router turns it into this event (Cancel, or chips → field). */
    data object EditorBack : SettingsEvent
    data class Captured(val key: CapturedKey) : SettingsEvent
    data class CaptureTimedOut(val token: Long) : SettingsEvent
    data class PinResult(val purpose: PinPurpose, val granted: Boolean) : SettingsEvent
    data class Notice(val text: String) : SettingsEvent
    /** Advanced view toggled elsewhere or restored. */
    data class Advanced(val on: Boolean) : SettingsEvent
    data class Tick(val nowMs: Long) : SettingsEvent
}

interface SettingsMachine {
    val ui: StateFlow<SettingsUiState>
    /**
     * (reviewed 2026-10-16, S2) [detached]: the route opens as a standalone layer with nothing of Settings behind it
     * (no Root in the stack, no sections pane); BACK at it leaves SETTINGS, and its links into PIN-gated routes ask for
     * the PIN. Used for PIN-free entries (sync chip → SyncStatus, error-card routes while "Lock settings" is on).
     */
    fun open(route: SettingsRoute, fromFull: Boolean, detached: Boolean = false)
    /**
     * The guide machine's Act in SETTINGS. False for BACK at the root (or at a detached route), and for OPEN_GUIDE and
     * EXIT (N11): the guide machine then leaves SETTINGS to the guide, or opens its exit dialog, as in M3/M4.
     */
    fun act(action: GuideEvent.Act): Boolean
    /** (S9) Called by the guide machine on every exit from SETTINGS, whatever the route; emits onClosed at most once per visit. */
    fun onLeave()
    fun release(action: BoxAction) = Unit
    fun dispatch(event: SettingsEvent)
    /** True while [SettingsUiState.editor] is open: the router passes every key to Compose (§2.12.5). */
    val textInputActive: Boolean
    val nextDeadlineMs: StateFlow<Long>
    var pinGate: PinGate?
}

fun interface SettingsMachineFactory {
    fun create(effects: SettingsEffects, nowMs: () -> Long): SettingsMachine
}
