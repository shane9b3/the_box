package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-channel-editor.md §3.3 "Menu summary", §2.4 "Menus (ruled order, no wrap)": the menu opens loading and
 * fills on `Summary`; a stale token is ignored; the lines for each case; CH± jumps; no wrap.
 */
class ChannelEditorSummaryTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }
    private val news = EditorList.Group("g:news")

    private fun lines(s: SelectionSummary, single: EditorChannelRow? = null, list: EditorList = news, groups: Int = 3) =
        EditorMenus.channelLines(EditorMenus.ChannelContext(list, EditorFilter.ALL, if (single == null) Selection.Keys(mapOf("a" to 0, "b" to 1)) else Selection.None, single, 20, groups), s)

    private fun List<MenuLine>.names() = joinToString("|") { it.action.name + (it.count?.let { c -> "($c)" } ?: "") + (if (!it.enabled) "!" + it.reason!!.name else "") }

    @Test fun opensLoadingAndFillsOnSummary() {
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT)
        w.hold += "summarize"
        w.k(BoxAction.RIGHT)
        val m = w.ui.menu!!
        assertTrue(m.loading)
        assertTrue(m.lines.isEmpty())
        assertEquals("MENU_TITLE_CHANNELS[2]", m.title)
        assertEquals(ChannelTarget.Keys(setOf("n1", "n2")), w.summaries.last().second)
        // A stale token is ignored.
        w.send(ChannelEditorEvent.Summary(w.summaries.last().first - 1, SelectionSummary(2, 0, 0, 0, 0, 0, 0)))
        assertTrue(w.ui.menu!!.loading)
        w.release("summarize")
        assertFalse(w.ui.menu!!.loading)
        // n1 shown + n2 hidden: mixed; Hide 1 first, Show 1 after Reorder.
        assertEquals("HIDE|MOVE_TO_GROUP|REORDER|SHOW|ADD_FAVORITE|LOCK|SELECT_ALL|FIND|FILTER|SORT|RESET", w.menuActions().joinToString("|"))
        assertEquals(1, w.ui.menu!!.lines[0].count)
    }

    @Test fun aSummaryForAClosedMenuIsIgnored() {
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT)
        w.hold += "summarize"
        w.k(BoxAction.RIGHT, BoxAction.BACK)
        w.release("summarize")
        assertEquals(null, w.ui.menu)
    }

    @Test fun oneFocusedRowNeedsNoSummary() {
        val w = EditorWorld().open("g:news")
        w.k(BoxAction.RIGHT)
        assertFalse(w.ui.menu!!.loading)
        assertTrue(w.summaries.isEmpty())
        assertEquals("BBC One", w.ui.menu!!.title)
    }

    @Test fun theLinesForEachCase() {
        val row = EditorWorld().channelRow("n1")
        // Single, shown, not a favorite, no custom number.
        assertEquals("HIDE(1)|MOVE_TO_GROUP|REORDER|ADD_FAVORITE|LOCK|RENAME|SET_NUMBER|SELECT_ALL(20)|FIND|FILTER|SORT|RESET(1)",
            lines(EditorMenus.summaryOf(row), row).names())
        // Single with a custom number: "Use the provider's number" after Set number.
        val n4 = EditorWorld().channelRow("n4")
        assertEquals(EditorAction.CLEAR_NUMBER, lines(EditorMenus.summaryOf(n4), n4).map { it.action }[7])
        // Many, all shown.
        assertEquals("HIDE(5)|MOVE_TO_GROUP|REORDER|ADD_FAVORITE|LOCK|SELECT_ALL(20)|FIND|FILTER|SORT|RESET(5)",
            lines(SelectionSummary(5, 0, 0, 0, 0, 0, 0)).names())
        // Many, all hidden by the user: Show first.
        assertEquals("SHOW(5)", lines(SelectionSummary(5, 5, 0, 0, 0, 0, 0)).names().substringBefore('|'))
        // Mixed hidden: Hide (shown count) first, Show (hidden count) after Reorder.
        assertEquals("HIDE(3)|MOVE_TO_GROUP|REORDER|SHOW(2)", lines(SelectionSummary(5, 2, 0, 0, 0, 0, 0)).names().split('|').take(4).joinToString("|"))
        // Rule-hidden only: Show is disabled with its reason.
        assertEquals("SHOW!D_RULE", lines(SelectionSummary(2, 0, 2, 0, 0, 0, 0)).names().substringBefore('|'))
        // Shown plus rule-hidden: Hide, and the disabled Show after Reorder.
        assertEquals("SHOW!D_RULE", lines(SelectionSummary(3, 0, 1, 0, 0, 0, 0)).names().split('|')[3])
        // Some user-hidden, some rule-hidden: Show counts the user's only.
        assertEquals("SHOW(1)", lines(SelectionSummary(3, 1, 2, 0, 0, 0, 0)).names().substringBefore('|'))
        // All favorites: Remove.
        assertTrue(EditorAction.REMOVE_FAVORITE in lines(SelectionSummary(2, 0, 0, 0, 0, 2, 0)).map { it.action })
        // All locked, some by their own lock: Unlock enabled; all by their group: disabled with the reason.
        assertEquals("UNLOCK", lines(SelectionSummary(2, 0, 0, 2, 1, 0, 1)).names().split('|')[4])
        assertEquals("UNLOCK!D_LOCKED_BY_GROUP", lines(SelectionSummary(2, 0, 0, 2, 2, 0, 2)).names().split('|')[4])
        // Partly locked: Lock.
        assertEquals("LOCK", lines(SelectionSummary(2, 0, 0, 1, 0, 0, 0)).names().split('|')[4])
        // fromLockedGroup does not disable Move (it asks for the PIN, ChannelEditorParentalTest).
        assertTrue(lines(SelectionSummary(2, 0, 0, 2, 2, 0, 2))[1].enabled)
        // One group only: Move is disabled.
        assertEquals("MOVE_TO_GROUP!D_NO_GROUPS", lines(SelectionSummary(2, 0, 0, 0, 0, 0, 0), groups = 1).names().split('|')[1])
        // All channels and Find: Reorder and Sort disabled with D_REORDER_LIST.
        val all = lines(SelectionSummary(2, 0, 0, 0, 0, 0, 0), list = EditorList.All).names()
        assertTrue("REORDER!D_REORDER_LIST" in all && "SORT!D_REORDER_LIST" in all, all)
    }

    @Test fun theGroupMenuLines() {
        val w = EditorWorld()
        val news = w.groupRow(w.groups[0])
        val adult = w.groupRow(w.groups[2])
        fun names(l: List<MenuLine>) = l.joinToString("|") { it.action.name + if (!it.enabled) "!" + it.reason!!.name else "" }
        assertEquals("HIDE|REORDER|LOCK|RENAME|EDIT_CHANNELS|SORT|SELECT_ALL|RESET", names(EditorMenus.groupLines(listOf(news), 1, false, true, 3)))
        assertEquals("HIDE|REORDER|LOCK|EDIT_CHANNELS|SORT|SELECT_ALL|RESET", names(EditorMenus.groupLines(listOf(news, adult), 2, false, true, 3)))
        assertEquals("HIDE|REORDER|UNLOCK|RENAME|EDIT_CHANNELS|SORT|SELECT_ALL|RESET", names(EditorMenus.groupLines(listOf(adult), 1, false, true, 3)))
        assertEquals("SHOW|REORDER|LOCK|RENAME|EDIT_CHANNELS|SORT|SELECT_ALL|RESET", names(EditorMenus.groupLines(listOf(news.copy(hidden = true)), 1, false, true, 3)))
        assertEquals("HIDE|REORDER!D_REORDER_HIDDEN_GROUPS|LOCK|RENAME|EDIT_CHANNELS|SORT|SELECT_ALL|RESET", names(EditorMenus.groupLines(listOf(news), 1, false, false, 3)))
        assertEquals("HIDE|REORDER!D_REORDER_ALL|SHOW|LOCK|UNLOCK|EDIT_CHANNELS|SORT|SELECT_ALL|RESET", names(EditorMenus.groupLines(null, 3, true, true, 3)))
        assertEquals("EDIT_CHANNELS|FIND", names(EditorMenus.headLines(EditorList.All)))
        assertEquals("EDIT_CHANNELS|FIND|DROP_RESULTS", names(EditorMenus.headLines(EditorList.Find("x"))))
    }

    @Test fun theMenuClampsAndChJumps() {
        val w = EditorWorld().open("g:news").k(BoxAction.RIGHT)
        val last = w.ui.menu!!.lines.lastIndex
        w.k(BoxAction.UP); assertEquals(0, w.ui.menu!!.focused, "no wrap at the top")
        w.k(BoxAction.ZAP_DOWN); assertEquals(last, w.ui.menu!!.focused)
        assertEquals(EditorAction.RESET, w.ui.menu!!.lines[last].action)
        w.k(BoxAction.DOWN); assertEquals(last, w.ui.menu!!.focused, "no wrap at the bottom")
        w.k(BoxAction.ZAP_UP); assertEquals(0, w.ui.menu!!.focused)
        w.k(BoxAction.PAGE_DOWN); assertEquals(last, w.ui.menu!!.focused)
        w.k(BoxAction.PAGE_UP); assertEquals(0, w.ui.menu!!.focused)
    }

    @Test fun labels() {
        assertEquals(EditorText.A_SORT_GROUPS, EditorMenus.labelOf(MenuLine(EditorAction.SORT, null, true, null), EditorPane.GROUPS))
        assertEquals(EditorText.A_SORT, EditorMenus.labelOf(MenuLine(EditorAction.SORT, null, true, null), EditorPane.CHANNELS))
        for (a in EditorAction.entries) EditorMenus.labelOf(MenuLine(a, null, true, null), EditorPane.CHANNELS)
    }

    @Test fun aSortAsksFirstWhenTheGroupHasItsOwnOrder() {
        val custom = EditorWorld.NEWS.copy(customOrder = true)
        EditorWorld(groups = listOf(custom, EditorWorld.SPORT)).open("g:news").apply {
            k(BoxAction.RIGHT); runLine(EditorAction.SORT); settle(); k(BoxAction.SELECT) // Name A–Z
            assertEquals(ConfirmKind.SORT_REPLACES_ORDER, ui.confirm!!.kind)
            assertEquals(listOf("News"), ui.confirm!!.args)
            k(BoxAction.LEFT, BoxAction.SELECT)
            assertEquals(EditorWrite.SortChannels(pid, "g:news", ChannelSort.NAME_AZ), writes.last())
            assertEquals("N_SORTED(SORT_NAME_AZ)", ui.notice)
        }
        EditorWorld(groups = listOf(custom, EditorWorld.SPORT)).open("g:news").apply {
            k(BoxAction.RIGHT); runLine(EditorAction.SORT); settle(); k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) // Provider order
            assertEquals(ConfirmKind.PROVIDER_ORDER, ui.confirm!!.kind)
        }
        // No custom order: sorted at once.
        EditorWorld().open("g:news").apply {
            k(BoxAction.RIGHT); runLine(EditorAction.SORT); settle(); k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) // Number
            assertEquals(null, ui.confirm)
            assertEquals(EditorWrite.SortChannels(pid, "g:news", ChannelSort.NUMBER), writes.last())
        }
        // Group sort: always confirmed.
        EditorWorld().open().apply {
            k(BoxAction.CONTEXT_MENU); runLine(EditorAction.SORT); settle(); k(BoxAction.SELECT)
            assertEquals(ConfirmKind.SORT_REPLACES_ORDER, ui.confirm!!.kind)
            k(BoxAction.LEFT, BoxAction.SELECT)
            assertEquals(EditorWrite.SortGroups(pid, GroupSort.NAME_AZ), writes.last())
        }
    }

    @Test fun renameAndNumberHandOffs() {
        val w = EditorWorld().open("g:news")
        w.textAnswer = { "" } // empty = the provider's name
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.RENAME); w.settle()
        assertEquals(EditorWrite.RenameChannel(w.pid, "n1", null), w.writes.last())
        assertEquals("N_NAME_RESET(BBC One)", w.ui.notice)
        w.textAnswer = { " BBC 1 " }
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.RENAME); w.settle()
        assertEquals(EditorWrite.RenameChannel(w.pid, "n1", "BBC 1"), w.writes.last())
        // Unchanged or cancelled: nothing written.
        val n = w.writes.size
        w.textAnswer = { "BBC One" }
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.RENAME); w.settle()
        w.textAnswer = { null }
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.RENAME); w.settle()
        w.numberAnswer = null
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.SET_NUMBER); w.settle()
        assertEquals(n, w.writes.size)
        // Use the provider's number.
        w.k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN, BoxAction.RIGHT); w.runLine(EditorAction.CLEAR_NUMBER); w.settle()
        assertEquals(EditorWrite.SetNumber(w.pid, "n4", null), w.writes.last())
        // Group rename.
        w.textAnswer = { "Headlines" }
        w.k(BoxAction.LEFT, BoxAction.CONTEXT_MENU); w.runLine(EditorAction.RENAME); w.settle()
        assertEquals(EditorWrite.RenameGroup(w.pid, "g:news", "Headlines"), w.writes.last())
        assertEquals(EditorText.EDIT_RENAME_GROUP, w.texts.last())
    }
}
