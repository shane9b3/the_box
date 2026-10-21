package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Table-driven key test of the editor machine (docs/design/m6-channel-editor.md §2.4, §6.1 `editor_keys.csv`): every
 * pane × layer × key, including unlisted keys (consumed), ← in the Groups pane (consumed, never a page pop),
 * CONTEXT_MENU and digits in layers (ignored), and ← closing choice lists, pickers and menus.
 */
class ChannelEditorKeyTableTest {
    data class Row(val line: Int, val section: String, val pre: String, val action: String, val pane: EditorPane,
                   val layer: String, val consumed: Boolean, val check: String)

    private fun rows(): List<Row> = javaClass.getResource("/editor_keys.csv")!!.readText().lines().mapIndexedNotNull { i, raw ->
        val l = raw.trim()
        if (l.isEmpty() || l.startsWith("#") || l.startsWith("section,")) return@mapIndexedNotNull null
        val c = l.split(',')
        require(c.size == 7) { "editor_keys.csv:${i + 1}: 7 columns expected: $l" }
        Row(i + 1, c[0], c[1], c[2], EditorPane.valueOf(c[3]), c[4], c[5].toBooleanStrict(), c[6])
    }

    @TestFactory
    fun keyTable(): List<DynamicTest> = rows().map { r -> DynamicTest.dynamicTest("${r.line} ${r.section}: ${r.pre} + ${r.action}") { run(r) } }

    @Test
    fun everyPreconditionIsUsedAndEveryLayerIsCovered() {
        val all = rows()
        assertEquals(PRE.keys, all.map { it.pre }.toSet(), "unused or missing preconditions")
        val sections = listOf("groups", "channels", "toolbar", "menu", "picker", "move", "confirm", "choice", "digits", "gone")
        for (s in sections) assertTrue(all.any { it.section == s }, "section $s")
        // The five keys plus Menu in every pane and layer.
        for (s in listOf("groups", "channels", "toolbar", "menu", "picker", "move", "confirm", "choice")) {
            for (a in listOf("UP", "DOWN", "LEFT", "RIGHT", "SELECT", "BACK", "CONTEXT_MENU")) {
                assertTrue(all.any { it.section == s && it.action == a }, "$s × $a")
            }
        }
        // Digits in every layer (ignored there).
        for (s in listOf("toolbar", "menu", "picker", "move", "confirm", "choice")) assertTrue(all.any { it.section == s && it.action.startsWith("DIGIT") }, "$s × DIGIT")
    }

    private fun run(r: Row) {
        val w = PRE.getValue(r.pre)()
        val writesBefore = w.writes.size
        val pinsBefore = w.pinRequests
        val textsBefore = w.texts.size
        val action = if (r.action.startsWith("DIGIT")) BoxAction.DIGIT else BoxAction.valueOf(r.action)
        val digit = if (r.action.startsWith("DIGIT")) r.action.removePrefix("DIGIT").toInt() else -1
        val answer = w.press(action, digit = digit)
        w.settle()
        val ui = w.ui
        val where = "line ${r.line}"
        assertEquals(r.consumed, answer, "$where consumed")
        assertEquals(r.pane, ui.pane, "$where pane")
        assertEquals(r.layer, w.layer(), "$where layer")
        if (r.check != "-") for (c in r.check.split(';')) {
            val k = c.substringBefore('=')
            val v = c.substringAfter('=')
            when (k) {
                "gc" -> assertEquals(v.toInt(), ui.groups.cursor, "$where gc")
                "cc" -> assertEquals(v.toInt(), assertNotNull(ui.channels, "$where channels").cursor, "$where cc")
                "mf" -> assertEquals(v.toInt(), assertNotNull(ui.menu, where).focused, "$where mf")
                "cf" -> assertEquals(v.toInt(), ui.choice?.focused ?: ui.confirm?.focused, "$where cf")
                "pf" -> assertEquals(v.toInt(), assertNotNull(ui.picker, where).cursor, "$where pf")
                "mi" -> assertEquals(v.toInt(), assertNotNull(ui.move, where).index, "$where mi")
                "tb" -> assertEquals(v.toInt(), ui.toolbar, "$where tb")
                "list" -> assertEquals(v, w.listName(), "$where list")
                "sel" -> assertEquals(v.toInt(), w.selected(), "$where sel")
                "gsel" -> assertEquals(v.toInt(), w.groupSelected(), "$where gsel")
                "writes" -> assertEquals(v.toInt(), w.writes.size - writesBefore, "$where writes ${w.writes.drop(writesBefore)}")
                "write" -> assertEquals(v, w.writes.last()::class.simpleName, "$where write")
                "notice" -> assertTrue(ui.notice?.startsWith(v) == true, "$where notice ${ui.notice}")
                "digits" -> assertEquals(if (v == "-") null else v, ui.digits, "$where digits")
                "text" -> assertEquals(v, w.texts.drop(textsBefore).lastOrNull()?.name, "$where text")
                "hidden" -> assertEquals(v.toBoolean(), ui.showHiddenGroups, "$where hidden")
                "filter" -> assertEquals(v, ui.filter.name, "$where filter")
                "head" -> assertEquals(v.toInt(), ui.groups.head.size, "$where head")
                "lines" -> assertEquals(v, w.menuActions().joinToString("|"), "$where lines")
                "pin" -> assertEquals(v.toInt(), w.pinRequests - pinsBefore, "$where pin")
                else -> error("$where unknown check $k")
            }
        }
    }

    companion object {
        private fun EditorWorld.keys(vararg a: BoxAction): EditorWorld { for (x in a) { press(x); settle() }; return this }

        private fun gFirst() = EditorWorld().open()
        private fun cTop() = EditorWorld().open("g:news")
        private fun menuSingle() = cTop().keys(BoxAction.RIGHT)

        val PRE: Map<String, () -> EditorWorld> = mapOf(
            "G_FIRST" to { gFirst() },
            "G_ALL_ROW" to { gFirst().keys(BoxAction.UP) },
            "G_TICKED" to { gFirst().keys(BoxAction.SELECT) },
            "G_RESULTS" to { cTop().keys(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.SELECT) },
            "G_TOOLBAR" to { gFirst().keys(BoxAction.UP, BoxAction.UP) },
            "G_TOOLBAR_HIDDEN" to { gFirst().keys(BoxAction.UP, BoxAction.UP, BoxAction.RIGHT) },
            "G_TOOLBAR_SORT" to { gFirst().keys(BoxAction.UP, BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT) },
            "G_TOOLBAR_TICKED" to { gFirst().keys(BoxAction.SELECT, BoxAction.UP, BoxAction.UP) },
            "C_TOP" to { cTop() },
            "C_ROW1" to { cTop().keys(BoxAction.DOWN) },
            "C_TICKED" to { cTop().keys(BoxAction.SELECT, BoxAction.DOWN) },
            "C_LOCKED_NOTE" to { EditorWorld(pinSet = true, unlocked = false).open("g:sport").keys(BoxAction.UP) },
            "C_GROUPS_FAILED" to { cTop().also { it.fail += "loadGroups"; it.send(ChannelEditorEvent.Changed); it.fail -= "loadGroups" } },
            "C_ALL_LOCKED_OUT" to {
                EditorWorld(groups = listOf(EditorWorld.ADULT), pinSet = true, unlocked = false).open().keys(BoxAction.UP, BoxAction.RIGHT)
            },
            "C_E7" to { EditorWorld(pinSet = true, unlocked = false).open("g:adult") },
            "C_TOOLBAR" to { cTop().keys(BoxAction.UP) },
            "C_TOOLBAR_SHOW" to { cTop().keys(BoxAction.UP, BoxAction.RIGHT) },
            "C_TOOLBAR_SORT" to { cTop().keys(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT) },
            "C_TOOLBAR_FIND" to { cTop().keys(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.RIGHT) },
            "MENU_SINGLE" to { menuSingle() },
            "MENU_LAST" to { menuSingle().keys(BoxAction.ZAP_DOWN) },
            "MENU_GROUP" to { gFirst().keys(BoxAction.CONTEXT_MENU) },
            "MENU_HEAD" to { gFirst().keys(BoxAction.UP, BoxAction.CONTEXT_MENU) },
            "MENU_LOADING" to { cTop().keys(BoxAction.SELECT, BoxAction.DOWN).also { it.hold += "summarize" }.keys(BoxAction.RIGHT) },
            "MENU_DISABLED" to { gFirst().keys(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.DOWN, BoxAction.DOWN) },
            "PICKER" to { menuSingle().keys(BoxAction.DOWN, BoxAction.SELECT) },
            "PICKER_HERE" to { menuSingle().keys(BoxAction.DOWN, BoxAction.SELECT, BoxAction.UP) },
            "MOVE" to { menuSingle().keys(BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) },
            "MOVE_DOWN1" to { menuSingle().keys(BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT, BoxAction.DOWN) },
            "G_MOVE" to { gFirst().keys(BoxAction.CONTEXT_MENU, BoxAction.DOWN, BoxAction.SELECT) },
            "CONFIRM" to { menuSingle().keys(BoxAction.ZAP_DOWN, BoxAction.SELECT) },
            "CONFIRM_LEFT" to { menuSingle().keys(BoxAction.ZAP_DOWN, BoxAction.SELECT, BoxAction.LEFT) },
            "CHOICE" to { cTop().keys(BoxAction.UP, BoxAction.RIGHT, BoxAction.SELECT) },
            "CHOICE_DOWN1" to { cTop().keys(BoxAction.UP, BoxAction.RIGHT, BoxAction.SELECT, BoxAction.DOWN) },
            "CHOICE_LAST" to { cTop().keys(BoxAction.UP, BoxAction.RIGHT, BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN) },
            "DIGITS" to { cTop().also { it.press(BoxAction.DIGIT, digit = 1) } },
            "GONE" to { cTop().also { it.send(ChannelEditorEvent.PlaylistGone) } },
        )
    }

    /**
     * The press budget of §2.4 (Menu, long OK and → count as one press each), measured from a focused channel row with
     * three channels ticked (Hide, Move) or from the focused row (Reorder, Reset).
     */
    @Test
    fun pressBudget() {
        fun ticked3(): EditorWorld = EditorWorld().open("g:news").keys(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT)

        // Hide the selection: → (menu), OK = 2.
        ticked3().apply {
            val before = writes.size
            keys(BoxAction.RIGHT, BoxAction.SELECT)
            assertEquals(EditorWrite.HideChannels(pid, ChannelTarget.Keys(setOf("n1", "n3", "n4")), true), writes.drop(before).single())
        }
        // Move to group: Menu, ↓, OK = 3 (the picker is open; its OK is the move itself).
        ticked3().apply { keys(BoxAction.CONTEXT_MENU, BoxAction.DOWN, BoxAction.SELECT); assertEquals("picker", layer()) }
        // Channel Reorder: Menu, ↓, ↓, OK = 4.
        EditorWorld().open("g:news").apply { keys(BoxAction.CONTEXT_MENU, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT); assertEquals("move", layer()) }
        // Group Reorder: Menu, ↓, OK = 3.
        EditorWorld().open().apply { keys(BoxAction.CONTEXT_MENU, BoxAction.DOWN, BoxAction.SELECT); assertEquals("move", layer()); assertEquals(EditorPane.GROUPS, ui.move!!.pane) }
        // Reset: Menu, CH−, OK = 3, then the confirm card (focus on Cancel).
        EditorWorld().open("g:news").apply {
            keys(BoxAction.CONTEXT_MENU, BoxAction.ZAP_DOWN, BoxAction.SELECT)
            assertEquals(ConfirmKind.RESET_CHANNELS, ui.confirm!!.kind)
            assertEquals(1, ui.confirm!!.focused)
        }
        // Favorites (accelerator) = 1; from the menu = 5 (Menu, ↓ × 3, OK); Lock = 6.
        EditorWorld().open("g:news").apply { val b = writes.size; keys(BoxAction.FAVOURITES); assertEquals(EditorWrite.Favorite(pid, ChannelTarget.Keys(setOf("n1")), true), writes.drop(b).single()) }
        EditorWorld().open("g:news").apply { val b = writes.size; keys(BoxAction.CONTEXT_MENU, BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT); assertEquals(true, (writes.drop(b).single() as EditorWrite.Favorite).on) }
        EditorWorld().open("g:news").apply { val b = writes.size; keys(BoxAction.CONTEXT_MENU, BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT); assertEquals(true, (writes.drop(b).single() as EditorWrite.LockChannels).locked) }
        // Rename = 7, Set number = 8 (single channel).
        EditorWorld().open("g:news").apply { keys(BoxAction.CONTEXT_MENU); repeat(5) { keys(BoxAction.DOWN) }; keys(BoxAction.SELECT); assertEquals(EditorText.EDIT_RENAME_CHANNEL, texts.last()) }
        EditorWorld().open("g:news").apply { keys(BoxAction.CONTEXT_MENU); repeat(6) { keys(BoxAction.DOWN) }; keys(BoxAction.SELECT); assertEquals(EditorText.EDIT_NUMBER, numbers.last()) }
    }
}
