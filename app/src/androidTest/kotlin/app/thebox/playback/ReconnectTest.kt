package app.thebox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StallKind
import app.thebox.player.api.BufferingCause
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** I-3/I-4/I-5 (D4 reconnects), I-8 token expiry, I-14/I-15 stalls, I-16 slow network. */
@RunWith(AndroidJUnit4::class)
class ReconnectTest : PanelTestBase() {

    private fun bufferingGaps(seen: List<PlaybackState>): List<Long> {
        // Coarse: count Buffering entries; durations come from the event log timestamps below.
        return events(PlaybackEventType.RECONNECT_OK).zip(events(PlaybackEventType.RECONNECT)).map { (ok, rc) -> ok.elapsedMs - rc.elapsedMs }
    }

    @Test
    fun i03TransparentReconnect() {
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(closeEvery = 15, reconnectMode = "continue")))
        launch(ch(1))
        awaitFirstMedia(1)
        val seen = runFor(70_000)
        assertNoError(seen)
        val s = stats()
        log("I-3 reconnects=${s.reconnects}, buffering entries=${seen.count { it is PlaybackState.Buffering }}, gaps=${bufferingGaps(seen)}")
        assertTrue("telemetry reconnects ${s.reconnects}", s.reconnects >= 4)
        assertTrue(notices.none { it is PlaybackNotice.Reconnecting })
        val p0 = main { controller.position().positionMs }
        Thread.sleep(1_000)
        val p1 = main { controller.position().positionMs }
        assertTrue("position advancing $p0 → $p1", p1 > p0)
    }

    private fun ptsDiscontinuity(mode: String) {
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(closeEvery = 15, reconnectMode = mode)))
        launch(ch(1))
        awaitFirstMedia(1)
        val seen = runFor(40_000)
        assertNoError(seen)
        val jumps = events(PlaybackEventType.PCR_JUMP)
        val decisions = events(PlaybackEventType.DECISION).filter { it.code == "Reprepare" }
        log("I-4/5 $mode: PCR_JUMP=${jumps.size} Reprepare=${decisions.size}")
        assertTrue("PCR_JUMP logged", jumps.isNotEmpty())
        assertTrue("Reprepare decision after the jump", decisions.any { d -> jumps.any { it.elapsedMs <= d.elapsedMs } })
        awaitPlaying(10_000)
    }

    @Test fun i04PtsReset() = ptsDiscontinuity("reset")
    @Test fun i05PtsJump() = ptsDiscontinuity("jump")

    @Test
    fun i08TokenExpiryRedirect() {
        panel.config(
            FakePanelAdmin.Config(
                faults = FakePanelAdmin.Faults(closeEvery = 8),
                streamFaults = mapOf(1 to FakePanelAdmin.Faults(redirect = true, tokenTtl = 5)),
            ),
        )
        launch(ch(1))
        awaitFirstMedia(1)
        val seen = runFor(40_000)
        assertNoError(seen)
        val stats = panel.stats()
        val log = stats.streamRequests(1)
        log("I-8 requests for ch1: ${log.map { "${it.optString("path")} token=${it.optBoolean("token_present")}" }}")
        assertFalse("expired token rejected", stats.rejections.containsKey("token_expired"))
        // Every reconnect starts from the original /live/… URL (re-resolve; the tokenised redirect is never reused).
        assertTrue(log.isNotEmpty() && !log.first().optBoolean("token_present"))
        assertTrue(controller.state.value is PlaybackState.Playing || controller.state.value is PlaybackState.Buffering)
    }

    @Test
    fun i14StallAtStart() {
        panel.config(FakePanelAdmin.Config(streamFaults = mapOf(1 to FakePanelAdmin.Faults(stall = 6))))
        launch(ch(1))
        val (_, ms) = awaitPlaying(15_000)
        log("I-14 Playing after $ms ms")
        assertNoError(runFor(2_000))
    }

    @Test
    fun i15StallMidStream() {
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(stallAfter = 10 to 8)))
        launch(ch(1))
        awaitFirstMedia(1)
        val seen = runFor(30_000)
        assertNoError(seen)
        val noBytes = events(PlaybackEventType.NO_BYTES)
        log("I-15 NO_BYTES at ${noBytes.map { it.elapsedMs }} ; buffering(reconnecting)=${seen.count { it is PlaybackState.Buffering && it.cause == BufferingCause.RECONNECTING }}")
        assertTrue("NO_BYTES event", noBytes.isNotEmpty())
        awaitPlaying(10_000)
    }

    @Test
    fun i16SlowNetwork() {
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(slow = 100_000)))
        launch(ch(1))
        val seen = runFor(60_000)
        assertNoError(seen)
        val rebuffers = events(PlaybackEventType.REBUFFER).size
        val s = stats()
        log("I-16 rebuffers=$rebuffers profile=${s.bufferProfile} notices=${notices.filterIsInstance<PlaybackNotice.BufferIncreased>()}")
        assertTrue(notices.any { it is PlaybackNotice.BufferIncreased })
        assertTrue((s.bufferProfile?.level ?: 0) >= 1)
        assertTrue("no DECODER_STUCK (B6)", events(PlaybackEventType.STALL).none { it.code == StallKind.DECODER_STUCK.name })
    }
}
