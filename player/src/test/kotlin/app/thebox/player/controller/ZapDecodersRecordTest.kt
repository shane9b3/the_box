package app.thebox.player.controller

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.ControllerFixture
import app.thebox.player.controller.fakes.FakeExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keep-decoders WP-0: Media3's decoder callbacks reach the zap record (diagnostics screen and log export) and change
 * nothing else: the player journal of a zap is the same with or without them.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZapDecodersRecordTest {
    private val f = ControllerFixture()

    @After fun tearDown() = f.close()

    private fun videoInit(name: String, ms: Long) = f.exo.analytics.toList().forEach { it.onVideoDecoderInitialized(FakeExoPlayer.eventTime(), name, 0L, ms) }
    private fun audioInit(name: String, ms: Long) = f.exo.analytics.toList().forEach { it.onAudioDecoderInitialized(FakeExoPlayer.eventTime(), name, 0L, ms) }
    private fun videoReleased(name: String) = f.exo.analytics.toList().forEach { it.onVideoDecoderReleased(FakeExoPlayer.eventTime(), name) }
    private fun formats(video: androidx.media3.common.Format = FakeExoPlayer.VIDEO, audio: androidx.media3.common.Format = FakeExoPlayer.AUDIO) =
        f.exo.analytics.toList().forEach {
            it.onVideoInputFormatChanged(FakeExoPlayer.eventTime(), video, null)
            it.onAudioInputFormatChanged(FakeExoPlayer.eventTime(), audio, null)
        }

    @Test
    fun eachZapRecordsItsDecoderInitsAndTheOldDecodersRelease() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        formats()
        videoInit("hw.avc", 38)
        audioInit("aac", 4)
        f.advance(300)
        f.play()
        val first = f.events.zaps.single()
        val d1 = first.decoders!!
        assertEquals("hw.avc", d1.videoDecoder)
        assertEquals(38L, d1.videoInitMs)
        assertEquals("aac", d1.audioDecoder)
        assertEquals(4L, d1.audioInitMs)
        assertTrue("prepare to first frame: $d1", d1.prepareToFirstFrameMs!! >= 300)
        assertNull("the first tune stops nothing", d1.stopToPrepareMs)
        assertEquals("the channel kind, no name", "avc 720", d1.videoKind())
        assertEquals("mp4a-latm 2ch", d1.audioKind())
        assertEquals(42L, d1.initTotalMs)

        f.advance(5_000)
        // A ladder-free repair during playback must not count towards the next zap.
        videoInit("late", 99)
        // The resolve hop really suspends here (as Dispatchers.IO does on the device), so tune() returns before prepare.
        f.controller.ioDispatcher = kotlinx.coroutines.Dispatchers.Main
        f.controller.tune(TuneTarget.Channel(CH2))
        assertEquals("not prepared yet", 1, f.exo.prepares)
        // The old decoder's release arrives while the new zap resolves (before its prepare); so does a late init of the
        // old channel, which must not count.
        videoReleased("hw.avc")
        videoInit("late.old", 77)
        f.idle()
        videoInit("hw.avc", 31)
        audioInit("aac", 3)
        f.play()
        val d2 = f.events.zaps.last().decoders!!
        assertEquals(31L, d2.videoInitMs)
        assertEquals(1, d2.videoInits)
        assertEquals(3L, d2.audioInitMs)
        assertNotNull("old decoder released after the zap's stop", d2.videoReleaseMs)
        assertNotNull(d2.stopToPrepareMs)
        assertNotNull(d2.prepareToFirstFrameMs)
    }

    @Test
    fun theCallbacksChangeNoPlayerCall() {
        fun run(fx: ControllerFixture, withEvents: Boolean): List<String> {
            fx.controller.tune(TuneTarget.Channel(CH1))
            fx.idle()
            if (withEvents) fx.exo.analytics.toList().forEach { it.onVideoDecoderInitialized(FakeExoPlayer.eventTime(), "hw.avc", 0L, 40L) }
            fx.play()
            fx.controller.tune(TuneTarget.Channel(CH2))
            fx.idle()
            if (withEvents) fx.exo.analytics.toList().forEach { it.onVideoDecoderReleased(FakeExoPlayer.eventTime(), "hw.avc") }
            fx.play()
            return fx.journal.entries.toList()
        }
        val plain = ControllerFixture()
        try {
            assertEquals(run(plain, withEvents = false), run(f, withEvents = true))
        } finally {
            plain.close()
        }
    }
}
