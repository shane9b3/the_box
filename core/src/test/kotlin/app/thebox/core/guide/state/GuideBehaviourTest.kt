package app.thebox.core.guide.state

import app.thebox.core.guide.CellKind
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.EmptyKind
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.GridMove
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.PanelButton
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.Zone
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.state.GuideKeyTableTest.Companion.nowMin
import app.thebox.core.guide.state.GuideKeyTableTest.Companion.overlayName
import app.thebox.core.input.BoxAction.*
import app.thebox.core.input.Keys
import app.thebox.core.playback.PlaybackErrorCode
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** §5.4 behaviour tests of the state machine (everything the key table cannot express as one press). */
class GuideBehaviourTest {

    // ------------------------------------------------------------------ BackLadderTest (§3.4.6, B2)
    @Nested inner class BackLadder {
        @Test fun fromScrolledGridHomeThenFullThenHomeThenExitNeverFullAgain() {
            val w = GuideWorld()
            w.press(DOWN); w.press(RIGHT); w.press(RIGHT)
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode); assertEquals(0, w.machine.frame.focus!!.row)
            w.press(BACK)
            assertEquals(ScreenMode.FULL, w.ui.mode)
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode); assertEquals(0, w.machine.frame.focus!!.row)
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "never FULL again (B2)")
            assertEquals("ExitHint", overlayName(w))
            w.press(BACK)
            assertTrue(w.exited)
        }

        @Test fun anotherActionAfterReturningClearsTheFlag() {
            val w = GuideWorld()
            w.press(SELECT); w.press(BACK)              // FULL → home, returnedFromFull
            w.press(DOWN)                               // any other action clears it
            w.press(BACK)                               // home
            w.press(BACK)
            assertEquals(ScreenMode.FULL, w.ui.mode)
        }

        @Test fun exitHintExpiresAfterTwoSeconds() {
            val w = GuideWorld(startTuned = false)
            w.advance(200) // let the startup description debounce pass
            w.press(BACK)
            assertEquals("ExitHint", overlayName(w))
            assertEquals(w.nowMs + GuideStateMachineImpl.EXIT_CONFIRM_MS, w.machine.nextDeadlineMs.value)
            w.advance(2_000)
            assertEquals("none", overlayName(w))
            w.press(BACK)
            assertEquals("ExitHint", overlayName(w))
            assertFalse(w.exited)
        }

        @Test fun nonGridZoneGoesToGridFirstAndOverlaysCloseFirst() {
            val w = GuideWorld()
            w.press(UP); w.press(UP)
            assertEquals(Zone.PANEL, w.ui.zone)
            w.press(BACK)
            assertEquals(Zone.GRID, w.ui.zone)
            w.press(GROUPS_PANEL); w.press(BACK)
            assertNull(w.ui.overlay)
        }

        @Test fun askAndNone() {
            val ask = GuideWorld(startTuned = false, settings = GuideSettings(exitConfirmation = ExitConfirmation.ASK))
            ask.press(BACK); assertEquals("ExitDialog", overlayName(ask)); ask.press(SELECT); assertTrue(ask.exited)
            val none = GuideWorld(startTuned = false, settings = GuideSettings(exitConfirmation = ExitConfirmation.NONE))
            none.press(BACK); assertTrue(none.exited)
        }
    }

    // ------------------------------------------------------------------ no dead ends (property)
    @Test fun noDeadEndsBackAlwaysReachesExit() {
        val rnd = java.util.Random(20261014)
        val actions = values().filter { it != EXIT }
        repeat(300) { walk ->
            val exit = ExitConfirmation.entries[walk % 3]
            val w = GuideWorld(startTuned = walk % 2 == 0, settings = GuideSettings(exitConfirmation = exit))
            repeat(40) {
                when (rnd.nextInt(12)) {
                    0 -> w.advance(rnd.nextInt(2_500).toLong())
                    1 -> if (rnd.nextInt(4) == 0) w.alert(id = rnd.nextLong(), startAtMs = w.nowMs + 30_000)
                    else -> {
                        val a = actions[rnd.nextInt(actions.size)]
                        w.press(a, repeat = if (rnd.nextInt(6) == 0) 1 + rnd.nextInt(4) else 0, digit = rnd.nextInt(10), heldMs = rnd.nextInt(1_500).toLong())
                    }
                }
                if (w.exited) return@repeat
            }
            var backs = 0
            while (!w.exited && backs < 10) {
                w.advance(700)
                if (exit == ExitConfirmation.ASK && w.ui.overlay is Overlay.Exit && (w.ui.overlay as Overlay.Exit).dialog) { w.press(SELECT); continue }
                w.press(BACK)
                backs++
            }
            assertTrue(w.exited, "walk $walk ($exit): Back did not reach the exit step; ui=${w.ui}")
        }
    }

    // ------------------------------------------------------------------ ReminderCardTest (S2)
    @Nested inner class ReminderCard {
        @Test fun modalWithGuard() {
            val w = GuideWorld()
            val focus = w.machine.frame.focus
            w.alert(startAtMs = w.nowMs + 60_000)
            val c = w.ui.reminder!!
            assertEquals(w.nowMs + 600, c.inputFromMs)
            assertEquals(120, c.secondsLeft)
            val mark = w.calls.calls.size
            w.press(SELECT); w.press(BACK); w.press(DOWN)
            assertNotNull(w.ui.reminder, "keys ignored during the 600 ms guard")
            assertEquals(focus, w.machine.frame.focus)
            assertEquals(emptyList(), w.effectsSince(mark))
            w.advance(600)
            w.press(DOWN); w.press(GROUPS_PANEL)
            assertEquals(focus, w.machine.frame.focus, "modal: other keys ignored")
            assertNull(w.ui.overlay)
            w.press(RIGHT); w.press(SELECT)
            assertNull(w.ui.reminder)
            assertEquals(listOf(99L, false), w.calls.last("answerReminder")!!.args)
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
        }

        @Test fun switchNowGoesFull() {
            val w = GuideWorld()
            w.alert(); w.advance(600); w.press(SELECT)
            assertEquals(listOf(99L, true), w.calls.last("answerReminder")!!.args)
            assertEquals(ScreenMode.FULL, w.ui.mode)
        }

        @Test fun autoSwitchAtStart() {
            val w = GuideWorld()
            w.advance(200)
            w.alert(startAtMs = w.nowMs + 60_000, autoSwitch = true)
            assertEquals(w.nowMs + 600, w.machine.nextDeadlineMs.value)
            w.advance(59_000)
            assertNotNull(w.ui.reminder)
            w.advance(1_000)
            assertNull(w.ui.reminder)
            assertEquals(ScreenMode.FULL, w.ui.mode)
        }

        @Test fun countdownAndClear() {
            val w = GuideWorld()
            w.alert(startAtMs = w.nowMs - 10_000)
            assertEquals(60, w.ui.reminder!!.secondsLeft)
            w.advance(5_000)
            assertEquals(55, w.ui.reminder!!.secondsLeft)
            w.send(GuideEvent.Alert(null))
            assertNull(w.ui.reminder)
        }

        @Test fun cardInFullTakesKeysFirst() {
            val w = GuideWorld()
            w.press(SELECT)
            w.alert(); w.advance(600)
            w.press(UP)
            assertNull(w.calls.last("zap"))
            w.press(BACK)
            assertEquals(ScreenMode.FULL, w.ui.mode, "Back dismissed the card, not FULL")
        }
    }

    // ------------------------------------------------------------------ StaleWindowTest (S10a)
    @Test fun staleWindowsAreDropped() {
        val w = GuideWorld()
        val fresh = w.machine.frame.data
        val old = GuideFixtures.windowData(listOf(listOf(GuideFixtures.Cell(0, 10))), fromMin = 0, toMin = 10, lineupVersion = 0)
        w.send(GuideEvent.Window(old))
        assertSame(fresh, w.machine.frame.data, "older lineup version dropped")
        w.send(GuideEvent.RowsChanged(5))
        val olderRows = GuideFixtures.windowData(GuideFixtures.syntheticRows(10, GuideWorld.FROM, GuideWorld.TO), fromMin = GuideWorld.FROM, toMin = GuideWorld.TO, rowsVersion = 4)
        w.send(GuideEvent.Window(olderRows))
        assertSame(fresh, w.machine.frame.data, "rows version older than the last RowsChanged dropped")
        val ok = GuideFixtures.windowData(GuideFixtures.syntheticRows(10, GuideWorld.FROM, GuideWorld.TO), fromMin = GuideWorld.FROM, toMin = GuideWorld.TO, rowsVersion = 5)
        val v = w.machine.frameVersion.value
        w.send(GuideEvent.Window(ok))
        assertSame(ok, w.machine.frame.data)
        assertTrue(w.machine.frameVersion.value > v)
    }

    // ------------------------------------------------------------------ HoldTest (§3.4.7, S16)
    @Nested inner class Hold {
        @Test fun verticalHoldShowsBubbleAndFreezesThePanel() {
            val w = GuideWorld()
            w.press(DOWN)
            w.advance(200)
            val panel = w.ui.panel
            w.press(DOWN, repeat = 1, heldMs = 500)
            w.press(DOWN, repeat = 2, heldMs = 550)
            assertEquals(3, w.machine.frame.focus!!.row)
            assertEquals(104, w.machine.frame.bubbleNumber)
            assertSame(panel, w.ui.panel, "PanelState not rebuilt while repeat > 0")
            assertEquals(1, w.calls.last("ensureWindow")!!.args[2], "holdDirection")
            val loads = w.calls.names().count { it == "loadInfo" }
            w.advance(300)
            assertEquals(loads, w.calls.names().count { it == "loadInfo" }, "no description while held")
            w.release(DOWN)
            assertEquals(-1, w.machine.frame.bubbleNumber)
            assertEquals(w.channels[3].displayName, w.ui.panel!!.channelName)
            assertEquals(0, w.calls.last("ensureWindow")!!.args[2])
        }

        @Test fun horizontalHoldStepsTimeAfterOneSecond() {
            val w = GuideWorld()
            w.press(RIGHT, repeat = 3, heldMs = 900)
            assertEquals(GridMove.RIGHT, w.nav.moves.last())
            w.press(RIGHT, repeat = 4, heldMs = 1_000)
            assertEquals(GridMove.STEP_FORWARD, w.nav.moves.last())
            w.press(LEFT, repeat = 9, heldMs = 2_000)
            assertEquals(GridMove.STEP_BACK, w.nav.moves.last())
        }

        @Test fun heldLeftNeverOpensGroups() {
            val w = GuideWorld()
            w.press(DOWN)
            w.press(LEFT, repeat = 2, heldMs = 600)
            assertNull(w.ui.overlay)
            w.press(LEFT)
            assertIs<Overlay.Groups>(w.ui.overlay)
        }
    }

    // ------------------------------------------------------------------ PanelDebounceTest (§3.9.1)
    @Nested inner class PanelDebounce {
        @Test fun descriptionLoadsAfter150MsWithCurrentTokenOnly() {
            val w = GuideWorld()
            w.press(DOWN)
            assertNull(w.ui.panel!!.description)
            val due = w.machine.nextDeadlineMs.value
            assertEquals(w.nowMs + 150, due)
            w.advance(149)
            assertNull(w.calls.last("loadInfo"))
            w.advance(1)
            val call = w.calls.last("loadInfo")!!
            val token = call.args[0] as Long
            w.send(GuideEvent.Info(token - 1, info("stale")))
            assertNull(w.ui.panel!!.description)
            w.send(GuideEvent.Info(token, info("Plot")))
            assertEquals("Plot", w.ui.panel!!.description)
            assertEquals("Drama", w.ui.panel!!.genre)
        }

        @Test fun lowEndWaits600() {
            val w = GuideWorld(settings = GuideSettings(performance = PerformanceMode.LOW))
            w.press(DOWN)
            w.advance(599); assertNull(w.calls.last("loadInfo"))
            w.advance(1); assertNotNull(w.calls.last("loadInfo"))
        }

        @Test fun autoUsesDeviceClass() {
            val w = GuideWorld()
            w.send(GuideEvent.Settings(GuideSettings(), DeviceClass(lowRam = true, totalMemBytes = 1L shl 30, memoryClassMb = 128)))
            assertTrue(w.machine.frame.lowEnd)
        }

        @Test fun noInfoCellsNeedNoLoad() {
            val w = GuideWorld()
            w.press(DOWN); w.press(DOWN); w.press(DOWN)
            val p = w.ui.panel!!
            assertTrue(p.noInfo)
            assertEquals(GuideTexts.NO_INFORMATION, p.title)
            assertEquals(GuideTexts.NO_GUIDE_DATA_CHANNEL, p.description)
            w.advance(500)
            assertNull(w.calls.calls.lastOrNull { it.name == "loadInfo" && (it.args[1] as app.thebox.core.guide.GuideChannel).rowId == w.channels[3].rowId })
        }

        @Test fun buttonsPerCellClass() {
            val w = GuideWorld()
            assertEquals(listOf(PanelButton.WATCHING, PanelButton.FROM_START, PanelButton.MORE), w.ui.panel!!.buttons)
            w.press(LEFT)
            assertEquals(listOf(PanelButton.WATCH, PanelButton.MORE), w.ui.panel!!.buttons)
            w.press(DOWN) // row 1 past, no archive
            assertEquals(listOf(PanelButton.WATCH_NOW, PanelButton.MORE), w.ui.panel!!.buttons)
            assertTrue(w.ui.panel!!.timeLine.endsWith(GuideTexts.NOT_IN_CATCHUP) || w.ui.panel!!.onNow)
            val f = GuideWorld(); f.press(RIGHT)
            assertEquals(listOf(PanelButton.REMIND, PanelButton.WATCH_CHANNEL_NOW, PanelButton.MORE), f.ui.panel!!.buttons)
            f.press(REMIND)
            assertEquals(PanelButton.REMINDER_SET, f.ui.panel!!.buttons[0])
            val e = GuideWorld(); e.tuneTo(e.channels[0], MiniPhase.ERROR, error = PlaybackErrorCode.SERVER_ERROR); e.flush()
            assertEquals(listOf(PanelButton.WATCH_NOW, PanelButton.MORE), e.ui.panel!!.buttons)
        }

        private fun info(d: String) = ProgrammeInfo(0, 0, 1, "t", null, d, "Drama", null, 0, 0)
    }

    // ------------------------------------------------------------------ number entry in the machine (§3.9.4)
    @Nested inner class NumberEntryFlow {
        @Test fun digitsFocusRowAfter1500Ms() {
            val w = GuideWorld()
            w.press(DIGIT, digit = 1); w.advance(100); w.press(DIGIT, digit = 0); w.advance(100); w.press(DIGIT, digit = 3)
            assertEquals("103", (w.ui.overlay as Overlay.Number).digits)
            w.advance(150)
            assertEquals(w.channels[2].displayName, (w.ui.overlay as Overlay.Number).preview?.displayName, "live preview")
            w.advance(1_350)
            assertNull(w.ui.overlay)
            assertEquals(2, w.machine.frame.focus!!.row)
            assertTrue(w.nowMin() in w.machine.frame.focus!!.cellStart until w.machine.frame.focus!!.cellEnd)
            w.press(SELECT)
            assertEquals(w.channels[2], w.calls.last("tune")!!.args[0], "a second OK tunes")
        }

        @Test fun notFoundShowsThenCloses() {
            val w = GuideWorld()
            w.press(DIGIT, digit = 9); w.press(SELECT)
            assertTrue((w.ui.overlay as Overlay.Number).notFound)
            w.advance(1_500)
            assertNull(w.ui.overlay)
        }

        @Test fun fourDigitsMax() {
            val w = GuideWorld()
            repeat(6) { w.press(DIGIT, digit = 1) }
            assertEquals("1111", (w.ui.overlay as Overlay.Number).digits)
        }

        @Test fun inFullCommitTunes() {
            val w = GuideWorld()
            w.press(SELECT)
            w.press(DIGIT, digit = 1); w.press(DIGIT, digit = 0); w.press(DIGIT, digit = 5)
            w.press(SELECT)
            assertEquals(w.channels[4], w.calls.last("tune")!!.args[0])
            assertEquals(ScreenMode.FULL, w.ui.mode)
        }

        @Test fun dpadEntry() {
            val w = GuideWorld()
            w.press(NUMBER_ENTRY)
            w.press(UP); w.press(RIGHT); w.press(RIGHT); repeat(4) { w.press(UP) }
            assertEquals("104", (w.ui.overlay as Overlay.Number).digits)
            w.press(SELECT)
            assertEquals(3, w.machine.frame.focus!!.row)
        }

        @Test fun channelOutsideTheSourceSwitchesToAll() {
            val w = GuideWorld()
            w.press(CONTEXT_MENU); w.press(DOWN); w.press(SELECT) // add channel 0 to favourites
            w.press(FAVOURITES)
            assertIs<GuideSource.Favourites>(w.calls.last("loadLineup")!!.args[0])
            w.press(DIGIT, digit = 1); w.press(DIGIT, digit = 0); w.press(DIGIT, digit = 6)
            w.press(SELECT)
            assertEquals(GuideSource.All(1), w.calls.last("loadLineup")!!.args[0])
            assertEquals(5, w.machine.frame.focus!!.row, "pending focus on its row id")
        }
    }

    // ------------------------------------------------------------------ GroupsAcceleratorTest (§3.8.3)
    @Nested inner class GroupsAccelerators {
        @Test fun leftAtTheEdgeOpensGroupsOnTheCurrentSource() {
            val w = GuideWorld()
            w.press(DOWN); w.press(LEFT)
            val o = w.ui.overlay as Overlay.Groups
            assertEquals(1, o.current)
            assertEquals(o.shortcuts.size + 1, o.focused)
        }

        @Test fun chPlusAppliesNextEntryAndKeepsThePanel() {
            val w = GuideWorld()
            w.press(GROUPS_PANEL)
            w.press(ZAP_DOWN)
            assertEquals(w.groupSource, w.calls.last("loadLineup")!!.args[0])
            assertEquals(w.groupSource, w.calls.last("persistSource")!!.args[0])
            val o = w.ui.overlay as Overlay.Groups
            assertEquals(2, o.current)
            w.press(ZAP_DOWN); w.press(ZAP_DOWN)
            assertEquals(3, (w.ui.overlay as Overlay.Groups).current, "clamped at the last entry")
            assertEquals("Sport", w.ui.sourceLabel)
        }

        @Test fun longLeftRightInFullCyclesWithCard() {
            val w = GuideWorld()
            w.press(SELECT)
            w.press(GROUP_NEXT)
            assertEquals("News", w.ui.groupCard)
            assertEquals(ScreenMode.FULL, w.ui.mode)
            w.press(GROUP_PREV); w.press(GROUP_PREV)
            assertEquals("Favourites", w.ui.groupCard)
            w.press(GROUP_PREV)
            assertEquals("Sport", w.ui.groupCard, "wraps")
            w.advance(2_000)
            assertNull(w.ui.groupCard)
        }

        @Test fun greenTogglesFavouritesAndBack() {
            val w = GuideWorld()
            w.press(FAVOURITES)
            assertEquals(w.favSource, w.calls.last("loadLineup")!!.args[0])
            w.press(FAVOURITES)
            assertEquals(w.allSource, w.calls.last("loadLineup")!!.args[0])
            val none = GuideWorld(favouritesList = false)
            none.press(FAVOURITES)
            assertEquals(listOf(GuideToast.NO_FAVOURITES, null), none.calls.last("toast")!!.args)
        }

        @Test fun groupMenuActs() {
            val w = GuideWorld()
            w.press(GROUPS_PANEL); w.press(DOWN); w.press(CONTEXT_MENU); w.press(DOWN); w.press(SELECT)
            val c = w.calls.last("groupAction")!!
            assertEquals(w.groupSource, (c.args[0] as app.thebox.core.guide.GuideSourceEntry).source)
            assertEquals(app.thebox.core.guide.GroupAction.MOVE_UP, c.args[1])
        }
    }

    // ------------------------------------------------------------------ StartupFocusTest (§3.8.4) and data events
    @Nested inner class StartupAndData {
        @Test fun tunedRowAtNow() {
            val w = GuideWorld(tunedRow = 4)
            val f = w.machine.frame.focus!!
            assertEquals(4, f.row)
            assertTrue(w.nowMin() in f.cellStart until f.cellEnd)
            assertEquals(GuideSource.All(1), w.calls.calls.first { it.name == "loadLineup" }.args[0])
            assertNull(w.calls.last("persistSource"), "startup does not persist")
        }

        @Test fun untunedRowZero() {
            assertEquals(0, GuideWorld(startTuned = false).machine.frame.focus!!.row)
        }

        @Test fun openGuideOnLastAndFavourites() {
            val last = GuideWorld(lastSourceHint = GuideSource.Group(1, "sport"))
            assertEquals(GuideSource.Group(1, "sport"), last.calls.calls.first { it.name == "loadLineup" }.args[0])
            val gone = GuideWorld(lastSourceHint = GuideSource.Group(1, "gone"))
            assertEquals(GuideSource.All(1), gone.calls.calls.first { it.name == "loadLineup" }.args[0])
            val fav = GuideWorld(settings = GuideSettings(openGuideOn = OpenGuideOn.FAVOURITES))
            assertEquals(fav.favSource, fav.calls.calls.first { it.name == "loadLineup" }.args[0])
        }

        @Test fun emptyKinds() {
            val w = GuideWorld(sendSources = false)
            w.send(GuideEvent.Sources(emptyList()))
            assertEquals(EmptyKind.NO_PLAYLIST, w.ui.empty)
            val e = GuideWorld()
            e.send(GuideEvent.Lineup(GuideLineup(e.groupSource, 2, LongArray(0), 0)))
            assertEquals(EmptyKind.EMPTY_SOURCE, e.ui.empty)
            assertNull(e.machine.frame.focus)
            e.press(UP); e.press(BACK); e.press(BACK)
            val g = GuideWorld()
            g.send(GuideEvent.Generation(null))
            assertEquals(EmptyKind.NO_GUIDE_DATA, g.ui.empty)
        }

        @Test fun lineupReemissionKeepsTheFocusedRowId() {
            val w = GuideWorld()
            w.press(DOWN); w.press(DOWN) // row 2
            val ids = w.channels.map { it.rowId }.reversed().toLongArray()
            w.rows.rows = w.channels.reversed()
            w.send(GuideEvent.Lineup(GuideLineup(w.allSource, 2, ids, 7)))
            assertEquals(7, w.machine.frame.focus!!.row)
        }

        @Test fun searchClosedFocusesAndGoesFull() {
            val w = GuideWorld()
            w.press(UP); w.press(UP); w.press(UP); w.press(RIGHT); w.press(SELECT)
            assertEquals(ScreenMode.SEARCH, w.ui.mode)
            w.send(GuideEvent.SearchClosed(w.channels[6], tuned = true))
            assertEquals(ScreenMode.FULL, w.ui.mode)
            assertEquals(6, w.machine.frame.focus!!.row)
            assertEquals(Zone.GRID, w.ui.zone)
        }

        @Test fun missedRemindersToast() {
            val w = GuideWorld()
            w.send(GuideEvent.MissedReminders(3))
            assertEquals(listOf(GuideToast.REMINDERS_MISSED, "3"), w.calls.last("toast")!!.args)
        }

        @Test fun catchupNotInArchiveMarksSuspect() {
            val w = GuideWorld()
            w.tuneTo(w.channels[0], MiniPhase.ERROR, archive = true, error = PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE); w.flush()
            assertTrue(w.channels[0].identityHash in w.machine.frame.suspectArchive.toList())
            assertEquals(0, w.ui.panel!!.catchupDays)
            assertFalse(PanelButton.FROM_START in w.ui.panel!!.buttons)
        }

        @Test fun archiveGoesFullOnlyWhenTheArchiveTuneArrives() {
            val w = GuideWorld()
            w.archiveSucceeds = false // the launcher failed (toast by the host): no archive Tuned arrives
            w.press(LEFT); w.press(SELECT)
            assertEquals(1, w.calls.names().count { it == "playArchive" })
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            w.tuneTo(w.channels[0], MiniPhase.CONNECTING, archive = true); w.flush()
            assertEquals(ScreenMode.FULL, w.ui.mode)
            val unrelated = GuideWorld(); unrelated.archiveSucceeds = false
            unrelated.press(LEFT); unrelated.press(SELECT)
            unrelated.tuneTo(unrelated.channels[2]); unrelated.flush()
            assertEquals(ScreenMode.GUIDE, unrelated.ui.mode, "a live tune of another channel does not go FULL")
            assertNotNull(TunedState())
        }

        @Test fun nowChipAppearsAndDisappears() {
            val w = GuideWorld()
            assertEquals(0, w.ui.nowDirection)
            w.press(SEEK_FORWARD)
            assertEquals(-1, w.ui.nowDirection)
            assertEquals(3, w.ui.headerChips.size)
            w.press(UP); w.press(RIGHT); w.press(SELECT)
            assertEquals(0, w.ui.nowDirection)
            assertEquals(Zone.GRID, w.ui.zone)
        }

        @Test fun tickAdvancesNowAndReloadsTunedProgrammeAtItsEnd() {
            val w = GuideWorld()
            w.send(GuideEvent.TunedNow(ProgrammeInfo(0, w.nowMin() - 10, w.nowMin() + 1, "x", null, null, null, null, 0, 0)))
            val loads = w.calls.names().count { it == "loadTunedNow" }
            w.advance(60_000)
            assertEquals(w.nowMin(), w.machine.frame.nowMin)
            assertEquals(loads + 1, w.calls.names().count { it == "loadTunedNow" })
        }

        @Test fun footerFromKeysSeen() {
            val w = GuideWorld()
            assertEquals(3, w.ui.footer.size)
            w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_RED, Keys.CHANNEL_UP, Keys.DIGIT_0 + 5)))
            assertEquals(listOf("Browse", "Watch", "Options", "From start", "Page", "Channel"), w.ui.footer.map { it.label })
            w.press(RIGHT)
            assertEquals("Remind", w.ui.footer[3].label)
        }

        @Test fun dayJumpKeepsRowAndUsesToTime() {
            val w = GuideWorld()
            w.press(DOWN)
            w.press(DAY_STRIP); w.press(RIGHT); w.press(RIGHT); w.press(SELECT) // Tomorrow
            assertEquals(GridMove.TO_TIME, w.nav.moves.last())
            assertEquals(1, w.machine.frame.focus!!.row)
            assertEquals("Tomorrow", w.ui.dayLabel)
        }

        @Test fun hiddenOrNonEpgRowsAreLoadingSafe() {
            val w = GuideWorld()
            w.rows.rows = listOf(null) + w.channels.drop(1)
            w.press(DOWN); w.press(UP)
            assertNull(w.ui.panel, "no channel loaded: no panel")
            w.press(SELECT)
            assertNull(w.calls.last("tune"))
            assertEquals(CellKind.PROGRAMME, GuideWorld.cellKindAt(w.data, 0, w.nowMin()))
        }
    }
}
