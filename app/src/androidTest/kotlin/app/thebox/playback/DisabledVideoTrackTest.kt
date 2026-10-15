package app.thebox.playback

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.thebox.core.playback.PlaybackEventType
import app.thebox.player.api.PlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Q-1 (docs/design/m2-playback-core.md §5.13): a session with the video track off (viewport HIDDEN: the M3 still mini
 * player and low-memory still mode; the HDR poster in MINI) plays without a rebuffer, its position never goes back, and
 * re-enabling the track (still → FULL, poster → FULL, during a rebuffer, after the standby grace) continues from the
 * buffered keyframe: a first rendered frame within [FIRST_FRAME_MAX_MS] (emulator; the hardware gate is 1.5 s), no
 * load restart, no re-opened connection.
 *
 * Every test records a timeline under the `Q1` logcat tag (an [AnalyticsListener] on the controller's `ExoPlayer`:
 * states, tracks, renderer enable/disable, first frames, loads, discontinuities, audio track and underruns; Media3's
 * `EventLogger` under `Q1EL`; a 200 ms sample of position / buffered / `isLoading`; the controller's STALL, DECISION,
 * BUFFER_LEVEL and FIRST_FRAME events), and a 50 ms position probe for the "never goes back" check.
 *
 * Emulator note (Q-1 finding 1): the ranchu audio HAL drops ~1.5 s of audio ("pcm_write was late reading frames,
 * dropping 22131 us") during the first ~2 s after its output leaves standby, so the AudioTrack clock runs ~1.4× and a
 * paced live stream rebuffers once, with the video track on or off. [warmAudioHal] keeps the HAL out of standby with
 * a silent track so the assertions measure the player; `-e q1cold 1` skips it (the control run).
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DisabledVideoTrackTest : PanelTestBase() {

    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var t0 = 0L
    private var exo: ExoPlayer? = null
    private var listener: AnalyticsListener? = null
    private var eventLogger: EventLogger? = null
    private val rebuffersAt: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val canceledAt: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val discontinuities: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var firstFrames = 0
    @Volatile private var probing = false
    @Volatile private var maxBack = 0L
    @Volatile private var maxBackAt = 0L
    /** Set on `DISCONTINUITY_REASON_REMOVE` (a new tune or re-prepare): the probe starts a new session baseline. */
    @Volatile private var newSession = false
    private var warm: AudioTrack? = null

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun now() = SystemClock.elapsedRealtime() - t0

    private fun rec(what: String) {
        val line = "t=${now()} $what"
        timeline += line
        Log.i(Q1, line)
    }

    /** Keeps the emulator's audio output out of standby (see the class note). */
    private fun warmAudioHal() {
        if (InstrumentationRegistry.getArguments().getString("q1cold") == "1") {
            rec("audio HAL cold (control run)")
            return
        }
        val rate = 48_000
        val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        @Suppress("DEPRECATION")
        val t = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
        warm = t
        t.play()
        Thread {
            val zeros = ByteArray(size / 2)
            while (warm === t) if (t.write(zeros, 0, zeros.size) < 0) break
        }.start()
        Thread.sleep(3_000) // past the HAL's start-up drops
        rec("audio HAL warmed")
    }

    private fun attach() {
        t0 = SystemClock.elapsedRealtime()
        warmAudioHal()
        onMain {
            val p = (controller.surfacePlayer as ForwardingPlayer).wrappedPlayer as ExoPlayer
            exo = p
            val l = object : AnalyticsListener {
                private var last = Player.STATE_IDLE
                override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                    rec("state ${stateName(state)} pos=${p.currentPosition} buf=${p.totalBufferedDuration} loading=${p.isLoading}")
                    if (last == Player.STATE_READY && state == Player.STATE_BUFFERING) rebuffersAt += now()
                    last = state
                }
                override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                    val sel = tracks.groups.joinToString { g -> "${typeName(g.type)}${if (g.isSelected) "*" else ""}" }
                    rec("tracks [$sel] videoDisabled=${C.TRACK_TYPE_VIDEO in p.trackSelectionParameters.disabledTrackTypes}")
                }
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    firstFrames++
                    rec("renderedFirstFrame pos=${p.currentPosition}")
                }
                override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) = rec("videoEnabled")
                override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) =
                    rec("videoDisabled rendered=${decoderCounters.renderedOutputBufferCount} dropped=${decoderCounters.droppedBufferCount}")
                override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) = rec("audioEnabled")
                override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) = rec("audioDisabled")
                override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) = rec("isLoading=$isLoading buf=${p.totalBufferedDuration}")
                override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    val d = "reason=$reason ${oldPosition.positionMs} -> ${newPosition.positionMs}"
                    if (reason == Player.DISCONTINUITY_REASON_REMOVE) newSession = true
                    discontinuities += d
                    rec("discontinuity $d")
                }
                override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
                    rec("audioTrackInitialized enc=${audioTrackConfig.encoding}")
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) = rec("audioPositionAdvancing pos=${p.currentPosition}")
                override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
                    rec("audioUnderrun sinceFeed=$elapsedSinceLastFeedMs")
                override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) =
                    rec("videoFormat ${format.sampleMimeType} ${format.width}x${format.height} transfer=${format.colorInfo?.colorTransfer}")
                override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, retryCount: Int) = rec("loadStarted retry=$retryCount")
                override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    canceledAt += now()
                    rec("loadCanceled bytes=${loadEventInfo.bytesLoaded}")
                }
            }
            listener = l
            p.addAnalyticsListener(l)
            eventLogger = EventLogger("Q1EL").also { p.addAnalyticsListener(it) }
        }
    }

    /**
     * Starts the 50 ms position probe. Call it after this session's first media: the tune itself moves the position from
     * the previous session's value to 0 (`DISCONTINUITY_REASON_REMOVE`), which is not a "going back"; neither is a later
     * new session (the lifecycle's re-tune after a wake).
     */
    private fun startProbe() {
        probing = true
        Thread {
            var last = -1L
            while (probing) {
                val pos = main { exo?.currentPosition ?: 0L }
                if (newSession) {
                    newSession = false
                    last = -1
                }
                if (last >= 0 && last - pos > maxBack) {
                    maxBack = last - pos
                    maxBackAt = now()
                }
                last = pos
                Thread.sleep(50)
            }
        }.start()
    }

    @After
    fun detach() {
        probing = false
        onMain {
            listener?.let { exo?.removeAnalyticsListener(it) }
            eventLogger?.let { exo?.removeAnalyticsListener(it) }
        }
        warm?.let {
            warm = null
            Thread.sleep(50)
            it.stop()
            it.release()
        }
    }

    /** Samples position, buffered and loading every 200 ms for [ms]; returns (elapsed, position) pairs. */
    private fun observe(ms: Long, statsToggleAtMs: Long = -1): List<Pair<Long, Long>> {
        val positions = mutableListOf<Pair<Long, Long>>()
        val start = SystemClock.elapsedRealtime()
        var toggled = false
        while (SystemClock.elapsedRealtime() - start < ms) {
            if (statsToggleAtMs >= 0 && !toggled && SystemClock.elapsedRealtime() - start >= statsToggleAtMs) {
                toggled = true
                onMain { controller.setStatsEnabled(true) }
                rec("stats overlay on")
                Thread.sleep(1_500)
                val st = stats()
                onMain { controller.setStatsEnabled(false) }
                rec("stats overlay off (buffer=${st.bufferMs} a=${st.audioDecoder} v=${st.videoDecoder} level=${st.bufferProfile?.level})")
            }
            val s = main { listOf(exo!!.playbackState.toLong(), exo!!.currentPosition, exo!!.totalBufferedDuration, if (exo!!.isLoading) 1L else 0L) }
            positions += now() to s[1]
            rec("sample ${stateName(s[0].toInt())} pos=${s[1]} buf=${s[2]} loading=${s[3] == 1L}")
            Thread.sleep(200)
        }
        return positions
    }

    private fun firstMediaAt(): Long? = events(PlaybackEventType.FIRST_FRAME).firstOrNull()?.elapsedMs?.let { it - t0 }

    private fun dumpEvents() {
        for (type in listOf(PlaybackEventType.FIRST_FRAME, PlaybackEventType.STALL, PlaybackEventType.DECISION, PlaybackEventType.BUFFER_LEVEL, PlaybackEventType.ERROR)) {
            for (e in events(type)) Log.i(Q1, "event t=${e.elapsedMs - t0} ${e.type} ${e.code} ${e.detail}")
        }
    }

    private fun videoTrackOff(): Boolean = main { C.TRACK_TYPE_VIDEO in exo!!.trackSelectionParameters.disabledTrackTypes && !exo!!.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) }

    /** No rebuffer, no stall, no level change and ≥ 7.5 s of progress in the 10 s after [from]; the position never goes back. */
    private fun assertSmooth(label: String, positions: List<Pair<Long, Long>>, from: Long) {
        dumpEvents()
        val window = from until from + NO_REBUFFER_WINDOW_MS
        val rebuffers = synchronized(rebuffersAt) { rebuffersAt.filter { it in window } }
        val stalls = events(PlaybackEventType.STALL).map { "${it.code} ${it.detail}" }
        val levels = events(PlaybackEventType.BUFFER_LEVEL).map { it.code }
        val inWindow = positions.filter { it.first in window }
        val advanced = if (inWindow.size >= 2) inWindow.last().second - inWindow.first().second else 0
        val span = if (inWindow.size >= 2) inWindow.last().first - inWindow.first().first else 0
        Log.i(Q1, "SUMMARY $label from=$from rebuffers=$rebuffers maxBack=$maxBack@$maxBackAt advanced=$advanced in $span stalls=$stalls levels=$levels canceled=$canceledAt discontinuities=$discontinuities")
        assertTrue("$label: no rebuffer within $NO_REBUFFER_WINDOW_MS ms (at $rebuffers)", rebuffers.isEmpty())
        assertTrue("$label: position never goes back (max back $maxBack ms at $maxBackAt)", maxBack <= MAX_BACK_MS)
        assertTrue("$label: no stall verdict ($stalls)", stalls.isEmpty())
        assertTrue("$label: no buffer level change ($levels)", levels.isEmpty())
        assertTrue("$label: position advanced $advanced ms in $span ms", advanced >= span - 1_000)
    }

    /** Switches the harness to FULL and returns the ms to the next rendered first frame (-1: none within 3 s). */
    private fun toFullAndTimeFirstFrame(label: String): Long {
        val frames = firstFrames
        val canceled = canceledAt.size
        rec("$label: -> full")
        val ok = SystemClock.elapsedRealtime()
        setHarnessViewport("full")
        var ms = -1L
        while (SystemClock.elapsedRealtime() - ok < 3_000) {
            if (firstFrames != frames) {
                ms = SystemClock.elapsedRealtime() - ok
                break
            }
            Thread.sleep(10)
        }
        rec("$label: first rendered frame $ms ms after the switch; loads cancelled since: ${canceledAt.size - canceled}")
        assertEquals("$label: re-enabling the track does not restart the load (cancelled at $canceledAt)", canceled, canceledAt.size)
        return ms
    }

    // ------------------------------------------------------------------ tests

    /** Video track off from the tune (still mini player / low-memory still): HIDDEN at tune time. */
    @Test
    fun hiddenAtTuneDoesNotRebuffer() = hiddenAtTune(statsToggle = false)

    /** Same with the stats overlay toggled 1.5 s after first media (I-16's original probe order). */
    @Test
    fun hiddenAtTuneWithStatsToggleDoesNotRebuffer() = hiddenAtTune(statsToggle = true)

    private fun hiddenAtTune(statsToggle: Boolean) {
        attach()
        launch(ch(1), stats = false, hidden = true)
        awaitFirstMedia(1)
        startProbe()
        val fm = firstMediaAt() ?: now()
        rec("first media")
        assertTrue("video track off", videoTrackOff())
        val positions = observe(NO_REBUFFER_WINDOW_MS + 500, statsToggleAtMs = if (statsToggle) 1_500 else -1)
        assertEquals("milestone was audio", "audio", events(PlaybackEventType.FIRST_FRAME).first().code)
        assertSmooth(if (statsToggle) "hiddenAtTune+stats" else "hiddenAtTune", positions, fm)
    }

    /** Still mode entered while already Playing in FULL (the track disabled mid-stream), then back to FULL. */
    @Test
    fun hiddenWhilePlayingThenFull() {
        attach()
        launch(ch(1), stats = false)
        awaitFirstMedia(1)
        startProbe()
        awaitPlaying()
        Thread.sleep(3_000)
        rec("-> hidden")
        val at = now()
        setHarnessViewport("hidden")
        val positions = observe(NO_REBUFFER_WINDOW_MS + 500)
        assertTrue("video track off", videoTrackOff())
        assertSmooth("hiddenWhilePlaying", positions, at)
        val ms = toFullAndTimeFirstFrame("hiddenWhilePlaying")
        val after = observe(4_000)
        assertTrue("still → FULL renders a frame within $FIRST_FRAME_MAX_MS ms ($ms ms)", ms in 0..FIRST_FRAME_MAX_MS)
        assertSmooth("hiddenWhilePlaying→FULL", after, after.first().first)
    }

    /** Still → FULL re-enables the video track: a frame within [FIRST_FRAME_MAX_MS], no load restart, no rebuffer, no position jump. */
    @Test
    fun hiddenToFullRendersAFrameQuickly() {
        attach()
        launch(ch(1), stats = false, hidden = true)
        awaitFirstMedia(1)
        startProbe()
        Thread.sleep(4_000)
        val ms = toFullAndTimeFirstFrame("hiddenToFull")
        val positions = observe(NO_REBUFFER_WINDOW_MS + 500)
        assertTrue("hidden → FULL renders a frame within $FIRST_FRAME_MAX_MS ms ($ms ms)", ms in 0..FIRST_FRAME_MAX_MS)
        assertSmooth("hiddenToFull", positions, positions.first().first - 300)
    }

    /** HDR poster (§3.9.5): viewport MINI, the track off because the stream is HDR10; same behaviour, then FULL. */
    @Test
    fun hdrPosterInMiniBehavesTheSame() {
        panel.config(FakePanelAdmin.Config(mediaOverride = mapOf(1 to "hdr")))
        attach()
        launch(ch(1), stats = false, mini = true)
        awaitFirstMedia(1)
        startProbe()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!main { controller.video.value.posterMode } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("HDR10 in MINI with the POSTER setting → poster (hdr=${main { controller.video.value.hdr }})", main { controller.video.value.posterMode })
        val fm = firstMediaAt() ?: now()
        assertTrue("video track off", videoTrackOff())
        val positions = observe(NO_REBUFFER_WINDOW_MS + 500)
        assertSmooth("hdrPoster", positions, fm)
        val ms = toFullAndTimeFirstFrame("hdrPoster")
        val after = observe(4_000)
        assertTrue("poster → FULL renders a frame within $FIRST_FRAME_MAX_MS ms ($ms ms)", ms in 0..FIRST_FRAME_MAX_MS)
        assertSmooth("hdrPoster→FULL", after, after.first().first)
    }

    /** FULL while the hidden session rebuffers (the panel holds the socket for 3 s): video comes back with the data. */
    @Test
    fun fullDuringARebuffer() {
        panel.config(FakePanelAdmin.Config(streamFaults = mapOf(1 to FakePanelAdmin.Faults(stallAfter = 6 to 3))))
        attach()
        launch(ch(1), stats = false, hidden = true)
        awaitFirstMedia(1)
        startProbe()
        awaitState("Buffering", 15_000) { it is PlaybackState.Buffering }
        rec("controller Buffering")
        val frames = firstFrames
        val canceled = canceledAt.size
        setHarnessViewport("full")
        rec("-> full during the rebuffer")
        awaitPlaying(15_000)
        val readyAt = SystemClock.elapsedRealtime()
        while (firstFrames == frames && SystemClock.elapsedRealtime() - readyAt < 3_000) Thread.sleep(10)
        val ms = if (firstFrames != frames) SystemClock.elapsedRealtime() - readyAt else -1
        rec("frame $ms ms after Playing")
        observe(3_000)
        dumpEvents()
        Log.i(Q1, "SUMMARY fullDuringARebuffer frameAfterPlaying=$ms maxBack=$maxBack canceled=$canceledAt discontinuities=$discontinuities")
        assertTrue("a frame within $FIRST_FRAME_MAX_MS ms of Playing ($ms ms)", ms in 0..FIRST_FRAME_MAX_MS)
        assertEquals("no load restart", canceled, canceledAt.size)
        assertTrue("position never goes back (max back $maxBack ms)", maxBack <= MAX_BACK_MS)
    }

    /**
     * Standby grace with the track off: no verdict while the screen is off, no stop when it comes back inside the grace;
     * then FULL shows video. (Google TV pauses the active media session at screen-off through `MediaSession`, so the
     * test resumes after the wake, as the user would.)
     */
    @Test
    fun standbyGraceWithTheTrackOff() {
        attach()
        launch(ch(1), stats = false, hidden = true)
        awaitFirstMedia(1)
        startProbe()
        Thread.sleep(2_000)
        val sleptAt = SystemClock.elapsedRealtime()
        device.sleep()
        try {
            Thread.sleep(1_500) // inside the scaled 3 s grace
            rec("grace: state ${controller.state.value}")
        } finally {
            device.wakeUp()
        }
        Thread.sleep(1_000)
        if ((controller.state.value as? PlaybackState.Playing)?.paused == true) onMain { controller.resume() }
        awaitPlaying(10_000)
        val stalls = events(PlaybackEventType.STALL).filter { it.elapsedMs >= sleptAt }
        assertTrue("no stall verdict around the grace: $stalls", stalls.isEmpty())
        assertTrue("video track still off", videoTrackOff())
        Thread.sleep(1_000)
        val ms = toFullAndTimeFirstFrame("afterGrace")
        observe(2_000)
        dumpEvents()
        Log.i(Q1, "SUMMARY standbyGrace frame=$ms maxBack=$maxBack discontinuities=$discontinuities states=${synchronized(states) { states.map { it.javaClass.simpleName } }}")
        assertTrue("FULL after the grace renders a frame within $FIRST_FRAME_MAX_MS ms ($ms ms)", ms in 0..FIRST_FRAME_MAX_MS)
        assertTrue("position never goes back (max back $maxBack ms)", maxBack <= MAX_BACK_MS)
    }

    companion object {
        const val Q1 = "Q1"
        const val NO_REBUFFER_WINDOW_MS = 10_000L
        /**
         * Emulator bound for the first frame after a re-enable. The decoder catches up from the GOP's keyframe
         * (≈ GOP / (decode speed − 1)); on `OMX.google.h264.decoder` the guide's worst case was 1,497 ms. The 1.5 s
         * figure is the **hardware gate** (M2 design §5.13, M6), not this emulator assertion.
         */
        const val FIRST_FRAME_MAX_MS = 2_500L
        /** The 50 ms probe reads `currentPosition` on main; the audio clock's smoothing can step back a few ms. */
        const val MAX_BACK_MS = 50L

        fun stateName(s: Int) = when (s) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "?$s"
        }

        fun typeName(t: Int) = when (t) {
            C.TRACK_TYPE_VIDEO -> "V"
            C.TRACK_TYPE_AUDIO -> "A"
            C.TRACK_TYPE_TEXT -> "T"
            else -> "?$t"
        }
    }
}
