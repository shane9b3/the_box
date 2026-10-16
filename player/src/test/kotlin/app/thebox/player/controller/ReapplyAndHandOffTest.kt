package app.thebox.player.controller

import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.ControllerFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (M5, docs/design/m5-setup-settings.md §0.6, §2.12 item 6, S9) `reapplySettings()`: one re-prepare of the live tune with
 * the new settings, same engine and cycle, and no-op cases; `handOffExternal()` (§3.3.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReapplyAndHandOffTest {
    private val f = ControllerFixture()

    @After fun tearDown() = f.close()

    private fun reapplies() = f.events.events.count { it.type == PlaybackEventType.DECISION && it.code == "reapply_settings" }

    private fun playing() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
    }

    @Test
    fun reapplyReopensTheLiveTuneOnceWithTheNewSettings() {
        playing()
        val builds = f.sources.builds.size
        val tuneId = f.state.target!!.tuneId
        f.settings.update { it.copy(bufferMode = BufferMode.LARGE, tunneling = true) }
        f.controller.reapplySettings()
        f.idle()
        assertEquals(1, reapplies())
        assertEquals("one new session", builds + 1, f.sources.builds.size)
        assertEquals("never a new engine", 1, f.playerFactory.creates)
        assertEquals("the same cycle: no new TUNE event", 1, f.events.events.count { it.type == PlaybackEventType.TUNE })
        assertTrue(f.state.target!!.tuneId > tuneId)
        assertTrue(f.playerFactory.bundle!!.trackSelector.parameters.tunnelingEnabled)
        assertTrue("shown as recovering, not a fresh connect", f.state is PlaybackState.Buffering)
    }

    @Test
    fun reapplyIsANoOpWhenNothingPlays() {
        f.controller.reapplySettings()
        f.idle()
        assertEquals(0, reapplies())
        assertEquals(PlaybackState.Idle, f.state)
    }

    @Test
    fun reapplyIsANoOpAfterAStop() {
        playing()
        f.controller.stop(StopReason.USER)
        val builds = f.sources.builds.size
        f.controller.reapplySettings()
        f.idle()
        assertEquals(0, reapplies())
        assertEquals(builds, f.sources.builds.size)
    }

    @Test
    fun reapplyIsANoOpWhilePaused() {
        playing()
        f.controller.pause()
        val builds = f.sources.builds.size
        f.controller.reapplySettings()
        f.idle()
        assertEquals(builds, f.sources.builds.size)
    }

    @Test
    fun handOffReturnsTheResolvedStreamAndStopsOurs() {
        playing()
        val handOff = assertNotNullAndGet(f.controller.handOffExternal())
        assertEquals("http://panel.example:8080/live/u/p/1.ts", handOff.url)
        assertEquals("TheBox", handOff.headers["User-Agent"])
        assertFalse("toString keeps the host only", handOff.toString().contains("/live/u/p"))
        val st = f.state as PlaybackState.Stopped
        assertEquals(StopReason.USER, st.reason)
        assertEquals("the connection is released", 1, f.transport.ended.size)
    }

    @Test
    fun handOffWithNothingTunedIsNull() {
        assertNull(f.controller.handOffExternal())
    }

    private fun <T> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
