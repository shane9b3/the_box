package app.thebox.player.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.StreamFormat
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.TsCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q-1 (docs/design/m2-playback-core.md §5.13): re-enabling the video track of a live TS session (still → FULL, the HDR
 * poster ending, the standby grace's surface coming back) must continue from the buffered keyframe, not reset the
 * period. Before the fix the disabled video queue was discarded up to the playback position without regard to
 * keyframes, so `ProgressiveMediaPeriod.selectTracks` could not seek inside it and called `seekToUs`, which for an
 * unseekable live source means position 0: the load was cancelled, the connection re-opened, the buffer dropped and the
 * position jumped (5,975 → 0 → 7,077 ms on the emulator).
 *
 * Real `ExoPlayer` (Robolectric) on the 2 s fixture (IDR at 0 and 1 s) served once, then stalled like a live body.
 * Shadow audio never advances (S14), so audio is dropped and a dead ID3 PID keeps one track enabled: the standalone
 * clock then plays while the video track is off. The extra track is needed because with every track disabled
 * `ProgressiveMediaPeriod.selectTracks` always resets (lines 375–377), which would mask the bug. A mechanism test, not a
 * buffer-accounting one (start threshold 0, 1 s GOP, no byte-cap pressure: the long-GOP cap case is an M6 item, §5.13).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisabledVideoTrackTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(/* isAutoAdvancing = */ true)
    private var bundle: PlayerBundle? = null

    @After fun tearDown() {
        bundle?.player?.release()
    }

    private class Recorder : AnalyticsListener {
        val discontinuities = mutableListOf<String>()
        var loadsStarted = 0
        var loadsCanceled = 0
        @Volatile var firstFrames = 0
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            firstFrames++
        }
        override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            discontinuities += "reason=$reason ${oldPosition.positionMs}->${newPosition.positionMs}"
        }
        override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, retryCount: Int) {
            loadsStarted++
        }
        override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            loadsCanceled++
        }
    }

    @Test
    fun loadControlRetainsTheBackBufferFromTheKeyframe() {
        val b = EngineTestSupport.bundle(context, clock).also { bundle = it }
        val alc = b.loadControl as AdaptiveLoadControl
        assertEquals("no back buffer by duration (S4)", 0L, alc.getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue("Q-1: the disabled video queue keeps its last keyframe", alc.retainBackBufferFromKeyframe(PlayerId.UNSET))
        assertTrue("the delegate agrees (review: no mismatch inviting a revert)", alc.delegate.retainBackBufferFromKeyframe(PlayerId.UNSET))
        assertEquals(0L, alc.delegate.getBackBufferDurationUs(PlayerId.UNSET))
    }

    @Test
    fun reEnablingTheVideoTrackContinuesFromTheBufferedKeyframe() {
        val b = EngineTestSupport.bundle(context, clock).also { bundle = it }
        val p = b.player
        val rec = Recorder().also { p.addAnalyticsListener(it) }
        // 0x15: ID3 in PES (Id3Reader), a dead PID: its format is known from the PMT, so prepare is not blocked.
        val ts = EngineTestSupport.withExtraPmtEntries(EngineTestSupport.fixture("h264_aac_2s.ts"), listOf(0x15 to 0x335))
        val opened = mutableListOf<EngineTestSupport.StallingDataSource>()
        val transport = EngineTestSupport.FakeTransport(DataSource.Factory { EngineTestSupport.StallingDataSource(ts).also { synchronized(opened) { opened += it } } })
        val built = EngineTestSupport.mediaSources().build(EngineTestSupport.context(), StreamFormat.TS, TsCompat.DEFAULT, transport, null)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        // The ID3 track never buffers a sample, so the buffered duration stays 0: start at once.
        (b.loadControl as AdaptiveLoadControl).let { it.profile = it.profile.copy(startMs = 0, afterRebufferMs = 0) }
        // The ID3 track is not selected by default: select it and turn video off (still mode / HDR poster from the tune)
        // as soon as the tracks are known, before playback starts.
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val id3 = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_METADATA } ?: return
                p.removeListener(this)
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(id3.mediaTrackGroup, 0))
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            }
        })
        p.setMediaSource(built.source)
        p.prepare()
        p.play()
        // Past the second IDR (1.0 s): the queue's first retained video sample must be that keyframe, not a P-frame.
        TestPlayerRunHelper.playUntilPosition(p, 0, 1_400)
        assertTrue("metadata track keeps the period loading", p.currentTracks.isTypeSelected(C.TRACK_TYPE_METADATA))
        assertEquals("video off", false, p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
        val before = p.currentPosition
        val loadsBefore = rec.loadsStarted
        val discontinuitiesBefore = rec.discontinuities.size
        val framesBefore = rec.firstFrames

        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        val t = clock.elapsedRealtime()
        TestPlayerRunHelper.advance(p).untilBackgroundThreadCondition { rec.firstFrames > framesBefore || clock.elapsedRealtime() - t > 3_000 }

        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(p)
        assertEquals("no period reset: ${rec.discontinuities}", discontinuitiesBefore, rec.discontinuities.size)
        assertEquals("no load restart", loadsBefore, rec.loadsStarted)
        assertEquals("no load cancelled", 0, rec.loadsCanceled)
        assertEquals("the connection is not re-opened", 1, synchronized(opened) { opened.size })
        assertTrue("position kept (${p.currentPosition} after $before)", p.currentPosition >= before)
        assertTrue("video on", p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
        assertTrue("stays READY", p.playbackState == Player.STATE_READY)
        assertEquals("a frame from the retained keyframe", framesBefore + 1, rec.firstFrames)
        assertNull(p.playerError)
    }
}
