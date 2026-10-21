package app.thebox.core.guide.state

import app.thebox.core.guide.CellFlags
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.FooterHint
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.Zone
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.GuideFixtures.Cell
import app.thebox.core.guide.state.GuideKeyTableTest.Companion.overlayName
import app.thebox.core.input.BoxAction.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 2026-10-19 B1: when now falls in a sliver (a GAP piece under `SLIVER_MIN`) on the home row, the real navigator
 * resolves home to the *next* cell, which does not contain now. Home is the focus `goHome()` produces, so Back still
 * walks home → Groups → top bar → exit. Always on [app.thebox.core.guide.layout.GuideNavigatorImpl] (the fake has no
 * slivers), whichever task runs it.
 */
class SliverAtNowTest {
    private val now = GuideWorld.NOW_MIN

    private fun world(startTuned: Boolean = true, exit: ExitConfirmation = ExitConfirmation.BACK_TWICE, data: GuideWindowData = SLIVER_AT_NOW) =
        GuideWorld(startTuned = startTuned, data = data, realNavigator = true, settings = GuideSettings(exitConfirmation = exit))

    private fun footerHas(w: GuideWorld, keys: String, label: String) = FooterHint(keys, label) in w.ui.footer

    @Test fun theFixturePutsNowInASliverOnTheTunedRow() {
        val w = world()
        val f = w.machine.frame.focus!!
        assertEquals(0, f.row)
        assertEquals(now + 1, f.cellStart, "the sliver at now resolves to the next cell")
        assertTrue(now < f.cellStart, "home's cell does not contain now")
    }

    @Test fun backFromHomeReachesGroupsTheTopBarAndTheExitStep() {
        val w = world()
        assertTrue(footerHas(w, GuideTexts.KEYS_BACK, GuideTexts.HINT_GROUPS), "home: Back · Groups; footer=${w.ui.footer}")
        w.press(BACK)
        assertTrue(assertIs<Overlay.Groups>(w.ui.overlay).viaBack, "one Back opens Groups")
        w.press(BACK)
        assertNull(w.ui.overlay); assertEquals(Zone.TABS, w.ui.zone); assertEquals(GuideStateMachineImpl.TAB_SEARCH, w.ui.tabFocused)
        w.press(BACK)
        assertEquals("ExitHint", overlayName(w))
        w.press(BACK)
        assertTrue(w.exited)
    }

    @Test fun fromAnotherRowBackGoesHomeThenOpensGroups() {
        val w = world()
        w.press(DOWN); w.press(DOWN)
        assertTrue(footerHas(w, GuideTexts.KEYS_BACK, GuideTexts.HINT_NOW))
        w.press(BACK)
        assertNull(w.ui.overlay); assertEquals(0, w.machine.frame.focus!!.row)
        assertTrue(footerHas(w, GuideTexts.KEYS_BACK, GuideTexts.HINT_GROUPS))
        w.press(BACK)
        assertTrue(assertIs<Overlay.Groups>(w.ui.overlay).viaBack)
    }

    @Test fun nothingTunedTheCurrentRowIsHome() {
        val w = world(startTuned = false)
        assertEquals(0, w.machine.frame.focus!!.row)
        w.press(BACK)
        assertTrue(assertIs<Overlay.Groups>(w.ui.overlay).viaBack)
    }

    @Test fun theGuideKeyAtHomeOpensGroupsAndClosesThem() {
        val w = world()
        w.press(OPEN_GUIDE)
        assertIs<Overlay.Groups>(w.ui.overlay)
        w.press(OPEN_GUIDE) // review L3: the Guide key closes Groups to the grid at home
        assertNull(w.ui.overlay); assertEquals(Zone.GRID, w.ui.zone); assertEquals(now + 1, w.machine.frame.focus!!.cellStart)
    }

    /** The no-dead-ends property of GuideBehaviourTest, on the real navigator over rows with a sliver every 30 minutes. */
    @Test fun noDeadEndsBackAlwaysReachesExitOverSliverRows() {
        val rnd = java.util.Random(20261019)
        val actions = values().filter { it != EXIT }
        repeat(200) { walk ->
            val exit = ExitConfirmation.entries[walk % 3]
            val w = world(startTuned = walk % 2 == 0, exit = exit, data = SLIVER_EVERY_ROW)
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
            while (!w.exited && backs < 12) {
                w.advance(700)
                if (exit == ExitConfirmation.ASK && (w.ui.overlay as? Overlay.Exit)?.dialog == true) { w.press(SELECT); continue }
                w.press(BACK)
                backs++
            }
            assertTrue(w.exited, "walk $walk ($exit): Back did not reach the exit step; ui=${w.ui}")
        }
    }

    companion object {
        private val NOW = GuideWorld.NOW_MIN
        private val FROM = GuideWorld.FROM
        private val TO = GuideWorld.TO

        /** Programmes of 28 min then a 2-min sliver, the sliver of the first period at [sliverAt, sliverAt + 2). */
        private fun sliverRow(sliverAt: Int): List<Cell> {
            val cells = ArrayList<Cell>()
            var t = sliverAt - 28
            while (t > FROM) t -= 30
            while (t < TO) {
                cells += Cell(t, t + 28, CellKind.PROGRAMME, "Show $t")
                cells += Cell(t + 28, t + 30, CellKind.GAP, null, CellFlags.SLIVER)
                t += 30
            }
            return cells
        }

        /** The default world's rows, with row 0 (tuned, 7-day archive) cut by a 2-min sliver at [NOW − 1, NOW + 1). */
        val SLIVER_AT_NOW: GuideWindowData = run {
            val rows = GuideFixtures.syntheticRows(10, FROM, TO, noEpgRows = setOf(3)).toMutableList()
            val row0 = ArrayList<Cell>()
            var t = FROM
            while (t + 30 <= NOW - 1) { row0 += Cell(t, t + 30, CellKind.PROGRAMME, "Row 0 show $t"); t += 30 }
            if (t < NOW - 1) row0 += Cell(t, NOW - 1, CellKind.PROGRAMME, "Row 0 show $t")
            row0 += Cell(NOW - 1, NOW + 1, CellKind.GAP, null, CellFlags.SLIVER)
            t = NOW + 1
            while (t < TO) { row0 += Cell(t, t + 30, CellKind.PROGRAMME, "Row 0 show $t"); t += 30 }
            rows[0] = row0
            GuideFixtures.windowData(rows, fromMin = FROM, toMin = TO)
        }

        /** Every EPG row has a sliver every 30 min; even rows have one at now (row 3 keeps no EPG). */
        val SLIVER_EVERY_ROW: GuideWindowData = GuideFixtures.windowData(
            List(10) { r -> if (r == 3) listOf(Cell(FROM, TO, CellKind.NO_EPG)) else sliverRow(NOW - 1 + (r % 2) * 10) },
            fromMin = FROM, toMin = TO,
        )
    }
}
