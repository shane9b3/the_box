package app.thebox.player.controller

import androidx.media3.common.PlaybackException
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.player.api.ChannelPlaybackMemory
import app.thebox.core.sports.hub.ClipFormat
import app.thebox.player.api.ClipTune
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.core.full.TunnelingOff
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.ControllerFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Owner ruling 2026-11-25, docs/decisions/2026-11-25-tunneling-off-per-visit.md) The ladder's tunneling switch-off lasts
 * for the visit: its own restarts and Retry keep it off (no on → stall → off loop), a new tune of the channel tries
 * tunneling again, nothing is saved; the channel options' "Off for this channel" still persists and wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TunnelingVisitTest {
    private val f = ControllerFixture()

    @Before fun tunnelingOn() = f.settings.update { it.copy(tunneling = true) }
    @After fun tearDown() = f.close()

    private fun error() = PlaybackException("e", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    private val tunneled get() = f.playerFactory.bundle!!.trackSelector.parameters.tunnelingEnabled

    private fun tune(id: app.thebox.core.refresh.ChannelIdentity) {
        f.controller.tune(TuneTarget.Channel(id))
        f.idle()
    }

    /** The first failure turns tunneling off (L27/L28), every later one fails the cycle. */
    private fun stallOffThenFail() {
        var used = false
        f.ladder.script = { _, _ -> if (!used) { used = true; LadderDecision.DisableTunnelingAndReprepare } else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.exo.emitError(error())
        f.idle()
    }

    @Test
    fun restartsAndRetryWithinTheVisitStayUntunneled() {
        tune(CH1)
        assertTrue(tunneled)
        stallOffThenFail()
        assertFalse(tunneled)
        assertTrue("not saved", f.memory.rows[CH1]?.tunnelingOff != true)
        // A ladder restart of the same visit: still off, and the ladder is told so.
        f.ladder.script = { _, _ -> LadderDecision.RetrySame(0) }
        f.exo.emitError(error())
        f.idle()
        assertFalse(tunneled)
        assertFalse(f.ladder.seen.last().ctx.tunnelingActive)
        // The error card's Retry is the same visit.
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.exo.emitError(error())
        f.idle()
        assertTrue(f.state is PlaybackState.Error)
        f.controller.retry()
        f.idle()
        assertFalse("Retry keeps it off", tunneled)
        f.exo.emitError(error())
        f.idle()
        assertFalse(f.ladder.seen.last().ctx.tunnelingActive)
    }

    @Test
    fun zappingAwayAndBackTriesTunnelingAgain() {
        tune(CH1)
        stallOffThenFail()
        assertFalse(tunneled)
        tune(CH2)
        assertTrue("another channel follows the setting", tunneled)
        tune(CH1)
        assertTrue("coming back tries again", tunneled)
        f.exo.emitError(error())
        f.idle()
        assertTrue(f.ladder.seen.last().ctx.tunnelingActive)
    }

    @Test
    fun retuningTheSameChannelIsANewVisit() {
        tune(CH1)
        stallOffThenFail()
        tune(CH1)
        assertTrue(tunneled)
    }

    @Test
    fun aClipBetweenEndsTheVisitAndNeverTunnels() {
        tune(CH1)
        stallOffThenFail()
        f.controller.playClip(ClipTune("https://cdn.example.net/pmp4/main.mp4?t=1", ClipFormat.MP4, null, "Recap"))
        f.idle()
        assertFalse("a clip never tunnels", tunneled)
        tune(CH1)
        assertTrue(tunneled)
    }

    @Test
    fun theStatsTellTheVisitFromTheSetting() {
        f.controller.setStatsEnabled(true)
        tune(CH1)
        f.play()
        f.advance(1_500)
        assertNotEquals(TunnelingOff.STALLED, f.controller.stats.value.tunnelingOff)
        stallOffThenFail()
        f.play()
        f.advance(1_500)
        assertEquals(TunnelingOff.STALLED, f.controller.stats.value.tunnelingOff)
        tune(CH2)
        f.play()
        f.advance(1_500)
        assertNotEquals(TunnelingOff.STALLED, f.controller.stats.value.tunnelingOff)
    }

    @Test
    fun theChannelOptionStillPersistsAndWins() {
        f.memory.rows[CH1] = ChannelPlaybackMemory(tunnelingOff = true)
        tune(CH1)
        assertFalse("the user's choice", tunneled)
        f.exo.emitError(error())
        f.idle()
        assertFalse(f.ladder.seen.last().ctx.tunnelingActive)
        tune(CH2)
        tune(CH1)
        assertFalse("still off after coming back", tunneled)
        assertEquals(true, f.memory.rows[CH1]?.tunnelingOff)
        f.controller.setStatsEnabled(true)
        f.play()
        f.advance(1_500)
        assertEquals("(B3) the user's option reads as the setting", TunnelingOff.SETTING, f.controller.stats.value.tunnelingOff)
    }

    /**
     * (Code review 2026-11-26, B2) A channel that stalls tunneled on two visits in a row starts its next visits
     * untunneled for the rest of the process, with no toast and "off for now (stalled before)" in the stats; another
     * channel is unaffected; nothing is saved. (A new controller is a new process: it tries again.)
     */
    @Test
    fun twoStalledVisitsInARowStartTheNextOnesUntunneled() {
        f.controller.setStatsEnabled(true)
        repeat(PlaybackControllerImpl.TUNNELING_STALLED_VISITS) {
            tune(CH1)
            assertTrue("visit ${it + 1} tries", tunneled)
            stallOffThenFail()
            tune(CH2)
        }
        val toasts = f.notices.count { it == PlaybackNotice.TunnelingDisabled }
        tune(CH1)
        assertFalse("damped: starts untunneled", tunneled)
        f.play()
        f.advance(1_500)
        assertEquals(TunnelingOff.STALLED_BEFORE, f.controller.stats.value.tunnelingOff)
        f.exo.emitError(error())
        f.idle()
        assertFalse("the ladder knows", f.ladder.seen.last().ctx.tunnelingActive)
        assertEquals("no toast", toasts, f.notices.count { it == PlaybackNotice.TunnelingDisabled })
        tune(CH2)
        assertTrue("another channel follows the setting", tunneled)
        tune(CH1)
        assertFalse("for the rest of the process", tunneled)
        assertTrue("not saved", f.memory.rows[CH1]?.tunnelingOff != true)
    }

    /** (B2) A visit that played tunneled without a stall in between breaks the run: two in a row are needed. */
    @Test
    fun aGoodVisitInBetweenResetsTheRun() {
        tune(CH1)
        stallOffThenFail()
        tune(CH2)
        tune(CH1)
        f.play() // plays tunneled, no stall
        f.advance(1_000)
        tune(CH2)
        tune(CH1)
        assertTrue(tunneled)
        stallOffThenFail()
        tune(CH2)
        tune(CH1)
        assertTrue("one stalled visit since the good one", tunneled)
    }

    /** (B3) A clip reads "not used for replays". */
    @Test
    fun aClipSaysTunnelingIsNotUsedForReplays() {
        f.controller.setStatsEnabled(true)
        f.controller.playClip(ClipTune("https://cdn.example.net/pmp4/main.mp4?t=1", ClipFormat.MP4, null, "Recap"))
        f.idle()
        f.play()
        f.advance(1_500)
        assertEquals(TunnelingOff.REPLAY, f.controller.stats.value.tunnelingOff)
    }
}
