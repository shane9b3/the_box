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
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** §5.4 behaviour tests of the state machine (everything the key table cannot express as one press). */
class GuideBehaviourTest {

    // ------------------------------------------------------------------ BackLadderTest (§3.4.6, B2)
    @Nested inner class BackLadder {
        /**
         * Decision 2026-10-19 ruling 2: Back walks outward (scrolled → home → Groups → top bar on Search → exit); hold
         * Back is the way to FULL.
         */
        @Test fun fromScrolledGridHomeThenGroupsThenSearchThenExit() {
            val w = GuideWorld()
            w.press(DOWN); w.press(RIGHT); w.press(RIGHT)
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode); assertEquals(0, w.machine.frame.focus!!.row); assertEquals(Zone.GRID, w.ui.zone)
            assertNull(w.ui.overlay)
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "never FULL from a single Back")
            val o = assertIs<Overlay.Groups>(w.ui.overlay)
            assertTrue(o.viaBack); assertEquals(o.shortcuts.size + 1, o.focused, "on the current source (All channels)")
            w.press(BACK)
            assertNull(w.ui.overlay); assertEquals(Zone.TABS, w.ui.zone); assertEquals(GuideStateMachineImpl.TAB_SEARCH, w.ui.tabFocused)
            w.press(BACK)
            assertEquals("ExitHint", overlayName(w))
            w.press(BACK)
            assertTrue(w.exited)
        }

        @Test fun backFromHomeAfterFullOpensGroupsNotFull() {
            val w = GuideWorld()
            w.press(SELECT); w.press(BACK)              // FULL → home
            w.press(DOWN); w.press(BACK)                // scrolled → home
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode); assertEquals(Zone.GRID, w.ui.zone)
            assertTrue(assertIs<Overlay.Groups>(w.ui.overlay).viaBack)
        }

        /** review-ux S1/B1: "press Back until Groups appears" works from any row and any time, then Search is next. */
        @Test fun backLadderReachesGroupsThenSearchFromAnyRow() {
            for (row in 0 until 10) for (scroll in listOf(null, SEEK_FORWARD, SEEK_BACK, RIGHT)) {
                val w = GuideWorld()
                repeat(row) { w.press(DOWN) }
                scroll?.let { w.press(it) }
                var backs = 0
                while (w.ui.overlay !is Overlay.Groups && backs < 5) { w.press(BACK); backs++ }
                val o = assertIs<Overlay.Groups>(w.ui.overlay, "row $row $scroll")
                assertTrue(o.viaBack, "row $row $scroll: opened by the ladder")
                assertTrue(backs <= 2, "row $row $scroll: Groups after $backs Backs")
                assertEquals(if (row == 0 && scroll == null) 1 else 2, backs, "row $row $scroll")
                w.press(BACK)
                assertEquals(Zone.TABS, w.ui.zone, "row $row $scroll"); assertEquals(GuideStateMachineImpl.TAB_SEARCH, w.ui.tabFocused)
                w.press(SELECT)
                assertEquals(ScreenMode.SEARCH, w.ui.mode, "Back, Back(, Back), OK opens Search")
            }
        }

        /** review-ux S1: Groups opened any other way closes on Back to where it was opened from. */
        @Test fun backAfterLeftOpenedGroupsReturnsToGrid() {
            val w = GuideWorld()
            w.press(DOWN); w.press(LEFT) // row 1 has no archive: Left on now opens Groups
            assertFalse(assertIs<Overlay.Groups>(w.ui.overlay).viaBack)
            val focus = w.machine.frame.focus
            w.press(BACK)
            assertNull(w.ui.overlay); assertEquals(Zone.GRID, w.ui.zone); assertEquals(focus, w.machine.frame.focus)
            w.press(LEFT); w.press(RIGHT)
            assertNull(w.ui.overlay); assertEquals(Zone.GRID, w.ui.zone)
            // Yellow and the Guide key (at home) close back into the grid too; the Group chip closes back to the chip.
            w.press(BACK) // row 1 → home
            w.press(GROUPS_PANEL); assertFalse((w.ui.overlay as Overlay.Groups).viaBack); w.press(BACK)
            assertNull(w.ui.overlay); assertEquals(Zone.GRID, w.ui.zone)
            w.press(OPEN_GUIDE); assertFalse((w.ui.overlay as Overlay.Groups).viaBack); w.press(BACK)
            assertNull(w.ui.overlay); assertEquals(Zone.GRID, w.ui.zone)
            w.press(UP); w.press(SELECT); assertIs<Overlay.Groups>(w.ui.overlay); w.press(BACK)
            assertNull(w.ui.overlay); assertEquals(Zone.HEADER, w.ui.zone)
        }

        /** Ruling 2: Right always closes into the grid, Groups opened by Back included. */
        @Test fun rightClosesGroupsOpenedByBackIntoTheGrid() {
            val w = GuideWorld()
            w.press(BACK); w.press(RIGHT)
            assertNull(w.ui.overlay); assertEquals(Zone.GRID, w.ui.zone)
            w.press(BACK); assertTrue((w.ui.overlay as Overlay.Groups).viaBack, "home again: Back reopens Groups")
        }

        /** Ruling 3: the ladder lands on Search; Up from the panel or the header lands on Guide; Settings keeps the gear. */
        @Test fun topBarLandingTab() {
            val w = GuideWorld()
            w.press(BACK); w.press(BACK)
            assertEquals(GuideStateMachineImpl.TAB_SEARCH, w.ui.tabFocused)
            w.press(DOWN); w.press(UP) // TABS → PANEL → TABS
            assertEquals(Zone.TABS, w.ui.zone); assertEquals(GuideStateMachineImpl.TAB_GUIDE, w.ui.tabFocused)
            w.press(RIGHT); w.press(RIGHT); w.press(SELECT); w.press(BACK) // Settings and back
            assertEquals(Zone.TABS, w.ui.zone); assertEquals(2, w.ui.tabFocused)
            // A stale tab from an earlier visit (review S3 grid_home_stale_tab): the ladder still lands on Search.
            w.press(DOWN); w.press(DOWN); w.press(DOWN) // TABS → PANEL → HEADER → GRID
            assertEquals(Zone.GRID, w.ui.zone)
            w.press(BACK); w.press(BACK)
            assertEquals(Zone.TABS, w.ui.zone); assertEquals(GuideStateMachineImpl.TAB_SEARCH, w.ui.tabFocused)
        }

        @Test fun exitHintExpiresAfterTwoSeconds() {
            val w = GuideWorld(startTuned = false)
            w.advance(200) // let the startup description debounce pass
            w.press(BACK) // home → Groups
            w.press(BACK) // Groups → top bar
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
            ask.press(BACK); ask.press(BACK); ask.press(BACK); assertEquals("ExitDialog", overlayName(ask)); ask.press(SELECT); assertTrue(ask.exited)
            val none = GuideWorld(startTuned = false, settings = GuideSettings(exitConfirmation = ExitConfirmation.NONE))
            none.press(BACK); none.press(BACK); assertFalse(none.exited); none.press(BACK); assertTrue(none.exited)
        }
    }

    // ------------------------------------------------------------------ Left edge cue (decision 2026-10-19, rulings 1 and 7)
    @Nested inner class LeftEdgeCue {
        private fun cue(w: GuideWorld) = w.machine.frame.leftEdgeCue
        private fun clip(w: GuideWorld) = w.machine.frame.clipCue

        /** Review S7 item 2: before every single Left in the grid, the drawn cue says whether that Left opens Groups. */
        @Test fun cueEqualsTheLeftResultOverARandomWalk() {
            val rnd = java.util.Random(20261019)
            val moves = listOf(LEFT, LEFT, LEFT, RIGHT, RIGHT, UP, DOWN, DOWN, SEEK_BACK, SEEK_FORWARD, JUMP_TO_NOW)
            var lefts = 0; var opened = 0
            repeat(60) { walk ->
                val w = GuideWorld(tunedRow = walk % 10)
                repeat(80) { step ->
                    if (w.ui.overlay != null || w.ui.zone != Zone.GRID || w.ui.mode != ScreenMode.GUIDE) {
                        if (w.ui.mode == ScreenMode.FULL) w.press(BACK)
                        while (w.ui.overlay != null) w.press(BACK)
                        if (w.ui.zone != Zone.GRID) w.press(BACK)
                    }
                    if (rnd.nextInt(15) == 0) { w.advance(rnd.nextInt(40).toLong() * 60_000L); return@repeat }
                    val a = moves[rnd.nextInt(moves.size)]
                    if (a == LEFT) {
                        val before = cue(w)
                        val clipped = clip(w)
                        w.press(LEFT)
                        assertEquals(before, w.ui.overlay is Overlay.Groups, "walk $walk step $step: cue $before")
                        assertFalse(before && clipped, "walk $walk step $step: both cues")
                        lefts++; if (before) opened++
                    } else w.press(a)
                }
            }
            assertTrue(lefts > 500 && opened > 20, "lefts $lefts, opened $opened")
        }

        /** Review S7 item 3a: an archive failure makes row 0 a non-archive row with no key: the cue turns on. */
        @Test fun archiveFailureTurnsTheCueOnWithoutAKey() {
            val w = GuideWorld()
            assertFalse(cue(w), "row 0 has 7-day catch-up: Left browses")
            w.send(GuideEvent.Archive(app.thebox.core.catchup.archive.ArchiveStatus(
                phase = app.thebox.core.catchup.archive.ArchivePhase.FAILED, identityHash = w.channels[0].identityHash,
                failure = app.thebox.core.catchup.archive.ArchiveFailure.NOT_IN_ARCHIVE,
            )))
            assertTrue(cue(w))
            assertTrue(w.ui.footer.any { it.keys == GuideTexts.KEYS_LEFT && it.label == GuideTexts.HINT_GROUPS })
            w.press(LEFT)
            assertIs<Overlay.Groups>(w.ui.overlay)
        }

        /** Review S7 item 3b: once now passes the focused cell's end, that cell is past and Left browses again. */
        @Test fun tickPastTheCellEndKeepsTheCueEqualToTheProbe() {
            val w = GuideWorld()
            w.press(DOWN) // row 1: no archive, on now
            assertTrue(cue(w))
            val end = w.machine.frame.focus!!.cellEnd
            while (w.nowMin() <= end) w.advance(60_000)
            assertFalse(cue(w), "the focused cell is in the past now")
            assertFalse(w.ui.footer.any { it.keys == GuideTexts.KEYS_LEFT })
            w.press(LEFT)
            assertNull(w.ui.overlay)
        }

        /** Review S7 item 3c: a new window snapshot, with no key, re-runs the probe. */
        @Test fun windowEventRefreshesTheCue() {
            val w = GuideWorld()
            w.press(DOWN); w.press(RIGHT) // row 1, the next programme: Left goes back to now
            assertFalse(cue(w))
            val noEpg = GuideFixtures.windowData(
                GuideFixtures.syntheticRows(10, GuideWorld.FROM, GuideWorld.TO, noEpgRows = setOf(1, 3)),
                fromMin = GuideWorld.FROM, toMin = GuideWorld.TO, seq = 2,
            )
            w.send(GuideEvent.Window(noEpg))
            assertEquals(GuideWorld.FROM, w.machine.frame.focus!!.cellStart, "resolved onto the row's one NO_EPG cell")
            assertTrue(cue(w), "the cell starts at earliest: Left opens Groups")
        }

        /** Review S7 item 4: walking Left on an archive longer than the EPG ends at earliest, where Left opens Groups. */
        @Test fun leftAtTheEarliestCellOpensGroups() {
            val w = GuideWorld()
            var presses = 0
            var lastCue = false
            while (w.ui.overlay !is Overlay.Groups && presses < 2_000) {
                lastCue = cue(w)
                if (!lastCue) assertTrue(w.machine.frame.focus!!.cellStart > w.machine.frame.earliestMin)
                w.press(LEFT); presses++
            }
            assertIs<Overlay.Groups>(w.ui.overlay)
            assertTrue(presses > 20, "walked back $presses cells")
            assertTrue(lastCue, "the cue was on before the last Left")
            assertTrue(w.machine.frame.focus!!.cellStart <= w.machine.frame.earliestMin)
        }

        /** Ruling 7: the clip cue marks a clipped catch-up cell whose Left moves; ruling 1: that Left never opens Groups. */
        @Test fun clippedCatchupCellShowsTheClipCueAndLeftMoves() {
            val w = GuideWorld(startMs = (GuideWorld.NOW_MIN + 100) * 60_000L + 20_000L)
            val f = w.machine.frame.focus!!
            assertTrue(f.cellStart < w.machine.frame.windowStartMin, "clipped on-now cell")
            assertTrue(clip(w)); assertFalse(cue(w))
            assertEquals(GuideTexts.HINT_GROUPS, w.ui.footer.first { it.keys == GuideTexts.KEYS_BACK }.label, "home: Back · Groups")
            w.press(LEFT)
            assertNull(w.ui.overlay); assertTrue(w.machine.frame.focus!!.cellEnd <= f.cellStart, "moved to the previous programme")
            assertFalse(clip(w), "the window followed the focus")
        }

        /** The cues are drawn only in the grid with no overlay; the probe itself keeps feeding the footer. */
        @Test fun cuesOnlyInTheGridWithNoOverlay() {
            val w = GuideWorld()
            w.press(DOWN)
            assertTrue(cue(w))
            w.press(GROUPS_PANEL); assertFalse(cue(w)); w.press(BACK); assertTrue(cue(w))
            w.press(UP); w.press(UP); assertEquals(Zone.HEADER, w.ui.zone); assertFalse(cue(w))
            w.press(DOWN); assertTrue(w.machine.frame.focus!!.row == 0); assertFalse(cue(w), "row 0 is an archive row")
            w.press(DOWN); assertTrue(cue(w))
        }

        /** A held Left never opens Groups, even with the cue on. */
        @Test fun heldLeftWithTheCueOnStillSteps() {
            val w = GuideWorld()
            w.press(DOWN)
            assertTrue(cue(w))
            w.press(LEFT, repeat = 2, heldMs = 600)
            assertNull(w.ui.overlay)
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
            // Decision 2026-10-19 (review S5): the longest ladder is 8 (group menu → Groups → header → grid → home →
            // Groups via Back → top bar → exit hint → exit), 10 with a reminder card and a PIN prompt; 12 leaves room.
            var backs = 0
            while (!w.exited && backs < 12) {
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

    /** Device bug G1 (§2.13 item 20): an older rows version whose rows all have the current basis is not stale. */
    @Test fun olderRowsVersionWithUnchangedBasisIsAccepted() {
        val w = GuideWorld()
        w.send(GuideEvent.RowsChanged(9))
        val basis = LongArray(10) { app.thebox.core.guide.epgBasisOf(w.rows.channel(it)) }
        val same = GuideFixtures.windowData(GuideFixtures.syntheticRows(10, GuideWorld.FROM, GuideWorld.TO), fromMin = GuideWorld.FROM, toMin = GuideWorld.TO, rowsVersion = 8, rowEpgBasis = basis)
        w.send(GuideEvent.Window(same))
        assertSame(same, w.machine.frame.data, "basis unchanged: accepted")
        basis[3] = basis[3] + 1
        val changed = GuideFixtures.windowData(GuideFixtures.syntheticRows(10, GuideWorld.FROM, GuideWorld.TO), fromMin = GuideWorld.FROM, toMin = GuideWorld.TO, rowsVersion = 8, rowEpgBasis = basis)
        w.send(GuideEvent.Window(changed))
        assertSame(same, w.machine.frame.data, "a changed basis is still stale")
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

        /** Review 2026-10-19 S1: like the panel, the footer is not rebuilt per row during a vertical hold, only on release. */
        @Test fun verticalHoldDoesNotRebuildTheFooter() {
            val w = GuideWorld()
            val footer = w.ui.footer
            assertTrue(app.thebox.core.guide.FooterHint(GuideTexts.KEYS_BACK, GuideTexts.HINT_GROUPS) in footer, "home: Back · Groups")
            // Rows 1–6 cross no-archive, no-EPG (3) and archive (5) rows: the Left probe and home both flip mid-hold.
            for (i in 1..6) w.press(DOWN, repeat = i, heldMs = 450L + 50L * i)
            assertEquals(6, w.machine.frame.focus!!.row)
            assertSame(footer, w.ui.footer, "footer not rebuilt while repeat > 0")
            w.release(DOWN)
            assertTrue(app.thebox.core.guide.FooterHint(GuideTexts.KEYS_BACK, GuideTexts.HINT_NOW) in w.ui.footer, "rebuilt on release")
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

        @Test fun artworkFillsThePanelForTheCurrentTokenOnlyAndNeverOverEpgArt() {
            val w = GuideWorld()
            w.press(DOWN)
            w.advance(150)
            val token = w.calls.last("loadInfo")!!.args[0] as Long
            w.send(GuideEvent.Info(token, info("Plot")))
            assertNull(w.ui.panel!!.imageUrl)
            w.send(GuideEvent.Artwork(token - 1, "https://img/stale.jpg"))
            assertNull(w.ui.panel!!.imageUrl)
            w.send(GuideEvent.Artwork(token, null))
            assertNull(w.ui.panel!!.imageUrl)
            w.send(GuideEvent.Artwork(token, "https://img/poster.jpg"))
            assertEquals("https://img/poster.jpg", w.ui.panel!!.imageUrl)
            // (review 2026-10-18) A minute tick and a Tuned rebuild the panel without a new cell: the poster stays.
            w.advance(60_000)
            w.tuneTo(w.channels[1])
            assertEquals("https://img/poster.jpg", w.ui.panel!!.imageUrl)
            assertEquals("Plot", w.ui.panel!!.description)

            // Another cell: the panel starts without art; the EPG's own icon arrives and a later lookup never replaces it.
            w.press(DOWN)
            assertNull(w.ui.panel!!.imageUrl)
            w.advance(150)
            val token2 = w.calls.last("loadInfo")!!.args[0] as Long
            assertTrue(token2 != token)
            w.send(GuideEvent.Info(token2, info("Plot").copy(iconUrl = "https://epg/icon.png")))
            w.send(GuideEvent.Artwork(token2, "https://img/other.jpg"))
            assertEquals("https://epg/icon.png", w.ui.panel!!.imageUrl)
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

        /** Review 2026-10-19 L4: Yellow and the Guide key commit the entry like OK and do not open Groups under the jump. */
        @Test fun yellowAndGuideCommitTheEntryWithoutOpeningGroups() {
            for (key in listOf(GROUPS_PANEL, OPEN_GUIDE)) {
                val w = GuideWorld()
                w.press(DIGIT, digit = 1); w.press(DIGIT, digit = 0); w.press(DIGIT, digit = 3)
                w.press(key)
                assertNull(w.ui.overlay, "$key")
                assertEquals(2, w.machine.frame.focus!!.row, "$key: the entry committed")
            }
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
            // M5: Hide, Lock, Move up, Move down.
            w.press(GROUPS_PANEL); w.press(DOWN); w.press(CONTEXT_MENU); w.press(DOWN); w.press(DOWN); w.press(SELECT)
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
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "FULL waits for the searched channel's own Tuned (§2.13 item 7)")
            assertEquals(6, w.machine.frame.focus!!.row)
            assertEquals(Zone.GRID, w.ui.zone)
            w.tuneTo(w.channels[6], MiniPhase.CONNECTING); w.flush()
            assertEquals(ScreenMode.FULL, w.ui.mode)
        }

        /** Device bug G4 (I-19): leaving Search without a channel returns to the grid, not the TABS zone it came from. */
        @Test fun searchClosedWithoutAChannelReturnsToTheGrid() {
            val w = GuideWorld()
            w.press(UP); w.press(UP); w.press(UP); w.press(RIGHT); w.press(SELECT)
            assertEquals(ScreenMode.SEARCH, w.ui.mode)
            w.send(GuideEvent.SearchClosed(null, tuned = false))
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            assertEquals(Zone.GRID, w.ui.zone)
            w.press(UP); w.press(UP); w.press(UP); w.press(RIGHT); w.press(SELECT)
            w.press(BACK) // the root sink's BACK with an idle field
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            assertEquals(Zone.GRID, w.ui.zone)
        }

        /** §2.13 item 11: a catch-up launched from Search goes FULL on the archive Tuned only; a failure stays in GUIDE. */
        @Test fun searchCatchupGoesFullOnlyWhenTheArchivePlays() {
            val w = GuideWorld()
            w.tuneTo(w.channels[0]); w.flush()
            w.press(UP); w.press(UP); w.press(UP); w.press(RIGHT); w.press(SELECT)
            w.send(GuideEvent.SearchClosed(w.channels[3], tuned = true, archive = true))
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            w.tuneTo(w.channels[0]); w.flush() // the old channel keeps updating: no FULL
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            w.tuneTo(w.channels[3], MiniPhase.ERROR, archive = true, error = PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE); w.flush()
            assertEquals(ScreenMode.GUIDE, w.ui.mode)

            val ok = GuideWorld()
            ok.tuneTo(ok.channels[0]); ok.flush()
            ok.press(UP); ok.press(UP); ok.press(UP); ok.press(RIGHT); ok.press(SELECT)
            ok.send(GuideEvent.SearchClosed(ok.channels[3], tuned = true, archive = true))
            ok.tuneTo(ok.channels[3], MiniPhase.CONNECTING, archive = true); ok.flush()
            assertEquals(ScreenMode.FULL, ok.ui.mode)
        }

        @Test fun missedRemindersToast() {
            val w = GuideWorld()
            w.send(GuideEvent.MissedReminders(3))
            assertEquals(listOf(GuideToast.REMINDERS_MISSED, "3"), w.calls.last("toast")!!.args)
        }

        /** (M4 §0.6, review B1) The hollow ↺ follows ArchiveStatus FAILED(NOT_IN_ARCHIVE), not a raw probe error. */
        @Test fun catchupNotInArchiveMarksSuspect() {
            val w = GuideWorld()
            w.tuneTo(w.channels[0], MiniPhase.ERROR, archive = true, error = PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE); w.flush()
            assertFalse(w.channels[0].identityHash in w.machine.frame.suspectArchive.toList(), "a probe error is not final")
            w.send(GuideEvent.Archive(app.thebox.core.catchup.archive.ArchiveStatus(
                phase = app.thebox.core.catchup.archive.ArchivePhase.FAILED, identityHash = w.channels[0].identityHash,
                failure = app.thebox.core.catchup.archive.ArchiveFailure.NOT_IN_ARCHIVE,
            )))
            assertTrue(w.channels[0].identityHash in w.machine.frame.suspectArchive.toList())
            assertEquals(0, w.ui.panel!!.catchupDays)
            assertFalse(PanelButton.FROM_START in w.ui.panel!!.buttons)
        }

        /** U-S5 / P-L1: the session's 1 s status polls leave GuideUiState.archive identical; a phase change publishes. */
        @Test fun archiveStatusPollsDoNotChurnTheGuideState() {
            val w = GuideWorld()
            val h = w.channels[0].identityHash
            val t0 = w.nowMs
            fun st(i: Int, phase: app.thebox.core.catchup.archive.ArchivePhase = app.thebox.core.catchup.archive.ArchivePhase.PLAYING) =
                app.thebox.core.catchup.archive.ArchiveStatus(
                    phase = phase, channel = w.channels[0], identityHash = h, wallMs = t0 - 600_000 + i * 1_000L, atMs = t0 + i * 1_000L,
                    advancing = true, liveEdgeMs = t0 + i * 1_000L, timeShift = true, settledSeq = i.toLong(),
                )
            w.send(GuideEvent.Archive(st(0)))
            val first = w.ui.archive
            for (i in 1..10) w.send(GuideEvent.Archive(st(i)))
            assertSame(first, w.ui.archive, "ten polls on the extrapolation: the same object")
            w.send(GuideEvent.Archive(st(11).copy(wallMs = t0 - 400_000)))
            assertNotSame(first, w.ui.archive, "a seek (wall left the extrapolation) publishes")
            val sought = w.ui.archive
            w.send(GuideEvent.Archive(st(12, app.thebox.core.catchup.archive.ArchivePhase.PAUSED).copy(wallMs = t0 - 400_000 + 1_000, advancing = false)))
            assertNotSame(sought, w.ui.archive, "a phase change publishes")
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
            // Decision 2026-10-19 ruling 8: home on an archive row, Left browses: "Back · Groups"; owner ruling 2026-10-17:
            // "Hold Back · Full screen" while something plays.
            assertEquals(listOf("Browse", "Watch", "Groups", "Options", "Full screen"), w.ui.footer.map { it.label })
            w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_RED, Keys.CHANNEL_UP, Keys.DIGIT_0 + 5)))
            assertEquals(listOf("Browse", "Watch", "Groups", "Options", "Full screen", "From start", "Page", "Channel"), w.ui.footer.map { it.label })
            w.press(RIGHT)
            // Not home, Left browses: "Back · Now" and, Red having been seen (colour family), "Yellow · Groups".
            assertEquals(listOf("Browse", "Watch", "Now", "Groups", "Options", "Full screen", "Remind", "Page", "Channel"), w.ui.footer.map { it.label })
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

    /** §2.13 item 2: the machine tells the host whether to animate (the host no longer guesses). */
    @Nested inner class AnimateFlag {
        @Test fun singleMovesAnimateRepeatsAndLowEndSnap() {
            val w = GuideWorld()
            w.press(DOWN)
            assertTrue(w.machine.frame.animate, "a single move animates")
            w.press(DOWN, repeat = 1)
            assertTrue(w.machine.frame.animate, "repeat 1 still animates")
            w.press(DOWN, repeat = GuideStateMachineImpl.SNAP_AFTER_REPEATS)
            assertFalse(w.machine.frame.animate, "repeat >= SNAP_AFTER_REPEATS snaps")
            w.release(DOWN)
            val before = w.machine.frame.windowStartMin
            w.press(UP) // row 3 is a multi-day NO_EPG row: its window is days away from row 2's
            val far = kotlin.math.abs(w.machine.frame.windowStartMin - before) > 2 * app.thebox.core.guide.GuideGeometry.WINDOW_MIN
            assertEquals(!far, w.machine.frame.animate, "a target more than two windows away snaps")

            val low = GuideWorld(settings = GuideSettings(performance = PerformanceMode.LOW))
            low.press(DOWN)
            assertFalse(low.machine.frame.animate, "low-end mode always snaps")
        }

        @Test fun lineupPlacementsSnap() {
            val w = GuideWorld()
            w.press(DOWN)
            val ids = w.channels.map { it.rowId }.reversed().toLongArray()
            w.rows.rows = w.channels.reversed()
            w.send(GuideEvent.Lineup(GuideLineup(w.allSource, 2, ids, 7)))
            assertFalse(w.machine.frame.animate, "a lineup change is a jump, not a move")
        }
    }

    // ------------------------------------------------------------------ code review 2026-10-14 (docs/review/m3-code-review.md)
    @Nested inner class CodeReviewFixes {
        private fun GuideWorld.menuSelect(item: app.thebox.core.guide.MenuItem) {
            press(CONTEXT_MENU)
            val m = ui.overlay as Overlay.Menu
            repeat(m.items.indexOf(item)) { press(DOWN) }
            press(SELECT)
        }

        /** H-S1 (§3.9.3): hiding the focused channel keeps the index (the lineup re-emits without it). */
        @Test fun hideKeepsTheIndex() {
            val w = GuideWorld()
            repeat(5) { w.press(DOWN) }
            assertEquals(5, w.machine.frame.focus!!.row)
            w.menuSelect(app.thebox.core.guide.MenuItem.HIDE_CHANNEL)
            assertNotNull(w.calls.last("hideChannel"))
            assertEquals(5, w.machine.frame.focus!!.row, "focus stays on index 5")
            assertEquals(w.channels[6], w.rows.channel(5), "row 5 is now the next channel")
        }

        @Test fun hidingTheLastRowFocusesTheNewLastRow() {
            val w = GuideWorld()
            repeat(9) { w.press(DOWN) }
            w.menuSelect(app.thebox.core.guide.MenuItem.HIDE_CHANNEL)
            assertEquals(8, w.machine.frame.focus!!.row)
        }

        @Test fun unfavouritingInFavouritesKeepsTheIndex() {
            val w = GuideWorld()
            w.channels.take(4).forEach { w.favourites += it.identityHash }
            w.press(FAVOURITES)
            w.press(DOWN); w.press(DOWN)
            assertEquals(2, w.machine.frame.focus!!.row)
            w.menuSelect(app.thebox.core.guide.MenuItem.REMOVE_FAVOURITE)
            assertEquals(2, w.machine.frame.focus!!.row, "same index, not row 0")
            assertEquals(w.channels[3], w.rows.channel(2))
        }

        /** H-S2: the startup Tuned arrives after the first Lineup. */
        @Test fun startupTuneAfterLineupFocusesTheTunedRow() {
            val w = GuideWorld(tuneAfterLineup = true, tunedRow = 4)
            val f = w.machine.frame.focus!!
            assertEquals(4, f.row, "focus after the startup Tuned")
            assertTrue(w.nowMin() in f.cellStart until f.cellEnd, "at now")
            assertEquals(4, GuideWorld(tunedRow = 4).machine.frame.focus!!.row, "the usual order still focuses the tuned row")
        }

        @Test fun startupTuneAfterAUserMoveKeepsTheUserFocus() {
            val w = GuideWorld(startTuned = false)
            w.press(DOWN)
            w.tuneTo(w.channels[4]); w.flush()
            assertEquals(1, w.machine.frame.focus!!.row, "a user action cancels the startup focus")
        }

        @Test fun laterTunesDoNotMoveFocus() {
            val w = GuideWorld(tuneAfterLineup = true, tunedRow = 4)
            w.tuneTo(w.channels[7]); w.flush()
            assertEquals(4, w.machine.frame.focus!!.row, "only the first Tuned is the startup one")
        }

        /** H-B2: the card waits in SETTINGS (not drawn there): BACK leaves Settings and the card then shows, modal. */
        @Test fun invisibleCardDoesNotEatBackInSettings() {
            val w = GuideWorld()
            w.press(GROUPS_PANEL)
            while ((w.ui.overlay as Overlay.Groups).focused > 1) w.press(UP)
            w.press(SELECT)
            assertEquals(ScreenMode.SETTINGS, w.ui.mode)
            w.alert()
            w.advance(700)
            val n = w.calls.calls.size
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "BACK left Settings")
            assertTrue("answerReminder" !in w.effectsSince(n), "the card was not answered")
            val c = assertNotNull(w.ui.reminder, "the card is still pending")
            assertEquals(w.nowMs + GuideStateMachineImpl.REMINDER_CARD_GUARD_MS, c.inputFromMs, "a fresh guard once it is visible")
            w.press(SELECT)
            assertNotNull(w.ui.reminder, "guarded")
            w.advance(600)
            w.press(BACK)
            assertNull(w.ui.reminder)
            assertEquals(listOf(99L, false), w.calls.last("answerReminder")!!.args)
        }

        @Test fun invisibleCardDoesNotEatBackInSearchNorAutoSwitch() {
            val w = GuideWorld()
            w.press(UP); w.press(UP); w.press(UP); w.press(RIGHT); w.press(SELECT)
            assertEquals(ScreenMode.SEARCH, w.ui.mode)
            w.advance(200) // let the panel description timer run out
            w.alert(startAtMs = w.nowMs + 1_000, autoSwitch = true)
            assertEquals(Long.MAX_VALUE, w.machine.nextDeadlineMs.value, "no card timers while it is hidden")
            w.advance(5_000)
            assertEquals(ScreenMode.SEARCH, w.ui.mode, "no auto-switch out of Search")
            assertNull(w.calls.last("answerReminder"))
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "BACK closed Search")
            assertNotNull(w.ui.reminder)
            w.advance(600)
            assertEquals(ScreenMode.FULL, w.ui.mode, "auto-switch once the card is visible and the guard passed")
        }

        /** H-S7: Switch now on a channel that is gone toasts and stays in the guide; the effect gets the shown alert. */
        @Test fun cardAcceptChannelGoneStaysInGuide() {
            val w = GuideWorld()
            w.reminderChannelGone = true
            w.alert(id = 42)
            w.advance(600)
            w.press(SELECT)
            assertEquals(ScreenMode.GUIDE, w.ui.mode)
            assertEquals(42L, w.calls.lastAlert!!.reminderId)
            assertEquals(w.channels[1].identity, w.calls.lastAlert!!.identity)
            assertEquals(GuideToast.REMINDER_CHANNEL_GONE, w.calls.last("toast")!!.args[0])
            w.press(DOWN)
            w.tuneTo(w.channels[1]); w.flush()
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "a later tune of that channel does not go FULL")
        }

        @Test fun cardAcceptOfTheTunedChannelGoesFullAtOnce() {
            val w = GuideWorld(tunedRow = 1)
            w.reminderChannelGone = true // no Tuned follows; FULL must not wait for one
            w.alert(); w.advance(600); w.press(SELECT)
            assertEquals(ScreenMode.FULL, w.ui.mode)
        }

        /** H-S11: a lost key-up must not leave a hold stuck (no descriptions, a past deadline). */
        @Test fun freshPressEndsAStuckHorizontalHold() {
            val w = GuideWorld()
            w.press(RIGHT, repeat = 1, heldMs = 300)
            w.advance(200)
            val n = w.calls.calls.size
            w.press(DOWN)
            w.advance(200)
            assertTrue(w.calls.calls.drop(n).any { it.name == "loadInfo" }, "description loads after a plain DOWN")
            assertTrue(w.machine.nextDeadlineMs.value == Long.MAX_VALUE || w.machine.nextDeadlineMs.value > w.nowMs, "no past deadline")
        }

        @Test fun freshPressEndsAStuckVerticalHold() {
            val w = GuideWorld()
            w.press(DOWN, repeat = 3)
            assertTrue(w.machine.frame.bubbleNumber >= 0, "holding: bubble shown")
            w.press(SELECT)
            assertEquals(-1, w.machine.frame.bubbleNumber, "a fresh press ends the hold")
            assertEquals(0, w.calls.last("ensureWindow")!!.args[2], "the window is re-ensured without hold bias")
        }

        /** U-B1: a hold that stops moving (edge, refused step) leaves no past deadline; the release re-arms the description. */
        @Test fun heldRightAtTheEdgeLeavesNoPastDeadline() {
            val w = GuideWorld()
            w.press(RIGHT, repeat = 1)
            w.advance(200)
            val d = w.machine.nextDeadlineMs.value
            assertTrue(d == Long.MAX_VALUE || d > w.nowMs, "no past deadline while the hold lasts ($d at ${w.nowMs})")
            val n = w.calls.calls.size
            w.release(RIGHT)
            assertTrue(w.machine.nextDeadlineMs.value in (w.nowMs + 1)..(w.nowMs + 1_000), "the release re-arms the description")
            w.advance(200)
            assertTrue(w.calls.calls.drop(n).any { it.name == "loadInfo" }, "description loads after the release")
        }

        /** H-S4, as reversed by M4 §7 item 15: FULL handles the media keys itself (the player machine answers). */
        @Test fun fullHandlesMediaKeys() {
            val w = GuideWorld()
            assertTrue(w.machine.handles(PLAY_PAUSE), "GUIDE: Play/Pause goes FULL")
            w.press(SELECT)
            assertEquals(ScreenMode.FULL, w.ui.mode)
            for (a in listOf(PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD)) assertTrue(w.machine.handles(a), "$a is FULL's own")
            for (a in listOf(UP, DOWN, ZAP_UP, BACK, SELECT, DIGIT)) assertTrue(w.machine.handles(a), "$a stays with the machine")
            w.alert()
            assertTrue(w.machine.handles(PLAY_PAUSE), "the modal card takes every key")
        }

        /** H-S6: Left on an empty source opens Groups, as the empty-state next step says. */
        @Test fun leftOnAnEmptySourceOpensGroups() {
            val w = GuideWorld()
            w.press(FAVOURITES)
            assertEquals(EmptyKind.EMPTY_SOURCE, w.ui.empty)
            w.press(LEFT)
            assertIs<Overlay.Groups>(w.ui.overlay)
        }
    }

    /** C-S6 (adopted from the core reviewer; §1.1, M3 H-L3): EXIT stops the stream before the app closes. */
    @Test
    fun exitStopsPlaybackBeforeTheAppCloses() {
        val w = GuideWorld()
        w.press(app.thebox.core.input.BoxAction.EXIT) // "Press Back again to exit"
        w.press(app.thebox.core.input.BoxAction.BACK)
        assertTrue(w.exited)
        val names = w.calls.names()
        assertTrue("stopPlayback" in names, "EXIT closed the app with the stream still open: ${names.takeLast(5)}")
        assertTrue(names.lastIndexOf("stopPlayback") < names.lastIndexOf("exitApp"), "the stream stops first")
    }
}
