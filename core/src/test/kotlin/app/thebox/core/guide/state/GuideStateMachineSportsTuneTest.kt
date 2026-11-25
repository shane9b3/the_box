package app.thebox.core.guide.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import app.thebox.core.input.BoxAction.BACK
import app.thebox.core.input.BoxAction.RIGHT
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.input.BoxAction.UP
import app.thebox.core.playback.PlaybackErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/design/m6-sports-hub.md §5.5, §5.6, §10.2 `GuideStateMachineSportsTuneTest`: `GuideEvent.SportsClosed` is
 * handled as `SearchClosed` handles a tuned channel; the BACK path out of Sports is unchanged.
 */
class GuideStateMachineSportsTuneTest {
    private fun openSports(w: GuideWorld) {
        w.press(UP); w.press(UP); w.press(UP); w.press(RIGHT); w.press(RIGHT); w.press(SELECT)
        assertEquals(ScreenMode.SPORTS, w.ui.mode)
    }

    @Test fun tunedPickFocusesTheChannelAndGoesFullOnItsFirstTuned() {
        val w = GuideWorld()
        openSports(w)
        w.send(GuideEvent.SportsClosed(w.channels[6], tuned = true, archive = false))
        assertEquals(ScreenMode.GUIDE, w.ui.mode, "FULL waits for the picked channel's own Tuned")
        assertEquals(Zone.GRID, w.ui.zone)
        assertEquals(6, w.machine.frame.focus!!.row)
        w.tuneTo(w.channels[0]); w.flush() // the old channel keeps updating: no FULL
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.tuneTo(w.channels[6], MiniPhase.CONNECTING); w.flush()
        assertEquals(ScreenMode.FULL, w.ui.mode)
    }

    @Test fun pickingTheChannelAlreadyPlayingGoesFullAtOnce() {
        val w = GuideWorld()
        w.tuneTo(w.channels[4]); w.flush()
        openSports(w)
        w.send(GuideEvent.SportsClosed(w.channels[4], tuned = true, archive = false))
        assertEquals(ScreenMode.FULL, w.ui.mode)
    }

    /** A refused tune (PIN prompt) never brings the channel's Tuned: the page closes into the guide and stays there. */
    @Test fun refusedTuneStaysInTheGuide() {
        val w = GuideWorld()
        openSports(w)
        w.send(GuideEvent.SportsClosed(w.channels[3], tuned = true, archive = false))
        w.idle() // the guard refused: the controller never tuned, the view model shows nothing for it
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertEquals(3, w.machine.frame.focus!!.row)
        // An error of the picked channel ends the wait; a later unrelated Tuned does not go FULL.
        w.tuneTo(w.channels[3], MiniPhase.ERROR, error = PlaybackErrorCode.STREAM_UNDECODABLE); w.flush()
        w.tuneTo(w.channels[3], MiniPhase.PLAYING); w.flush()
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
    }

    @Test fun archivePickGoesFullOnlyWhenTheArchivePlays() {
        val w = GuideWorld()
        w.tuneTo(w.channels[0]); w.flush()
        openSports(w)
        w.send(GuideEvent.SportsClosed(w.channels[3], tuned = true, archive = true))
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.tuneTo(w.channels[3], MiniPhase.PLAYING, archive = false); w.flush() // live, not the catch-up
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.tuneTo(w.channels[3], MiniPhase.ERROR, archive = true, error = PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE); w.flush()
        assertEquals(ScreenMode.GUIDE, w.ui.mode)

        val ok = GuideWorld()
        ok.tuneTo(ok.channels[0]); ok.flush()
        openSports(ok)
        ok.send(GuideEvent.SportsClosed(ok.channels[3], tuned = true, archive = true))
        ok.tuneTo(ok.channels[3], MiniPhase.CONNECTING, archive = true); ok.flush()
        assertEquals(ScreenMode.FULL, ok.ui.mode)
    }

    @Test fun backPathIsUnchanged() {
        val w = GuideWorld()
        w.tuneTo(w.channels[2]); w.flush()
        openSports(w)
        w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertEquals(Zone.GRID, w.ui.zone)
        w.tuneTo(w.channels[2]); w.flush()
        assertEquals(ScreenMode.GUIDE, w.ui.mode, "BACK arms no FULL follow")
    }

    /** A picked channel the current lineup does not hold opens All channels of its playlist on that row. */
    @Test fun channelOutsideTheLineupOpensItsPlaylist() {
        val w = GuideWorld()
        openSports(w)
        val outsider = w.channels[5].copy(rowId = 9_999)
        w.send(GuideEvent.SportsClosed(outsider, tuned = true, archive = false))
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertEquals("loadLineup", w.calls.calls.last { it.name == "loadLineup" }.name)
    }
}
