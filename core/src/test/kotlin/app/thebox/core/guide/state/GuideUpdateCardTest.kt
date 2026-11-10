package app.thebox.core.guide.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.update.UpdateAction
import app.thebox.core.update.UpdateCardPhase
import app.thebox.core.update.UpdateCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private fun focusedCard(w: GuideWorld) {
        w.send(GuideEvent.Update(card))
        w.advance(3_000)
        assertEquals(0, w.ui.update?.focused)
    }

    /** 2026-11-10 (m5-update-visible-install.md §1): after Update the card stays through every busy phase. */
    @Test
    fun cardStaysThroughDownloadingVerifyingOpening() {
        val w = GuideWorld()
        focusedCard(w)
        w.press(BoxAction.SELECT)
        assertEquals(UpdateAction.UPDATE, w.calls.last("updateAction")!!.args[0])
        for (phase in listOf(UpdateCardPhase.DOWNLOADING, UpdateCardPhase.VERIFYING, UpdateCardPhase.OPENING_INSTALLER)) {
            w.send(GuideEvent.Update(card.copy(phase = phase, progress = if (phase == UpdateCardPhase.DOWNLOADING) 0.4f else null)))
            assertEquals(phase, w.ui.update?.phase)
        }
    }

    /** Review S2: BACK in a busy phase hides the card (Later); OK and arrows stay the guide's. */
    @Test
    fun backInABusyPhaseHidesTheCard() {
        val w = GuideWorld()
        focusedCard(w)
        w.send(GuideEvent.Update(card.copy(phase = UpdateCardPhase.DOWNLOADING, progress = 0.1f)))
        val before = w.calls.names().count { it == "updateAction" }
        w.press(BoxAction.RIGHT)
        w.press(BoxAction.SELECT)
        assertEquals(before, w.calls.names().count { it == "updateAction" }, "no button in a busy phase")
        w.advance(3_000)
        w.press(BoxAction.BACK)
        assertNull(w.ui.update)
        assertEquals(UpdateAction.LATER, w.calls.last("updateAction")!!.args[0])
    }

    /** Review §6: the returning Not installed card has Update focused; the guide does not copy the old focus over it. */
    @Test
    fun notInstalledTakesTheModelsFocus() {
        val w = GuideWorld()
        focusedCard(w)
        w.press(BoxAction.RIGHT)
        w.press(BoxAction.RIGHT)
        assertEquals(2, w.ui.update?.focused)
        w.send(GuideEvent.Update(card.copy(phase = UpdateCardPhase.OPENING_INSTALLER)))
        w.send(GuideEvent.Update(card.copy(phase = UpdateCardPhase.NOT_INSTALLED, focused = 0)))
        assertEquals(0, w.ui.update?.focused)
        // Within the same phase the guide's focus is kept.
        w.press(BoxAction.RIGHT)
        w.send(GuideEvent.Update(card.copy(phase = UpdateCardPhase.NOT_INSTALLED, focused = 0)))
        assertEquals(1, w.ui.update?.focused)
    }
}
