package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** docs/design/m6-channel-editor.md §2.6 "Selection scope", "Select all", §3.3 "Write queue" (ticks after a write). */
class ChannelEditorSelectionTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }

    @Test fun ticksKeepTheirListIndex() {
        val w = EditorWorld().open("g:news").k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT, BoxAction.UP, BoxAction.UP, BoxAction.SELECT)
        assertEquals(Selection.Keys(mapOf("n3" to 2, "n1" to 0)), w.ui.channelSelection)
        w.k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) // untick n3
        assertEquals(Selection.Keys(mapOf("n1" to 0)), w.ui.channelSelection)
        w.k(BoxAction.UP, BoxAction.UP, BoxAction.SELECT) // untick the last: None
        assertEquals(Selection.None, w.ui.channelSelection)
    }

    @Test fun groupTicksUseGroupIndices() {
        val w = EditorWorld().open().k(BoxAction.DOWN, BoxAction.SELECT)
        assertEquals(Selection.Keys(mapOf("g:sport" to 1)), w.ui.groupSelection)
        w.k(BoxAction.UP, BoxAction.UP, BoxAction.SELECT) // OK on All channels opens it; it is not tickable
        assertEquals(EditorPane.CHANNELS, w.ui.pane)
        assertEquals(Selection.Keys(mapOf("g:sport" to 1)), w.ui.groupSelection)
    }

    @Test fun selectAllIsSymbolicAndCountsExceptions() {
        val w = EditorWorld().open("g:news").k(BoxAction.RIGHT)
        w.runLine(EditorAction.SELECT_ALL); w.settle()
        assertEquals(Selection.AllExcept(emptySet()), w.ui.channelSelection)
        assertEquals(4, w.selected())
        w.k(BoxAction.SELECT) // untick n1
        assertEquals(Selection.AllExcept(setOf("n1")), w.ui.channelSelection)
        assertEquals(3, w.selected())
        w.k(BoxAction.SELECT) // tick it again
        assertEquals(4, w.selected())
        // Unticking every row leaves no selection.
        repeat(4) { w.k(BoxAction.SELECT, BoxAction.DOWN) }
        assertEquals(Selection.None, w.ui.channelSelection)
    }

    @Test fun aListChangeClearsTicksWithANotice() {
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT)
        assertEquals(2, w.selected())
        w.k(BoxAction.LEFT) // the list does not change: ticks kept
        assertEquals(2, w.selected())
        w.k(BoxAction.DOWN) // the Groups cursor moves to Sport; after the debounce the list follows
        assertEquals("G:g:sport", w.listName())
        assertEquals(0, w.selected())
        assertEquals("N_UNSELECTED[2]", w.ui.notice)
    }

    @Test fun aFilterChangeClearsTicks() {
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT)
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.FILTER); w.settle()
        w.k(BoxAction.DOWN, BoxAction.SELECT) // Shown only
        assertEquals(EditorFilter.SHOWN, w.ui.filter)
        assertEquals(Selection.None, w.ui.channelSelection)
        assertEquals("N_UNSELECTED[1]", w.ui.notice)
        assertEquals(3, w.ui.channels!!.count)
    }

    @Test fun hiddenGroupsToggleClearsGroupTicks() {
        val w = EditorWorld().open().k(BoxAction.SELECT, BoxAction.UP, BoxAction.UP, BoxAction.RIGHT, BoxAction.SELECT)
        assertEquals(false, w.ui.showHiddenGroups)
        assertEquals(Selection.None, w.ui.groupSelection)
        assertEquals(false, w.groupLoads.last().first, "reloaded without hidden groups")
    }

    @Test fun ticksAreClearedAfterTheirWriteAndNewTicksSurvive() {
        val w = EditorWorld().open("g:news")
        w.hold += "write"
        w.k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) // n1, n3
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.HIDE); w.settle()
        assertEquals(EditorWrite.HideChannels(w.pid, ChannelTarget.Keys(setOf("n1", "n3")), true), w.writes.single())
        assertEquals(2, w.selected(), "ticks stay until the write lands")
        w.k(BoxAction.DOWN, BoxAction.SELECT) // n4, ticked while saving
        w.release("write")
        assertEquals(Selection.Keys(mapOf("n4" to 3)), w.ui.channelSelection)
        assertEquals("N_HIDDEN[1]", w.ui.notice)
    }

    @Test fun aFailedWriteKeepsTheTicks() {
        val w = EditorWorld().open("g:news")
        w.writeResult = { WriteResult.Failed(WriteFailure.STORAGE) }
        w.k(BoxAction.SELECT, BoxAction.RIGHT); w.runLine(EditorAction.HIDE); w.settle()
        assertEquals(1, w.selected())
        assertEquals("E_WRITE", w.ui.notice)
    }

    @Test fun selectAllIsClearedAfterItsWrite() {
        val w = EditorWorld().open("g:news").k(BoxAction.RIGHT)
        w.runLine(EditorAction.SELECT_ALL); w.settle()
        w.k(BoxAction.SELECT) // except n1
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.HIDE); w.settle()
        assertEquals(EditorWrite.HideChannels(w.pid, ChannelTarget.AllOf(EditorList.Group("g:news"), EditorFilter.ALL, setOf("n1")), true), w.writes.last())
        assertEquals(Selection.None, w.ui.channelSelection)
    }

    @Test fun theTargetBuiltForEachWrite() {
        val pid = 7L
        // Focused row, no ticks: that one key.
        EditorWorld().open("g:news").k(BoxAction.DOWN, BoxAction.DOWN).apply {
            k(BoxAction.RIGHT); runLine(EditorAction.LOCK); settle()
            assertEquals(EditorWrite.LockChannels(pid, ChannelTarget.Keys(setOf("n3")), true), writes.last())
        }
        // Explicit ticks: their keys (not the focused row's).
        EditorWorld().open("g:news").k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN).apply {
            k(BoxAction.RIGHT); runLine(EditorAction.ADD_FAVORITE); settle()
            assertEquals(EditorWrite.Favorite(pid, ChannelTarget.Keys(setOf("n1")), true), writes.last())
        }
        // Select all under a filter: AllOf with that list and filter.
        EditorWorld().open("g:news").apply {
            k(BoxAction.RIGHT); runLine(EditorAction.FILTER); settle(); k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) // Hidden only
            k(BoxAction.RIGHT); runLine(EditorAction.SELECT_ALL); settle()
            k(BoxAction.RIGHT); runLine(EditorAction.SHOW); settle()
            assertEquals(EditorWrite.HideChannels(pid, ChannelTarget.AllOf(EditorList.Group("g:news"), EditorFilter.HIDDEN, emptySet()), false), writes.last())
        }
        // Groups: ticked keys, or AllOf(showHidden, except).
        EditorWorld().open().apply {
            k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT, BoxAction.CONTEXT_MENU); runLine(EditorAction.HIDE); settle()
            assertEquals(EditorWrite.HideGroups(pid, GroupTarget.Keys(setOf("g:news", "g:sport")), true), writes.last())
        }
        EditorWorld().open().apply {
            k(BoxAction.CONTEXT_MENU); runLine(EditorAction.SELECT_ALL); settle()
            k(BoxAction.SELECT) // except News
            k(BoxAction.CONTEXT_MENU); runLine(EditorAction.LOCK); settle()
            assertEquals(EditorWrite.LockGroups(pid, GroupTarget.AllOf(true, setOf("g:news")), true), writes.last())
        }
    }

    @Test fun theGreenKeyTogglesFavoritesOnTheSelection() {
        val w = EditorWorld().open("g:news").k(BoxAction.DOWN, BoxAction.DOWN) // n3 is a favorite
        w.k(BoxAction.FAVOURITES)
        assertEquals(EditorWrite.Favorite(w.pid, ChannelTarget.Keys(setOf("n3")), false), w.writes.last())
        w.k(BoxAction.SELECT, BoxAction.UP, BoxAction.UP, BoxAction.SELECT) // n3 + n1: not all favorites → add
        w.k(BoxAction.FAVOURITES)
        val last = w.writes.last()
        assertIs<EditorWrite.Favorite>(last)
        assertTrue(last.on)
        assertEquals(ChannelTarget.Keys(setOf("n1", "n3")), last.target)
    }
}
