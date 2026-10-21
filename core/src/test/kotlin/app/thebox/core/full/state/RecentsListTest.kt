package app.thebox.core.full.state

import app.thebox.core.full.PlayerLayer
import app.thebox.core.guide.GuideSource
import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals

/** Owner ruling 2026-10-18: Right in the full-screen player opens the recently watched channels (what is on them now). */
class RecentsListTest {
    @Test fun rightOpensTheRecentlyWatchedList() {
        val w = PlayerWorld(recentEntry = true)
        w.press(BoxAction.RIGHT)
        assertEquals(PlayerLayer.CHANNELS, w.ui.layer)
        assertEquals(GuideSource.Recent, w.ui.channels?.source)
    }

    @Test fun rightFallsBackToTheInfoBarWithoutARecentsSource() {
        val w = PlayerWorld(recentEntry = false)
        w.press(BoxAction.RIGHT)
        assertEquals(PlayerLayer.INFO, w.ui.layer)
    }
}
