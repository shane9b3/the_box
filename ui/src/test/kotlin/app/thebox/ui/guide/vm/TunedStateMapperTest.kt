package app.thebox.ui.guide.vm

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.MiniPhase
import app.thebox.core.model.StableKey
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.BufferingCause
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.ReconnectProgress
import app.thebox.player.api.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/m3-guide.md §3.16.2 table, row by row. */
class TunedStateMapperTest {
    private val id = ChannelIdentity(3, StableKey("xc:42"))
    private val live = NowPlaying(tuneId = 1, identity = id, title = "News", isArchive = false, format = StreamFormat.TS)
    private val archive = live.copy(isArchive = true)

    @Test
    fun idle() {
        val t = TunedStateMapper.map(PlaybackState.Idle, rowId = 99, still = false)
        assertEquals(MiniPhase.IDLE, t.phase)
        assertNull(t.identity)
        assertEquals(-1L, t.rowId)
        assertEquals(0L, t.identityHash)
    }

    @Test
    fun connecting() {
        val t = TunedStateMapper.map(PlaybackState.Connecting(live, ConnectPhase.CONNECTING, 1, 0), 7, false)
        assertEquals(MiniPhase.CONNECTING, t.phase)
        assertEquals(id, t.identity)
        assertEquals(7L, t.rowId)
        assertEquals(GuideChannel.hashOf(id), t.identityHash)
    }

    @Test
    fun bufferingReconnectingCarriesAttempts() {
        val t = TunedStateMapper.map(PlaybackState.Buffering(live, BufferingCause.RECONNECTING, ReconnectProgress(2, 5)), 7, false)
        assertEquals(MiniPhase.RECONNECTING, t.phase)
        assertEquals(2, t.reconnectAttempt)
        assertEquals(5, t.reconnectMax)
    }

    @Test
    fun bufferingOther() {
        assertEquals(MiniPhase.BUFFERING, TunedStateMapper.map(PlaybackState.Buffering(live, BufferingCause.REBUFFER, null), 7, false).phase)
        assertEquals(MiniPhase.BUFFERING, TunedStateMapper.map(PlaybackState.Buffering(live, BufferingCause.RECOVERING, null), 7, false).phase)
    }

    @Test
    fun playingAndPaused() {
        assertEquals(MiniPhase.PLAYING, TunedStateMapper.map(PlaybackState.Playing(live), 7, false).phase)
        assertEquals(MiniPhase.PAUSED, TunedStateMapper.map(PlaybackState.Playing(live, paused = true), 7, false).phase)
    }

    @Test
    fun stalledIsReconnectingWithAttempts() {
        val t = TunedStateMapper.map(PlaybackState.Stalled(live, null, "Reprepare", 1, 3), 7, false)
        assertEquals(MiniPhase.RECONNECTING, t.phase)
        assertEquals(1, t.reconnectAttempt)
        assertEquals(3, t.reconnectMax)
    }

    @Test
    fun stopped() {
        assertEquals(MiniPhase.STOPPED, TunedStateMapper.map(PlaybackState.Stopped(live, StopReason.USER, resumable = true), 7, false).phase)
    }

    @Test
    fun errorCarriesTheCode() {
        val t = TunedStateMapper.map(PlaybackState.Error(live, PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)), 7, false)
        assertEquals(MiniPhase.ERROR, t.phase)
        assertEquals(PlaybackErrorCode.CHANNEL_OFFLINE, t.error)
    }

    @Test
    fun archiveAndStillFlags() {
        val t = TunedStateMapper.map(PlaybackState.Playing(archive), 7, still = true)
        assertTrue(t.isArchive)
        assertTrue(t.still)
        assertFalse(TunedStateMapper.map(PlaybackState.Playing(live), 7, still = false).isArchive)
    }

    /** P-S4 = U-B3: the 10 s live-pause release is still the user's pause; every other stop is STOPPED. */
    @Test
    fun livePauseTimeoutIsPaused() {
        assertEquals(MiniPhase.PAUSED, TunedStateMapper.map(PlaybackState.Stopped(live, StopReason.LIVE_PAUSE_TIMEOUT, resumable = true), 7, false).phase)
        assertEquals(MiniPhase.PAUSED, TunedStateMapper.map(PlaybackState.Stopped(archive, StopReason.LIVE_PAUSE_TIMEOUT, resumable = true), 7, false).phase)
        assertEquals(MiniPhase.STOPPED, TunedStateMapper.map(PlaybackState.Stopped(live, StopReason.LIVE_PAUSE_TIMEOUT, resumable = false), 7, false).phase)
        for (r in StopReason.entries - StopReason.LIVE_PAUSE_TIMEOUT) {
            assertEquals("$r", MiniPhase.STOPPED, TunedStateMapper.map(PlaybackState.Stopped(live, r, resumable = true), 7, false).phase)
        }
    }
}
