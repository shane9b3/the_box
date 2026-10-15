package app.thebox.core.guide.state

import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.HeaderChip
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import app.thebox.core.input.BoxAction
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.PlaybackErrorCode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Table-driven key test (docs/design/m3-guide.md §5.4 `GuideKeyTableTest`): `guide/keys.csv` lists every row of
 * §3.4.2–§3.4.6 as (precondition, action) → (mode, zone, overlay, effects, focus check).
 */
class GuideKeyTableTest {

    data class Row(val line: Int, val section: String, val pre: String, val action: String, val mode: ScreenMode, val zone: Zone,
                   val overlay: String, val effects: String, val check: String)

    private fun rows(): List<Row> {
        val text = javaClass.getResource("/guide/keys.csv")!!.readText()
        return text.lines().mapIndexedNotNull { i, raw ->
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("section,")) return@mapIndexedNotNull null
            val c = l.split(',')
            require(c.size == 8) { "keys.csv:${i + 1}: 8 columns expected: $l" }
            Row(i + 1, c[0], c[1], c[2], ScreenMode.valueOf(c[3]), Zone.valueOf(c[4]), c[5], c[6], c[7])
        }
    }

    @TestFactory
    fun keyTable(): List<DynamicTest> = rows().map { r ->
        DynamicTest.dynamicTest("${r.line} ${r.section}: ${r.pre} + ${r.action}") { run(r) }
    }

    @Test
    fun everyPreconditionIsUsedAndEveryZoneAndOverlayIsCovered() {
        val all = rows()
        assertEquals(PRE.keys, all.map { it.pre }.toSet(), "unused or missing preconditions")
        val sections = all.map { it.section }.toSet()
        for (s in listOf("grid", "tabs", "panel", "header", "groups", "groupmenu", "days", "menu", "sheet", "number", "exit", "card", "full", "back", "search", "settings")) {
            assertTrue(s in sections, "section $s")
        }
        // Every BoxAction appears in the GRID and FULL tables (or is an explicit no-op row).
        val gridActions = all.filter { it.section == "grid" }.map { it.action.removeDigit() }.toSet()
        for (a in BoxAction.entries) if (a != EXIT) assertTrue(a.name in gridActions, "GRID row for $a")
    }

    private fun String.removeDigit() = if (startsWith("DIGIT")) "DIGIT" else this

    private fun run(r: Row) {
        val w = (PRE[r.pre] ?: fail("unknown precondition ${r.pre}"))()
        val before = w.machine.frame.focus
        val mark = w.calls.calls.size
        val (action, digit) = if (r.action.startsWith("DIGIT") && r.action.length > 5) DIGIT to r.action.substring(5).toInt() else BoxAction.valueOf(r.action) to -1
        w.press(action, digit = digit)
        val ui = w.ui
        assertEquals(r.mode, ui.mode, "mode")
        assertEquals(r.zone, ui.zone, "zone")
        assertEquals(r.overlay, overlayName(w), "overlay")
        val fx = w.effectsSince(mark)
        if (r.effects == "-") assertEquals(emptyList(), fx, "effects")
        else for (name in r.effects.split(';')) assertTrue(name in fx, "effect $name in $fx")
        val f = w.machine.frame.focus
        val (k, v) = r.check.split(':').let { it[0] to it.getOrNull(1) }
        when (k) {
            "-" -> Unit
            "same" -> assertEquals(before, f, "focus unchanged")
            "moved" -> assertNotEquals(before, f, "focus moved")
            "home" -> { assertNotNull(f); assertEquals(0, f.row, "home row"); assertTrue(w.nowMin() in f.cellStart until f.cellEnd, "cell at now") }
            "now" -> { assertNotNull(f); assertEquals(before?.row, f.row); assertTrue(w.nowMin() in f.cellStart until f.cellEnd, "cell at now") }
            "row" -> assertEquals(v!!.toInt(), f?.row, "row")
            "tab" -> assertEquals(v!!.toInt(), ui.tabFocused, "tab")
            "chip" -> assertEquals(HeaderChip.valueOf(v!!), ui.headerChips[ui.headerFocused], "chip")
            "panel" -> assertEquals(v!!.toInt(), ui.panelFocused, "panel button")
            "groups" -> assertEquals(v!!.toInt(), (ui.overlay as Overlay.Groups).focused, "groups focus")
            "days" -> assertEquals(v!!.toInt(), (ui.overlay as Overlay.Days).focused, "days focus")
            "menu" -> assertEquals(v!!.toInt(), (ui.overlay as Overlay.Menu).focused, "menu focus")
            "sheet" -> assertEquals(v!!.toInt(), (ui.overlay as Overlay.Sheet).focused, "sheet focus")
            "digits" -> assertEquals(v!!, (ui.overlay as Overlay.Number).digits, "digits")
            "card" -> assertEquals(v!!.toInt(), ui.reminder!!.focused, "card focus")
            else -> fail("bad check ${r.check}")
        }
    }

    companion object {
        fun GuideWorld.nowMin() = (nowMs / 60_000L).toInt()

        fun overlayName(w: GuideWorld): String {
            if (w.ui.reminder != null) return "Card"
            return when (val o = w.ui.overlay) {
                null -> "none"
                is Overlay.Groups -> if (o.menu != null) "GroupMenu" else "Groups"
                is Overlay.Days -> "Days"
                is Overlay.Menu -> "Menu"
                is Overlay.Sheet -> "Sheet"
                is Overlay.Number -> "Number"
                is Overlay.Exit -> if (o.dialog) "ExitDialog" else "ExitHint"
            }
        }

        private fun GuideWorld.keys(vararg a: BoxAction): GuideWorld { a.forEach { press(it) }; return this }

        private fun home() = GuideWorld()
        private fun idle() = GuideWorld(startTuned = false)
        private fun alerted(): GuideWorld = home().also { it.alert() }

        /** Named preconditions used by keys.csv. */
        val PRE: Map<String, () -> GuideWorld> = linkedMapOf(
            "grid_home" to ::home,
            "grid_idle_home" to ::idle,
            "grid_home_no_favourites" to { GuideWorld(favouritesList = false) },
            "grid_home_error_retry" to { home().also { it.tuneTo(it.channels[0], MiniPhase.ERROR, error = PlaybackErrorCode.SERVER_ERROR); it.flush() } },
            "grid_home_error_noretry" to { home().also { it.tuneTo(it.channels[0], MiniPhase.ERROR, error = PlaybackErrorCode.DNS_FAILURE); it.flush() } },
            "grid_row1" to { home().keys(DOWN) },
            "grid_row1_future" to { home().keys(DOWN, RIGHT) },
            "grid_row1_past" to { home().keys(DOWN, SEEK_BACK) },
            "grid_row0_past_archive" to { home().keys(LEFT) },
            "grid_row0_onnow_untuned" to ::idle,
            "grid_row3_noepg" to { home().keys(DOWN, DOWN, DOWN) },
            "grid_last" to { home().also { w -> repeat(9) { w.press(DOWN) } } },
            "grid_scrolled" to { home().keys(DOWN, RIGHT, RIGHT) },
            "tabs" to { home().keys(UP, UP, UP) },
            "tabs_search" to { home().keys(UP, UP, UP, RIGHT) },
            "tabs_settings" to { home().keys(UP, UP, UP, RIGHT, RIGHT) },
            "panel" to { home().keys(UP, UP) },
            "panel_from_start" to { home().keys(UP, UP, RIGHT) },
            "panel_more" to { home().keys(UP, UP, RIGHT, RIGHT) },
            "header" to { home().keys(UP) },
            "header_day" to { home().keys(UP, RIGHT) },
            "header_now" to { home().keys(SEEK_FORWARD, UP, RIGHT).also { check(it.ui.headerChips[it.ui.headerFocused] == HeaderChip.NOW) } },
            "groups" to { home().keys(GROUPS_PANEL) },
            "groups_search" to { home().keys(GROUPS_PANEL, UP, UP, UP) },
            "groups_settings" to { home().keys(GROUPS_PANEL, UP, UP) },
            "groups_news" to { home().keys(GROUPS_PANEL, DOWN) },
            "groups_menu" to { home().keys(GROUPS_PANEL, DOWN, CONTEXT_MENU) },
            "days" to { home().keys(UP, RIGHT, SELECT) },
            "menu" to { home().keys(CONTEXT_MENU) },
            "menu_favourite" to { home().keys(CONTEXT_MENU, DOWN) },
            "menu_hide" to { home().keys(CONTEXT_MENU, DOWN, DOWN) },
            "sheet" to { home().keys(DOWN, RIGHT, SELECT) },
            "sheet_watch" to { home().keys(DOWN, RIGHT, SELECT, RIGHT) },
            "number" to { home().also { it.press(DIGIT, digit = 1) } },
            "exit_hint" to { idle().keys(BACK) },
            "exit_dialog" to { GuideWorld(startTuned = false, settings = GuideSettings(exitConfirmation = ExitConfirmation.ASK)).keys(BACK) },
            "exit_dialog_cancel" to { GuideWorld(startTuned = false, settings = GuideSettings(exitConfirmation = ExitConfirmation.ASK)).keys(BACK, RIGHT) },
            "card_guard" to ::alerted,
            "card" to { alerted().also { it.advance(600) } },
            "card_dismiss" to { alerted().also { it.advance(600); it.press(RIGHT) } },
            "card_channel_gone" to { alerted().also { it.reminderChannelGone = true; it.advance(600) } },
            "settings_card" to { home().keys(UP, UP, UP, RIGHT, RIGHT, SELECT).also { it.alert(); it.advance(700) } },
            "search_card" to { home().keys(UP, UP, UP, RIGHT, SELECT).also { it.alert(); it.advance(700) } },
            "grid_empty_source" to { home().keys(FAVOURITES).also { check(it.machine.frame.focus == null) } },
            "full" to { home().keys(SELECT) },
            "full_error_retry" to { home().keys(SELECT).also { it.tuneTo(it.channels[0], MiniPhase.ERROR, error = PlaybackErrorCode.SERVER_ERROR); it.flush() } },
            "full_error_noretry" to { home().keys(SELECT).also { it.tuneTo(it.channels[0], MiniPhase.ERROR, error = PlaybackErrorCode.DNS_FAILURE); it.flush() } },
            "full_returned" to { home().keys(SELECT, BACK) },
            "ask_home" to { GuideWorld(startTuned = false, settings = GuideSettings(exitConfirmation = ExitConfirmation.ASK)) },
            "none_home" to { GuideWorld(startTuned = false, settings = GuideSettings(exitConfirmation = ExitConfirmation.NONE)) },
            "search" to { home().keys(UP, UP, UP, RIGHT, SELECT) },
            "settings" to { home().keys(UP, UP, UP, RIGHT, RIGHT, SELECT) },
        )
    }
}
