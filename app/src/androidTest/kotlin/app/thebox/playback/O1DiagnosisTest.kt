package app.thebox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.playback.PlaybackEventType
import app.thebox.player.api.PlaybackState
import app.thebox.player.debug.PlayerDebugHooks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O-1 diagnosis harness (follow-up 2026-10-14, design §5.10). Not part of the suite: every test is skipped unless the
 * instrumentation argument `o1diag` is given (`default`, `aud-off` or `compat1`):
 *
 *   tools/fakepanel/run-instrumented.sh --class app.thebox.playback.O1DiagnosisTest \
 *       -Pandroid.testInstrumentationRunnerArguments.o1diag=aud-off
 *
 * It replays I-3 (ch 1, `close_every: 15`, continue mode, 70 s) with either only `FLAG_DETECT_ACCESS_UNITS` off
 * (`PlayerDebugHooks`) or the channel at TS compat level 1 (MULTI_PMT and the flag off), and logs every freeze (position
 * flat for ≥ 3 s while not paused), the stall verdicts and decisions, and how long each freeze lasted. Nothing is
 * asserted: the output is the finding.
 */
@RunWith(AndroidJUnit4::class)
class O1DiagnosisTest : PanelTestBase() {
    private val mode: String? = InstrumentationRegistry.getArguments().getString("o1diag")

    @After
    fun resetOverride() {
        PlayerDebugHooks.detectAccessUnitsOff = false
    }

    @Test
    fun i03WithDiagnosisOverride() {
        assumeTrue("no -e o1diag argument: diagnosis run skipped", mode == "aud-off" || mode == "compat1" || mode == "default")
        if (mode == "aud-off") PlayerDebugHooks.detectAccessUnitsOff = true
        if (mode == "compat1") runBlocking { ep.channelMemory().update(ch(1)) { it.copy(tsCompatLevel = 1) } }
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(closeEvery = 15, reconnectMode = "continue")))
        launch(ch(1))
        awaitFirstMedia(1)
        var lastPos = -1L
        var flatSince = 0L
        var freezes = 0
        val freezeMs = mutableListOf<Long>()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 70_000) {
            Thread.sleep(500)
            val now = System.currentTimeMillis() - t0
            val p = main { controller.position() }
            val st = controller.state.value
            val paused = st is PlaybackState.Playing && st.paused
            if (p.positionMs == lastPos && !paused) {
                if (flatSince == 0L) flatSince = now
            } else {
                if (flatSince != 0L && now - flatSince >= 3_000) {
                    freezes++
                    freezeMs += now - flatSince
                    log("O-1 $mode: freeze #$freezes from ${flatSince} ms to $now ms (${now - flatSince} ms), buffered=${p.bufferedMs}")
                }
                flatSince = 0L
            }
            lastPos = p.positionMs
            if ((now / 500) % 10 == 0L) log("O-1 $mode t=$now pos=${p.positionMs} buffered=${p.bufferedMs} state=${st.javaClass.simpleName}")
        }
        if (flatSince != 0L) {
            freezes++
            freezeMs += 70_000 - flatSince
            log("O-1 $mode: freeze #$freezes still running at the end (${70_000 - flatSince} ms)")
        }
        val stalls = events(PlaybackEventType.STALL).map { "${it.code} ${it.detail}" }
        val decisions = events(PlaybackEventType.DECISION).map { it.code }
        log("O-1 $mode SUMMARY freezes=$freezes durations=$freezeMs reconnects=${stats().reconnects} stalls=$stalls decisions=$decisions state=${controller.state.value.javaClass.simpleName}")
    }
}
