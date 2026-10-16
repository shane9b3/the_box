package app.thebox.settings

import android.view.KeyEvent
import app.thebox.core.guide.ScreenMode
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parental controls on the device (docs/design/m5-setup-settings.md §3.9, §6.3): I-M5-8 groups review and PIN offer,
 * I-M5-14 PIN prompt and lockout (B1 rows), I-M5-15 lock settings, I-M5-28 keystore reset (S8), I-M5-29 no PIN, adult
 * content filtered (B3). **Awaits WP-C** (PIN store/session, TuneGuard, PIN pad, Groups panel entries) **and WP-A/WP-B**
 * (shell, groups review). The fake panel's adult category (§6.4: `adult_category`) supplies the adult group.
 */
class ParentalTest : SettingsTestBase() {

    private fun setPin(pin: String = "1234") {
        openSettings("parental")
        activate("parental.pin")
        createPin(pin)
        awaitSettings("PIN set") { s -> s.page?.rows?.any { it.id.key == "parental.remove_pin" || it.label.contains("Change", true) } == true }
        assertTrue(probe.parental().pinSet)
        leaveSettings()
    }

    /**
     * Enter [pin] twice on the create pad. (M5 integration) WP-C's pad asks "Easy to guess. Use it anyway?" after the
     * first entry of a PIN like 1234 (§3.9.3); OK on its focused "Use it" goes on to the confirmation.
     */
    private fun createPin(pin: String) {
        awaitGui("PIN pad (create)") { it.pin != null }
        digits(pin)
        Thread.sleep(300)
        // The soft confirm focuses "Choose another" (the safe button): ← then OK picks "Use it".
        if (gui().pin?.message == app.thebox.core.parental.PinMessage.EASY) { press(KeyEvent.KEYCODE_DPAD_LEFT); press(KeyEvent.KEYCODE_DPAD_CENTER) }
        awaitGui("PIN pad (confirm)") { it.pin != null && it.pin?.mode == app.thebox.core.parental.PinPadMode.CREATE_CONFIRM }
        digits(pin)
        awaitGui("PIN pad closed", 10_000) { it.pin == null }
    }

    /** I-M5-8 */
    @Test
    fun groupsReviewAndPinOffer() {
        panel.adultCategory(true)
        wipe()
        launch()
        awaitGui("first run") { it.mode == ScreenMode.SETTINGS }
        // Onboard through the debug seed is not the onboarding flow; I-M5-8 uses the AddSource → GroupsReview path.
        activate(rowWhere("Xtream entry") { r -> isLinkTo(r) { it is SettingsRoute.AddSource } }.id.key)
        editText(rowWhere("server", p = textRow(app.thebox.core.settings.TextHint.URL)), base)
        editText(rowWhere("username", p = textRow(app.thebox.core.settings.TextHint.USERNAME)), "test")
        editText(rowWhere("password", p = textRow(app.thebox.core.settings.TextHint.PASSWORD)), "test")
        // (M5 integration) Connect by WP-B's id, Continue on the result card, Start watching on the import page (§3.4.4–6).
        activate(rowWhere("Connect") { it.kind is RowKind.Action && it.id.key == "setup.xtream.connect" }.id.key)
        rowWhere("result card", 30_000) { it.id.key == "setup.card.primary" }
        activate("setup.card.primary")
        awaitSettings("import progress", 60_000) { it.route is SettingsRoute.ImportProgress }
        activate(rowWhere("Start watching", 180_000) { it.kind is RowKind.Action && it.enabled }.id.key)
        awaitSettings("groups review", 180_000) { it.route is SettingsRoute.GroupsReview }
        val adult = rowWhere("adult group starts locked with 18+") { r -> (r.kind as? RowKind.Item)?.badges?.contains(app.thebox.core.settings.Badge.ADULT) == true }
        assertTrue((adult.kind as RowKind.Item).badges.contains(app.thebox.core.settings.Badge.LOCKED))
        // Hide two non-adult groups → the EPG whitelist excludes their channels (importer count).
        val others = rows().filter { r -> r.kind is RowKind.Item && r.id != adult.id }.take(2)
        others.forEach { r -> activate(r.id.key) }
        // Set PIN → the Groups panel shows the adult group with the lock glyph.
        activate(rowWhere("Set a PIN offer") { it.label.contains("PIN", true) && (it.kind is RowKind.Action) }.id.key)
        createPin("1234")
        // (M5 integration) Creating the PIN unlocks the session (the user just typed it, WP-C): the policy is LOCKED_AUTO.
        assertTrue(probe.parental().pinSet)
        assertEquals(ParentalAccess.LOCKED_AUTO, probe.parental().copy(unlocked = false).access)
    }

    /** I-M5-14 */
    @Test
    fun pinPromptLockoutAndRelock() {
        wipe() // (M5 integration) the seed's reset keeps the PIN; every test starts from fresh data
        panel.adultCategory(true)
        val pid = seedXtream()
        launch()
        setPin()
        // (M5 integration) Creating the PIN unlocks the session until the app closes (WP-C, §3.9.5): close and reopen
        // so the session is locked again, as the design's "PIN set; OK on the locked group → pad" assumes.
        relaunch()
        awaitGui("guide after the relaunch", 15_000) { it.mode == ScreenMode.GUIDE }
        Thread.sleep(1_500)
        // OK on the locked group in the Groups panel → pad; 3 wrong → 30 s countdown (debug clock); right PIN → the group opens.
        // (M5 integration) ← opens Groups only at the left edge of a channel without catch-up (M3 I-7); the catalog's
        // first row has an archive, so go through the group chip (↑ to the header, OK), as M3's KeysOnDeviceTest does.
        var up = 0
        while (gui().zone != app.thebox.core.guide.Zone.HEADER && up++ < 40) press(KeyEvent.KEYCODE_DPAD_UP, intervalMs = 120)
        var left = 0
        while (gui().headerFocused != 0 && left++ < 4) press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val groups = awaitGui("groups panel") { it.overlay is app.thebox.core.guide.Overlay.Groups }.overlay as app.thebox.core.guide.Overlay.Groups
        val target = groups.shortcuts.size + groups.entries.indexOfFirst { it.locked }
        assertTrue("the adult group is listed with the lock", target >= groups.shortcuts.size)
        var guard = 0
        while ((gui().overlay as app.thebox.core.guide.Overlay.Groups).focused != target && guard++ < 200) {
            press(if ((gui().overlay as app.thebox.core.guide.Overlay.Groups).focused < target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, intervalMs = 60)
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitGui("PIN pad for the locked group") { it.pin != null }
        repeat(3) { digits("0000") }
        awaitGui("lockout countdown", 5_000) { it.pin.toString().contains("lock", ignoreCase = true) }
        debugLink("clock_offset_ms=31000")
        // (M5 integration) The pad re-enables on its next 1 s tick; digits typed before it are dropped by design.
        awaitGui("lockout over on the pad", 5_000) { it.pin?.lockedUntilMs == null }
        digits("1234")
        awaitGui("group opened", 10_000) { it.pin == null }
        assertTrue(probe.parental().unlocked)
        // Tune the adult channel, then Home and back while it plays → PIN pad, no video (B1).
        val adultLineup = runBlocking { library.lineup(groups.entries.first { it.locked }.source, ParentalAccess.OPEN).first() }
        val adultChannel = runBlocking { library.channels(adultLineup.rowIds, 0, 1) }.first()!!
        onMain { controller.tune(app.thebox.player.api.TuneTarget.Channel(adultChannel.identity)) }
        runBlocking { withTimeoutOrNull(20_000) { controller.state.first { it is app.thebox.player.api.PlaybackState.Playing } } }
        // (M5 integration) Past first media and M2's 1 s minimum, so the channel can become "previous" later.
        Thread.sleep(2_500)
        // (M5 integration) An injected HOME never reaches the system from the app's instrumentation (no pause in the log):
        // stop the activity the way Home does (onStop), then bring it back.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Thread.sleep(2_000)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        awaitGui("PIN pad after returning to a locked channel", 10_000) { it.pin != null }
        assertFalse("relocked after the app stopped", probe.parental().unlocked)
        assertTrue("no video of the locked channel", controller.state.value !is app.thebox.player.api.PlaybackState.Playing)
        press(KeyEvent.KEYCODE_BACK)
        // Blue (previous channel) after a relock → PIN pad. (M5 integration) The locked channel becomes "previous" only
        // once another channel plays (M2 rule): tune an open channel first.
        val openChannel = runBlocking { library.channels(library.lineup(app.thebox.core.guide.GuideSource.All(pid), ParentalAccess.LOCKED_AUTO).first().rowIds, 0, 1) }.first()!!
        onMain { controller.tune(app.thebox.player.api.TuneTarget.Channel(openChannel.identity)) }
        runBlocking { withTimeoutOrNull(20_000) { controller.state.first { it is app.thebox.player.api.PlaybackState.Playing } } }
        Thread.sleep(1_500)
        press(KeyEvent.KEYCODE_PROG_BLUE)
        awaitGui("PIN pad for the previous (locked) channel", 5_000) { it.pin != null }
        press(KeyEvent.KEYCODE_BACK)
        // CH± after the 1-hour expiry never lands on a locked channel.
        openSettings("parental")
        // (M5 integration) "Stay unlocked" is an advanced row (§3.3.8): show advanced settings first.
        showAdvanced()
        choose("parental.stay_unlocked", "one_hour")
        leaveSettings()
        debugLink("clock_offset_ms=${31_000 + 3_601_000}")
        repeat(20) {
            press(KeyEvent.KEYCODE_CHANNEL_UP, intervalMs = 400)
            assertTrue("CH± never lands on the locked channel", controller.state.value.target?.identity != adultChannel.identity)
        }
        debugLink("clock_offset_ms=0")
        assertNotNull(pid)
    }

    /** I-M5-15 */
    @Test
    fun lockSettingsAsksForThePinAtEveryEntry() {
        wipe() // (M5 integration) the seed's reset keeps the PIN; every test starts from fresh data
        seedXtream()
        launch()
        setPin()
        openSettings("parental")
        activate("parental.lock_settings")
        leaveSettings()
        // (M5 integration) The session is unlocked since the PIN was created: relock by closing the app.
        relaunch()
        awaitGui("guide after the relaunch", 15_000) { it.mode == ScreenMode.GUIDE }
        // OPEN_SETTINGS key (Keys.SETTINGS = 176) asks for the PIN.
        press(KeyEvent.KEYCODE_SETTINGS)
        awaitGui("PIN for Settings") { it.pin != null }
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(ScreenMode.GUIDE, gui().mode)
        // First run (fresh data) does not.
        wipe()
        launch()
        awaitGui("first run without a PIN") { it.mode == ScreenMode.SETTINGS && it.pin == null }
    }

    /** I-M5-28 (S8) */
    @Test
    fun keystoreResetFailsOpenWithContentFiltered() {
        wipe() // (M5 integration) the seed's reset keeps the PIN; every test starts from fresh data
        panel.adultCategory(true)
        seedXtream()
        launch()
        setPin()
        closeApp()
        debugLink("keystore_reset=1")
        launch()
        assertTrue("pin lost flag", probe.parental().pinLostToKeyReset)
        assertFalse(probe.parental().pinSet)
        assertEquals(ParentalAccess.FILTERED_NO_PIN, probe.parental().access)
        openSettings("parental")
        assertNull("Settings open without a PIN", gui().pin)
        leaveSettings()
        setPin("4321")
        assertEquals(ParentalAccess.LOCKED_AUTO, probe.parental().copy(unlocked = false).access)
    }

    private val library get() = probe.library

    /** I-M5-29 (B3) */
    @Test
    fun noPinAdultContentFiltered() {
        wipe() // (M5 integration) the seed's reset keeps the PIN; every test starts from fresh data
        panel.adultCategory(true)
        val pid = seedXtream()
        launch()
        val access = probe.parental().access
        assertEquals("no PIN, auto-lock on → FILTERED_NO_PIN", ParentalAccess.FILTERED_NO_PIN, access)
        // Absent from All channels, Search and number entry (CH± walks the same lineup).
        val all = runBlocking { withTimeoutOrNull(10_000) { library.lineup(app.thebox.core.guide.GuideSource.All(pid), access).first() } }!!
        val channels = runBlocking { library.channels(all.rowIds, 0, all.rowIds.size) }.filterNotNull()
        assertFalse("adult channel absent from All channels", channels.any { it.displayName.contains("18+") })
        val hits = runBlocking { ep.search().search("Late Night", System.currentTimeMillis(), access) }
        assertTrue("adult channel absent from Search", hits.channels.none { it.displayName.contains("18+") })
        assertNull("number entry does not reach it", runBlocking { library.byNumber(pid, 14, access) })
        // The Groups panel lists the adult group with 18+ (locked = true).
        val entries = runBlocking { withTimeoutOrNull(10_000) { library.sources(access).first() } }!!
        val adult = entries.firstOrNull { it.label.contains("Adult") }
        assertNotNull("the Groups panel lists the adult group", adult)
        assertTrue("listed locked", adult!!.locked)
        // The Parental row reads "On · not protected".
        openSettings("parental")
        val row = rowWhere("Parental auto-lock row") { it.id.key == "parental.auto_lock" }
        assertTrue("value: ${row.value}", row.value?.contains("not protected", ignoreCase = true) == true)
        leaveSettings()
        // OK on the adult group → "not protected" card → Open anyway shows it: opened-anyway sources are queried with OPEN.
        val opened = runBlocking { withTimeoutOrNull(10_000) { library.lineup(adult.source, ParentalAccess.OPEN).first() } }!!
        assertTrue("Open anyway shows the adult channels", opened.rowIds.isNotEmpty())
    }
}
