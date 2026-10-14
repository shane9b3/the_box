package app.thebox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StallKind
import app.thebox.player.api.BufferingCause
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/** I-3/I-4/I-5 (D4 reconnects), I-8 token expiry, I-14/I-15 stalls, I-16 slow network. */
@RunWith(AndroidJUnit4::class)
class ReconnectTest : PanelTestBase() {

    private fun bufferingGaps(seen: List<PlaybackState>): List<Long> {
        // Coarse: count Buffering entries; durations come from the event log timestamps below.
        return events(PlaybackEventType.RECONNECT_OK).zip(events(PlaybackEventType.RECONNECT)).map { (ok, rc) -> ok.elapsedMs - rc.elapsedMs }
    }

    // Integration 2026-10-13 (docs/design/m2-playback-core.md §5.8): on the API 30 x86 emulator the software
    // OMX.google.h264.decoder stops producing output after some in-source splices (input keeps being queued, rendered
    // count flat, position frozen at the splice) until the NO_BYTES backstop re-resolves ~30 s later. Reconnects
    // themselves are transparent (Buffering(RECONNECTING) lasts ~20 ms). Needs a real-device run and a decoder-output
    // stall detector decision before this can be un-ignored.
    @Ignore("emulator: soft H.264 decoder stalls after a reconnect splice; see §5.8 open item O-1")
    @Test
    fun i03TransparentReconnect() {
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(closeEvery = 15, reconnectMode = "continue")))
        launch(ch(1))
        awaitFirstMedia(1)
        val from = states.size
        repeat(14) {
            Thread.sleep(5_000)
            val p = main { controller.position() }
            log("I-3 t=${(it + 1) * 5}s position=${p.positionMs} buffered=${p.bufferedMs} state=${controller.state.value.javaClass.simpleName}")
        }
        val seen = synchronized(states) { states.drop(from) }
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

    @Test
    fun i03bTransparentReconnectAudioOnly() {
        // Code review 2026-10-14, E-S6: D4a coverage while I-3 stays ignored for O-1 (the emulator's soft H.264 decoder).
        // ch 13 is audio-only, so no video decoder can stall at the splice.
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(closeEvery = 15, reconnectMode = "continue")))
        launch(ch(13))
        awaitFirstMedia(1)
        val from = states.size
        Thread.sleep(50_000)
        val seen = synchronized(states) { states.drop(from) }
        assertNoError(seen)
        val s = stats()
        log("I-3b reconnects=${s.reconnects} buffering=${seen.count { it is PlaybackState.Buffering }} gaps=${bufferingGaps(seen)}")
        assertTrue("telemetry reconnects ${s.reconnects}", s.reconnects >= 2)
        assertTrue("no Reconnecting notice", notices.none { it is PlaybackNotice.Reconnecting })
        assertTrue("no re-resolve", events(PlaybackEventType.DECISION).none { it.code == "ReResolve" })
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
        // §5.5 I-4/I-5 (code review 2026-10-14, E-S6): back to Playing within 5 s of the rebuffer that follows each
        // jump. The jump surfaces when the buffer drains; the Reprepare decision marks that moment.
        val endAt = android.os.SystemClock.elapsedRealtime()
        for (j in jumps) {
            val d = decisions.firstOrNull { it.elapsedMs >= j.elapsedMs } ?: continue
            if (endAt - d.elapsedMs < 5_000) continue // the run ended inside the window; checked by the wait below
            val back = firstStateAfter(d.elapsedMs) { it is PlaybackState.Playing && !it.paused }
            log("I-4/5 $mode: jump at ${j.elapsedMs}, Reprepare at ${d.elapsedMs}, Playing at $back")
            assertNotNull("Playing again after the Reprepare at ${d.elapsedMs}", back)
            assertTrue("back to Playing ${back!! - d.elapsedMs} ms after the rebuffer", back - d.elapsedMs <= 5_000)
        }
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
        // §5.5 I-8 (code review 2026-10-14, E-S6): every connection, the first and every reconnect, requests the original
        // /live/… URL first: each tokenised request directly follows a tokenless one (the redirect is never reused).
        assertTrue("requests logged", log.size >= 4)
        assertFalse("first request is tokenless", log.first().optBoolean("token_present"))
        log.forEachIndexed { i, r ->
            if (r.optBoolean("token_present")) assertFalse("request $i reused a token", log[i - 1].optBoolean("token_present"))
        }
        assertTrue("reconnects happened", log.count { !it.optBoolean("token_present") } >= 2)
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
