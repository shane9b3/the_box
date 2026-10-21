package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/review/2026-10-18-channel-editor-review-core-ui.md: S1, S2 (Changed), S4, S5, S9 on the machine. */
class ChannelEditorReviewFixTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }

    @Test fun s1AGroupsFailureLeavesTheChannelRowsAndOkRetries() {
        val w = EditorWorld().open("g:news")
        w.fail += "loadGroups"
        w.send(ChannelEditorEvent.Changed) // the groups reload fails, the channels reload works
        assertTrue(w.ui.groupsFailed)
        assertNull(w.ui.empty, "the channels pane keeps its rows, with no error over them")
        assertEquals(4, w.ui.channels!!.count)
        // OK in the channels pane retries (no tick on a row the user may not trust), from either pane.
        w.fail -= "loadGroups"
        val loads = w.groupLoads.size
        w.k(BoxAction.SELECT)
        assertEquals(loads + 1, w.groupLoads.size)
        assertEquals(Selection.None, w.ui.channelSelection)
        assertFalse(w.ui.groupsFailed)
        // Now OK ticks again.
        w.k(BoxAction.SELECT)
        assertEquals(1, w.selected())
    }

    @Test fun s1AChannelsFailureShowsELoadOnlyThereAndOkRetriesFromTheGroupsPane() {
        val w = EditorWorld().open("g:news")
        w.fail += "loadChannels"
        w.send(ChannelEditorEvent.Changed)
        assertEquals(EditorText.E_LOAD, w.ui.empty)
        assertNull(w.ui.channels)
        assertFalse(w.ui.groupsFailed)
        w.k(BoxAction.RIGHT) // no rows: no menu
        assertNull(w.ui.menu)
        w.fail -= "loadChannels"
        w.k(BoxAction.LEFT, BoxAction.SELECT) // OK on a group row retries instead of ticking it
        assertEquals(Selection.None, w.ui.groupSelection)
        assertNull(w.ui.empty)
        assertEquals(4, w.ui.channels!!.count)
    }

    @Test fun s2ChangedReloadsBothPanesKeepingTheFocusedKeys() {
        val w = EditorWorld().open("g:news").k(BoxAction.DOWN, BoxAction.DOWN)
        // The library is re-sorted elsewhere: n3 moves to the top.
        w.groups = listOf(EditorWorld.NEWS.copy(channels = EditorWorld.NEWS.channels.sortedByDescending { it.key == "n3" }), EditorWorld.SPORT, EditorWorld.ADULT)
        w.send(ChannelEditorEvent.Changed)
        assertEquals(Triple(EditorList.Group("g:news"), EditorFilter.ALL, "n3"), w.channelLoads.last())
        assertEquals("g:news", w.groupLoads.last().second)
        assertEquals(0, w.ui.channels!!.cursor, "the cursor follows the row")
    }

    @Test fun s4ASummaryFailureClosesTheMenuWithANotice() {
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT)
        w.fail += "summarize"
        w.k(BoxAction.RIGHT)
        assertNull(w.ui.menu)
        assertEquals("E_SUMMARY", w.ui.notice)
        assertNotNull(w.ui.channels, "a count failure is not a list failure")
        assertNull(w.ui.empty)
        // A stale token is ignored.
        w.fail -= "summarize"; w.hold += "summarize"
        w.k(BoxAction.RIGHT)
        w.send(ChannelEditorEvent.SummaryFailed(w.summaries.last().first - 1))
        assertTrue(w.ui.menu!!.loading)
        w.release("summarize")
        assertFalse(w.ui.menu!!.loading)
    }

    @Test fun s5ANullRowReloadsAtMostOncePerList() {
        val w = EditorWorld().open("g:news")
        val loads = w.channelLoads.size
        fun gap() = w.send(ChannelEditorEvent.ChannelRows(w.ui.channels!!.version, 0, listOf(w.channelRow("n1"), null, w.channelRow("n3"), w.channelRow("n4"))))
        gap()
        assertEquals(loads + 1, w.channelLoads.size)
        gap(); gap()
        assertEquals(loads + 1, w.channelLoads.size, "no loop")
        // Another list may reload once again.
        w.k(BoxAction.LEFT, BoxAction.DOWN, BoxAction.UP)
        val again = w.channelLoads.size
        gap()
        assertEquals(again + 1, w.channelLoads.size)
    }

    @Test fun s9AllLockedOutShowsTheNoteAlone() {
        val locked = listOf(EditorWorld.ADULT, EditorWorld.SPORT.copy(channels = listOf(FC("s1", "ESPN", 201, locked = true))))
        val w = EditorWorld(groups = locked, pinSet = true, unlocked = false).open().k(BoxAction.UP) // All channels
        assertEquals("All", w.listName())
        assertEquals(0, w.ui.channels!!.count)
        assertEquals(2, w.ui.lockedOut)
        assertNull(w.ui.empty, "not E_NO_CHANNELS")
        w.k(BoxAction.RIGHT)
        assertEquals(-1, w.ui.channels!!.cursor, "the note row is focused")
        w.k(BoxAction.SELECT)
        assertEquals(1, w.pinRequests)
        // Find whose matches are all locked out.
        val f = EditorWorld(groups = locked, pinSet = true, unlocked = false).open("g:sport")
        f.textAnswer = { "espn" }
        f.k(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.SELECT)
        assertEquals("Find:espn", f.listName())
        assertEquals(1, f.ui.lockedOut)
        assertNull(f.ui.empty, "not E_FIND_NONE")
        // Without locked-out channels the empty texts stay.
        val e = EditorWorld(groups = locked).open("g:sport")
        e.textAnswer = { "zzz" }
        e.k(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.SELECT)
        assertEquals(EditorText.E_FIND_NONE, e.ui.empty)
    }

    @Test fun s8TheGroupSortConfirmHasNoChannelGroupArgs() {
        val w = EditorWorld().open()
        w.k(BoxAction.CONTEXT_MENU); w.runLine(EditorAction.SORT); w.settle(); w.k(BoxAction.SELECT)
        val c = w.ui.confirm!!
        assertTrue(c.pending is EditorWrite.SortGroups)
        assertEquals(emptyList(), c.args)
    }
}
