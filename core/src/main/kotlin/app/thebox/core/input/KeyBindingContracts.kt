// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.7. Verbatim; changes go through the lead.
package app.thebox.core.input

import kotlinx.coroutines.flow.StateFlow

enum class Press { SHORT, LONG }

/** One user change: [action] null = "unbound" (removes a default). Keyed by (context, keyCode, press). */
@kotlinx.serialization.Serializable
data class KeyOverride(
    val context: KeyContext,
    val keyCode: Int,
    val press: Press,
    /** BoxAction.id, or null for unbound. Unknown ids (from a newer backup) are ignored, not crashed on. */
    val action: String?,
    /** InputDevice name at capture time, for the list ("on Amazon Fire TV Remote"). Display only. */
    val capturedFrom: String? = null,
    val atMs: Long,
)

@kotlinx.serialization.Serializable
data class KeyBindingOverrides(val version: Int = 1, val entries: List<KeyOverride> = emptyList())

/** What capture saw (the router fills it from the native KeyEvent). */
data class CapturedKey(val keyCode: Int, val keyName: String, val deviceName: String?, val deviceId: Int, val isVirtual: Boolean)

data class CaptureState(val token: Long, val action: BoxAction, val press: Press, val startedAtMs: Long, val captured: CapturedKey? = null, val verdict: BindingVerdict? = null)

sealed interface BindingVerdict {
    data object Free : BindingVerdict
    /** Saving moves the key from [current] in [contexts] (the later binding wins). */
    data class Replaces(val current: BoxAction, val contexts: Set<KeyContext>) : BindingVerdict
    data class Reserved(val reason: ReservedReason) : BindingVerdict
    /** Adding a long binding makes the short press act on release and disables its auto-repeat (§3.8.2). */
    data class LosesRepeat(val shortAction: BoxAction, val then: BindingVerdict) : BindingVerdict
}
/** (reviewed 2026-10-16, S14) UNKNOWN_CODE: KEYCODE_UNKNOWN (0), which some CEC/IR remotes send for several buttons. */
enum class ReservedReason { NAVIGATION, OK, BACK, DIGIT, SYSTEM, CONTEXT_MENU_LONG_OK, UNKNOWN_CODE }

/** Action metadata for the mapping screen (labels come from :ui resources by [BoxAction.id]). */
data class ActionInfo(val action: BoxAction, val contexts: Set<KeyContext>, val group: ActionGroup)
/** (reviewed 2026-10-16, N1) Same five groups as §3.8.1. */
enum class ActionGroup { WATCHING, CHANNELS, GUIDE, PLAYER_OVERLAYS, APP }

interface KeyBindingStore {
    val overrides: StateFlow<KeyBindingOverrides>
    /** Applies [BindingRules.save] (later binding wins) and persists. */
    fun bind(action: BoxAction, key: CapturedKey, press: Press, nowMs: Long)
    fun unbind(context: KeyContext, keyCode: Int, press: Press, nowMs: Long)
    fun resetKey(keyCode: Int)
    fun resetAction(action: BoxAction)
    fun resetAll()
}

/** The KeyMap the router uses (InputModule binds it). Snapshot rebuilt off the main thread on every change. */
interface EffectiveKeyMap : KeyMap {
    val defaults: KeyMap
    /** Keys bound to [action] in [context] (short and long), for the mapping list and the footer hints. */
    fun keysFor(context: KeyContext, action: BoxAction): List<Pair<Int, Press>>
}
