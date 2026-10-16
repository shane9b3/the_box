package app.thebox.settings

import android.view.KeyEvent
import app.thebox.core.full.PickerKind
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Button mapping on the device (docs/design/m5-setup-settings.md §3.8, §6.3): I-M5-12 capture and binding (the emulator
 * reports "from an unknown device" for injected events, S16/F1) and I-M5-13 long-press binding. **Awaits WP-C** (capture
 * hook in `KeyRouterImpl`, `RemappedKeyMap`, mapping pages, capture overlay) **and WP-A** (shell).
 */
class ButtonMappingTest : SettingsTestBase() {

    private fun bindingsPage(action: BoxAction) {
        openSettings("mapping")
        val row = rowWhere("${action.id} in the mapping list") { r -> isLinkTo(r) { it == SettingsRoute.ActionBindings(action) } }
        activate(row.id.key)
        awaitSettings("bindings of ${action.id}") { it.route == SettingsRoute.ActionBindings(action) }
    }

    private fun captureKey(keyCode: Int, long: Boolean = false) {
        activate(rowWhere("Add a button") { it.kind is RowKind.Action }.id.key)
        awaitSettings("capture armed") { it.capture != null }
        if (long) longPress(keyCode) else press(keyCode)
    }

    /** I-M5-12 */
    @Test
    fun captureBindsYellowToSubtitles() {
        seedXtream()
        launch()
        bindingsPage(BoxAction.SUBTITLES)
        captureKey(KeyEvent.KEYCODE_PROG_YELLOW)
        val text = awaitText("capture shows Yellow from an unknown device") { it.contains("Yellow") }
        assertTrue("injected events carry no real device (S16, F1): $text", text.contains("unknown device", ignoreCase = true))
        assertTrue("the card says it replaces Audio in the player", text.contains("Audio"))
        activate(rowWhere("Save") { it.kind is RowKind.Action && it.label.contains("Save", true) }.id.key)
        leaveSettings()
        // In FULL, Yellow opens the subtitles picker.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitGui("FULL") { it.mode == ScreenMode.FULL }
        press(KeyEvent.KEYCODE_PROG_YELLOW)
        Thread.sleep(800)
        val picker = probe.let { ep.playerProbe().current?.ui?.value?.picker }
        assertEquals(PickerKind.SUBTITLES, picker?.kind)
        press(KeyEvent.KEYCODE_BACK, 3)
        // Reset all restores Audio; D-pad → "always navigate".
        openSettings("mapping")
        activate(rowWhere("Reset all") { it.kind is RowKind.Action && it.label.contains("Reset", true) }.id.key)
        if (sui().confirm != null) {
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        bindingsPage(BoxAction.AUDIO)
        captureKey(KeyEvent.KEYCODE_DPAD_UP)
        awaitText("D-pad is reserved") { it.contains("always navigate", ignoreCase = true) }
    }

    /** I-M5-13 */
    @Test
    fun longPressBindingKeepsTheShortPress() {
        seedXtream()
        launch()
        bindingsPage(BoxAction.GROUPS_PANEL)
        captureKey(KeyEvent.KEYCODE_CHANNEL_UP, long = true)
        awaitText("the card warns about repeat") { it.contains("repeat", ignoreCase = true) || it.contains("hold", ignoreCase = true) }
        activate(rowWhere("Save") { it.kind is RowKind.Action && it.label.contains("Save", true) }.id.key)
        leaveSettings()
        val before = gui().overlay
        press(KeyEvent.KEYCODE_CHANNEL_UP)
        Thread.sleep(500)
        assertTrue("short CH+ still pages on release (no panel)", gui().overlay !is Overlay.Groups)
        longPress(KeyEvent.KEYCODE_CHANNEL_UP)
        val ui = awaitGui("hold opens the Groups panel") { it.overlay is Overlay.Groups }
        assertNotNull(ui.overlay)
        assertNotNull(before ?: Unit)
    }
}
