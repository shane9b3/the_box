package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-channel-editor.md §2.6 "Parental": E7, the locked-out note row in All and Find, the PIN for Unlock,
 * Reset and a move out of a locked group (never for Lock), a refused PIN writes nothing, REFUSED shows the same text.
 */
class ChannelEditorParentalTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }
    private fun locked() = EditorWorld(pinSet = true, unlocked = false)

    @Test fun aLockedGroupShowsE7UntilThePinIsEntered() {
        val w = locked().open("g:adult")
        assertTrue(w.ui.lockedList)
        assertEquals(EditorText.E_LOCKED, w.ui.empty)
        assertTrue(w.channelRowLoads.isEmpty(), "nothing listed, nothing read")
        w.k(BoxAction.SELECT)
        assertEquals(1, w.pinRequests)
        assertFalse(w.ui.lockedList)
        assertEquals(1, w.ui.channels!!.count)
        assertEquals(null, w.ui.empty)
    }

    @Test fun aRefusedPinLeavesE7() {
        val w = locked().open("g:adult")
        w.pinGrants = false
        w.k(BoxAction.SELECT)
        assertTrue(w.ui.lockedList)
        assertEquals(null, w.ui.notice)
    }

    @Test fun theLockedOutNoteHeadsAllAndFind() {
        val w = locked().open().k(BoxAction.UP) // All channels
        assertEquals("All", w.listName())
        assertEquals(2, w.ui.lockedOut, "ESPN (own lock) and Late (Adult)")
        assertEquals(5, w.ui.channels!!.count)
        assertEquals(-1, w.ui.channels!!.top, "the note row is visible")
        w.k(BoxAction.RIGHT, BoxAction.UP)
        assertEquals(-1, w.ui.channels!!.cursor, "the note row is focusable")
        w.k(BoxAction.SELECT)
        assertEquals(1, w.pinRequests)
        assertEquals(0, w.ui.lockedOut, "unlocked: every channel listed")
        assertEquals(7, w.ui.channels!!.count)
        // Find, locked again.
        val f = locked().open("g:news").k(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.SELECT) // Find "s"
        assertEquals("Find:s", f.listName())
        assertEquals(1, f.ui.lockedOut, "ESPN")
        assertEquals(2, f.ui.channels!!.count)
    }

    @Test fun lockNeverAsks() {
        val w = locked().open("g:news")
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.LOCK); w.settle()
        assertEquals(0, w.pinRequests)
        assertEquals(EditorWrite.LockChannels(w.pid, ChannelTarget.Keys(setOf("n1")), true), w.writes.last())
        assertEquals("N_LOCKED[1]", w.ui.notice)
        w.k(BoxAction.LEFT, BoxAction.DOWN, BoxAction.CONTEXT_MENU); w.runLine(EditorAction.LOCK); w.settle()
        assertEquals(0, w.pinRequests)
        assertEquals(EditorWrite.LockGroups(w.pid, GroupTarget.Keys(setOf("g:sport")), true), w.writes.last())
    }

    @Test fun lockWithoutAPinSaysItDoesNotProtect() {
        val w = EditorWorld().open("g:news")
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.LOCK); w.settle()
        assertEquals("N_LOCKED_NO_PIN", w.ui.notice)
    }

    /** The session relocks while a list that shows locked channels is open (its reload is held). */
    private fun relocked(group: String): EditorWorld {
        val w = EditorWorld(pinSet = true, unlocked = true).open(group)
        w.hold += "loadChannels"
        w.unlocked = false
        w.send(ChannelEditorEvent.Access(true, false))
        return w
    }

    @Test fun unlockAsksAndAGrantWrites() {
        val w = relocked("g:sport")
        w.k(BoxAction.RIGHT) // ESPN, locked by itself
        w.runLine(EditorAction.UNLOCK); w.settle()
        assertEquals(1, w.pinRequests)
        assertEquals(EditorWrite.LockChannels(w.pid, ChannelTarget.Keys(setOf("s1")), false), w.writes.last())
    }

    @Test fun aRefusedPinWritesNothingAndSaysWhy() {
        for ((group, action, text) in listOf(
            Triple("g:sport", EditorAction.UNLOCK, "E_PIN_UNLOCK"),
            Triple("g:adult", EditorAction.MOVE_TO_GROUP, "E_PIN_MOVE"),
        )) {
            val w = relocked(group)
            w.pinGrants = false
            w.k(BoxAction.RIGHT); w.runLine(action); w.settle()
            assertEquals(1, w.pinRequests, "$action asks")
            assertTrue(w.writes.isEmpty(), "$action wrote ${w.writes}")
            assertEquals("none", w.layer(), "$action: no picker after a refusal")
            assertEquals(text, w.ui.notice)
        }
        // Reset: confirm first, then the PIN.
        val w = locked().open("g:news")
        w.pinGrants = false
        w.k(BoxAction.RIGHT, BoxAction.ZAP_DOWN, BoxAction.SELECT)
        assertEquals(0, w.pinRequests, "the confirm card comes first")
        w.k(BoxAction.LEFT, BoxAction.SELECT)
        assertEquals(1, w.pinRequests)
        assertTrue(w.writes.isEmpty())
        assertEquals("E_PIN_RESET", w.ui.notice)
        // Group Unlock.
        val g = locked().open()
        g.pinGrants = false
        g.k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.CONTEXT_MENU); g.runLine(EditorAction.UNLOCK); g.settle()
        assertEquals(1, g.pinRequests)
        assertTrue(g.writes.isEmpty())
        assertEquals("E_PIN_UNLOCK", g.ui.notice)
    }

    @Test fun aMoveOutOfALockedGroupAsksThenOpensThePicker() {
        val w = relocked("g:adult")
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.MOVE_TO_GROUP); w.settle()
        assertEquals(1, w.pinRequests)
        assertEquals("picker", w.layer())
        // A move out of an unlocked group does not ask.
        val n = locked().open("g:news")
        n.k(BoxAction.RIGHT); n.runLine(EditorAction.MOVE_TO_GROUP); n.settle()
        assertEquals(0, n.pinRequests)
        assertEquals("picker", n.layer())
    }

    @Test fun anOpenSessionNeverAsks() {
        val w = EditorWorld(pinSet = true, unlocked = true).open("g:sport")
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.UNLOCK); w.settle()
        w.k(BoxAction.RIGHT, BoxAction.ZAP_DOWN, BoxAction.SELECT, BoxAction.LEFT, BoxAction.SELECT)
        assertEquals(0, w.pinRequests)
        assertEquals(listOf("LockChannels", "ResetChannels"), w.writes.map { it::class.simpleName })
    }

    @Test fun refusedWritesShowTheActionsPinText() {
        val w = EditorWorld(pinSet = true, unlocked = true).open("g:sport")
        w.writeResult = { WriteResult.Failed(WriteFailure.REFUSED) }
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.UNLOCK); w.settle()
        assertEquals("E_PIN_UNLOCK", w.ui.notice)
        w.k(BoxAction.RIGHT, BoxAction.ZAP_DOWN, BoxAction.SELECT, BoxAction.LEFT, BoxAction.SELECT)
        assertEquals("E_PIN_RESET", w.ui.notice)
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.MOVE_TO_GROUP); w.settle(); w.k(BoxAction.SELECT)
        assertEquals("E_PIN_MOVE", w.ui.notice)
        assertEquals(1, w.channelLoads.size, "a refusal changes nothing, so nothing is reloaded")
    }

    @Test fun anAccessChangeReloadsBothPanes() {
        val w = EditorWorld(pinSet = true, unlocked = true).open("g:news")
        val c = w.channelLoads.size
        val g = w.groupLoads.size
        w.send(ChannelEditorEvent.Access(true, true))
        assertEquals(c, w.channelLoads.size, "no change, no reload")
        w.unlocked = false
        w.send(ChannelEditorEvent.Access(true, false))
        assertEquals(c + 1, w.channelLoads.size)
        assertEquals(g + 1, w.groupLoads.size)
    }
}
