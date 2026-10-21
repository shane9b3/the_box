package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-channel-editor.md §3.3 "Write queue (FIFO)": order kept, never merged, bounded at 8, "Saving…". */
class ChannelEditorWriteQueueTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }

    @Test fun theQueueIsFifoAndBounded() {
        val q = EditorWriteQueue<String>(max = 2)
        val a = EditorWrite.SortGroups(1, GroupSort.NAME_AZ)
        val b = EditorWrite.SortGroups(1, GroupSort.PROVIDER)
        assertTrue(q.offer(a, "a")); assertTrue(q.offer(b, "b"))
        assertTrue(q.full); assertFalse(q.offer(a, "c"))
        assertEquals("a", q.complete(a)!!.meta)
        assertEquals("b", q.complete(b)!!.meta)
        assertNull(q.complete(b))
        assertTrue(q.isEmpty())
        // Equal writes are two entries, completed oldest first.
        q.offer(a, "1"); q.offer(a, "2")
        assertEquals("1", q.complete(a)!!.meta)
        assertEquals(1, q.size)
    }

    @Test fun hideThenMoveThenLockAreAllSentInOrder() {
        val w = EditorWorld().open("g:news")
        w.hold += "write"
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.HIDE); w.settle()
        w.k(BoxAction.DOWN, BoxAction.RIGHT); w.runLine(EditorAction.MOVE_TO_GROUP); w.settle(); w.k(BoxAction.SELECT)
        w.k(BoxAction.DOWN, BoxAction.RIGHT); w.runLine(EditorAction.LOCK); w.settle()
        assertEquals(listOf("HideChannels", "MoveChannels", "LockChannels"), w.writes.map { it::class.simpleName })
        assertEquals(3, w.ui.pendingWrites)
        w.release("write")
        assertEquals(0, w.ui.pendingWrites)
        assertEquals(3, w.writes.size, "none dropped or merged")
        assertEquals("N_LOCKED_NO_PIN", w.ui.notice, "the last write's notice")
    }

    @Test fun theNinthWriteIsRefusedWhileEightAreSaving() {
        val w = EditorWorld().open("g:news")
        w.hold += "write"
        repeat(ChannelEditorMachine.WRITE_QUEUE_MAX) { w.k(BoxAction.FAVOURITES) }
        assertEquals(8, w.ui.pendingWrites)
        w.k(BoxAction.FAVOURITES)
        assertEquals(8, w.writes.size)
        assertEquals("N_QUEUE_FULL", w.ui.notice)
        // A drop in move mode is refused too and keeps the block lifted.
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.REORDER); w.settle(); w.k(BoxAction.DOWN, BoxAction.SELECT)
        assertEquals("move", w.layer())
        w.release("write")
        assertEquals(0, w.ui.pendingWrites)
        w.k(BoxAction.SELECT)
        assertEquals("ReorderChannels", w.writes.last()::class.simpleName)
    }

    @Test fun failuresShowTheirReasonAndGoneReloads() {
        val w = EditorWorld().open("g:news")
        for ((f, text) in listOf(
            WriteFailure.STORAGE to "E_WRITE", WriteFailure.BUSY to "E_WRITE_BUSY", WriteFailure.GONE to "E_GONE",
        )) {
            w.writeResult = { WriteResult.Failed(f) }
            val loads = w.channelLoads.size
            w.k(BoxAction.FAVOURITES)
            assertEquals(text, w.ui.notice)
            assertEquals(if (f == WriteFailure.GONE) loads + 1 else loads, w.channelLoads.size, "$f reload")
        }
    }

    @Test fun doneNoticesComeFromTheResult() {
        val w = EditorWorld().open("g:news")
        w.writeResult = { WriteResult.Done(3, skippedByRule = 2) }
        w.k(BoxAction.DOWN, BoxAction.RIGHT); w.runLine(EditorAction.SHOW); w.settle()
        assertEquals("N_SHOWN[3] · N_SKIPPED_RULE[2]", w.ui.notice)
        w.writeResult = { WriteResult.Done(1, numberClash = "ESPN") }
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.SET_NUMBER); w.settle()
        assertEquals(EditorWrite.SetNumber(w.pid, "n2", 555), w.writes.last())
        assertEquals("N_NUMBER_CLASH(555,ESPN)", w.ui.notice)
    }
}
