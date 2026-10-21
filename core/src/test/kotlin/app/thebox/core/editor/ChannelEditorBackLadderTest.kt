package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-channel-editor.md §2.5: each Back takes the first rung that applies, in order. */
class ChannelEditorBackLadderTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }
    private fun EditorWorld.back(): Boolean = press(BoxAction.BACK).also { settle() }

    @Test fun theDeepestStateLeavesInFiveBacks() {
        // A picker over a selection in Find results.
        val w = EditorWorld().open("g:news")
        w.k(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.SELECT) // Find "s" → Results focused
        assertEquals("Find:s", w.listName())
        w.k(BoxAction.RIGHT, BoxAction.SELECT, BoxAction.CONTEXT_MENU)
        w.runLine(EditorAction.MOVE_TO_GROUP); w.settle()
        assertEquals("picker", w.layer())
        // 1. the layer
        assertTrue(w.back()); assertEquals("none", w.layer()); assertEquals(1, w.selected())
        // 4. the selection (rungs 2–3 do not apply)
        assertTrue(w.back()); assertEquals(0, w.selected()); assertEquals(EditorPane.CHANNELS, w.ui.pane)
        assertTrue(w.ui.notice!!.startsWith("N_UNSELECTED[1]"))
        // 5. the channels pane
        assertTrue(w.back()); assertEquals(EditorPane.GROUPS, w.ui.pane)
        // 7. Find results focused: dropped
        assertTrue(w.back()); assertEquals(1, w.ui.groups.head.size); assertEquals("All", w.listName())
        // 8. leave
        assertFalse(w.back())
    }

    @Test fun moveModeThenToolbarThenGroupTicks() {
        val w = EditorWorld().open()
        w.k(BoxAction.DOWN, BoxAction.SELECT, BoxAction.UP, BoxAction.SELECT) // tick Sport, then News
        assertEquals(2, w.groupSelected())
        w.k(BoxAction.CONTEXT_MENU); w.runLine(EditorAction.REORDER); w.settle()
        assertEquals("move", w.layer())
        assertTrue(w.back()); assertNull(w.ui.move) // 2. cancel move mode
        assertEquals(2, w.groupSelected(), "Back drops modes, not ticks")
        w.k(BoxAction.UP, BoxAction.UP) // to the toolbar
        assertEquals("toolbar", w.layer())
        assertTrue(w.back()); assertEquals("none", w.layer()) // 3. leave the toolbar
        assertTrue(w.back()); assertEquals(0, w.groupSelected()) // 6. clear the group ticks
        assertFalse(w.back()) // 8. leave (no Results row)
    }

    @Test fun everyLayerClosesFirst() {
        for (open in listOf<(EditorWorld) -> Unit>(
            { it.k(BoxAction.RIGHT) }, // menu
            { it.k(BoxAction.RIGHT, BoxAction.ZAP_DOWN, BoxAction.SELECT) }, // confirm
            { it.k(BoxAction.UP, BoxAction.RIGHT, BoxAction.SELECT) }, // choice (over the toolbar)
            { it.k(BoxAction.RIGHT, BoxAction.DOWN, BoxAction.SELECT) }, // picker
            { it.k(BoxAction.RIGHT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT) }, // move
        )) {
            val w = EditorWorld().open("g:news")
            open(w)
            val before = w.layer()
            assertTrue(w.back(), before)
            assertEquals(EditorPane.CHANNELS, w.ui.pane, before)
            assertTrue(w.layer() == "none" || w.layer() == "toolbar", "$before → ${w.layer()}")
        }
    }

    @Test fun nothingIsLostOnBack() {
        // A queued write still lands after the user backs out of the editor.
        val w = EditorWorld().open("g:news")
        w.hold += "write"
        w.k(BoxAction.SELECT, BoxAction.RIGHT, BoxAction.SELECT) // Hide n1
        assertEquals(1, w.ui.pendingWrites)
        @Suppress("ControlFlowWithEmptyBody") while (w.back()) { }
        w.release("write")
        assertEquals(0, w.ui.pendingWrites)
        assertEquals(1, w.writes.size)
    }

    @Test fun backAtTheBottomReturnsFalseEveryTime() {
        val w = EditorWorld().open()
        assertFalse(w.back())
        assertFalse(w.back())
    }
}
