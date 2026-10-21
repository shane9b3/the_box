package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** docs/design/m6-channel-editor.md §2.3 E5, §4 item 6: block gather, clamps, CH± by 8, one write on drop, cancel. */
class ChannelEditorMoveModeTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }

    @Test fun gatherIsInListOrderAtTheFocusedRow() {
        val ticks = mapOf("c" to 5, "a" to 1, "b" to 3)
        val rows = mapOf("a" to "A", "b" to "B", "c" to "C")
        val b = EditorMoves.gather(ticks, rows, focus = 4, focusKey = null, focusRow = null)!!
        assertEquals(listOf("a", "b", "c"), b.keys)
        assertEquals(listOf("A", "B", "C"), b.rows)
        assertEquals(listOf(1, 3, 5), b.sourceIndices)
        assertEquals(2, b.start, "focus 4 minus the two ticked rows above it")
        assertNull(EditorMoves.gather(emptyMap(), emptyMap<String, String>(), 0, null, null))
    }

    @Test fun spliceAndSourceIndex() {
        val keys = listOf("k0", "k1", "k2", "k3", "k4", "k5")
        assertEquals(listOf("k0", "k2", "k1", "k3", "k4", "k5"), EditorMoves.splice(keys, listOf("k1"), 2))
        assertEquals(listOf("k1", "k3", "k0", "k2", "k4", "k5"), EditorMoves.splice(keys, listOf("k1", "k3"), 0))
        assertEquals(listOf("k0", "k2", "k4", "k5", "k1", "k3"), EditorMoves.splice(keys, listOf("k1", "k3"), 99))
        // Block of the rows originally at 1 and 3, drawn at display 2..3: the others keep their order.
        val drawn = (0 until 6).map { EditorMoves.sourceIndex(it, start = 2, blockSize = 2, sourceIndices = listOf(1, 3)) }
        assertEquals(listOf(0, 2, -1, -1, 4, 5), drawn)
        assertEquals(EditorMoves.splice(keys, listOf("k1", "k3"), 2), drawn.mapIndexed { i, s -> if (s < 0) listOf("k1", "k3")[i - 2] else keys[s] })
    }

    @Test fun nonContiguousTicksLiftAsOneBlockAndDropOnce() {
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT, BoxAction.DOWN) // n1, n3; focus n4
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle()
        val m = w.ui.move!!
        assertEquals(EditorPane.CHANNELS, m.pane)
        assertEquals(listOf("n1", "n3"), (m.lifted as Lifted.Channels).rows.map { it.stableKey })
        assertEquals(1, m.index, "focus 3 minus two ticked rows above it")
        assertEquals(listOf(0, 2), w.machine.liftedSourceIndices)
        assertEquals(listOf(0, 2), EditorMoves.liftedSources(w.ui))
        w.k(BoxAction.UP, BoxAction.UP) // clamped at 0
        assertEquals(0, w.ui.move!!.index)
        w.k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN) // 4 rows, block of 2: max 2
        assertEquals(2, w.ui.move!!.index)
        val before = w.writes.size
        w.k(BoxAction.SELECT)
        assertEquals(listOf(EditorWrite.ReorderChannels(w.pid, "g:news", listOf("n1", "n3"), 2)), w.writes.drop(before))
        assertNull(w.ui.move)
        assertEquals(2, w.ui.channels!!.cursor)
        assertEquals(Selection.None, w.ui.channelSelection, "the ticks are cleared once the write lands")
    }

    @Test fun aGatheredBlockDroppedInPlaceIsWritten() {
        // Review S6: ticks at 0 and 2, focus at 3: gathered at 1; OK at once brings them together.
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT, BoxAction.DOWN)
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle()
        assertEquals(1, w.ui.move!!.index)
        val before = w.writes.size
        w.k(BoxAction.SELECT)
        assertEquals(listOf(EditorWrite.ReorderChannels(w.pid, "g:news", listOf("n1", "n3"), 1)), w.writes.drop(before))
        // Contiguous ticks (1 and 2) dropped where they were: nothing to write.
        val c = EditorWorld().open("g:news").k(BoxAction.DOWN, BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT)
        c.k(BoxAction.RIGHT); c.runLine(EditorAction.REORDER); c.settle()
        val n = c.writes.size
        c.k(BoxAction.SELECT)
        assertEquals(n, c.writes.size)
        assertNull(c.ui.move)
        // Groups too: News (0) and Adult (2) ticked apart, dropped in place.
        val g = EditorWorld().open().k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) // News (0), Adult (2)
        g.k(BoxAction.CONTEXT_MENU); g.runLine(EditorAction.REORDER); g.settle()
        g.k(BoxAction.SELECT)
        assertEquals(EditorWrite.ReorderGroups(g.pid, listOf("g:news", "g:adult"), 1, showHidden = true), g.writes.last())
    }

    @Test fun aBackgroundCountChangeCancelsMoveMode() {
        val w = EditorWorld().open("g:news")
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle(); w.k(BoxAction.DOWN)
        w.groups = listOf(EditorWorld.NEWS.copy(channels = EditorWorld.NEWS.channels.drop(1)), EditorWorld.SPORT)
        w.send(ChannelEditorEvent.Changed)
        assertNull(w.ui.move, "review N12: the block returns when the list changes under it")
    }

    @Test fun liftedRowsSurviveTheirPagesBeingEvicted() {
        val w = EditorWorld(groups = EditorWorld.big(400)).open("g:0")
        w.k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) // rows 0 and 2
        repeat(36) { w.k(BoxAction.ZAP_DOWN) } // to row 290: page 0 is evicted
        assertFalse(0 in w.ui.channels!!.pages.keys)
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle()
        val m = w.ui.move!!
        assertEquals(listOf("c0-0", "c0-2"), (m.lifted as Lifted.Channels).rows.map { it.stableKey })
        assertEquals(290 - 2, m.index)
    }

    @Test fun chPlusMinusMovesByEight() {
        val w = EditorWorld(groups = EditorWorld.big(40)).open("g:0").k(BoxAction.DOWN, BoxAction.DOWN)
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle()
        assertEquals(2, w.ui.move!!.index)
        w.k(BoxAction.ZAP_DOWN); assertEquals(10, w.ui.move!!.index)
        w.k(BoxAction.PAGE_DOWN); assertEquals(18, w.ui.move!!.index)
        w.k(BoxAction.ZAP_UP, BoxAction.ZAP_UP, BoxAction.ZAP_UP); assertEquals(0, w.ui.move!!.index)
        repeat(6) { w.k(BoxAction.ZAP_DOWN) }; assertEquals(39, w.ui.move!!.index, "clamped at the end")
        w.k(BoxAction.SELECT)
        assertEquals(EditorWrite.ReorderChannels(w.pid, "g:0", listOf("c0-2"), 39), w.writes.last())
    }

    @Test fun cancelRestoresTheBlockAndTheCursor() {
        val w = EditorWorld().open("g:news").k(BoxAction.DOWN)
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle()
        assertEquals(listOf(1), EditorMoves.liftedSources(w.ui))
        w.k(BoxAction.DOWN, BoxAction.DOWN)
        val before = w.writes.size
        w.k(BoxAction.BACK)
        assertNull(w.ui.move)
        assertEquals(1, w.ui.channels!!.cursor)
        assertEquals(before, w.writes.size)
    }

    @Test fun groupsClampBelowTheFixedRows() {
        val w = EditorWorld().open().k(BoxAction.DOWN, BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT) // Sport, Adult
        w.k(BoxAction.CONTEXT_MENU); w.runLine(EditorAction.REORDER); w.settle()
        val m = w.ui.move!!
        assertEquals(EditorPane.GROUPS, m.pane)
        assertEquals(listOf("g:sport", "g:adult"), (m.lifted as Lifted.Groups).rows.map { it.groupKey })
        assertEquals(2, m.index, "pane index: head 1 + focus 2 (Adult) − 1 ticked above")
        assertEquals(listOf(1, 2), EditorMoves.liftedSources(w.ui))
        assertEquals(w.machine.liftedSourceIndices, EditorMoves.liftedSources(w.ui))
        w.k(BoxAction.UP, BoxAction.UP, BoxAction.UP)
        assertEquals(1, w.ui.move!!.index, "never above All channels")
        w.k(BoxAction.SELECT)
        assertEquals(EditorWrite.ReorderGroups(w.pid, listOf("g:sport", "g:adult"), 0, showHidden = true), w.writes.last())
    }

    @Test fun reorderIsDisabledUnderSelectAllAFilterAndOutsideAGroup() {
        val ctx = EditorMenus.ChannelContext(EditorList.Group("g"), EditorFilter.ALL, Selection.AllExcept(emptySet()), null, 10, 2)
        val s = SelectionSummary(10, 0, 0, 0, 0, 0, 0)
        assertEquals(MenuLine(EditorAction.REORDER, null, false, EditorText.D_REORDER_ALL), EditorMenus.channelLines(ctx, s)[2])
        assertEquals(EditorText.D_REORDER_FILTER, EditorMenus.channelLines(ctx.copy(selection = Selection.None, filter = EditorFilter.SHOWN), s)[2].reason)
        assertEquals(EditorText.D_REORDER_LOCKED, EditorMenus.channelLines(ctx.copy(selection = Selection.None, lockedOut = 1), s)[2].reason)
        assertEquals(EditorText.D_REORDER_LIST, EditorMenus.channelLines(ctx.copy(list = EditorList.All), s)[2].reason)
        assertEquals(EditorText.D_REORDER_LIST, EditorMenus.channelLines(ctx.copy(list = EditorList.Find("x")), s)[2].reason)
        // On the machine: Select all, then the Reorder line refuses with its reason.
        val w = EditorWorld().open("g:news").k(BoxAction.RIGHT)
        w.runLine(EditorAction.SELECT_ALL); w.settle()
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle()
        assertNull(w.ui.move)
        assertEquals("D_REORDER_ALL", w.ui.notice)
        assertIs<EditorMenu>(w.ui.menu, "a disabled line keeps the menu open")
    }

    @Test fun noReadsWhileMovingWithinLoadedRows() {
        val w = EditorWorld().open("g:news")
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle()
        val calls = w.calls.size
        w.k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.UP)
        assertEquals(calls, w.calls.size, "no effect per step: ${w.calls.drop(calls)}")
    }
}
