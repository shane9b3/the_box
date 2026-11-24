package app.thebox.playback

import app.thebox.player.api.ChannelPlaybackMemory
import org.junit.Assert.assertEquals
import org.junit.Test

/** (Smooth playback, 2026-11-23) The per-channel switches share one flags column; each keeps its own bit. */
class ChannelMemoryFlagsTest {
    @Test
    fun theFrameRateBitIsItsOwn() {
        val both = ChannelPlaybackMemory(tsCompatLevel = 1, knownHdr = true, tunnelingOff = true, afrAlways = true)
        assertEquals(0b11101, RoomChannelMemory.flagsOf(both))
        assertEquals(RoomChannelMemory.AFR_ALWAYS_BIT, RoomChannelMemory.flagsOf(ChannelPlaybackMemory(afrAlways = true)))
        assertEquals(RoomChannelMemory.TUNNELING_OFF_BIT, RoomChannelMemory.flagsOf(ChannelPlaybackMemory(tunnelingOff = true)))
    }
}
