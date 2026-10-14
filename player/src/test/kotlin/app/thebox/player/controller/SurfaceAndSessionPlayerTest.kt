package app.thebox.player.controller

import android.app.Activity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import app.thebox.player.controller.fakes.FakeExoPlayer
import app.thebox.player.controller.fakes.Journal
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.3 WP-D `SurfacePlayer` / `SessionPlayer`: command sets, surface capture and detach (B3), background refusal (B5). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SurfaceAndSessionPlayerTest {
    private val journal = Journal()
    private val fake = FakeExoPlayer(journal)
    private var exo: ExoPlayer? = null

    @After fun tearDown() { exo?.release() }

    @Test
    fun surfacePlayerExposesOnlyTheSurfaceCommand() {
        val sp = SurfacePlayer(fake.player)
        assertTrue(sp.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
        assertFalse(sp.isCommandAvailable(Player.COMMAND_PREPARE))
        assertFalse(sp.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertEquals(1, sp.availableCommands.size())
        sp.prepare(); sp.stop(); sp.seekTo(5_000); sp.play(); sp.setPlayWhenReady(true); sp.release()
        assertEquals(0, fake.prepares)
        assertEquals(0, fake.stops)
        assertTrue(fake.seeks.isEmpty())
        assertFalse(fake.playWhenReady)
    }

    @Test
    fun surfaceValidityFollowsTheHolderAndTheView() {
        // Code review 2026-10-14, E-S4.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = SurfaceView(activity)
        root.addView(view, ViewGroup.LayoutParams(100, 100))
        val reports = mutableListOf<Boolean>()
        val sp = SurfacePlayer(fake.player, onSurfaceValid = { reports += it })
        sp.setVideoSurfaceView(view)
        sp.holderCallback.surfaceCreated(view.holder)
        assertTrue(sp.surfaceValid)
        sp.holderCallback.surfaceDestroyed(view.holder) // standby: the Activity stopped, the view stays attached
        assertFalse(sp.surfaceValid)
        sp.holderCallback.surfaceCreated(view.holder)
        root.removeView(view) // detached: no surface for the player any more
        assertFalse(sp.surfaceValid)
        assertEquals(listOf(true, false, true, false), reports)
    }

    @Test
    fun surfaceViewIsForwardedCapturedAndClearedWhenItDetaches() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = SurfaceView(activity)
        root.addView(view, ViewGroup.LayoutParams(100, 100))
        var reported: SurfaceView? = null
        val sp = SurfacePlayer(fake.player) { reported = it }

        sp.setVideoSurfaceView(view)
        assertSame(view, fake.surfaceView)
        assertSame(view, sp.currentView)
        assertSame(view, reported)

        root.removeView(view) // Activity destroyed / PlayerSurface left composition
        assertNull(fake.surfaceView)
        assertNull(sp.currentView)
        assertNull(reported)

        root.addView(view) // the same view comes back
        assertSame(view, fake.surfaceView)
    }

    private class Actions(var foreground: Boolean, var archive: Boolean = false) : SessionPlayer.Actions {
        val calls = mutableListOf<String>()
        override fun isForeground() = foreground
        override fun isArchive() = archive
        override fun title() = "News"
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    }

    private fun realPlayer(): ExoPlayer = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build().also { exo = it }

    @Test
    fun sessionAdvertisesPlayAndNextOnlyInTheForeground() {
        val actions = Actions(foreground = true)
        val session = SessionPlayer(realPlayer(), actions)
        assertTrue(session.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(session.isCommandAvailable(Player.COMMAND_STOP))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(session.isCommandAvailable(Player.COMMAND_PREPARE))

        actions.foreground = false
        session.refresh()
        assertFalse(session.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(session.isCommandAvailable(Player.COMMAND_STOP))

        actions.archive = true
        actions.foreground = true
        session.refresh()
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }

    @Test
    fun sessionCommandsGoToTheControllerAndAreRefusedInTheBackground() {
        val actions = Actions(foreground = true)
        val session = SessionPlayer(realPlayer(), actions)
        session.onSeekCommand(Player.COMMAND_SEEK_TO_NEXT, 0)
        session.onSeekCommand(Player.COMMAND_SEEK_TO_PREVIOUS, 0)
        session.play()
        session.pause()
        session.stop()
        assertEquals(listOf("next", "previous", "play", "pause", "stop"), actions.calls)

        actions.calls.clear()
        actions.foreground = false
        session.refresh()
        session.onSeekCommand(Player.COMMAND_SEEK_TO_NEXT, 0)
        session.onSeekCommand(Player.COMMAND_SEEK_TO_PREVIOUS, 0)
        session.onSeekCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, 10) // live: no seek
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun sessionPlaylistHasNoUri() {
        val p = realPlayer()
        p.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri("http://panel.example/live/u/p/1.m3u8").setMediaId("7").build())
        val session = SessionPlayer(p, Actions(foreground = true))
        val item = session.currentMediaItem!!
        assertEquals("7", item.mediaId)
        assertNull(item.localConfiguration)
        assertEquals("News", item.mediaMetadata.title)
    }
}
