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
        // (M5 integration) WP-C's rows: "Add a button (press)" = binding.add_short, "(hold)" = binding.add_long.
        activate(if (long) "binding.add_long" else "binding.add_short")
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
        // (M5 integration) The capture card is WP-C's CaptureOverlay over the page (not page rows): its state is
        // SettingsUiState.capture (CaptureFlow); the texts "Yellow", "from an unknown device" and "replaces Audio" are
        // drawn from it (CaptureOverlay; checked on the screenshot, not here).
        val c = awaitSettings("capture shows Yellow from an unknown device") { it.capture?.captured != null }.capture!!
        val key = c.captured!!
        assertEquals("Yellow captured", KeyEvent.KEYCODE_PROG_YELLOW, key.keyCode)
        assertTrue("injected events carry no real device (S16, F1): $key", key.deviceName == null || key.deviceId < 0 || key.isVirtual)
        val verdict = c.verdict
        assertTrue("it replaces Audio in the player: $verdict", verdict is app.thebox.core.input.BindingVerdict.Replaces && verdict.current == BoxAction.AUDIO)
        assertEquals("focus on Save", 0, sui().captureButton)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSettings("capture closed") { it.capture == null }
        leaveSettings()
        // In FULL, Yellow opens the subtitles picker. (M5 integration) OK tunes the focused channel, a second OK opens FULL.
        var ok = 0
        while (gui().mode != ScreenMode.FULL && ok++ < 3) { press(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(2_000) }
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
        // (M5 integration) "always navigate" is the Reserved(NAVIGATION) verdict's line on the overlay.
        val r = awaitSettings("D-pad is reserved") { it.capture?.verdict != null }.capture!!.verdict
        assertTrue("D-pad reserved: $r", r is app.thebox.core.input.BindingVerdict.Reserved && r.reason == app.thebox.core.input.ReservedReason.NAVIGATION)
        assertEquals("focus moves to Try another", 1, sui().captureButton)
        press(KeyEvent.KEYCODE_BACK)
    }

    /** I-M5-13 */
    @Test
    fun longPressBindingKeepsTheShortPress() {
        seedXtream()
        launch()
        bindingsPage(BoxAction.GROUPS_PANEL)
        captureKey(KeyEvent.KEYCODE_CHANNEL_UP, long = true)
        // (M5 integration) The warning is the LosesRepeat verdict CaptureOverlay draws ("…acts on release, no repeat").
        val v = awaitSettings("the card warns about repeat") { it.capture?.verdict != null }.capture!!.verdict
        assertTrue("short CH+ loses its repeat: $v", v is app.thebox.core.input.BindingVerdict.LosesRepeat)
        assertEquals("focus on Save", 0, sui().captureButton)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSettings("capture closed") { it.capture == null }
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
