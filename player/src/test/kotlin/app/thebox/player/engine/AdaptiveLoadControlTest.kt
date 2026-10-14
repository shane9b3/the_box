package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocation
import app.thebox.core.playback.BufferProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Decisions of [AdaptiveLoadControl] (§3.6.3). The real-player cases are in [RealPlayerTest]. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdaptiveLoadControlTest {
    private val player = PlayerId("test")
    private val l0 = BufferProfile(startMs = 1_000, afterRebufferMs = 3_000, minBufferMs = 10_000, maxBufferMs = 20_000, targetBytes = 48 shl 20, level = 0)

    private fun params(bufferedMs: Long, rebuffering: Boolean = false, targetLiveOffsetUs: Long = C.TIME_UNSET, speed: Float = 1f) =
        LoadControl.Parameters(
            player, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()), 0L, bufferedMs * 1_000, speed, true, rebuffering, targetLiveOffsetUs, C.TIME_UNSET,
        )

    private fun control(profile: BufferProfile = l0, cap: Int = AdaptiveLoadControl.NORMAL_TARGET_BYTES) =
        AdaptiveLoadControl(cap, profile).also { it.onPrepared(player) }

    @Test
    fun startUsesTheStartThresholdThenTheRebufferThreshold() {
        val alc = control()
        assertFalse(alc.shouldStartPlayback(params(999)))
        assertTrue(alc.shouldStartPlayback(params(1_000)))
        assertFalse(alc.shouldStartPlayback(params(2_999, rebuffering = true)))
        assertTrue(alc.shouldStartPlayback(params(3_000, rebuffering = true)))
    }

    @Test
    fun profileChangeAppliesAtTheNextEvaluation() {
        val alc = control()
        assertTrue(alc.shouldStartPlayback(params(1_500)))
        alc.profile = l0.copy(startMs = 2_500, afterRebufferMs = 10_000, level = 2)
        assertFalse(alc.shouldStartPlayback(params(1_500)))
        assertFalse(alc.shouldStartPlayback(params(9_000, rebuffering = true)))
        assertTrue(alc.shouldStartPlayback(params(2_500)))
    }

    @Test
    fun continueLoadingHasHysteresisBetweenMinAndMax() {
        val alc = control()
        assertTrue(alc.shouldContinueLoading(params(5_000)))
        assertTrue("between min and max: keep loading", alc.shouldContinueLoading(params(15_000)))
        assertFalse(alc.shouldContinueLoading(params(20_000)))
        assertFalse("between min and max: stay stopped", alc.shouldContinueLoading(params(15_000)))
        assertTrue(alc.shouldContinueLoading(params(9_999)))
    }

    @Test
    fun minimumBufferNeverDropsBelowHalfASecond() {
        val alc = control(l0.copy(minBufferMs = 0, maxBufferMs = 0))
        assertTrue(alc.shouldContinueLoading(params(400)))
        assertFalse(alc.shouldContinueLoading(params(500)))
    }

    @Test
    fun byteCapWins() {
        val alc = control(l0.copy(targetBytes = 128 * 1024))
        val allocator = alc.getAllocator(player)
        val held = ArrayList<Allocation>()
        repeat(2) { held += allocator.allocate() } // 2 × 64 KiB segments
        assertEquals(128 * 1024, alc.bytesAllocated)
        assertFalse("below min but the cap is reached", alc.shouldContinueLoading(params(1_000)))
        assertTrue("cap reached: start even below the threshold", alc.shouldStartPlayback(params(100)))
        held.forEach { allocator.release(it) }
    }

    @Test
    fun bufferModeNoneThresholdsAreNotFloored() {
        // Follow-up 2026-10-14: NONE = 500 / 500 / 2,000 / 5,000. Start and rebuffer at 500 ms (no floor on start); the
        // 500 ms low-water floor is below NONE's 2 s minimum, so loading follows 2 s / 5 s.
        val none = app.thebox.core.playback.buffer.BufferProfileFsmImpl.manual(app.thebox.core.playback.BufferMode.NONE, lowRam = false)
        val alc = control(none)
        assertFalse(alc.shouldStartPlayback(params(499)))
        assertTrue(alc.shouldStartPlayback(params(500)))
        assertFalse(alc.shouldStartPlayback(params(499, rebuffering = true)))
        assertTrue(alc.shouldStartPlayback(params(500, rebuffering = true)))
        assertTrue(alc.shouldContinueLoading(params(1_999)))
        assertTrue("between min and max: keep loading", alc.shouldContinueLoading(params(4_000)))
        assertFalse(alc.shouldContinueLoading(params(5_000)))
        assertFalse("between min and max: stay stopped", alc.shouldContinueLoading(params(3_000)))
        assertTrue(alc.shouldContinueLoading(params(1_999)))
        // HLS live: min(500 ms, offset / 2) stays 500 ms for any offset ≥ 1 s, and drops below it only for a tiny offset.
        assertTrue(alc.shouldStartPlayback(params(500, targetLiveOffsetUs = 3_000_000)))
        assertFalse(alc.shouldStartPlayback(params(499, targetLiveOffsetUs = 3_000_000)))
        assertTrue(alc.shouldStartPlayback(params(300, targetLiveOffsetUs = 600_000)))
    }

    @Test
    fun hlsLiveClampsTheStartThresholdToHalfTheTargetOffset() {
        val alc = control(l0.copy(startMs = 5_000))
        assertFalse(alc.shouldStartPlayback(params(1_400, targetLiveOffsetUs = 3_000_000)))
        assertTrue(alc.shouldStartPlayback(params(1_500, targetLiveOffsetUs = 3_000_000)))
    }

    @Test
    fun playbackSpeedConvertsMediaToPlayoutDuration() {
        val alc = control()
        assertFalse("1.2 s of media at 1.25× is under 1 s of playout", alc.shouldStartPlayback(params(1_200, speed = 1.25f)))
        assertTrue(alc.shouldStartPlayback(params(1_300, speed = 1.25f)))
    }

    @Test
    fun backBufferIsAlwaysZeroAndLifecycleCallsDoNotThrow() {
        val alc = control()
        assertEquals(0L, alc.getBackBufferDurationUs(player))
        assertFalse(alc.retainBackBufferFromKeyframe(player))
        alc.onStopped(player)
        alc.onPrepared(player)
        alc.onReleased(player)
    }

    @Test
    fun defaultProfileIsAutoLevelZero() {
        val p = AdaptiveLoadControl.defaultProfile(AdaptiveLoadControl.LOW_RAM_TARGET_BYTES)
        assertEquals(BufferProfile(1_000, 3_000, 10_000, 20_000, 24 shl 20, 0), p)
        assertEquals(p, AdaptiveLoadControl(AdaptiveLoadControl.LOW_RAM_TARGET_BYTES).profile)
    }
}
