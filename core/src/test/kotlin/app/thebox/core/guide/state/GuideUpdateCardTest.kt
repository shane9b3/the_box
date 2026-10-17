package app.thebox.core.guide.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.update.UpdateAction
import app.thebox.core.update.UpdateCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** C-S2 (M5 code review 2026-10-16, §3.12.3): the update card takes keys only while focused, after 3 s of idle. */
class GuideUpdateCardTest {
    private val card = UpdateCardState("1.0.4", "Fixes")

    /** C-S2 (adopted from the core reviewer): OK on a programme while browsing never presses the card's Update. */
    @Test
    fun updateCardDoesNotStealKeysFromABrowsingUser() {
        val w = GuideWorld()
        w.press(BoxAction.DOWN) // browsing
        w.send(GuideEvent.Update(card))
        val before = w.calls.names().count { it == "updateAction" }
        w.press(BoxAction.SELECT) // the user meant the focused programme
        assertEquals(before, w.calls.names().count { it == "updateAction" }, "OK while browsing pressed the update card's Update")
    }

    /** C-S2: the card shows unfocused (no button lit) until 3 s pass with no key; a key restarts the wait. */
    @Test
    fun focusedOnlyAfterThreeIdleSeconds() {
        val w = GuideWorld()
        w.send(GuideEvent.Update(card))
        assertEquals(-1, assertNotNull(w.ui.update).focused, "unfocused when it appears")
        w.advance(2_000)
        w.press(BoxAction.DOWN)
        w.advance(2_000)
        assertEquals(-1, w.ui.update?.focused, "a key restarted the wait")
        kotlin.test.assertTrue(w.machine.nextDeadlineMs.value <= w.nowMs + 1_000, "a deadline for the focus")
        w.advance(1_000)
        assertEquals(0, w.ui.update?.focused, "focused on Update after 3 s idle")
        w.press(BoxAction.RIGHT)
        assertEquals(1, w.ui.update?.focused)
        w.press(BoxAction.SELECT)
        assertEquals(UpdateAction.LATER, w.calls.last("updateAction")!!.args[0])
    }
}
