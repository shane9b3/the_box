package app.thebox.playback

import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.player.api.ChannelPlaybackMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (Smooth playback, 2026-11-23) The per-channel switches share one flags column; each keeps its own bit. */
class ChannelMemoryFlagsTest {
    @Test
    fun theFrameRateBitIsItsOwn() {
        val both = ChannelPlaybackMemory(tsCompatLevel = 1, knownHdr = true, tunnelingOff = true, afrAlways = true)
        assertEquals(0b110101, RoomChannelMemory.flagsOf(both))
        assertEquals(RoomChannelMemory.AFR_ALWAYS_BIT, RoomChannelMemory.flagsOf(ChannelPlaybackMemory(afrAlways = true)))
        assertEquals(RoomChannelMemory.TUNNELING_OFF_BIT, RoomChannelMemory.flagsOf(ChannelPlaybackMemory(tunnelingOff = true)))
    }

    /**
     * (2026-11-25, docs/decisions/2026-11-25-tunneling-off-per-visit.md) The old tunneling bit was written by the ladder
     * and by the options page alike: it is no longer read (the one-time clear) and never written again; the user's
     * choice has its own bit.
     */
    @Test
    fun theLegacyTunnelingBitIsIgnoredAndDroppedOnTheNextWrite() {
        val legacy = ChannelPrefsEntity(playlistId = 1, stableKey = "a", updatedAt = 0, playbackFlags = RoomChannelMemory.LEGACY_TUNNELING_OFF_BIT or 0b01)
        val m = RoomChannelMemory.toMemory(legacy)
        assertFalse(m.tunnelingOff)
        assertEquals(1, m.tsCompatLevel)
        assertEquals(0b01, RoomChannelMemory.flagsOf(m))
        val user = legacy.copy(playbackFlags = RoomChannelMemory.TUNNELING_OFF_BIT)
        assertTrue(RoomChannelMemory.toMemory(user).tunnelingOff)
    }
}
