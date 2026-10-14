package app.thebox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.NextStep
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** I-1 smoke, I-2 (D3) zap correctness, I-6/I-7 (D5) linger. */
@RunWith(AndroidJUnit4::class)
class SmokeAndZapTest : PanelTestBase() {

    @Test
    fun i01SmokePlaysChannelOne() {
        launch(ch(1))
        awaitPlaying(10_000)
        val zap = awaitFirstMedia(1, 10_000)
        log("I-1 zap: resolve=${zap.resolveMs} lease=${zap.leaseMs} firstByte=${zap.firstByteMs} ready=${zap.readyMs} firstMedia=${zap.firstFrameMs}")
        Thread.sleep(1_000) // one stats publish
        val s = stats()
        log("I-1 stats: $s")
        assertNotNull("video decoder", s.videoDecoder)
        assertNotNull("audio decoder", s.audioDecoder)
        assertEquals("${base.substringAfter("://")}", s.host)
        assertEquals(200, s.httpStatus)
    }

    @Test
    fun i02ZapCorrectnessWithOneConnection() {
        panel.config(FakePanelAdmin.Config(maxConnections = 1))
        refreshPlaylist()
        val list = listOf(ch(1), ch(2), ch(4), ch(3))
        launch(list[0], zap = list)
        awaitFirstMedia(1)

        val maxActive = AtomicInteger(0)
        val sampling = AtomicBoolean(true)
        val sampler = thread(name = "panel-sampler") {
            while (sampling.get()) {
                runCatching { panel.stats().activeConnections }.getOrNull()?.let { a -> maxActive.accumulateAndGet(a) { x, y -> maxOf(x, y) } }
                Thread.sleep(250)
            }
        }
        val times = mutableListOf<Long>()
        try {
            repeat(40) { i ->
                val before = currentTuneId()
                onMain { controller.zap(+1) }
                val z = awaitFirstMedia(before + 1, 10_000)
                z.firstFrameMs?.let { times += it }
                Thread.sleep((2_000 - (z.firstFrameMs ?: 0)).coerceAtLeast(200))
                log("I-2 zap ${i + 1}: firstMedia=${z.firstFrameMs} ms")
            }
        } finally {
            sampling.set(false)
            sampler.join(2_000)
        }
        val stats = panel.stats()
        log("I-2 tune→first media P50=${percentile(times, 0.5)} P90=${percentile(times, 0.9)} n=${times.size}; rejections=${stats.rejections}")
        assertTrue("panel saw ${maxActive.get()} active connections", maxActive.get() <= 1)
    }

    private fun lingerRun(status: Any) {
        panel.config(FakePanelAdmin.Config(maxConnections = 1, faults = FakePanelAdmin.Faults(linger = 2, lingerStatus = status)))
        refreshPlaylist()
        val list = listOf(ch(1), ch(2), ch(4), ch(3))
        launch(list[0], zap = list)
        awaitFirstMedia(1)
        val opensBefore = panel.stats().streamRequests
        val tuneBefore = currentTuneId()
        repeat(10) {
            onMain { controller.zap(+1, isRepeat = true) }
            Thread.sleep(300)
        }
        // The trailing (debounced) zap fires after the burst: wait for its first media, not the old channel's Playing.
        awaitFirstMedia(tuneBefore + 1, 15_000)
        val burstRequests = panel.stats().streamRequests - opensBefore
        log("I-6 linger_status=$status: $burstRequests stream requests for a 10-press burst")
        assertTrue("debounced burst made $burstRequests requests", burstRequests <= 2 + 3) // + ladder L2/L3 retries on linger

        var ok = 0
        repeat(10) {
            val before = currentTuneId()
            onMain { controller.zap(+1) }
            Thread.sleep(1_000)
            val reached = runCatching { awaitFirstMedia(before + 1, 10_000) }.isSuccess
            if (reached) ok++
        }
        log("I-6 linger_status=$status: $ok/10 single zaps reached first media")
        awaitPlaying(15_000)
    }

    @Test fun i06LingerStatus403() = lingerRun(403)
    @Test fun i06LingerStatus458() = lingerRun(458)
    @Test fun i06LingerStatus509() = lingerRun(509)
    @Test fun i06LingerStatusClose() = lingerRun("close")

    @Test
    fun i07LongLingerEndsInConnectionLimit() {
        panel.config(FakePanelAdmin.Config(maxConnections = 1, faults = FakePanelAdmin.Faults(linger = 30, lingerStatus = 458)))
        refreshPlaylist()
        val list = listOf(ch(1), ch(2), ch(4))
        launch(list[0], zap = list)
        awaitFirstMedia(1)
        onMain { controller.zap(+1) }
        Thread.sleep(500)
        onMain { controller.zap(+1) }
        val (err, ms) = awaitError(15_000)
        log("I-7 CONNECTION_LIMIT after $ms ms (design: ≤ 4 s)")
        assertEquals(PlaybackErrorCode.CONNECTION_LIMIT, err.error.code)
        assertEquals(1, err.error.detail.maxConnections)
        assertTrue(NextStep.RETRY in err.error.actions)
        assertTrue(controller.state.value is PlaybackState.Error)
    }
}
