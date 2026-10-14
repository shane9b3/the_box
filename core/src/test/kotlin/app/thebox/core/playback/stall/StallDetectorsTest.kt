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
    fun tsBackstopIsSilentWhileNoReadIsPending() {
        // E-B1/N-S2: WP-C reports age 0 when the loader is parked (no pending read), whatever the buffer level.
        playing().apply { bufferedDurationMs = 20_000; lastByteAgeMs = 0 }
        d.onSample(s)
        repeat(30) { assertNull(step { positionMs += tick; videoRenderedCount += 25; lastByteAgeMs = 0 }) }
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

    /** (N-S4) Ten seconds of healthy READY playback at [bytesPerSecond]: b1 learns the stream's own rate from them. */
    private fun learnRate(bytesPerSecond: Long) {
        playing()
        d.onSample(s)
        repeat(10) { assertNull(step { positionMs += tick; bytesTotal += bytesPerSecond; videoRenderedCount += 25 }) }
        assertEquals(bytesPerSecond, d.learnedStreamRate)
    }

    /** Switches the running sample to a rebuffer (keeps the learned rate and the counters). */
    private fun startRebuffer() = step { playbackState = StallConstants.STATE_BUFFERING; isPlaying = false }

    @Test
    fun slowNetworkOnAHighBitrateStreamDoesNotFire() {
        // Code review 2026-10-14, N-S4. This was the old "fires at six seconds" case: 50 kB/s that turn into 50 ms of
        // media per second is an 8 Mbit/s stream on a 400 kbit/s link — a slow network, not a stuck decoder.
        bufferingWithBytes()
        d.onSample(s)
        repeat(30) { assertNull(step { bytesTotal += 50_000; bufferedDurationMs += 50 }) }
    }

    @Test
    fun slowNetworkOnALearnedHighBitrateStreamDoesNotFire() {
        // 8 Mbit/s stream (1 MB per media second) learned while playing, then a 500 kbit/s network for 30 s.
        learnRate(1_000_000)
        assertNull(startRebuffer())
        repeat(30) { assertNull(step { bytesTotal += 62_500; bufferedDurationMs += 62 }) }
    }

    @Test
    fun decoderStuckFiresWhenBytesMatchTheStreamRateButMediaDoesNotGrow() {
        learnRate(1_000_000)
        assertNull(startRebuffer())
        repeat(5) { assertNull(step { bytesTotal += 1_000_000 }) }
        val v = step { bytesTotal += 1_000_000 }
        assertEquals(StallKind.DECODER_STUCK, v?.kind)
        assertTrue(v!!.evidence.contains("bytes+6000000"), v.evidence)
    }

    @Test
    fun decoderStuckNeedsTheWindowsWorthAtTheLearnedRate() {
        // 5.4 s of media's worth of bytes in a 6 s window: the network is slower than real time, so no verdict.
        learnRate(1_000_000)
        startRebuffer()
        repeat(30) { assertNull(step { bytesTotal += 900_000 }) }
    }

    @Test
    fun decoderStuckWithoutALearnedRateNeedsTwoMebibytes() {
        bufferingWithBytes()
        d.onSample(s)
        repeat(30) { assertNull(step { bytesTotal += 300_000 }) } // 1.8 MB per window: below the unknown-rate floor
    }

    @Test
    fun decoderStuckWithoutALearnedRateFiresAboveTwoMebibytes() {
        bufferingWithBytes()
        d.onSample(s)
        repeat(5) { assertNull(step { bytesTotal += 400_000 }) }
        assertEquals(StallKind.DECODER_STUCK, step { bytesTotal += 400_000 }?.kind) // 2.4 MB, no media growth
    }

    @Test
    fun newStreamForgetsTheLearnedRate() {
        learnRate(1_000_000)
        d.newStream(s.nowMs)
        assertEquals(0L, d.learnedStreamRate)
        d.reset(s.nowMs)
        learnRate(250_000)
        d.reset(s.nowMs) // a seek keeps the rate
        assertEquals(250_000L, d.learnedStreamRate)
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

    // ------------------------------------------------------------------ (b1r) readiness stall, follow-up 2026-10-14 (O-1)

    /** The O-1 shape: playing, then BUFFERING with a full buffer (loader parked), no bytes, nothing rendered. */
    private fun o1(bufferedMs: Long = 20_000, loading: Boolean = false) = playing().apply {
        playbackState = StallConstants.STATE_BUFFERING; isPlaying = false
        bufferedDurationMs = bufferedMs; isLoading = loading; readyThresholdMs = 3_000
        firstMediaThisSession = true; framesSinceDisruption = 500; msSinceSeek = Long.MAX_VALUE / 4; videoDecoderInits = 1
        videoQueuedMinusOutput = 40
    }

    @Test
    fun readinessStallFiresInTheO1Shape() {
        o1()
        assertNull(d.onSample(s)) // baseline
        repeat(4) { assertNull(step()) }
        val v = step()
        assertEquals(StallKind.DECODER_STUCK, v?.kind)
        assertTrue(v!!.evidence.startsWith("readiness stall"), v.evidence)
        assertTrue(v.evidence.contains("queued-output 40"), v.evidence)
    }

    @Test
    fun readinessStallFiresWhileLoadingOnceTheStartThresholdIsBuffered() {
        o1(bufferedMs = 3_000, loading = true)
        d.onSample(s)
        repeat(4) { assertNull(step()) }
        assertEquals(StallKind.DECODER_STUCK, step()?.kind)
    }

    @Test
    fun readinessStallIsSilentInAStarvedRebuffer() {
        o1(bufferedMs = 1_500, loading = true) // below the after-rebuffer threshold and still loading
        d.onSample(s)
        repeat(30) { assertNull(step { bufferedDurationMs = minOf(bufferedDurationMs + 40, 2_900) }) }
    }

    @Test
    fun readinessStallIsSilentDuringStartBuffering() {
        o1().apply { firstMediaThisSession = false; framesSinceDisruption = 0 }
        d.onSample(s)
        repeat(30) { assertNull(step()) }
    }

    @Test
    fun readinessStallWaitsSixSecondsAfterASeek() {
        o1().apply { msSinceSeek = 0 }
        d.onSample(s)
        repeat(5) { assertNull(step { msSinceSeek += tick }) } // 5 s buffering, but only 5 s since the seek
        assertEquals(StallKind.DECODER_STUCK, step { msSinceSeek += tick }?.kind)
    }

    @Test
    fun readinessStallNeedsFramesSinceTheLastDisruption() {
        o1().apply { framesSinceDisruption = 0 }
        d.onSample(s)
        repeat(30) { assertNull(step()) }
    }

    @Test
    fun readinessStallIsSilentWithoutVideoExpected() {
        // Invalid surface, standby grace, audio-only and the HDR poster all reach the detector as videoExpected = false.
        o1().apply { videoExpected = false }
        d.onSample(s)
        repeat(30) { assertNull(step()) }
    }

    @Test
    fun aDecoderReinitRestartsTheReadinessBaseline() {
        o1()
        d.onSample(s)
        repeat(3) { assertNull(step()) }
        assertNull(step { videoDecoderInits = 2 }) // SPS/resolution change: codec re-initialised
        repeat(4) { assertNull(step()) }
        assertEquals(StallKind.DECODER_STUCK, step()?.kind) // 5 s after the re-init, not 5 s after BUFFERING began
    }

    @Test
    fun readinessStallSharesTheDecoderStuckCoolDownWithB1() {
        // Both b1 (bytes flow, no media growth, 2.4 MB per window) and the readiness stall qualify: whichever fires
        // first, the other stays silent for the 15 s DECODER_STUCK cool-down.
        o1(bufferedMs = 20_000, loading = true)
        d.onSample(s)
        val kinds = mutableListOf<Pair<Long, StallKind>>()
        repeat(20) { step { bytesTotal += 400_000 }?.let { kinds += s.nowMs to it.kind } }
        assertTrue(kinds.isNotEmpty())
        assertTrue(kinds.all { it.second == StallKind.DECODER_STUCK })
        for (i in 1 until kinds.size) assertTrue(kinds[i].first - kinds[i - 1].first >= 15_000, "$kinds")
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
        repeat(40) { step { bytesTotal += 400_000 }?.let { kinds += it.kind } } // 2.4 MB per window ≥ the unknown-rate floor
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
