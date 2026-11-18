package app.thebox.settings

import android.view.KeyEvent
import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.guide.ScreenMode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings persistence and effect timing (docs/design/m5-setup-settings.md §0.5, §0.6, §6.3): I-M5-10 and I-M5-11.
 * **Awaits WP-A** (RoomSettingsStore, persisted adapters, page builders of TV guide / Playback / Interface / Remote,
 * `LiveSettingsApplier`) and **WP-B** for `playlist.name_prefix`. Choice option ids are the enum names of the stored
 * fields (§2.2); the integration pass aligns them if WP-A chose others.
 */
class PersistenceTest : SettingsTestBase() {

    private fun reprepares(): Int = controller.diagnostics.events().count { it.type == PlaybackEventType.DECISION && it.code.contains("reapply", ignoreCase = true) } +
        controller.diagnostics.events().count { it.type == PlaybackEventType.TUNE && it.detail.contains("reapply", ignoreCase = true) }

    /**
     * I-M5-10, phase 1 of 2. A force-stop kills the instrumentation too, so the suite runs this method, then
     * [persistencePhase2AfterForceStop] in a **second** invocation with the APKs left installed:
     * `run-instrumented.sh --class app.thebox.settings.PersistenceTest#persistencePhase1Write
     *  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, `adb shell am force-stop app.thebox.debug`, then
     * `--class …#persistencePhase2AfterForceStop`.
     */
    private val phase: String? get() = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("m5_phase")

    @Test
    fun persistencePhase1Write() {
        // Only with -Pandroid.testInstrumentationRunnerArguments.m5_phase=1 (the two-invocation run of the KDoc).
        if (phase != "1") { log("phase 1 skipped: no m5_phase=1 argument"); return }
        writeValues()
    }

    /** I-M5-10, phase 2: a fresh process; the values are restored before the first tune and the load is logged. */
    @Test
    fun persistencePhase2AfterForceStop() {
        if (phase != "2") { log("phase 2 skipped: no m5_phase=2 argument"); return }
        val docs = runBlocking { probe.documents() }
        assertTrue("phase 1 did not run in this install (run it first, see phase 1's KDoc)", docs["player"] != null)
        launch()
        assertEquals("Up/Down = Change channel restored before the first tune", UpDownInPlayer.ZAP, ep.playerSettings().settings.value.upDown)
        assertFalse("12-hour clock restored", ep.guideSettings().settings.value.clock24)
        assertTrue("playback document stored: $docs", docs["playback"].toString().contains("LARGE"))
        val load = probe.logRing.snapshot().lastOrNull { Regex("""(?i)settings.*load.*\d+ ?ms""").containsMatchIn(it) }
        log("store load line: $load")
        val ms = load?.let { Regex("""(\d+) ?ms""").findAll(it).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() }
        assertTrue("store load logged and ≤ 50 ms (SETTINGS_LOAD_BUDGET_MS): $load", ms != null && ms <= 50)
        // D-pad Up zaps in FULL.
        val before = controller.state.value.target?.tuneId
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(3_000)
        press(KeyEvent.KEYCODE_DPAD_UP)
        Thread.sleep(2_000)
        assertTrue("Up zapped in FULL", controller.state.value.target?.tuneId != before)
    }

    /** I-M5-10 in one process (store memory survives; checks the stored documents and a relaunch). */
    @Test
    fun valuesAreStoredAndSurviveARelaunch() {
        writeValues()
        relaunch()
        assertEquals(UpDownInPlayer.ZAP, ep.playerSettings().settings.value.upDown)
        assertFalse(ep.guideSettings().settings.value.clock24)
    }

    private fun writeValues() {
        val pid = seedXtream()
        openSettings("interface")
        choose("interface.clock", "H12")
        leaveSettings()
        openSettings("playback")
        moveTo("playback.buffer")
        choose("playback.buffer", "LARGE")
        leaveSettings()
        openSettings("remote")
        choose("remote.updown", "ZAP")
        leaveSettings()
        openSettings("playlist:$pid")
        choose("playlist.name_prefix", "OFF")
        leaveSettings()
        runBlocking { probe.store.flush() }
        val docs = runBlocking { probe.documents() }
        assertTrue("playback document stored: $docs", docs["playback"].toString().contains("LARGE"))
        assertTrue("player document stored: $docs", docs["player"].toString().contains("ZAP"))
    }

    /** I-M5-11 */
    @Test
    fun liveRowsNeverReprepareAndRepreparedRowsApplyOnceOnClose() {
        val pid = seedXtream()
        launch()
        val ch = runBlocking { ep.channels().browseAll(pid, unlocked = true).first() }.first()
        onMain { controller.tune(TuneTarget.Channel(app.thebox.core.refresh.ChannelIdentity(ch.playlistId, ch.stableKey))) }
        runBlocking { withTimeoutOrNull(20_000) { controller.state.first { it is PlaybackState.Playing } } }
        val tuneId = controller.state.value.target!!.tuneId
        val before = reprepares()
        openSettings("interface")
        choose("interface.clock", "H24")
        activate("interface.numbers")
        leaveSettings()
        Thread.sleep(1_500)
        assertEquals("same tuneId after LIVE rows", tuneId, controller.state.value.target?.tuneId)
        assertEquals("zero re-prepares after LIVE rows", before, reprepares())
        openSettings("playback")
        choose("playback.audio_output", "ALWAYS_DECODE")
        press(KeyEvent.KEYCODE_BACK)
        openSettings("playback")
        activate("playback.tunneling")
        assertEquals("nothing applied while Settings is open", before, reprepares())
        leaveSettings()
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (reprepares() == before && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        Thread.sleep(1_500)
        assertEquals("exactly one re-prepare when Settings closes", before + 1, reprepares())
        assertEquals(ScreenMode.GUIDE, gui().mode)
    }
}
