package app.thebox.core.guide.state

import app.thebox.core.full.ListColumn
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.state.PlayerWorld
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.SourceSection
import app.thebox.core.input.BoxAction.*
import app.thebox.core.parental.ParentalActions
import app.thebox.core.parental.ParentalAnswer
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinCheck
import app.thebox.core.parental.PinMessage
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPadMode
import app.thebox.core.parental.PinPurpose
import app.thebox.core.parental.isNotProtectedCard
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.RecordingSettingsMachine
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m5-setup-settings.md §3.9.2 / §0.9: every trigger opens the one prompt with the right purpose, success
 * runs it, cancel leaves the owner where it was; the B1/B3 review rows (S16); the PIN layer's keys (§3.9.3).
 */
class PinRoutingTest {
    private val pinLocked = ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy())
    private val pinOpen = ParentalState(pinSet = true, unlocked = true, policy = ParentalPolicy())
    private val noPin = ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy())
    private val noPinNoAuto = ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy(autoLockAdult = false))

    private class Actions : ParentalActions {
        val log = ArrayList<String>()
        val stored = ArrayList<String>()
        override fun storePin(digits: CharArray) { stored += String(digits); digits.fill('\u0000'); log += "storePin" }
        override fun queryLockout() { log += "queryLockout" }
        override fun openAnyway(source: GuideSource) { log += "openAnyway:$source" }
        override fun openAnyway(identity: ChannelIdentity) { log += "openAnywayChannel" }
    }

    private val adultSource = GuideSource.Group(1, "adult")

    private fun world(state: ParentalState): Pair<GuideWorld, Actions> {
        val w = GuideWorld()
        val a = Actions()
        w.machine.parentalActions = a
        w.send(GuideEvent.Parental(state))
        val locked = GuideSourceEntry(adultSource, "Adult", 4, SourceSection.GROUP, null, locked = state.access != app.thebox.core.parental.ParentalAccess.OPEN)
        w.send(GuideEvent.Sources(w.entries + locked))
        return w to a
    }

    private fun GuideWorld.typePin(pin: String) = pin.forEach { press(DIGIT, digit = it - '0') }
    private fun GuideWorld.lastVerifyToken(): Long = calls.last("verifyPin")!!.args[0] as Long
    private fun GuideWorld.openLockedGroup() {
        press(GROUPS_PANEL)
        val o = ui.overlay as Overlay.Groups
        repeat(o.shortcuts.size + o.entries.indexOfFirst { it.source == adultSource } - o.focused) { press(DOWN) }
        press(SELECT)
    }

    @Nested inner class Doors {
        @Test fun lockedGroupInTheGroupsPanelAsksThenOpens() {
            val (w, a) = world(pinLocked)
            w.openLockedGroup()
            val pin = assertNotNull(w.ui.pin)
            assertEquals(PinPadMode.VERIFY, pin.mode)
            assertEquals(adultSource, (assertIs<PinPurpose.OpenSource>(pin.purpose)).entry.source)
            assertEquals(PinOwner.GUIDE, pin.purpose!!.owner)
            assertTrue("queryLockout" in a.log, "a prompt opened during a lockout shows the countdown at once")
            assertIs<Overlay.Groups>(w.ui.overlay, "the panel stays behind the prompt")
            w.typePin("2580")
            assertTrue(w.ui.pin!!.checking)
            assertEquals(4, w.calls.last("verifyPin")!!.args[1], "four digits handed to the effect")
            w.send(GuideEvent.Parental(pinOpen)) // the effect unlocked the session before answering
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.Ok))
            assertNull(w.ui.pin)
            assertNull(w.ui.overlay)
            assertEquals(adultSource, w.calls.last("loadLineup")!!.args[0], "the entry is selected (grid reloads)")
        }

        @Test fun cancelLeavesTheOwnerWhereItWas() {
            val (w, _) = world(pinLocked)
            w.openLockedGroup()
            w.typePin("12")
            w.press(BACK); w.press(BACK) // delete, delete
            assertNotNull(w.ui.pin)
            w.press(BACK) // cancel
            assertNull(w.ui.pin)
            assertIs<Overlay.Groups>(w.ui.overlay)
            assertFalse(w.calls.calls.any { it.name == "loadLineup" && it.args[0] == adultSource })
        }

        @Test fun unlockedSessionOpensWithoutAPrompt() {
            val (w, _) = world(pinOpen)
            w.openLockedGroup()
            assertNull(w.ui.pin)
            assertEquals(adultSource, w.calls.last("loadLineup")!!.args[0])
        }

        /** B3: FILTERED_NO_PIN → the not-protected card; Open anyway opens it until the app closes. */
        @Test fun filteredNoPinShowsTheNotProtectedCardThenOpenAnyway() {
            val (w, a) = world(noPin)
            w.openLockedGroup()
            val card = assertNotNull(w.ui.pin)
            assertTrue(card.isNotProtectedCard)
            assertEquals(0, card.focusedKey, "Set a PIN focused")
            w.press(DIGIT, digit = 1)
            assertEquals(0, w.ui.pin!!.filled, "the card takes no digits")
            w.press(RIGHT); w.press(SELECT)
            assertTrue("openAnyway:$adultSource" in a.log)
            assertNull(w.ui.pin)
            assertEquals(adultSource, w.calls.last("loadLineup")!!.args[0])
        }

        @Test fun filteredNoPinSetAPinCreatesTwiceThenOpens() {
            val (w, a) = world(noPin)
            w.openLockedGroup()
            w.press(SELECT) // Set a PIN
            assertEquals(PinPadMode.CREATE_FIRST, w.ui.pin!!.mode)
            w.typePin("2580")
            assertEquals(PinPadMode.CREATE_CONFIRM, w.ui.pin!!.mode)
            w.typePin("2580")
            assertEquals(listOf("2580"), a.stored)
            assertTrue(w.ui.pin!!.checking)
            w.machine.parentalAnswer(ParentalAnswer.PinStored(true))
            assertNull(w.machine.ui.value.pin)
            assertEquals(adultSource, w.calls.last("loadLineup")!!.args[0])
        }

        @Test fun storeFailureStaysOnThePadWithTheReason() {
            val (w, _) = world(noPin)
            w.openLockedGroup()
            w.press(SELECT)
            w.typePin("2580"); w.typePin("2580")
            w.machine.parentalAnswer(ParentalAnswer.PinStored(false))
            assertEquals(PinMessage.STORE_FAILED, w.machine.ui.value.pin!!.message)
            assertEquals(PinPadMode.CREATE_FIRST, w.machine.ui.value.pin!!.mode)
        }
    }

    @Nested inner class Pad {
        @Test fun wrongThenLockoutCountdownThenDigitsAgain() {
            val (w, _) = world(pinLocked)
            w.openLockedGroup()
            w.typePin("1111")
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.Wrong(2)))
            assertEquals(PinMessage.wrong(2), w.ui.pin!!.message)
            assertTrue(w.ui.pin!!.wrong)
            assertFalse(w.ui.pin!!.checking)
            w.typePin("2222")
            val until = w.nowMs + 30_000
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.LockedOut(until)))
            assertEquals(until, w.ui.pin!!.lockedUntilMs)
            assertEquals(PinMessage.LOCKED_OUT, w.ui.pin!!.message)
            assertTrue(w.machine.nextDeadlineMs.value <= w.nowMs + 1_000, "the countdown ticks")
            val calls = w.calls.calls.count { it.name == "verifyPin" }
            w.typePin("3333")
            assertEquals(calls, w.calls.calls.count { it.name == "verifyPin" }, "digits disabled during the lockout")
            w.advance(31_000)
            assertNull(w.ui.pin!!.lockedUntilMs)
            w.typePin("3333")
            assertEquals(calls + 1, w.calls.calls.count { it.name == "verifyPin" })
        }

        @Test fun unavailableFailsClosed() {
            val (w, _) = world(pinLocked)
            w.openLockedGroup()
            w.typePin("2580")
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.Unavailable))
            assertEquals(PinMessage.UNAVAILABLE, w.ui.pin!!.message)
            assertFalse(w.calls.calls.any { it.name == "loadLineup" && it.args[0] == adultSource })
        }

        @Test fun staleAnswerIsDropped() {
            val (w, _) = world(pinLocked)
            w.openLockedGroup()
            w.typePin("2580")
            w.send(GuideEvent.PinChecked(w.lastVerifyToken() + 7, PinCheck.Ok))
            assertNotNull(w.ui.pin)
        }

        @Test fun theLayerIsModalAboveEveryKey() {
            val (w, _) = world(pinLocked)
            w.openLockedGroup()
            val before = w.ui.copy()
            for (a in listOf(ZAP_UP, GROUPS_PANEL, EXIT, OPEN_GUIDE, CONTEXT_MENU, PLAY_PAUSE, OPEN_SETTINGS)) w.press(a)
            assertEquals(before.mode, w.ui.mode)
            assertEquals(before.overlay, w.ui.overlay)
            assertNotNull(w.ui.pin)
            assertFalse(w.calls.calls.any { it.name == "exitApp" })
        }

        @Test fun dPadAndOkTypeOnTheGrid() {
            val (w, _) = world(pinLocked)
            w.openLockedGroup()
            w.press(UP); w.press(LEFT); w.press(SELECT) // key 0 = "1"
            assertEquals(1, w.ui.pin!!.filled)
            assertEquals(0, w.ui.pin!!.focusedKey)
        }
    }

    @Nested inner class Tunes {
        /** B1: a tune the guard refused (the host routes it here) asks, then tunes. */
        @Test fun refusedTuneAsksThenTunes() {
            val (w, _) = world(pinLocked)
            val ch = w.channels[3]
            assertTrue(w.machine.request(PinPurpose.Tune(ch, PinOwner.GUIDE)))
            w.flush()
            w.typePin("2580")
            w.send(GuideEvent.Parental(pinOpen))
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.Ok))
            assertEquals(ch, w.calls.last("tune")!!.args[0])
        }

        /** S16: "Blue after relock → PIN prompt" (the guarded previous channel reports the refusal as a Tune). */
        @Test fun blueAfterRelockAsks() {
            val (w, _) = world(pinOpen)
            w.send(GuideEvent.Parental(pinLocked))
            w.press(PREVIOUS_CHANNEL)
            assertNotNull(w.calls.last("previousChannel"))
            // The VM's guarded controller refused the previous channel and asked:
            assertTrue(w.machine.request(PinPurpose.Tune(w.channels[2], PinOwner.GUIDE)))
            assertIs<PinPurpose.Tune>(w.machine.ui.value.pin!!.purpose)
        }

        /** S16: ON_STOP → ON_START with a locked tuned channel: the prompt, no tune; BACK leaves the guide on All channels. */
        @Test fun resumeRefusedAsksAndBackLeavesTheGuideOnAll() {
            val (w, _) = world(pinOpen)
            w.openLockedGroup() // open now (unlocked)
            w.press(SELECT) // tune the focused row
            w.flush()
            assertEquals(ScreenMode.FULL, w.ui.mode)
            w.send(GuideEvent.Parental(pinLocked)) // app stopped: relocked
            // The re-queried Groups panel lists the group as locked: the guide moves to All channels at now (§3.9.5).
            w.send(GuideEvent.Sources(w.entries + GuideSourceEntry(adultSource, "Adult", 4, SourceSection.GROUP, null, locked = true)))
            assertEquals(GuideSource.All(1), w.calls.last("loadLineup")!!.args[0], "off the locked group")
            w.idle() // nothing plays: the controller refused the resume (Stopped(LOCKED) shows as nothing)
            val tunes = w.calls.calls.count { it.name == "tune" }
            assertTrue(w.machine.request(PinPurpose.Tune(w.channels[0], PinOwner.GUIDE)))
            assertEquals(tunes, w.calls.calls.count { it.name == "tune" }, "no tune before the PIN")
            w.press(BACK)
            assertNull(w.ui.pin)
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "BACK leaves the guide with nothing playing")
        }

        @Test fun noPinNoAutoLockProceedsAtOnce() {
            val (w, _) = world(noPinNoAuto)
            assertFalse(w.machine.request(PinPurpose.Tune(w.channels[1], PinOwner.GUIDE)))
            assertNull(w.ui.pin)
        }

        /** B3: a refused tune in FILTERED_NO_PIN shows the card; Open anyway opens the channel's group. */
        @Test fun filteredTuneCardOpenAnyway() {
            val (w, a) = world(noPin)
            assertTrue(w.machine.request(PinPurpose.Tune(w.channels[1], PinOwner.GUIDE)))
            assertTrue(w.machine.ui.value.pin!!.isNotProtectedCard)
            w.press(RIGHT); w.press(SELECT)
            assertTrue("openAnywayChannel" in a.log)
            assertEquals(w.channels[1], w.calls.last("tune")!!.args[0])
        }
    }

    @Nested inner class SettingsAndMenus {
        private val lockSettings = pinLocked.copy(policy = ParentalPolicy(lockSettings = true))

        @Test fun lockSettingsAsksAtEveryEntry() {
            val (w, _) = world(lockSettings)
            var guard = 0
            while (w.ui.zone != app.thebox.core.guide.Zone.TABS && guard++ < 5) w.press(UP)
            while (w.ui.tabFocused < 2) w.press(RIGHT)
            w.press(SELECT)
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "not yet")
            assertEquals(PinPurpose.EnterSettings(SettingsRoute.Root), w.ui.pin!!.purpose)
            w.typePin("2580")
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.Ok))
            assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        }

        @Test fun settingsKeyAndSetupRoutes() {
            val (w, _) = world(lockSettings)
            w.press(OPEN_SETTINGS)
            assertIs<PinPurpose.EnterSettings>(w.ui.pin!!.purpose)
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            w.send(GuideEvent.OpenSetup(SettingsRoute.FirstRun))
            assertEquals(ScreenMode.SETTINGS, w.ui.mode, "first run never asks")
            assertNull(w.ui.pin)
        }

        @Test fun noLockSettingsNoPrompt() {
            val (w, _) = world(pinLocked)
            w.press(OPEN_SETTINGS)
            assertEquals(ScreenMode.SETTINGS, w.ui.mode)
            assertNull(w.ui.pin)
        }

        private fun GuideWorld.menuItem(item: MenuItem) {
            press(CONTEXT_MENU)
            val o = ui.overlay as Overlay.Menu
            repeat(o.items.indexOf(item)) { press(DOWN) }
            press(SELECT)
        }

        @Test fun lockChannelAsksForThePinThenLocks() {
            val (w, _) = world(pinLocked)
            w.menuItem(MenuItem.LOCK_CHANNEL)
            assertIs<PinPurpose.SettingsAction>(w.ui.pin!!.purpose)
            assertNull(w.calls.last("setChannelLocked"))
            w.typePin("2580")
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.Ok))
            assertEquals(true, w.calls.last("setChannelLocked")!!.args[1])
        }

        @Test fun lockWithoutAPinFiltersAtOnceUnderAutoLock() {
            val (w, _) = world(noPin)
            w.menuItem(MenuItem.LOCK_CHANNEL)
            assertNull(w.ui.pin)
            assertEquals(true, w.calls.last("setChannelLocked")!!.args[1])
        }

        @Test fun lockWithAutoLockOffCreatesAPinFirst() {
            val (w, a) = world(noPinNoAuto)
            w.menuItem(MenuItem.LOCK_CHANNEL)
            assertEquals(PinPadMode.CREATE_FIRST, w.ui.pin!!.mode)
            w.typePin("2580"); w.typePin("2580")
            assertEquals(listOf("2580"), a.stored)
            w.machine.parentalAnswer(ParentalAnswer.PinStored(true))
            assertEquals(true, w.calls.last("setChannelLocked")!!.args[1])
        }

        @Test fun unlockOfferedOnlyWhileOpen() {
            val (w, _) = world(pinOpen)
            w.press(CONTEXT_MENU)
            assertTrue(MenuItem.UNLOCK_CHANNEL in (w.ui.overlay as Overlay.Menu).items)
            w.press(BACK)
            w.send(GuideEvent.Parental(pinLocked))
            w.press(CONTEXT_MENU)
            assertFalse(MenuItem.UNLOCK_CHANNEL in (w.ui.overlay as Overlay.Menu).items)
            assertTrue(MenuItem.LOCK_CHANNEL in (w.ui.overlay as Overlay.Menu).items)
        }

        @Test fun fixGuideDataAndChannelOptionsOpenTheirRoutes() {
            val (w, _) = world(pinLocked)
            w.menuItem(MenuItem.FIX_GUIDE_DATA)
            assertEquals(ScreenMode.SETTINGS, w.ui.mode)
            assertIs<SettingsRoute.FixGuide>(w.calls.last("openSettingsRoute")!!.args[0])
            w.press(BACK)
            w.menuItem(MenuItem.CHANNEL_OPTIONS)
            assertIs<SettingsRoute.Channel>(w.calls.last("openSettingsRoute")!!.args[0])
        }

        @Test fun groupMenuLockAndUnlock() {
            val (w, _) = world(pinOpen)
            w.press(GROUPS_PANEL); w.press(DOWN) // first entry after the shortcuts
            w.press(CONTEXT_MENU)
            val menu = (w.ui.overlay as Overlay.Groups).menu!!
            assertEquals(app.thebox.core.guide.GroupMenuItem.LOCK, menu[1])
            w.press(DOWN); w.press(SELECT)
            assertEquals(true, w.calls.last("setGroupLocked")!!.args[1], "unlocked session: no prompt")
            // A locked entry offers Unlock (and asks).
            w.send(GuideEvent.Parental(pinLocked))
            w.send(GuideEvent.Sources(w.entries + GuideSourceEntry(adultSource, "Adult", 4, SourceSection.GROUP, null, locked = true)))
            w.press(BACK)
            w.openLockedGroup(); w.press(BACK) // cancel the door; the panel stays on the entry
            w.press(CONTEXT_MENU)
            assertEquals(app.thebox.core.guide.GroupMenuItem.UNLOCK, (w.ui.overlay as Overlay.Groups).menu!![1])
            w.press(DOWN); w.press(SELECT)
            assertIs<PinPurpose.SettingsAction>(w.ui.pin!!.purpose)
        }

        @Test fun changePinVerifiesThenCreates() {
            val (w, a) = world(pinOpen)
            w.machine.startPinSetup(change = true)
            assertEquals(PinPadMode.VERIFY, w.machine.ui.value.pin!!.mode)
            w.typePin("2580")
            w.send(GuideEvent.PinChecked(w.lastVerifyToken(), PinCheck.Ok))
            assertEquals(PinPadMode.CREATE_FIRST, w.ui.pin!!.mode)
            w.typePin("1357"); w.typePin("1357")
            assertEquals(listOf("1357"), a.stored)
        }

        /** N11 with a settings machine attached: it declines OPEN_GUIDE and EXIT; the guide leaves Settings. */
        @Test fun guideKeyAndExitLeaveSettings() {
            val (w, _) = world(pinOpen)
            val s = RecordingSettingsMachine()
            w.machine.settings = s
            w.press(OPEN_SETTINGS)
            assertEquals(ScreenMode.SETTINGS, w.ui.mode)
            w.press(OPEN_GUIDE)
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            assertEquals(1, s.leaves, "onLeave once (S9)")
            w.press(OPEN_SETTINGS)
            w.press(EXIT)
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            assertEquals("ExitHint", GuideKeyTableTest.overlayName(w))
        }
    }

    /** §0.9: the player asks through the guide's prompt; the answer comes back by purpose. */
    @Nested inner class PlayerOwned {
        private val adult = GuideSourceEntry(GuideSource.Group(1, "adult"), "Adult", 3, SourceSection.GROUP, null, locked = true)

        private fun world(): PlayerWorld {
            val w = PlayerWorld(withGuide = true)
            w.sendGuide(GuideEvent.Parental(pinLocked))
            w.send(PlayerEvent.Parental(pinLocked))
            w.send(PlayerEvent.Sources(w.entries + adult))
            return w
        }

        @Test fun lockedGroupInTheChannelListAsksThenShowsIt() {
            val w = world()
            w.press(LEFT) // channel list, groups column
            assertEquals(ListColumn.GROUPS, w.ui.channels!!.column)
            repeat(w.entries.size) { w.press(DOWN) }
            w.press(SELECT)
            val pin = assertNotNull(w.gui.pin)
            assertEquals(PinPurpose.OpenSource(adult, PinOwner.PLAYER), pin.purpose)
            "2580".forEach { w.press(DIGIT, digit = it - '0') }
            val token = w.gx.last("verifyPin")!!.args[0] as Long
            w.sendGuide(GuideEvent.PinChecked(token, PinCheck.Ok))
            assertNull(w.gui.pin)
            assertEquals(PlayerLayer.CHANNELS, w.ui.layer)
            assertEquals(adult.source, w.ui.channels!!.source)
        }

        @Test fun lockFromTheChannelListMenuAsks() {
            val w = world()
            w.press(SELECT) // the channel list
            w.press(CONTEXT_MENU)
            val items = w.ui.channels!!.menu!!
            assertTrue(app.thebox.core.full.ChannelMenuItem.LOCK_CHANNEL in items)
            repeat(items.indexOf(app.thebox.core.full.ChannelMenuItem.LOCK_CHANNEL)) { w.press(DOWN) }
            w.press(SELECT)
            assertIs<PinPurpose.SettingsAction>(w.gui.pin!!.purpose)
            assertFalse(w.fx.names().contains("setChannelLocked"))
            "2580".forEach { w.press(DIGIT, digit = it - '0') }
            w.sendGuide(GuideEvent.PinChecked(w.gx.last("verifyPin")!!.args[0] as Long, PinCheck.Ok))
            assertTrue(w.fx.names().contains("setChannelLocked"), "routed back to the player by its token")
        }

        @Test fun refusedListTuneTunesAfterThePin() {
            val w = world()
            val ch = w.channels[4]
            assertTrue(w.guide!!.request(PinPurpose.Tune(ch, PinOwner.PLAYER)))
            "2580".forEach { w.press(DIGIT, digit = it - '0') }
            val mark = w.mark()
            w.sendGuide(GuideEvent.PinChecked(w.gx.last("verifyPin")!!.args[0] as Long, PinCheck.Ok))
            assertTrue("tune" in w.effectsSince(mark))
        }
    }
}
