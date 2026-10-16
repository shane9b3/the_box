package app.thebox.settings

import android.view.KeyEvent
import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I-M5-20 "Five-key settings journey" (docs/design/m5-setup-settings.md §3.15, §6.3): D-pad/OK/Back only: every
 * section, a choice row, a toggle, a link two levels deep, back to the guide; the end state equals the JVM table
 * (`SettingsReachabilityTest`: BACK from any depth leaves Settings within 12 presses, focus returns to the guide).
 * **Awaits WP-A** (shell, sections, machine) and every WP's section page.
 */
class FiveKeySettingsTest : SettingsTestBase() {
    @Test
    fun fiveKeyJourney() {
        seedXtream()
        launch()
        openSettings("root")
        awaitSettings("sections pane") { it.pane == Pane.SECTIONS && it.sections.isNotEmpty() }
        // Every section: ↓ through the sections pane, → opens each page.
        val sections = sui().sections
        for ((i, id) in sections.withIndex()) {
            while (sui().sectionCursor < i) press(KeyEvent.KEYCODE_DPAD_DOWN, intervalMs = 100)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitSettings("page of $id") { it.pane == Pane.PAGE && it.route == SettingsRoute.Section(id) && it.page != null }
            press(KeyEvent.KEYCODE_BACK)
            awaitSettings("back to sections from $id") { it.pane == Pane.SECTIONS }
        }
        // A choice row and a toggle (Interface: clock = Choice, numbers = Toggle (advanced: turn advanced on first)).
        while (sui().sectionCursor != sections.indexOf(SectionId.INTERFACE)) press(if (sui().sectionCursor < sections.indexOf(SectionId.INTERFACE)) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, intervalMs = 100)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val choice = rowWhere("a Choice row") { it.kind is RowKind.Choice }
        moveTo(choice.id.key)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSettings("inline choice open") { it.choice != null }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSettings("choice closed") { it.choice == null }
        rows().firstOrNull { it.kind is RowKind.Toggle }?.let { t ->
            val on = (t.kind as RowKind.Toggle).on
            activate(t.id.key)
            awaitSettings("toggle flipped") { s -> (rows(s).first { it.id == t.id }.kind as RowKind.Toggle).on != on }
        }
        // A link two levels deep: About → Diagnostics → a connection test.
        press(KeyEvent.KEYCODE_BACK)
        while (sui().sectionCursor != sections.indexOf(SectionId.ABOUT)) press(if (sui().sectionCursor < sections.indexOf(SectionId.ABOUT)) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, intervalMs = 100)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        activate("about.diagnostics")
        awaitSettings("Diagnostics") { it.route == SettingsRoute.Diagnostics }
        val test = rowWhere("a connection-test link") { r -> isLinkTo(r) { it is SettingsRoute.NetworkTest } }
        activate(test.id.key)
        awaitSettings("connection test (two levels deep)") { it.route is SettingsRoute.NetworkTest }
        // Back to the guide with BACK only, within 12 presses (SettingsReachabilityTest (c)).
        var presses = 0
        while (gui().mode == ScreenMode.SETTINGS && presses < 12) {
            press(KeyEvent.KEYCODE_BACK, intervalMs = 250)
            presses++
        }
        assertEquals("left Settings with BACK", ScreenMode.GUIDE, gui().mode)
        assertTrue("within 12 BACKs ($presses)", presses <= 12)
    }
}
