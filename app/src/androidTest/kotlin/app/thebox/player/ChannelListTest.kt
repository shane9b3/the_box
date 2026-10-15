package app.thebox.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.full.ListColumn
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.core.guide.ScreenMode
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-3 (channel list paging over `--synthetic 2000`), I-M4-4 (groups column and CH±), I-M4-5 (Previous row and the
 * quick bar's Previous channel), docs/design/m4-player-ux.md §3.3, §3.4.1, §5.5.
 * Panel: synthetic 2000, defaults. Depends on WP-A (ChannelListModel), WP-C (ProgrammeFeed.nowFor), WP-D (list rows cache,
 * ChannelNowCache; rows are read from `PlayerViewModel.listRows` / `nowOf`).
 */
@RunWith(AndroidJUnit4::class)
class ChannelListTest : PlayerTestBase() {

    /** I-M4-3: OK opens on the tuned row; 200 × DOWN at 25 Hz: no pending row 300 ms after; "now" titles; RIGHT tunes, panel stays; OK closes. */
    @Test
    fun pagingOver2000() {
        val ch = fullOn(1)
        awaitController("playing") { it is PlaybackState.Playing }
        toBareVideo()
        press(K_OK)
        val open = awaitPlayer("channel list open") { it.layer == PlayerLayer.CHANNELS && it.channels?.column == ListColumn.CHANNELS }.channels!! // WP-A behaviour
        val vm = playerVm()
        val tunedIndex = main { vm.listRows.indexOf(ch.rowId) }
        assertEquals("list opens on the tuned row", tunedIndex, open.focused) // WP-A behaviour
        press(K_DOWN, times = 200, intervalMs = 40)
        Thread.sleep(300)
        val st = pui().channels!!
        val pending = main { (st.top until minOf(st.top + 10, st.size)).filter { vm.listRows.channel(it) == null } }
        log("I-M4-3 after 200 DOWN: focused=${st.focused} top=${st.top} pending=$pending")
        assertTrue("no PENDING row visible 300 ms after the last key: $pending", pending.isEmpty()) // WP-D behaviour
        assertTrue("cursor moved ~200 rows (${st.focused})", st.focused >= 150) // WP-A behaviour (accelerated holds do not apply to separate presses)
        Thread.sleep(1_000)
        val withNow = main { (st.top until minOf(st.top + 10, st.size)).count { i -> vm.listRows.channel(i)?.let { vm.nowOf(it) } != null } }
        assertTrue("\"now\" titles present for the visible rows ($withNow of 10)", withNow >= 5) // WP-C/WP-D behaviour (5 % of channels have no EPG)
        val focusedRow = main { vm.listRows.channel(pui().channels!!.focused) }!!
        press(K_RIGHT)
        awaitController("RIGHT tunes the focused row", 15_000) { it is PlaybackState.Playing && it.target.identity == focusedRow.identity } // WP-A behaviour
        assertEquals("the panel stays open after RIGHT", PlayerLayer.CHANNELS, pui().layer) // WP-A behaviour
        press(K_OK)
        awaitPlayer("OK on the tuned row closes the list") { it.layer == PlayerLayer.NONE } // WP-A behaviour
    }

    /** I-M4-4: LEFT → groups; OK on another group reloads the list; the guide's source is unchanged after Back; CH± step groups. */
    @Test
    fun groupsColumn() {
        fullOn(1)
        val guideSource = ui().sourceLabel
        toBareVideo()
        press(K_OK)
        awaitPlayer("channel list") { it.layer == PlayerLayer.CHANNELS }
        press(K_LEFT)
        val g = awaitPlayer("groups column") { it.channels?.column == ListColumn.GROUPS }.channels!! // WP-A behaviour
        val src0 = g.source
        press(K_DOWN)
        awaitPlayer("group focus moved") { it.channels?.groupFocused == g.groupFocused + 1 }
        press(K_OK)
        val after = awaitPlayer("another group applied", 5_000) { it.channels?.column == ListColumn.CHANNELS && it.channels?.source != src0 }.channels!! // WP-A/WP-D behaviour
        log("I-M4-4 source $src0 → ${after.source}, size ${after.size}")
        press(K_LEFT)
        awaitPlayer("groups again") { it.channels?.column == ListColumn.GROUPS }
        val s1 = pui().channels!!.source
        press(K_CH_UP)
        awaitPlayer("CH+ in the groups column applies the next group at once") { it.channels?.source != s1 } // WP-A behaviour (accelerator 2)
        // Back out of the list and FULL: the guide's own source is unchanged.
        toBareVideo()
        press(K_BACK)
        awaitUi("back in the guide", 5_000) { it.mode == ScreenMode.GUIDE }
        assertEquals("the guide's source did not follow the channel list", guideSource, ui().sourceLabel) // WP-A behaviour (§7 item 9)
    }

    /** I-M4-5: tune A, tune B: the list's Previous row shows A and OK tunes A; Up → quick bar → Previous channel tunes B. */
    @Test
    fun previousRowAndQuickBar() {
        val a = fullOn(10)
        awaitController("A playing") { it is PlaybackState.Playing && it.target.identity == a.identity }
        press(K_CH_UP)
        val b = awaitPlayer("zap card for B") { it.info?.channel != null && it.info!!.channel!!.identity != a.identity }.info!!.channel!!
        awaitController("B playing") { it is PlaybackState.Playing && it.target.identity == b.identity }
        toBareVideo()
        press(K_OK)
        val list = awaitPlayer("list with a Previous row") { it.layer == PlayerLayer.CHANNELS && it.channels?.previous != null }.channels!! // WP-A/WP-D behaviour
        assertEquals("Previous row shows A", a.identity, list.previous!!.identity)
        var guard = 0
        while (pui().channels!!.focused != -1 && guard++ < 40) press(K_UP, intervalMs = 60)
        assertEquals("UP from row 0 reaches the Previous row", -1, pui().channels!!.focused) // WP-A behaviour
        press(K_OK)
        awaitController("Previous row tunes A") { it is PlaybackState.Playing && it.target.identity == a.identity } // WP-A behaviour
        toBareVideo()
        openQuickBar()
        quickSelect(QuickItem.PREVIOUS_CHANNEL)
        awaitController("quick bar Previous channel tunes B") { it is PlaybackState.Playing && it.target.identity == b.identity } // WP-A behaviour
        assertNotEquals(a.identity, b.identity)
    }
}
