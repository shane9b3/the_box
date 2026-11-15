package app.thebox.core.full.state

import app.thebox.core.full.PlayerLayer
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Owner report 2026-11-14 (docs/decisions/2026-11-14-guide-opens-on-playing-channel.md), composed machines: a channel
 * tuned from the player's channel list in another group is "tuned from" that group, so the guide entered from FULL
 * opens on it when the group the guide shows does not hold the channel.
 */
class ListTuneGuideGroupTest {
    @Test fun aListTuneMakesItsGroupTheTunedFromGroup() {
        val probe = PlayerWorld(withGuide = true)
        val w = PlayerWorld(withGuide = true, members = mapOf(probe.favSource to setOf(0), probe.newsSource to setOf(3, 4)))
        // The guide on Favourites (channel 0 only), back in FULL on channel 0.
        w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.gui.mode)
        w.press(FAVOURITES)
        assertEquals(w.favSource, w.gx.last("loadLineup")!!.args[0])
        w.press(PLAY)
        assertEquals(ScreenMode.FULL, w.gui.mode)
        // The channel list: Groups column → News → its first row (channel 3) → OK tunes it.
        w.press(SELECT)
        assertEquals(PlayerLayer.CHANNELS, w.ui.layer)
        w.press(LEFT)
        repeat(w.entries.indexOfFirst { it.source == w.newsSource }) { w.press(DOWN) }
        w.press(SELECT)
        w.press(SELECT)
        assertEquals(w.channels[3].identity, w.tunedChannel?.identity)
        // Back closes the list, Back returns to the guide: News (the list's group), not All (the startup source).
        while (w.gui.mode == ScreenMode.FULL) w.press(BACK)
        assertEquals(w.newsSource, w.gx.last("loadLineup")!!.args[0])
        val f = w.guide!!.frame.focus!!
        assertEquals(w.channels[3], w.guide.frame.rows.channel(f.row))
    }

    /**
     * A list tune the guard refuses (nothing plays) does not make its group the tuned-from group: channel 0, tuned from
     * All in the grid, stays bound to All although News holds it too.
     */
    @Test fun aRefusedListTuneSetsNoTunedFromGroup() {
        val probe = PlayerWorld(withGuide = true)
        val w = PlayerWorld(withGuide = true, members = mapOf(probe.favSource to setOf(1), probe.newsSource to setOf(0, 3, 4)))
        w.refused += w.channels[3].identityHash
        w.press(BACK)
        w.press(FAVOURITES)
        w.press(PLAY)
        w.press(SELECT)
        w.press(LEFT)
        repeat(w.entries.indexOfFirst { it.source == w.newsSource }) { w.press(DOWN) }
        w.press(SELECT) // News: rows 0 (channel 0, tuned), 3, 4
        w.press(DOWN)
        w.press(SELECT) // channel 3: refused
        assertEquals(w.channels[3], w.fx.last("tune")!!.args[0], "the list asked for channel 3")
        assertEquals(w.channels[0].identity, w.tunedChannel?.identity, "refused: channel 0 still plays")
        while (w.gui.mode == ScreenMode.FULL) w.press(BACK)
        assertEquals(w.allSource, w.gx.last("loadLineup")!!.args[0], "All (its group), not News (the refused list tune's)")
        val f = w.guide!!.frame.focus!!
        assertEquals(w.channels[0], w.guide.frame.rows.channel(f.row))
    }
}
