package app.thebox.ui.parental

import android.content.res.Resources
import app.thebox.core.common.BoxLog
import app.thebox.core.input.ActionGroup
import app.thebox.core.input.BoxAction
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.parental.ParentalDomain
import app.thebox.core.parental.ParentalHost
import app.thebox.core.parental.ParentalPinStore
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.RelockReason
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.tree.ButtonMappingRows
import app.thebox.core.settings.tree.ParentalRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs the Parental page's commands (docs/design/m5-setup-settings.md §3.3.8): policy rows write the `parental`
 * domain; Set / Change PIN go through the guide machine's one prompt ([ParentalHost.startPinSetup]); Lock now relocks;
 * Remove PIN deletes the verifier (and turns "Lock settings" off: nothing could open it). WP-A's settings effects route
 * `parental.*` intents here (`ParentalRows.command`).
 */
class ParentalPageHost @Inject constructor(
    private val store: SettingsStore,
    private val pins: ParentalPinStore,
    private val session: ParentalSession,
) {
    fun run(command: ParentalRows.Command, host: ParentalHost?, scope: CoroutineScope) {
        when (command) {
            is ParentalRows.Command.SetupPin -> host?.startPinSetup(command.change)
            is ParentalRows.Command.Policy -> store.update(ParentalDomain, command.transform)
            ParentalRows.Command.LockNow -> session.relock(RelockReason.USER)
            ParentalRows.Command.RemovePin -> scope.launch {
                try {
                    pins.clear()
                    store.update(ParentalDomain) { it.copy(lockSettings = false) }
                    session.relock(RelockReason.PIN_CHANGED)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BoxLog.w(TAG, "PIN not removed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "ParentalPage"
    }
}

/**
 * Runs the button-mapping pages' commands (§3.8.3) over [KeyBindingStore]; `Capture` and `TestKey` are the settings
 * machine's capture overlay (WP-A's machine embeds `CaptureFlow`; the router hook is `KeyRouter.capture`).
 */
class MappingPageHost @Inject constructor(private val bindings: KeyBindingStore) {
    /** True when the command was a store write (the page reloads); false for capture commands the machine runs. */
    fun run(command: ButtonMappingRows.Command): Boolean = when (command) {
        ButtonMappingRows.Command.ResetAll -> { bindings.resetAll(); true }
        is ButtonMappingRows.Command.ResetAction -> { bindings.resetAction(command.action); true }
        is ButtonMappingRows.Command.ResetKey -> { bindings.resetKey(command.keyCode); true }
        is ButtonMappingRows.Command.Unbind -> { bindings.unbind(command.context, command.keyCode, command.press, System.currentTimeMillis()); true }
        is ButtonMappingRows.Command.Capture, ButtonMappingRows.Command.TestKey -> false
    }
}

/** Key, action and group names for the mapping pages and the capture overlay (§3.8.3: a table of the common keys). */
class ResourceMappingNames(private val res: Resources) : ButtonMappingRows.Names {
    override fun key(keyCode: Int): String = KeyNames.name(res, keyCode)
    override fun action(action: BoxAction): String = res.getString(ParentalStrings.actionLabel(action))
    override fun group(group: ActionGroup): String = res.getString(ParentalStrings.groupLabel(group))
}
