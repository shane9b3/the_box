package app.thebox.core.guide.state

import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction
import app.thebox.core.input.KeyContext
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinCheck
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsPresentation
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.RecordingSettingsMachine
import app.thebox.core.settings.state.SettingsPanelRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (Settings panel A1, docs/design/m6-player-settings-panel.md §2.2–§2.6, §10 `GuideSettingsPanelTest`) The guide side of
 * the in-player settings panel: entries, the key routing (S1, S11), the BACK ladder, the expand rule (S10), the reopen
 * rule (Q5), the TalkBack announcement (N10) and the one-source-of-truth invariant (S2).
 */
class GuideSettingsPanelTest {
    private val lockSettings = ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy(lockSettings = true))
    private val playback = SettingsRoute.Section(SectionId.PLAYBACK)

    /** A settings fake with a page stack: [push] goes one page deeper (as a key would); BACK pops until the root list. */
    private class Panel {
        val s = RecordingSettingsMachine()
        val stack = ArrayList<SettingsRoute>()
        /** What SELECT does (null: nothing but consume). */
        var onSelect: (() -> Unit)? = null
        init {
            s.consume = { a ->
                when (a.action) {
                    BoxAction.BACK -> if (stack.isEmpty()) false else {
                        stack.removeAt(stack.lastIndex)
                        s.showRoute(stack.lastOrNull() ?: SettingsRoute.Root)
                        true
                    }
                    BoxAction.OPEN_GUIDE, BoxAction.EXIT -> false
                    BoxAction.SELECT -> { onSelect?.invoke(); true }
                    else -> true
                }
            }
        }
        fun push(r: SettingsRoute) { stack += r; s.showRoute(r) }
    }

    private class World(val w: GuideWorld, val p: Panel, val full: FakePlayerStateMachine) {
        val s get() = p.s
        val ui get() = w.ui
    }

    private fun full(parental: ParentalState? = null, attachSettings: Boolean = true): World {
        val w = GuideWorld(startTuned = false)
        val f = FakePlayerStateMachine()
        w.machine.full = f
        val p = Panel()
        if (attachSettings) w.machine.settings = p.s
        parental?.let { w.send(GuideEvent.Parental(it)) }
        w.send(GuideEvent.StartFull)
        w.tuneTo(w.channels[2])
        w.flush()
        check(w.ui.mode == ScreenMode.FULL)
        return World(w, p, f)
    }

    private fun World.open(): World { w.send(GuideEvent.OpenSettings); return this }

    // ---- entries (§3)

    @Test
    fun quickBarSettingsInFullOpensThePanelPresentedBeforeOpen() {
        val x = full().open()
        assertEquals(ScreenMode.FULL, x.ui.mode, "the mode stays FULL")
        assertTrue(x.ui.settingsPanel)
        assertEquals(listOf("present(PANEL)", "open(Root,true,false)", "announceOpen"), x.s.log, "present before open; N10 announce")
        assertEquals(SettingsPresentation.PANEL, x.s.ui.value.presentation)
        assertEquals(true, x.full.panelCalls.last(), "the player hears the flag")
        assertEquals(0, x.full.leaveCount, "FULL is never left")
        assertEquals(KeyContext.GUIDE, x.w.machine.keyContext, "S1: the GUIDE key table while open")
    }

    @Test
    fun fromTheGuideSettingsStaysFullScreen() {
        val w = GuideWorld()
        w.machine.full = FakePlayerStateMachine()
        val s = RecordingSettingsMachine()
        w.machine.settings = s
        w.send(GuideEvent.OpenSettings)
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        assertFalse(w.ui.settingsPanel)
        assertTrue(s.presented.isEmpty())
    }

    @Test
    fun withoutASettingsMachineFullOpensFullScreenSettings() {
        val x = full(attachSettings = false).open()
        assertEquals(ScreenMode.SETTINGS, x.ui.mode, "S11 of M5: today's path")
        assertFalse(x.ui.settingsPanel)
    }

    @Test
    fun lockSettingsAsksForThePinOverTheVideoThenOpensThePanel() {
        val x = full(lockSettings).open()
        assertEquals(PinPurpose.EnterSettings(SettingsRoute.Root), x.ui.pin?.purpose)
        assertFalse(x.ui.settingsPanel)
        "2580".forEach { x.w.press(BoxAction.DIGIT, digit = it - '0') }
        x.w.send(GuideEvent.PinChecked(x.w.calls.last("verifyPin")!!.args[0] as Long, PinCheck.Ok))
        assertNull(x.ui.pin)
        assertEquals(ScreenMode.FULL, x.ui.mode)
        assertTrue(x.ui.settingsPanel)
        assertEquals(SettingsRoute.Root, x.s.opened.single().first)
    }

    @Test
    fun theSettingsKeyOpensThePanelAndASecondPressExpands() {
        val x = full()
        x.full.actFn = { false }
        x.w.press(BoxAction.OPEN_SETTINGS)
        assertTrue(x.ui.settingsPanel)
        assertEquals(ScreenMode.FULL, x.ui.mode)
        x.p.push(playback)
        x.w.press(BoxAction.OPEN_SETTINGS)
        assertEquals(ScreenMode.SETTINGS, x.ui.mode, "a second press expands")
        assertFalse(x.ui.settingsPanel)
        assertEquals(playback, x.s.ui.value.route, "same path")
        assertEquals(0, x.s.leaves, "no onLeave: the visit continues")
        assertEquals(SettingsPresentation.FULL_SCREEN, x.s.ui.value.presentation)
        assertEquals(2, x.s.announces, "N10: on open and on expand")
        assertFalse(x.s.acts.any { it.action == BoxAction.OPEN_SETTINGS }, "the expand key never reaches the machine")
    }

    @Test
    fun menuExpandsOnTheRootListOnlyNotOnAPage() {
        val x = full().open()
        x.p.push(playback)
        x.w.press(BoxAction.CONTEXT_MENU)
        assertTrue(x.ui.settingsPanel, "S11: a long OK / Menu on a page is the machine's")
        assertEquals(BoxAction.CONTEXT_MENU, x.s.acts.last().action)
        x.w.press(BoxAction.BACK)
        assertEquals(SettingsRoute.Root, x.s.ui.value.route)
        x.w.press(BoxAction.CONTEXT_MENU)
        assertEquals(ScreenMode.SETTINGS, x.ui.mode, "☰ on the root list expands")
    }

    @Test
    fun anErrorCardRouteOpensThePanelDetachedAndBackClosesIt() {
        val x = full(lockSettings)
        x.w.send(GuideEvent.OpenSettingsDetached(SettingsRoute.Playlist(1)))
        assertNull(x.ui.pin, "detached routes stay PIN-free")
        assertTrue(x.ui.settingsPanel)
        assertEquals(Triple<SettingsRoute, Boolean, Boolean>(SettingsRoute.Playlist(1), true, true), x.s.opened.single())
        x.w.press(BoxAction.BACK) // declined at the detached bottom
        assertFalse(x.ui.settingsPanel)
        assertEquals(ScreenMode.FULL, x.ui.mode)
        assertEquals(1, x.s.leaves)
        assertEquals(false, x.full.panelCalls.last(), "the player returns to its card (A2)")
    }

    @Test
    fun anExpandingRouteFromFullStillOpensFullScreen() {
        val x = full()
        x.w.send(GuideEvent.OpenSettingsDetached(SettingsRoute.AddSource()))
        assertEquals(ScreenMode.SETTINGS, x.ui.mode)
        assertFalse(x.ui.settingsPanel)
    }

    // ---- keys and the BACK ladder (§2.2, §2.4)

    @Test
    fun backLadderClosesThePanelOnceAtTheRootList() {
        val x = full().open()
        x.p.push(playback)
        x.p.push(SettingsRoute.Diagnostics)
        x.p.push(SettingsRoute.ButtonMapping)
        repeat(3) { x.w.press(BoxAction.BACK); assertTrue(x.ui.settingsPanel, "pops a page") }
        assertEquals(SettingsRoute.Root, x.s.ui.value.route)
        x.w.press(BoxAction.BACK)
        assertFalse(x.ui.settingsPanel, "the root list's BACK closes the panel")
        assertEquals(ScreenMode.FULL, x.ui.mode)
        assertEquals(1, x.s.leaves, "S9: onLeave once")
        assertEquals(SettingsPresentation.FULL_SCREEN, x.s.ui.value.presentation)
        assertEquals(false, x.full.panelCalls.last())
        assertEquals(KeyContext.PLAYER, x.w.machine.keyContext)
        // The next BACK is the player's again.
        val before = x.full.acts.size
        x.w.press(BoxAction.BACK)
        assertEquals(before + 1, x.full.acts.size)
    }

    @Test
    fun aLongBackArrivesAsPlayAndIsTheSettingsMachines() {
        val x = full().open()
        x.p.push(playback)
        x.w.press(BoxAction.PLAY)
        assertTrue(x.ui.settingsPanel)
        assertEquals(ScreenMode.FULL, x.ui.mode)
        assertEquals(BoxAction.PLAY, x.s.acts.last().action)
        assertTrue(x.full.acts.isEmpty(), "nothing reached the player")
    }

    @Test
    fun playPauseIsTheOneKeyPassedToThePlayer() {
        val x = full().open()
        for (a in listOf(BoxAction.UP, BoxAction.DOWN, BoxAction.LEFT, BoxAction.RIGHT, BoxAction.SELECT, BoxAction.ZAP_UP, BoxAction.DIGIT)) x.w.press(a, digit = 3)
        assertTrue(x.full.acts.isEmpty(), "the panel owns the D-pad, CH± and digits")
        assertNull(x.ui.overlay, "no number entry")
        x.w.press(BoxAction.PLAY_PAUSE)
        assertEquals(BoxAction.PLAY_PAUSE, x.full.acts.single().action)
        assertFalse(x.s.acts.any { it.action == BoxAction.PLAY_PAUSE })
        assertTrue(x.w.machine.handles(BoxAction.SEEK_FORWARD), "every other key stays in the app while open")
    }

    @Test
    fun theGuideKeyClosesThePanelAndEntersTheGuide() {
        val x = full().open()
        x.p.push(playback)
        x.w.press(BoxAction.OPEN_GUIDE)
        x.w.answerHolding()
        assertEquals(ScreenMode.GUIDE, x.ui.mode)
        assertFalse(x.ui.settingsPanel)
        assertEquals(1, x.s.leaves)
    }

    @Test
    fun exitClosesThePanelAndRunsTheExitStep() {
        val x = full().open()
        x.w.press(BoxAction.EXIT)
        assertFalse(x.ui.settingsPanel)
        assertEquals(ScreenMode.FULL, x.ui.mode)
        assertIs<Overlay.Exit>(x.ui.overlay)
        assertEquals(1, x.s.leaves)
    }

    @Test
    fun openingThePanelClosesTheGuidesOverlays() {
        val x = full()
        x.full.actFn = { false }
        x.w.press(BoxAction.EXIT)
        assertIs<Overlay.Exit>(x.ui.overlay)
        x.open()
        assertNull(x.ui.overlay)
        assertTrue(x.ui.settingsPanel)
    }

    @Test
    fun textInputActiveCoversThePanel() {
        val x = full()
        x.s.textInputActive = true
        assertFalse(x.w.machine.textInputActive, "FULL without the panel: never")
        x.open()
        assertTrue(x.w.machine.textInputActive)
        x.s.textInputActive = false
        assertFalse(x.w.machine.textInputActive)
    }

    // ---- expand (§2.6, S10)

    @Test
    fun aKeyThatPushesAnExpandingRouteExpandsAfterActReturns() {
        val x = full().open()
        x.p.push(SettingsRoute.Section(SectionId.PLAYLISTS))
        var actsWhenPushed = -1
        x.p.onSelect = { actsWhenPushed = x.s.presented.size; x.p.push(SettingsRoute.AddSource()) }
        x.w.press(BoxAction.SELECT)
        assertEquals(1, actsWhenPushed, "inside act nothing had expanded yet (only present(PANEL))")
        assertEquals(ScreenMode.SETTINGS, x.ui.mode)
        assertFalse(x.ui.settingsPanel)
        assertEquals(SettingsRoute.AddSource(), x.s.ui.value.route)
        assertEquals(0, x.s.leaves)
        // After an expand, BACK down to Root and out returns to FULL (settingsFromFull), not to the panel.
        x.p.s.consume = { a -> a.action != BoxAction.BACK }
        x.w.press(BoxAction.BACK)
        assertEquals(ScreenMode.FULL, x.ui.mode)
        assertFalse(x.ui.settingsPanel)
        assertEquals(1, x.s.leaves)
    }

    @Test
    fun expandSettingsExpandsOnlyWhenOpenAndOnAnExpandingRoute() {
        val x = full()
        x.w.send(GuideEvent.ExpandSettings)
        assertEquals(ScreenMode.FULL, x.ui.mode, "closed: nothing")
        x.open()
        x.w.send(GuideEvent.ExpandSettings)
        assertTrue(x.ui.settingsPanel, "Root does not expand: nothing")
        x.s.showRoute(SettingsRoute.PhoneSetup()) // a page source's push, outside a key
        x.w.send(GuideEvent.ExpandSettings)
        assertEquals(ScreenMode.SETTINGS, x.ui.mode)
        assertEquals(SettingsRoute.PhoneSetup(), x.s.ui.value.route)
        assertEquals(0, x.s.leaves)
        x.w.send(GuideEvent.ExpandSettings)
        assertEquals(ScreenMode.SETTINGS, x.ui.mode, "idempotent")
    }

    // ---- reopen (Q5)

    @Test
    fun reopensAtTheLastPageWithinTenMinutesElseAtRoot() {
        val x = full().open()
        x.p.push(playback)
        x.p.push(SettingsRoute.Diagnostics)
        x.w.press(BoxAction.OPEN_GUIDE) // closes at a page
        x.w.answerHolding()
        assertEquals(ScreenMode.GUIDE, x.ui.mode)
        x.w.send(GuideEvent.StartFull)
        assertEquals(ScreenMode.FULL, x.ui.mode)
        x.w.advance(SettingsPanelRules.PANEL_REOPEN_MS - 60_000)
        x.open()
        assertEquals(SettingsRoute.Diagnostics, x.s.opened.last().first, "within 10 minutes: the last page")
        x.w.press(BoxAction.OPEN_GUIDE)
        x.w.answerHolding()
        x.w.send(GuideEvent.StartFull)
        x.w.advance(SettingsPanelRules.PANEL_REOPEN_MS + 1_000)
        x.open()
        assertEquals(SettingsRoute.Root, x.s.opened.last().first, "after 10 minutes: Root")
    }

    @Test
    fun detachedVisitsAreNeverRemembered() {
        val x = full()
        x.w.send(GuideEvent.OpenSettingsDetached(SettingsRoute.NetworkTest(1)))
        x.w.press(BoxAction.BACK)
        assertFalse(x.ui.settingsPanel)
        x.open()
        assertEquals(SettingsRoute.Root, x.s.opened.last().first)
    }

    // ---- one source of truth (S2)

    @Test
    fun everyPathOutOfFullWithThePanelOpenCallsOnLeaveOnce() {
        // A second open (a setup route from FULL opens the panel again).
        full().open().let { x ->
            x.w.send(GuideEvent.OpenSetup(SettingsRoute.Section(SectionId.ABOUT))) // a setup route from FULL: the panel again
            assertTrue(x.ui.settingsPanel)
            assertEquals(1, x.s.leaves, "a second open ends the first visit")
        }
        // The Guide key with nothing to look up (commitEntry(FULL) at once).
        full().open().let { x ->
            x.w.idle()
            x.w.press(BoxAction.OPEN_GUIDE)
            x.w.answerHolding()
            assertEquals(ScreenMode.GUIDE, x.ui.mode)
            assertFalse(x.ui.settingsPanel)
            assertEquals(1, x.s.leaves)
        }
        // cancelPin: a refused guide tune in FULL with nothing playing returns to the guide.
        full().open().let { x ->
            x.w.send(GuideEvent.Parental(ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy())))
            x.w.idle()
            x.w.machine.request(PinPurpose.Tune(x.w.channels[1], PinOwner.GUIDE))
            assertTrue(x.ui.settingsPanel, "the PIN pad over the panel")
            x.w.press(BoxAction.BACK) // the pad's BACK with nothing typed: cancel
            x.w.answerHolding()
            assertEquals(ScreenMode.GUIDE, x.ui.mode)
            assertFalse(x.ui.settingsPanel)
            assertEquals(1, x.s.leaves)
        }
        // An expanding route from FULL (full-screen SETTINGS) while the panel is open: the PIN grant's step.
        full(lockSettings).let { x ->
            x.w.send(GuideEvent.OpenSettingsDetached(SettingsRoute.Playlist(1)))
            assertTrue(x.ui.settingsPanel)
            x.w.send(GuideEvent.OpenSettingsDetached(SettingsRoute.AddSource()))
            assertEquals(ScreenMode.SETTINGS, x.ui.mode)
            assertEquals(1, x.s.leaves, "the panel visit ended before the full-screen one")
        }
        // The settings machine detached while open.
        full().open().let { x ->
            x.w.machine.settings = null
            assertFalse(x.ui.settingsPanel)
            assertEquals(1, x.s.leaves)
            assertEquals(false, x.full.panelCalls.last())
        }
    }

    @Test
    fun thePlayerHearsTheFlagAfterEveryDispatch() {
        val x = full()
        val n = x.full.panelCalls.size
        x.open()
        x.w.press(BoxAction.DOWN)
        x.w.advance(1_000)
        assertTrue(x.full.panelCalls.size >= n + 3)
        assertTrue(x.full.panelCalls.drop(n).all { it })
        x.w.press(BoxAction.BACK)
        assertEquals(false, x.full.panelCalls.last())
    }
}
