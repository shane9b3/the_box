package app.thebox.core.playback.stall

import app.thebox.core.playback.StallKind
import app.thebox.core.playback.WatchdogSample
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StallDetectorsTest {
    private val d = StallDetectorsImpl()
    private val s = WatchdogSample()
    private val tick = 1_000L

    /** A healthy live TS sample: playing, video and audio running, 10 s after prepare. */
    private fun playing(now: Long = 100_000) = s.apply {
        nowMs = now
        playbackState = StallConstants.STATE_READY
        playWhenReady = true
        isPlaying = true
        suppressed = false
        isLive = true
        positionMs = 0
        bufferedDurationMs = 3_000
        bytesTotal = 0
        lastByteAgeMs = 100
        inDataSourceReconnect = false
        isHls = false
        videoExpected = true
        firstFrameRendered = true
        videoRenderedCount = 0
        videoDroppedCount = 0
        tunneling = false
        audioExpected = true
        audioAdvancing = true
        msSinceFirstFrame = 10_000
        msSincePlayStart = 10_000
        audioUnderrunsLast3s = 0
        audioPassthrough = false
        msSinceSeekOrPrepare = 10_000
    }

    /** Advances time by one tick; the healthy defaults keep moving (position, bytes, frames) unless [mutate] says otherwise. */
    private fun step(mutate: WatchdogSample.() -> Unit = {}): app.thebox.core.playback.StallVerdict? {
        s.nowMs += tick
        s.msSinceSeekOrPrepare += tick
        s.msSinceFirstFrame += tick
        s.msSincePlayStart += tick
        s.mutate()
        return d.onSample(s)
    }

    private fun healthy(): WatchdogSample.() -> Unit = {
        positionMs += tick; bytesTotal += 300_000; videoRenderedCount += 25
    }

    @Test
    fun healthyPlaybackNeverFires() {
        playing()
        repeat(120) { assertNull(step(healthy())) }
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun hlsNoProgressFiresAtEightSecondsNotBefore() {
        playing().apply { isHls = true; playbackState = StallConstants.STATE_BUFFERING; isPlaying = false }
        assertNull(d.onSample(s)) // baseline
        repeat(7) { assertNull(step()) }
        assertEquals(StallKind.HLS_NO_PROGRESS, step()?.kind)
    }

    @Test
    fun hlsBytesResetTheBaseline() {
        playing().apply { isHls = true; playbackState = StallConstants.STATE_BUFFERING; isPlaying = false }
        d.onSample(s)
        repeat(7) { step() }
        assertNull(step { bytesTotal += 1 })
        repeat(7) { assertNull(step()) }
        assertNotNull(step())
    }

    @Test
    fun tsBackstopFiresOnlyWhenTheByteWatchdogFailed() {
        playing()
        assertNull(step { lastByteAgeMs = 8_000 })
        assertNull(step { lastByteAgeMs = 20_000; inDataSourceReconnect = true })
        assertNull(step { lastByteAgeMs = 20_000; inDataSourceReconnect = false; isLive = false })
        assertEquals(StallKind.NO_BYTES, step { lastByteAgeMs = 8_001; isLive = true }?.kind)
    }

    // ------------------------------------------------------------------ (b1)

    private fun bufferingWithBytes() = playing().apply { playbackState = StallConstants.STATE_BUFFERING; isPlaying = false; audioAdvancing = true }

    @Test
    fun decoderStuckFiresAtSixSecondsWhenBytesFlowButMediaDoesNot() {
        bufferingWithBytes()
        assertNull(d.onSample(s))
        repeat(5) { assertNull(step { bytesTotal += 50_000; bufferedDurationMs += 50 }) }
        val v = step { bytesTotal += 50_000; bufferedDurationMs += 50 }
        assertEquals(StallKind.DECODER_STUCK, v?.kind)
        assertTrue(v!!.evidence.contains("bytes+300000"), v.evidence)
    }

    @Test
    fun slowNetworkRebufferDoesNotFireDecoderStuck() {
        // B6: bytes arrive below the stream rate, the extractor turns them into media (buffer grows ≥ 500 ms).
        bufferingWithBytes()
        d.onSample(s)
        repeat(30) { assertNull(step { bytesTotal += 100_000; bufferedDurationMs += 400 }) }
    }

    @Test
    fun decoderStuckNeedsEnoughBytes() {
        bufferingWithBytes()
        d.onSample(s)
        repeat(30) { assertNull(step { bytesTotal += 40_000 }) }
    }

    // ------------------------------------------------------------------ (b2)

    @Test
    fun positionStuckFiresAtFourSeconds() {
        playing()
        d.onSample(s)
        repeat(3) { assertNull(step { bytesTotal += 100_000; videoRenderedCount += 25; positionMs += 100 }) }
        assertEquals(StallKind.POSITION_STUCK, step { bytesTotal += 100_000; videoRenderedCount += 25; positionMs += 100 }?.kind)
    }

    @Test
    fun positionStuckNeedsBytes() {
        playing()
        d.onSample(s)
        repeat(10) { assertNull(step { videoRenderedCount += 25 }) }
    }

    // ------------------------------------------------------------------ (c)

    @Test
    fun frozenVideoFiresAtThreeSecondsWhileAudioAdvances() {
        playing()
        d.onSample(s)
        repeat(2) { assertNull(step { positionMs += tick; bytesTotal += 300_000 }) }
        val v = step { positionMs += tick; bytesTotal += 300_000; videoDroppedCount += 3 }
        assertEquals(StallKind.FROZEN_VIDEO, v?.kind)
    }

    @Test
    fun frozenVideoGuards() {
        for (guard in listOf<WatchdogSample.() -> Unit>({ videoExpected = false }, { firstFrameRendered = false }, { tunneling = true })) {
            val det = StallDetectorsImpl()
            playing().apply(guard)
            det.onSample(s)
            repeat(10) {
                s.nowMs += tick; s.msSinceSeekOrPrepare += tick; s.positionMs += tick; s.bytesTotal += 300_000
                assertNull(det.onSample(s))
            }
        }
    }

    // ------------------------------------------------------------------ (d1)/(d2)

    @Test
    fun audioNotStartedMeasuresFromTheLaterOfFirstFrameAndPlayStart() {
        playing().apply { audioAdvancing = false; msSinceFirstFrame = 10_000; msSincePlayStart = 1_000 }
        assertNull(d.onSample(s))
        assertNull(step(healthy()))  // 2,000 since play start
        assertEquals(StallKind.AUDIO_NOT_STARTED, step(healthy())?.kind) // 3,000
    }

    @Test
    fun passthroughGetsFiveSeconds() {
        playing().apply { audioAdvancing = false; audioPassthrough = true; msSinceFirstFrame = 2_000; msSincePlayStart = 2_000 }
        repeat(2) { assertNull(step(healthy())) } // 4,000
        assertEquals(StallKind.AUDIO_NOT_STARTED, step(healthy())?.kind) // 5,000
    }

    @Test
    fun audioNotStartedGuards() {
        playing().apply { audioAdvancing = false; msSinceFirstFrame = 60_000; msSincePlayStart = 60_000; audioExpected = false }
        assertNull(step(healthy()))
        s.audioExpected = true
        s.firstFrameRendered = false
        assertNull(step(healthy()))
    }

    @Test
    fun underrunStormFiresOnlyWhenReady() {
        playing()
        assertNull(step { healthy()(); audioUnderrunsLast3s = 4 })
        assertNull(step { healthy()(); audioUnderrunsLast3s = 9; playbackState = StallConstants.STATE_BUFFERING })
        assertEquals(StallKind.AUDIO_UNDERRUN_STORM, step { healthy()(); audioUnderrunsLast3s = 5; playbackState = StallConstants.STATE_READY }?.kind)
    }

    // ------------------------------------------------------------------ global guards, cool-down, reset

    @Test
    fun globalGuardsSilenceEveryDetector() {
        val guards = listOf<WatchdogSample.() -> Unit>(
            { playWhenReady = false }, { suppressed = true }, { msSinceSeekOrPrepare = 1_999 },
            { playbackState = StallConstants.STATE_IDLE }, { playbackState = StallConstants.STATE_ENDED },
        )
        for (g in guards) {
            val det = StallDetectorsImpl()
            playing().apply { audioAdvancing = false; audioUnderrunsLast3s = 50; lastByteAgeMs = 60_000 }
            s.g()
            assertNull(det.onSample(s))
        }
    }

    @Test
    fun seekGraceRebaselines() {
        playing()
        d.onSample(s)
        repeat(3) { step { bytesTotal += 100_000; videoRenderedCount += 25 } }
        assertNull(step { msSinceSeekOrPrepare = 0 }) // a seek: baselines re-taken
        assertNull(step { msSinceSeekOrPrepare = 1_000; bytesTotal += 100_000 })
        // Back out of the grace: the first sample re-takes the baseline, then b2 needs a full 4 s again.
        repeat(4) { assertNull(step { bytesTotal += 100_000; videoRenderedCount += 25 }) }
        assertEquals(StallKind.POSITION_STUCK, step { bytesTotal += 100_000; videoRenderedCount += 25 }?.kind)
    }

    @Test
    fun coolDownSuppressesTheSameDetectorForFifteenSeconds() {
        playing().apply { audioAdvancing = false; msSincePlayStart = 3_000 }
        assertEquals(StallKind.AUDIO_NOT_STARTED, d.onSample(s)?.kind)
        repeat(14) { assertNull(step(healthy())) }
        assertEquals(StallKind.AUDIO_NOT_STARTED, step(healthy())?.kind)
    }

    @Test
    fun resetRestartsTheTimers() {
        playing()
        d.onSample(s)
        repeat(3) { step { bytesTotal += 100_000; videoRenderedCount += 25 } }
        d.reset(s.nowMs)
        repeat(4) { assertNull(step { bytesTotal += 100_000; videoRenderedCount += 25 }) }
        assertEquals(StallKind.POSITION_STUCK, step { bytesTotal += 100_000; videoRenderedCount += 25 }?.kind)
    }

    @Test
    fun repeatedVerdictsAfterCoolDownFeedTheLadderEscalation() {
        bufferingWithBytes()
        d.onSample(s)
        val kinds = mutableListOf<StallKind>()
        repeat(40) { step { bytesTotal += 50_000 }?.let { kinds += it.kind } }
        // Every 6 s window qualifies, but the cool-down spaces verdicts ≥ 15 s apart: t = 6, 24 (next window end ≥ 21), 42 is out of range.
        assertEquals(listOf(StallKind.DECODER_STUCK, StallKind.DECODER_STUCK), kinds)
    }

    @Test
    fun onSampleAllocatesNothingAfterWarmUp() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        playing()
        // Exercise every non-firing branch.
        val phases = listOf<WatchdogSample.() -> Unit>(
            healthy(),
            { isHls = true; playbackState = StallConstants.STATE_BUFFERING; bytesTotal += 1 },
            { isHls = false; playbackState = StallConstants.STATE_BUFFERING; bytesTotal += 100; bufferedDurationMs += 600 },
            { playbackState = StallConstants.STATE_READY; positionMs += tick; videoRenderedCount += 1; bytesTotal += 1 },
            { msSinceSeekOrPrepare = 0 },
            { msSinceSeekOrPrepare = 10_000; playWhenReady = true },
        )
        fun run(n: Int): Int {
            var verdicts = 0
            for (i in 0 until n) {
                val p = phases[i % phases.size]
                s.nowMs += tick
                s.p()
                if (d.onSample(s) != null) verdicts++
            }
            return verdicts
        }
        run(50_000) // warm-up (JIT)
        // A real per-tick allocation shows in every round (≥ 16 bytes × 10,000); a one-off JIT/OSR transition does not.
        var best = Long.MAX_VALUE
        repeat(5) {
            val before = bean.getThreadAllocatedBytes(tid)
            val calib = bean.getThreadAllocatedBytes(tid) - before
            val start = bean.getThreadAllocatedBytes(tid)
            val verdicts = run(10_000)
            val used = bean.getThreadAllocatedBytes(tid) - start - calib
            assertEquals(0, verdicts, "the measured phases must not fire (a verdict allocates by design)")
            best = minOf(best, used)
        }
        assertEquals(0L, best, "onSample allocated $best bytes over 10,000 ticks")
    }
}
