package app.thebox.settings

import android.view.KeyEvent
import app.thebox.core.full.ErrorButton
import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.TextHint
import app.thebox.player.api.PlaybackState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I-M5-21 "Error-card route" (docs/design/m5-setup-settings.md §3.14, §6.3): `auth_mode 401` in FULL → card with
 * "Edit login" → Settings on the login rows (detached, S2); BACK → FULL with the card; Retry after fixing the password
 * plays. **Awaits WP-C** (ErrorCardModel buttons, error routes) **and WP-A/WP-B** (shell, PlaylistSource page).
 */
class ErrorCardRouteTest : SettingsTestBase() {
    @Test
    fun editLoginFromTheCardAndRetry() {
        val pid = seedXtream()
        launch()
        cardToTheLoginRows(pid)
        // Fix the "password" (the panel accepts again) and Retry → plays.
        panel.authMode("ok")
        val retry = ep.playerProbe().current!!.ui.value.error!!.buttons.indexOf(ErrorButton.RETRY)
        repeat(6) { press(KeyEvent.KEYCODE_DPAD_LEFT, intervalMs = 80) }
        repeat(retry) { press(KeyEvent.KEYCODE_DPAD_RIGHT, intervalMs = 80) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNotNull("plays after Retry", runBlocking { withTimeoutOrNull(30_000) { controller.state.first { it is PlaybackState.Playing } } })
    }

    /**
     * I-M5-21 with "Lock settings" on (C-S1 = U-S8, code review 2026-10-16): the card's route enters Settings detached and
     * PIN-free (S2) on the login rows, not the PIN prompt and Root; BACK returns to FULL with the card.
     */
    @Test
    fun errorCardRouteIsDetachedAndPinFreeWithLockSettingsOn() {
        wipe()
        val pid = seedXtream()
        launch()
        setPin()
        openSettings("parental")
        activate("parental.lock_settings")
        awaitSettings("Lock settings on") { probe.parental().policy.lockSettings }
        leaveSettings()
        relaunch() // the session is locked again
        awaitGui("guide after the relaunch", 15_000) { it.mode == ScreenMode.GUIDE }
        assertTrue("the session is locked", !probe.parental().unlocked)
        cardToTheLoginRows(pid)
        assertNull("no PIN prompt for an error-card route", gui().pin)
        panel.authMode("ok")
    }

    /** `auth_mode 401` in FULL → the card's "Edit login" → Settings on the login rows (detached) → BACK → FULL with the card. */
    private fun cardToTheLoginRows(pid: Long) {
        awaitGui("guide lineup", 30_000) { it.empty == null && it.panel != null }
        Thread.sleep(1_000)
        panel.authMode("401")
        press(KeyEvent.KEYCODE_DPAD_CENTER) // tune the focused channel (mini player) …
        Thread.sleep(2_000)
        press(KeyEvent.KEYCODE_DPAD_CENTER) // … then OK again → FULL (M4)
        awaitGui("FULL", 20_000) { it.mode == ScreenMode.FULL }
        val card = run {
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            var c = ep.playerProbe().current?.ui?.value?.error
            while (c == null && android.os.SystemClock.elapsedRealtime() < deadline) { Thread.sleep(250); c = ep.playerProbe().current?.ui?.value?.error }
            c
        }
        assertNotNull("error card in FULL", card)
        assertEquals("the card routes to the login", SettingsRoute.PlaylistSource(pid), card!!.route)
        val idx = card.buttons.indexOf(ErrorButton.OPEN_SETTINGS)
        assertTrue("an Edit login button", idx >= 0)
        repeat(idx) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull("the card's route asks no PIN", run { Thread.sleep(500); gui().pin })
        awaitSettings("Settings on the login rows (detached)") { it.route == SettingsRoute.PlaylistSource(pid) && it.stack.none { r -> r == SettingsRoute.Root } }
        // (M5 integration) WP-B's page loads the stored source first (a placeholder), then the form rows.
        assertNotNull(rowWhere("the password row", 15_000) { (it.kind as? app.thebox.core.settings.RowKind.Text)?.hint == TextHint.PASSWORD })
        press(KeyEvent.KEYCODE_BACK)
        awaitGui("BACK → FULL with the card") { it.mode == ScreenMode.FULL }
        assertNotNull(ep.playerProbe().current?.ui?.value?.error)
    }
}
